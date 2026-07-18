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
        AutomationAccessibilityService service = instance;
        if (service == null) return false;
        service.mainHandler.post(() -> {
            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription.StrokeDescription stroke =
                    new GestureDescription.StrokeDescription(path, 0, 90);
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(stroke)
                    .build();
            service.dispatchGesture(gesture, null, null);
        });
        return true;
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
