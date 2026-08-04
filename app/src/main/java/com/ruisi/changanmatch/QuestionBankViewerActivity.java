package com.ruisi.changanmatch;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class QuestionBankViewerActivity extends Activity {
    private static final int PURPLE = Color.rgb(91, 61, 170);
    private static final int PURPLE_DARK = Color.rgb(61, 41, 112);
    private static final int PAGE_BG = Color.rgb(247, 245, 252);
    private static final int TEXT_DARK = Color.rgb(55, 54, 64);
    private static final int TEXT_MUTED = Color.rgb(112, 108, 124);
    private static final int GREEN = Color.rgb(24, 125, 76);

    private final List<QuestionItem> allItems = new ArrayList<>();
    private final List<QuestionItem> visibleItems = new ArrayList<>();
    private QuestionAdapter adapter;
    private TextView countView;
    private EditText searchInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        loadQuestionBank();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(14), dp(14), dp(14));
        root.setBackgroundColor(PAGE_BG);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));

        Button back = new Button(this);
        back.setText("返回");
        back.setTextSize(14);
        back.setTextColor(PURPLE);
        back.setAllCaps(false);
        back.setBackground(roundStroke(Color.WHITE, Color.rgb(174, 159, 212), 13));
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(76), dp(44)));

        TextView title = new TextView(this);
        title.setText("查看题库");
        title.setTextSize(23);
        title.setTextColor(PURPLE_DARK);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        header.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        TextView placeholder = new TextView(this);
        header.addView(placeholder, new LinearLayout.LayoutParams(dp(76), dp(44)));

        countView = new TextView(this);
        countView.setText("题库载入中…");
        countView.setTextSize(13);
        countView.setTextColor(GREEN);
        countView.setGravity(Gravity.CENTER);
        countView.setPadding(dp(10), dp(7), dp(10), dp(7));
        countView.setBackground(roundRect(Color.rgb(237, 249, 242), 14));
        LinearLayout.LayoutParams countParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        countParams.setMargins(0, dp(8), 0, dp(8));
        root.addView(countView, countParams);

        searchInput = new EditText(this);
        searchInput.setHint("搜索题目或答案");
        searchInput.setSingleLine(true);
        searchInput.setTextSize(16);
        searchInput.setTextColor(TEXT_DARK);
        searchInput.setHintTextColor(Color.rgb(150, 146, 160));
        searchInput.setPadding(dp(14), dp(10), dp(14), dp(10));
        searchInput.setBackground(roundStroke(Color.WHITE,
                Color.rgb(188, 177, 220), 14));
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52));
        searchParams.setMargins(0, 0, 0, dp(8));
        root.addView(searchInput, searchParams);

        ListView listView = new ListView(this);
        listView.setDividerHeight(dp(7));
        listView.setDivider(null);
        listView.setClipToPadding(false);
        listView.setPadding(0, dp(2), 0, dp(8));
        adapter = new QuestionAdapter();
        listView.setAdapter(adapter);
        root.addView(listView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence value, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence value, int start, int before, int count) {
                applyFilter(value == null ? "" : value.toString());
            }

            @Override
            public void afterTextChanged(Editable value) {
            }
        });

        setContentView(root);
    }

    private void loadQuestionBank() {
        new Thread(() -> {
            List<QuestionItem> loaded = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    getAssets().open("questions.jsonl"), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    try {
                        JSONObject object = new JSONObject(line);
                        String question = object.optString("q", "").trim();
                        JSONArray answerArray = object.optJSONArray("a");
                        if (question.isEmpty() || answerArray == null || answerArray.length() == 0) {
                            continue;
                        }
                        List<String> answers = new ArrayList<>();
                        for (int index = 0; index < answerArray.length(); index++) {
                            String answer = answerArray.optString(index, "").trim();
                            if (!answer.isEmpty()) answers.add(answer);
                        }
                        if (!answers.isEmpty()) {
                            loaded.add(new QuestionItem(question,
                                    android.text.TextUtils.join(" / ", answers)));
                        }
                    } catch (Exception ignored) {
                        // 单条损坏时继续读取后续题目。
                    }
                }
                runOnUiThread(() -> {
                    allItems.clear();
                    allItems.addAll(loaded);
                    applyFilter(searchInput == null ? "" : searchInput.getText().toString());
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    countView.setText("题库读取失败");
                    countView.setTextColor(Color.rgb(190, 45, 45));
                    Toast.makeText(this, "题库读取失败，请重新安装最新版",
                            Toast.LENGTH_LONG).show();
                });
            }
        }, "question-bank-viewer-loader").start();
    }

    private void applyFilter(String rawQuery) {
        if (adapter == null) return;
        String query = normalizeForSearch(rawQuery);
        visibleItems.clear();
        if (query.isEmpty()) {
            visibleItems.addAll(allItems);
        } else {
            for (QuestionItem item : allItems) {
                if (normalizeForSearch(item.question).contains(query) ||
                        normalizeForSearch(item.answer).contains(query)) {
                    visibleItems.add(item);
                }
            }
        }
        adapter.notifyDataSetChanged();
        if (allItems.isEmpty()) {
            countView.setText("题库载入中…");
        } else if (query.isEmpty()) {
            countView.setText("当前题库共 " + allItems.size() + " 道");
        } else {
            countView.setText("共找到 " + visibleItems.size() + " 道 · 总题库 " +
                    allItems.size() + " 道");
        }
    }

    private String normalizeForSearch(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{P}\\p{S}\\s]+", "");
    }

    private final class QuestionAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return visibleItems.size();
        }

        @Override
        public QuestionItem getItem(int position) {
            return visibleItems.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout card;
            TextView questionView;
            TextView answerView;
            if (convertView instanceof LinearLayout && convertView.getTag() instanceof RowHolder) {
                card = (LinearLayout) convertView;
                RowHolder holder = (RowHolder) card.getTag();
                questionView = holder.question;
                answerView = holder.answer;
            } else {
                card = new LinearLayout(QuestionBankViewerActivity.this);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(dp(14), dp(11), dp(14), dp(11));
                card.setBackground(roundRect(Color.WHITE, 14));
                card.setElevation(dp(1));

                questionView = new TextView(QuestionBankViewerActivity.this);
                questionView.setTextSize(15);
                questionView.setTextColor(TEXT_DARK);
                questionView.setLineSpacing(0, 1.12f);
                card.addView(questionView, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));

                answerView = new TextView(QuestionBankViewerActivity.this);
                answerView.setTextSize(14);
                answerView.setTextColor(GREEN);
                answerView.setPadding(0, dp(6), 0, 0);
                answerView.setTextIsSelectable(true);
                card.addView(answerView, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));
                card.setTag(new RowHolder(questionView, answerView));
            }

            QuestionItem item = getItem(position);
            questionView.setText((position + 1) + ". " + item.question);
            answerView.setText("答案：" + item.answer);
            return card;
        }
    }

    private static final class RowHolder {
        final TextView question;
        final TextView answer;

        RowHolder(TextView question, TextView answer) {
            this.question = question;
            this.answer = answer;
        }
    }

    private static final class QuestionItem {
        final String question;
        final String answer;

        QuestionItem(String question, String answer) {
            this.question = question;
            this.answer = answer;
        }
    }

    private GradientDrawable roundRect(int color, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        return drawable;
    }

    private GradientDrawable roundStroke(int fill, int stroke, int radiusDp) {
        GradientDrawable drawable = roundRect(fill, radiusDp);
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
