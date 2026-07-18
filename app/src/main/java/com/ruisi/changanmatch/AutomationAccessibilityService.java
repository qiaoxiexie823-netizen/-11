package com.ruisi.changanmatch;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;

public class AutomationAccessibilityService extends AccessibilityService {
    private static volatile AutomationAccessibilityService instance;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface TapCallback {
        void onResult(boolean success);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    public static boolean isReady() {
        return instance != null;
    }

    public static boolean performSwipe(float startX, float startY, float endX, float endY) {
        AutomationAccessibilityService service = instance;
        if (service == null) return false;
        service.mainHandler.post(() -> {
            Path path = new Path();
            path.moveTo(startX, startY);
            path.lineTo(endX, endY);
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(path, 0, 190);
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(stroke)
                    .build();
            service.dispatchGesture(gesture, null, null);
        });
        return true;
    }

    public static boolean performTap(float x, float y) {
        return performTap(x, y, null);
    }

    public static boolean performTap(float x, float y, TapCallback callback) {
        AutomationAccessibilityService service = instance;
        if (service == null) {
            if (callback != null) callback.onResult(false);
            return false;
        }
        service.mainHandler.post(() -> service.dispatchTapInternal(x, y, 0, callback));
        return true;
    }

    private void dispatchTapInternal(float x, float y, int attempt, TapCallback callback) {
        Path path = new Path();
        path.moveTo(x, y);
        // 部分手机会忽略纯单点路径，极短移动视觉上仍等同一次点击。
        path.lineTo(x + 0.6f, y + 0.6f);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 115);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();

        boolean accepted = dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                super.onCompleted(gestureDescription);
                if (callback != null) callback.onResult(true);
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                super.onCancelled(gestureDescription);
                retryTapOrFinish(x, y, attempt, callback);
            }
        }, null);

        if (!accepted) retryTapOrFinish(x, y, attempt, callback);
    }

    private void retryTapOrFinish(float x, float y, int attempt, TapCallback callback) {
        if (attempt < 1 && instance == this) {
            mainHandler.postDelayed(
                    () -> dispatchTapInternal(x, y, attempt + 1, callback), 140L);
        } else if (callback != null) {
            callback.onResult(false);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 不读取窗口内容，只执行本地识别模块请求的滑动或点击手势。
    }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }
}
