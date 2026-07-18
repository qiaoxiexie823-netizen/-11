package com.ruisi.changanmatch;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class QuestionBank {
    private static final Pattern NOISE = Pattern.compile("[\\p{P}\\p{S}\\s　]+|第\\d+题|请选择|正确答案|答案");
    private final List<Entry> entries = new ArrayList<>();
    private final Map<String, List<Integer>> gramIndex = new HashMap<>();

    public QuestionBank(Context context) {
        load(context);
        buildIndex();
    }

    public int size() {
        return entries.size();
    }

    private void load(Context context) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open("questions.jsonl"), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    JSONObject object = new JSONObject(line);
                    String question = object.optString("q", "").trim();
                    JSONArray answers = object.optJSONArray("a");
                    if (question.isEmpty() || answers == null || answers.length() == 0) continue;
                    List<String> answerList = new ArrayList<>();
                    for (int i = 0; i < answers.length(); i++) {
                        String answer = answers.optString(i, "").trim();
                        if (!answer.isEmpty()) answerList.add(answer);
                    }
                    if (!answerList.isEmpty()) {
                        entries.add(new Entry(question, String.join(" / ", answerList)));
                    }
                } catch (Exception ignored) {
                    // 单行损坏不影响其余题库加载。
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("题库加载失败", e);
        }
    }

    private void buildIndex() {
        for (int id = 0; id < entries.size(); id++) {
            Entry entry = entries.get(id);
            entry.normalized = normalize(entry.question);
            entry.grams = grams(entry.normalized);
            for (String gram : entry.grams) {
                gramIndex.computeIfAbsent(gram, key -> new ArrayList<>()).add(id);
            }
        }
    }

    public Match findBest(String text) {
        List<String> lines = new ArrayList<>();
        for (String line : text.split("[\\r\\n]+")) {
            if (!line.trim().isEmpty()) lines.add(line.trim());
        }
        return findBest(lines, text);
    }

    public Match findBest(List<String> recognizedLines, String fullText) {
        List<String> segments = makeSegments(recognizedLines, fullText);
        Match best = null;
        for (String segment : segments) {
            Match candidate = matchSegment(segment);
            if (candidate != null && (best == null || candidate.score > best.score)) {
                best = candidate;
                if (best.score >= 0.995) break;
            }
        }
        if (best == null) return null;
        int questionLength = normalize(best.question).length();
        double threshold = questionLength <= 5 ? 0.76 : (questionLength <= 9 ? 0.64 : 0.54);
        return best.score >= threshold ? best : null;
    }

    private List<String> makeSegments(List<String> lines, String fullText) {
        List<String> output = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            output.add(lines.get(i));
            if (i + 1 < lines.size()) output.add(lines.get(i) + lines.get(i + 1));
            if (i + 2 < lines.size()) output.add(lines.get(i) + lines.get(i + 1) + lines.get(i + 2));
        }
        if (fullText != null && fullText.length() <= 500) output.add(fullText);
        return output;
    }

    private Match matchSegment(String rawSegment) {
        String segment = normalize(rawSegment);
        if (segment.length() < 2) return null;

        Match contained = null;
        for (Entry entry : entries) {
            if (entry.normalized.length() >= 3 && segment.contains(entry.normalized)) {
                double score = 0.998 + Math.min(0.001, entry.normalized.length() / 10000.0);
                if (contained == null || entry.normalized.length() > normalize(contained.question).length()) {
                    contained = new Match(entry.question, entry.answer, score);
                }
            }
        }
        if (contained != null) return contained;

        if (segment.length() >= 4) {
            Match partial = null;
            for (Entry entry : entries) {
                if (entry.normalized.contains(segment)) {
                    double ratio = segment.length() / (double) Math.max(1, entry.normalized.length());
                    double score = Math.min(0.96, 0.78 + ratio * 0.18);
                    if (partial == null || score > partial.score) {
                        partial = new Match(entry.question, entry.answer, score);
                    }
                }
            }
            if (partial != null) return partial;
        }

        Set<String> segmentGrams = grams(segment);
        Map<Integer, Integer> overlapCount = new HashMap<>();
        for (String gram : segmentGrams) {
            List<Integer> ids = gramIndex.get(gram);
            if (ids == null) continue;
            for (Integer id : ids) overlapCount.merge(id, 1, Integer::sum);
        }

        Match best = null;
        for (Map.Entry<Integer, Integer> count : overlapCount.entrySet()) {
            Entry entry = entries.get(count.getKey());
            if (entry.normalized.length() < 3) continue;
            double dice = (2.0 * count.getValue()) /
                    Math.max(1, entry.grams.size() + segmentGrams.size());
            double lengthRatio = Math.min(entry.normalized.length(), segment.length()) /
                    (double) Math.max(entry.normalized.length(), segment.length());
            double edit = similarity(entry.normalized, segment);
            double score = Math.max(dice,
                    dice * 0.56 + edit * 0.34 + lengthRatio * 0.10);

            if (entry.normalized.length() >= 8) {
                String head = entry.normalized.substring(0, 4);
                String tail = entry.normalized.substring(entry.normalized.length() - 4);
                if (segment.contains(head)) score += 0.06;
                if (segment.contains(tail)) score += 0.06;
            }
            score = Math.min(0.997, score);
            if (best == null || score > best.score) {
                best = new Match(entry.question, entry.answer, score);
            }
        }
        return best;
    }

    public static String normalize(String input) {
        if (input == null) return "";
        String value = Normalizer.normalize(input, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replace("o", "0")
                .replace("l", "1");
        return NOISE.matcher(value).replaceAll("");
    }

    private static Set<String> grams(String value) {
        Set<String> result = new HashSet<>();
        if (value.length() <= 1) {
            if (!value.isEmpty()) result.add(value);
            return result;
        }
        for (int i = 0; i < value.length() - 1; i++) {
            result.add(value.substring(i, i + 2));
        }
        return result;
    }

    private static double similarity(String a, String b) {
        if (a.equals(b)) return 1.0;
        if (b.length() > a.length() + 18) {
            double best = 0;
            int window = Math.min(b.length(), a.length() + 12);
            int step = Math.max(1, a.length() / 4);
            for (int start = 0; start + Math.min(a.length(), 3) <= b.length(); start += step) {
                String part = b.substring(start, Math.min(b.length(), start + window));
                best = Math.max(best, similarityDirect(a, part));
            }
            return best;
        }
        return similarityDirect(a, b);
    }

    private static double similarityDirect(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1,
                        previous[j] + 1), previous[j - 1] + cost);
            }
            int[] temporary = previous;
            previous = current;
            current = temporary;
        }
        int max = Math.max(a.length(), b.length());
        return max == 0 ? 1.0 : 1.0 - previous[b.length()] / (double) max;
    }

    private static class Entry {
        final String question;
        final String answer;
        String normalized;
        Set<String> grams;

        Entry(String question, String answer) {
            this.question = question;
            this.answer = answer;
        }
    }

    public static class Match {
        public final String question;
        public final String answer;
        public final double score;

        Match(String question, String answer, double score) {
            this.question = question;
            this.answer = answer;
            this.score = score;
        }
    }
}
