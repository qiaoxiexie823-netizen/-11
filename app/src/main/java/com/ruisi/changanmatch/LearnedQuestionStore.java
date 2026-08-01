package com.ruisi.changanmatch;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class LearnedQuestionStore {
    private static final String PREFS = "quiz_learned_answers";
    private final SharedPreferences preferences;

    public LearnedQuestionStore(Context context) {
        preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String getAnswer(String question) {
        String raw = preferences.getString(QuestionBank.normalize(question), "");
        if (raw == null || raw.isEmpty()) return "";
        try {
            return new JSONObject(raw).optString("answer", "").trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    public boolean saveCorrection(String question, String correctAnswer, String previousAnswer) {
        String normalized = QuestionBank.normalize(question);
        String answer = cleanAnswer(correctAnswer);
        if (normalized.isEmpty() || answer.isEmpty()) return false;
        try {
            JSONObject object = new JSONObject();
            object.put("question", question.trim());
            object.put("answer", answer);
            object.put("previous", previousAnswer == null ? "" : previousAnswer.trim());
            object.put("updatedAt", System.currentTimeMillis());
            object.put("source", "manual-wrong-answer-detection");
            preferences.edit().putString(normalized, object.toString()).apply();
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    public int size() {
        return preferences.getAll().size();
    }

    public void clear() {
        preferences.edit().clear().apply();
    }

    public String recentText(int limit) {
        List<Record> records = new ArrayList<>();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            try {
                JSONObject object = new JSONObject(String.valueOf(entry.getValue()));
                records.add(new Record(
                        object.optString("question", ""),
                        object.optString("answer", ""),
                        object.optString("previous", ""),
                        object.optLong("updatedAt", 0L)));
            } catch (Exception ignored) {
            }
        }
        Collections.sort(records, Comparator.comparingLong((Record r) -> r.updatedAt).reversed());
        if (records.isEmpty()) return "暂无错题学习记录";
        StringBuilder text = new StringBuilder();
        SimpleDateFormat format = new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA);
        int count = Math.min(Math.max(1, limit), records.size());
        for (int i = 0; i < count; i++) {
            Record record = records.get(i);
            if (i > 0) text.append("\n\n");
            text.append(i + 1).append(". ").append(record.question)
                    .append("\n正确答案：").append(record.answer);
            if (!record.previous.isEmpty() &&
                    !QuestionBank.normalize(record.previous).equals(QuestionBank.normalize(record.answer))) {
                text.append("\n原题库答案：").append(record.previous);
            }
            if (record.updatedAt > 0L) {
                text.append("\n记录时间：").append(format.format(new Date(record.updatedAt)));
            }
        }
        return text.toString();
    }

    public static String cleanAnswer(String value) {
        if (value == null) return "";
        String answer = value.trim()
                .replaceFirst("^[A-Da-d1-4一二三四][\\.、:：\\)）\\s]+", "")
                .replaceFirst("^(正确答案|答案)[是为:：\\s]*", "")
                .trim();
        if (answer.length() > 60) answer = answer.substring(0, 60).trim();
        return answer;
    }

    private static final class Record {
        final String question;
        final String answer;
        final String previous;
        final long updatedAt;

        Record(String question, String answer, String previous, long updatedAt) {
            this.question = question;
            this.answer = answer;
            this.previous = previous;
            this.updatedAt = updatedAt;
        }
    }
}
