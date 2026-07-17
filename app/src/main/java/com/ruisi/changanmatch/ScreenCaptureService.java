package com.ruisi.changanmatch;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

public class ScreenCaptureService extends Service {
    public static final String ACTION_START = "com.ruisi.changanmatch.START";
    public static final String ACTION_STOP = "com.ruisi.changanmatch.STOP";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";
    public static final String EXTRA_ROWS = "rows";
    public static final String EXTRA_COLUMNS = "columns";
    public static final String EXTRA_KINDS = "kinds";
    public static final String EXTRA_SPEED_MODE = "speedMode";
    public static final String EXTRA_AUTO_MODE = "autoMode";

    private static final String CHANNEL_ID = "match3_capture";
    private static final int NOTIFICATION_ID = 9342;
    private static final double STABLE_THRESHOLD = 0.055;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean processing = new AtomicBoolean(false);
    private final BoardAnalyzer analyzer = new BoardAnalyzer();
    private final Match3Solver solver = new Match3Solver();

    private HandlerThread captureThread;
    private Handler captureHandler;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private WindowManager windowManager;
    private LinearLayout controlOverlay;
    private TextView statusView;
    private Button autoButton;
    private WindowManager.LayoutParams controlParams;
    private CalibrationView calibrationView;
    private SharedPreferences preferences;

    private int screenWidth;
    private int screenHeight;
    private int rows = 8;
    private int columns = 7;
    private int kinds = 5;
    private int speedMode;
    private long frameIntervalMs = 450;
    private long actionCooldownMs = 1400;
    private long sameBoardCooldownMs = 4500;
    private int requiredStableFrames = 3;
    private String speedLabel = "稳定";
    private volatile boolean autoMode;
    private volatile boolean calibrating;
    private long lastFrameAt;
    private long lastActionAt;
    private String lastActionSignature = "";
    private BoardAnalyzer.Frame previousFrame;
    private int stableFrames;

    @Override
    public void onCreate() {
        super.onCreate();
        preferences = getSharedPreferences("match3_settings", MODE_PRIVATE);
        captureThread = new HandlerThread("match3-capture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        if (ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!ACTION_START.equals(intent.getAction())) return START_NOT_STICKY;

        rows = clamp(intent.getIntExtra(EXTRA_ROWS, 8), 4, 10);
        columns = clamp(intent.getIntExtra(EXTRA_COLUMNS, 7), 4, 10);
        kinds = clamp(intent.getIntExtra(EXTRA_KINDS, 5), 4, 9);
        speedMode = clamp(intent.getIntExtra(EXTRA_SPEED_MODE, 0), 0, 2);
        applySpeedMode(speedMode);
        autoMode = intent.getBooleanExtra(EXTRA_AUTO_MODE, false);

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED);
        Intent resultData;
        if (Build.VERSION.SDK_INT >= 33) {
            resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent.class);
        } else {
            //noinspection deprecation
            resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        }
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startAsForeground();
        showControlOverlay();
        startProjection(resultCode, resultData);
        return START_NOT_STICKY;
    }

    private void startAsForeground() {
        Intent stopIntent = new Intent(this, ScreenCaptureService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 10, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        Notification notification = builder
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("宴会消消乐助手正在运行")
                .setContentText("可在浮窗中标定棋盘、切换自动模式或停止")
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "停止", stopPending).build())
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void startProjection(int resultCode, Intent resultData) {
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        mediaProjection = manager.getMediaProjection(resultCode, resultData);
        if (mediaProjection == null) {
            updateStatus("屏幕录制启动失败");
            stopSelf();
            return;
        }
        mediaProjection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                stopSelf();
            }
        }, mainHandler);
        createVirtualDisplay();
    }

    private void createVirtualDisplay() {
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        //noinspection deprecation
        wm.getDefaultDisplay().getRealMetrics(metrics);
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;

        imageReader = ImageReader.newInstance(screenWidth, screenHeight,
                PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(this::onImageAvailable, captureHandler);
        virtualDisplay = mediaProjection.createVirtualDisplay(
                "ChanganBanquetMatch3Capture",
                screenWidth,
                screenHeight,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                captureHandler);
        updateStatus("等待游戏棋盘…\n先点“标定”对准范围");
    }

    private void onImageAvailable(ImageReader reader) {
        Image image = reader.acquireLatestImage();
        if (image == null) return;
        long now = System.currentTimeMillis();
        if (calibrating || processing.get() || now - lastFrameAt < frameIntervalMs) {
            image.close();
            return;
        }
        processing.set(true);
        lastFrameAt = now;
        Bitmap bitmap = null;
        try {
            bitmap = imageToBitmap(image);
        } catch (Exception ignored) {
        } finally {
            image.close();
        }
        if (bitmap == null) {
            processing.set(false);
            return;
        }
        Bitmap finalBitmap = bitmap;
        captureHandler.post(() -> {
            try {
                processBitmap(finalBitmap);
            } finally {
                finalBitmap.recycle();
                processing.set(false);
            }
        });
    }

    private Bitmap imageToBitmap(Image image) {
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer buffer = plane.getBuffer();
        int pixelStride = plane.getPixelStride();
        int rowStride = plane.getRowStride();
        int rowPadding = rowStride - pixelStride * image.getWidth();
        Bitmap padded = Bitmap.createBitmap(
                image.getWidth() + rowPadding / pixelStride,
                image.getHeight(), Bitmap.Config.ARGB_8888);
        padded.copyPixelsFromBuffer(buffer);
        Bitmap cropped = Bitmap.createBitmap(padded, 0, 0,
                image.getWidth(), image.getHeight());
        if (cropped != padded) padded.recycle();
        return cropped;
    }

    private void processBitmap(Bitmap bitmap) {
        Rect boardRect = absoluteBoardRect(bitmap.getWidth(), bitmap.getHeight());
        BoardAnalyzer.Frame current = analyzer.extract(bitmap, boardRect, rows, columns);
        if (current == null) {
            updateStatus("棋盘范围无效，请重新标定");
            previousFrame = null;
            stableFrames = 0;
            return;
        }

        double change = analyzer.difference(previousFrame, current);
        previousFrame = current;
        if (change > STABLE_THRESHOLD) {
            stableFrames = 0;
            updateStatus("等待棋盘动画结束…");
            return;
        }
        stableFrames++;
        if (stableFrames < requiredStableFrames) {
            updateStatus("正在确认棋盘稳定…（" + speedLabel + "）");
            return;
        }

        int[][] board = analyzer.classify(current, kinds);
        if (board == null) {
            updateStatus("图标识别失败，请重新标定");
            return;
        }
        String signature = analyzer.signature(board);
        long now = System.currentTimeMillis();
        if (signature.equals(lastActionSignature) && now - lastActionAt < sameBoardCooldownMs) {
            updateStatus("等待方块落位…");
            return;
        }

        Match3Solver.Move move = solver.findBest(board);
        if (move == null) {
            updateStatus("暂未找到三连步骤\n可检查行列数和标定范围");
            return;
        }
        if (!autoMode) {
            updateStatus("建议：" + move.shortLabel() + "\n预计消除 " + move.matchedCells + " 格");
            return;
        }
        if (!AutomationAccessibilityService.isReady()) {
            autoMode = false;
            preferences.edit().putBoolean("auto_mode", false).apply();
            updateAutoButton();
            updateStatus("自动权限已关闭，已切换为提示模式");
            return;
        }
        if (now - lastActionAt < actionCooldownMs) return;

        float startX = (float) (boardRect.left + (move.column1 + 0.5) * boardRect.width() / columns);
        float startY = (float) (boardRect.top + (move.row1 + 0.5) * boardRect.height() / rows);
        float endX = (float) (boardRect.left + (move.column2 + 0.5) * boardRect.width() / columns);
        float endY = (float) (boardRect.top + (move.row2 + 0.5) * boardRect.height() / rows);
        if (AutomationAccessibilityService.performSwipe(startX, startY, endX, endY)) {
            lastActionAt = now;
            lastActionSignature = signature;
            stableFrames = 0;
            updateStatus("已滑动：" + move.shortLabel() + "\n等待下一轮…");
        } else {
            updateStatus("自动滑动失败，请检查无障碍权限");
        }
    }

    private Rect absoluteBoardRect(int width, int height) {
        RectF normalized = loadBoardRect();
        int left = clamp(Math.round(normalized.left * width), 0, width - 1);
        int top = clamp(Math.round(normalized.top * height), 0, height - 1);
        int right = clamp(Math.round(normalized.right * width), left + 1, width);
        int bottom = clamp(Math.round(normalized.bottom * height), top + 1, height);
        return new Rect(left, top, right, bottom);
    }

    private RectF loadBoardRect() {
        if (!preferences.contains("board_left")) {
            // Banquet board preset measured from the supplied portrait reference image.
            return new RectF(0.01f, 0.255f, 0.99f, 0.775f);
        }
        return new RectF(
                preferences.getFloat("board_left", 0.01f),
                preferences.getFloat("board_top", 0.255f),
                preferences.getFloat("board_right", 0.99f),
                preferences.getFloat("board_bottom", 0.775f));
    }

    private void applySpeedMode(int mode) {
        if (mode == 2) {
            frameIntervalMs = 180;
            actionCooldownMs = 520;
            sameBoardCooldownMs = 2200;
            requiredStableFrames = 2;
            speedLabel = "极速";
        } else if (mode == 1) {
            frameIntervalMs = 280;
            actionCooldownMs = 850;
            sameBoardCooldownMs = 3200;
            requiredStableFrames = 2;
            speedLabel = "快速";
        } else {
            frameIntervalMs = 450;
            actionCooldownMs = 1400;
            sameBoardCooldownMs = 4500;
            requiredStableFrames = 3;
            speedLabel = "稳定";
        }
    }

    private void showControlOverlay() {
        mainHandler.post(() -> {
            if (controlOverlay != null) return;
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            controlOverlay = new LinearLayout(this);
            controlOverlay.setOrientation(LinearLayout.HORIZONTAL);
            controlOverlay.setGravity(Gravity.CENTER_VERTICAL);
            controlOverlay.setPadding(dp(10), dp(7), dp(8), dp(7));
            GradientDrawable background = new GradientDrawable();
            background.setColor(Color.argb(232, 28, 22, 48));
            background.setCornerRadius(dp(14));
            background.setStroke(dp(1), Color.argb(145, 255, 255, 255));
            controlOverlay.setBackground(background);

            statusView = new TextView(this);
            statusView.setText("正在初始化…");
            statusView.setTextColor(Color.WHITE);
            statusView.setTextSize(14);
            statusView.setGravity(Gravity.CENTER_VERTICAL);
            statusView.setMinWidth(dp(190));
            statusView.setMaxWidth(dp(330));
            statusView.setPadding(dp(6), 0, dp(8), 0);
            controlOverlay.addView(statusView, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            autoButton = smallButton(autoMode ? "自动：开" : "自动：关");
            autoButton.setOnClickListener(v -> toggleAutoMode());
            controlOverlay.addView(autoButton);

            Button calibrationButton = smallButton("标定");
            calibrationButton.setOnClickListener(v -> showCalibration());
            controlOverlay.addView(calibrationButton);

            Button stopButton = smallButton("停止");
            stopButton.setOnClickListener(v -> stopSelf());
            controlOverlay.addView(stopButton);

            int type = Build.VERSION.SDK_INT >= 26
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            controlParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            controlParams.gravity = Gravity.TOP | Gravity.START;
            controlParams.x = dp(20);
            controlParams.y = dp(24);
            enableDrag(statusView);
            windowManager.addView(controlOverlay, controlParams);
        });
    }

    private Button smallButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(13);
        button.setTextColor(Color.WHITE);
        button.setAllCaps(false);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setPadding(dp(10), 0, dp(10), 0);
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.argb(190, 105, 75, 205));
        background.setCornerRadius(dp(10));
        button.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(42));
        params.setMargins(dp(3), 0, dp(3), 0);
        button.setLayoutParams(params);
        return button;
    }

    private void toggleAutoMode() {
        if (!autoMode && !AutomationAccessibilityService.isReady()) {
            updateStatus("请先在系统设置中开启自动滑动权限");
            return;
        }
        autoMode = !autoMode;
        preferences.edit().putBoolean("auto_mode", autoMode).apply();
        updateAutoButton();
        updateStatus(autoMode ? "自动模式已开启" : "已切换为只提示模式");
    }

    private void updateAutoButton() {
        mainHandler.post(() -> {
            if (autoButton != null) autoButton.setText(autoMode ? "自动：开" : "自动：关");
        });
    }

    private void showCalibration() {
        if (calibrating || windowManager == null) return;
        calibrating = true;
        previousFrame = null;
        stableFrames = 0;
        mainHandler.post(() -> {
            if (controlOverlay != null) controlOverlay.setVisibility(View.GONE);
            calibrationView = new CalibrationView(this, loadBoardRect(),
                    new CalibrationView.Callback() {
                        @Override
                        public void onSave(RectF normalizedBoard) {
                            preferences.edit()
                                    .putFloat("board_left", normalizedBoard.left)
                                    .putFloat("board_top", normalizedBoard.top)
                                    .putFloat("board_right", normalizedBoard.right)
                                    .putFloat("board_bottom", normalizedBoard.bottom)
                                    .apply();
                            finishCalibration("标定已保存，正在识别…");
                        }

                        @Override
                        public void onCancel() {
                            finishCalibration("已取消标定");
                        }
                    });
            int type = Build.VERSION.SDK_INT >= 26
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN |
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.START;
            windowManager.addView(calibrationView, params);
        });
    }

    private void finishCalibration(String message) {
        mainHandler.post(() -> {
            if (windowManager != null && calibrationView != null) {
                try {
                    windowManager.removeView(calibrationView);
                } catch (Exception ignored) { }
                calibrationView = null;
            }
            if (controlOverlay != null) controlOverlay.setVisibility(View.VISIBLE);
            calibrating = false;
            updateStatus(message);
        });
    }

    private void enableDrag(View handle) {
        handle.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX();
                        downY = event.getRawY();
                        startX = controlParams.x;
                        startY = controlParams.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        controlParams.x = startX + Math.round(event.getRawX() - downX);
                        controlParams.y = startY + Math.round(event.getRawY() - downY);
                        if (windowManager != null && controlOverlay != null) {
                            windowManager.updateViewLayout(controlOverlay, controlParams);
                        }
                        return true;
                    default:
                        return true;
                }
            }
        });
    }

    private void updateStatus(String text) {
        mainHandler.post(() -> {
            if (statusView != null) statusView.setText(text);
        });
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "消消乐屏幕识别", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("宴会消消乐助手运行状态");
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        processing.set(true);
        calibrating = false;
        if (virtualDisplay != null) virtualDisplay.release();
        if (imageReader != null) imageReader.close();
        if (mediaProjection != null) mediaProjection.stop();
        if (captureThread != null) captureThread.quitSafely();
        mainHandler.post(() -> {
            if (windowManager != null && calibrationView != null) {
                try { windowManager.removeView(calibrationView); } catch (Exception ignored) { }
                calibrationView = null;
            }
            if (windowManager != null && controlOverlay != null) {
                try { windowManager.removeView(controlOverlay); } catch (Exception ignored) { }
                controlOverlay = null;
            }
        });
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
