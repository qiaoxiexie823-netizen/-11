package com.ruisi.changanmatch;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

public class CalibrationView extends View {
    public interface Callback {
        void onSave(RectF normalizedBoard);
        void onCancel();
    }

    private final Paint shadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint buttonPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF selection = new RectF();
    private final RectF initialNormalized;
    private final RectF cancelButton = new RectF();
    private final RectF saveButton = new RectF();
    private final Callback callback;

    private int dragMode;
    private float downX;
    private float downY;
    private final RectF downSelection = new RectF();

    public CalibrationView(Context context, RectF normalizedBoard, Callback callback) {
        super(context);
        this.initialNormalized = new RectF(normalizedBoard);
        this.callback = callback;
        setBackgroundColor(Color.TRANSPARENT);

        shadePaint.setColor(Color.argb(155, 0, 0, 0));
        borderPaint.setColor(Color.rgb(255, 211, 72));
        borderPaint.setStyle(Paint.Style.STROKE);
        borderPaint.setStrokeWidth(dp(3));
        handlePaint.setColor(Color.rgb(255, 211, 72));
        buttonPaint.setColor(Color.argb(235, 74, 45, 160));
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(dp(16));
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        selection.set(initialNormalized.left * width, initialNormalized.top * height,
                initialNormalized.right * width, initialNormalized.bottom * height);
        float buttonWidth = dp(100);
        float buttonHeight = dp(44);
        float margin = dp(18);
        cancelButton.set(margin, margin, margin + buttonWidth, margin + buttonHeight);
        saveButton.set(width - margin - buttonWidth, margin,
                width - margin, margin + buttonHeight);
        clampSelection();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawRect(0, 0, getWidth(), selection.top, shadePaint);
        canvas.drawRect(0, selection.bottom, getWidth(), getHeight(), shadePaint);
        canvas.drawRect(0, selection.top, selection.left, selection.bottom, shadePaint);
        canvas.drawRect(selection.right, selection.top, getWidth(), selection.bottom, shadePaint);

        canvas.drawRect(selection, borderPaint);
        float radius = dp(11);
        canvas.drawCircle(selection.left, selection.top, radius, handlePaint);
        canvas.drawCircle(selection.right, selection.top, radius, handlePaint);
        canvas.drawCircle(selection.left, selection.bottom, radius, handlePaint);
        canvas.drawCircle(selection.right, selection.bottom, radius, handlePaint);

        canvas.drawRoundRect(cancelButton, dp(12), dp(12), buttonPaint);
        canvas.drawRoundRect(saveButton, dp(12), dp(12), buttonPaint);
        drawCenteredText(canvas, "取消", cancelButton);
        drawCenteredText(canvas, "保存标定", saveButton);

        textPaint.setTextSize(dp(17));
        textPaint.setShadowLayer(dp(3), 0, dp(1), Color.BLACK);
        canvas.drawText("拖动黄框，使其只包住完整棋盘；四角可缩放",
                getWidth() / 2f, Math.max(dp(88), selection.top - dp(18)), textPaint);
        textPaint.clearShadowLayer();
    }

    private void drawCenteredText(Canvas canvas, String text, RectF rect) {
        Paint.FontMetrics metrics = textPaint.getFontMetrics();
        float baseline = rect.centerY() - (metrics.ascent + metrics.descent) / 2f;
        canvas.drawText(text, rect.centerX(), baseline, textPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (saveButton.contains(x, y)) {
                    callback.onSave(normalizedSelection());
                    return true;
                }
                if (cancelButton.contains(x, y)) {
                    callback.onCancel();
                    return true;
                }
                dragMode = hitMode(x, y);
                downX = x;
                downY = y;
                downSelection.set(selection);
                return dragMode != 0;
            case MotionEvent.ACTION_MOVE:
                if (dragMode == 0) return false;
                applyDrag(x - downX, y - downY);
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragMode = 0;
                return true;
            default:
                return false;
        }
    }

    private int hitMode(float x, float y) {
        float hit = dp(42);
        if (near(x, y, selection.left, selection.top, hit)) return 1;
        if (near(x, y, selection.right, selection.top, hit)) return 2;
        if (near(x, y, selection.left, selection.bottom, hit)) return 3;
        if (near(x, y, selection.right, selection.bottom, hit)) return 4;
        if (selection.contains(x, y)) return 5;
        return 0;
    }

    private boolean near(float x, float y, float targetX, float targetY, float radius) {
        float dx = x - targetX;
        float dy = y - targetY;
        return dx * dx + dy * dy <= radius * radius;
    }

    private void applyDrag(float dx, float dy) {
        float minimum = dp(150);
        selection.set(downSelection);
        if (dragMode == 1) {
            selection.left = Math.min(downSelection.left + dx, downSelection.right - minimum);
            selection.top = Math.min(downSelection.top + dy, downSelection.bottom - minimum);
        } else if (dragMode == 2) {
            selection.right = Math.max(downSelection.right + dx, downSelection.left + minimum);
            selection.top = Math.min(downSelection.top + dy, downSelection.bottom - minimum);
        } else if (dragMode == 3) {
            selection.left = Math.min(downSelection.left + dx, downSelection.right - minimum);
            selection.bottom = Math.max(downSelection.bottom + dy, downSelection.top + minimum);
        } else if (dragMode == 4) {
            selection.right = Math.max(downSelection.right + dx, downSelection.left + minimum);
            selection.bottom = Math.max(downSelection.bottom + dy, downSelection.top + minimum);
        } else if (dragMode == 5) {
            selection.offset(dx, dy);
        }
        clampSelection();
    }

    private void clampSelection() {
        if (selection.width() > getWidth()) selection.left = 0;
        if (selection.height() > getHeight()) selection.top = 0;
        if (selection.left < 0) selection.offset(-selection.left, 0);
        if (selection.top < 0) selection.offset(0, -selection.top);
        if (selection.right > getWidth()) selection.offset(getWidth() - selection.right, 0);
        if (selection.bottom > getHeight()) selection.offset(0, getHeight() - selection.bottom);
        selection.left = Math.max(0, selection.left);
        selection.top = Math.max(0, selection.top);
        selection.right = Math.min(getWidth(), selection.right);
        selection.bottom = Math.min(getHeight(), selection.bottom);
    }

    private RectF normalizedSelection() {
        return new RectF(selection.left / getWidth(), selection.top / getHeight(),
                selection.right / getWidth(), selection.bottom / getHeight());
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
