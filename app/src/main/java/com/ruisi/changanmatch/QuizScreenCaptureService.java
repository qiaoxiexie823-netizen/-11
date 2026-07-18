package com.ruisi.changanmatch;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
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
import android.widget.TextView;

import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class QuizScreenCaptureService extends Service {
    public static final String ACTION_START = "com.ruisi.changanmatch.QUIZ_START";
    public static final String ACTION_STOP = "com.ruisi.changanmatch.QUIZ_STOP";
    public static final String EXTRA_RESULT_CODE = "quizResultCode";
    public static final String EXTRA_RESULT_DATA = "quizResultData";
    public static final String EXTRA_CENTER_ONLY = "quizCenterOnly";

    private static final String CHANNEL_ID = "quiz_capture";
    private static final int NOTIFICATION_ID = 8232;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean processing = new AtomicBoolean(false);

    private HandlerThread captureThread;
    private Handler captureHandler;
    private MediaProjection mediaProjection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private TextRecognizer recognizer;
    private QuestionBank questionBank;
    private WindowManager windowManager;
    private TextView overlayView;
    private WindowManager.LayoutParams overlayParams;
    private boolean centerOnly = true;
    private long lastProcessAt;
    private String lastQuestion = "";

    @Override
    public void onCreate() {
        super.onCreate();
        questionBank = new QuestionBank(this);
        recognizer = TextRecognition.getClient(
                new ChineseTextRecognizerOptions.Builder().build());
        captureThread = new HandlerThread("embedded-quiz-capture");
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

        centerOnly = intent.getBooleanExtra(EXTRA_CENTER_ONLY, true);
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
        showOverlay("正在载入 3603 道题库…\n长按悬浮窗停止");
        startProjection(resultCode, resultData);
        return START_NOT_STICKY;
    }

    private void startAsForeground() {
        Intent stopIntent = new Intent(this, QuizScreenCaptureService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 11, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        Notification notification = builder
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("深情助手正在识题")
                .setContentText("本地 OCR 与题库匹配运行中")
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
            updateOverlay("屏幕录制启动失败");
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
        WindowManager manager = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        //noinspection deprecation
        manager.getDefaultDisplay().getRealMetrics(metrics);
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        int density = metrics.densityDpi;

        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(this::onImageAvailable, captureHandler);
        virtualDisplay = mediaProjection.createVirtualDisplay(
                "EmbeddedQuizCapture", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(), null, captureHandler);
        updateOverlay("等待识别题目…\n长按悬浮窗停止");
    }

    private void onImageAvailable(ImageReader reader) {
        Image image = reader.acquireLatestImage();
        if (image == null) return;
        long now = System.currentTimeMillis();
        if (processing.get() || now - lastProcessAt < 850L) {
            image.close();
            return;
        }
        processing.set(true);
        lastProcessAt = now;

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

        Bitmap prepared = prepareBitmap(bitmap);
        if (prepared != bitmap) bitmap.recycle();
        InputImage inputImage = InputImage.fromBitmap(prepared, 0);
        Task<Text> task = recognizer.process(inputImage);
        task.addOnSuccessListener(this::handleOcrResult)
                .addOnFailureListener(error -> updateOverlay("识别失败，正在重试…"))
                .addOnCompleteListener(done -> {
                    prepared.recycle();
                    processing.set(false);
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
        Bitmap cropped = Bitmap.createBitmap(
                padded, 0, 0, image.getWidth(), image.getHeight());
        if (cropped != padded) padded.recycle();
        return cropped;
    }

    private Bitmap prepareBitmap(Bitmap source) {
        int top = centerOnly ? Math.round(source.getHeight() * 0.12f) : 0;
        int bottom = centerOnly
                ? Math.round(source.getHeight() * 0.90f)
                : source.getHeight();
        Bitmap cropped = Bitmap.createBitmap(source, 0, top,
                source.getWidth(), Math.max(1, bottom - top));
        int maxWidth = 1600;
        if (cropped.getWidth() <= maxWidth) return cropped;
        int targetHeight = Math.round(cropped.getHeight() *
                (maxWidth / (float) cropped.getWidth()));
        Bitmap scaled = Bitmap.createScaledBitmap(
                cropped, maxWidth, targetHeight, true);
        if (scaled != cropped) cropped.recycle();
        return scaled;
    }

    private void handleOcrResult(Text result) {
        List<String> lines = new ArrayList<>();
        for (Text.TextBlock block : result.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                String value = line.getText().trim();
                if (!value.isEmpty()) lines.add(value);
            }
        }
        QuestionBank.Match match = questionBank.findBest(lines, result.getText());
        if (match == null) {
            if (lastQuestion.isEmpty()) {
                updateOverlay("正在寻找题目…\n长按悬浮窗停止");
            }
            return;
        }
        if (!match.question.equals(lastQuestion)) {
            lastQuestion = match.question;
            updateOverlay("答案：" + match.answer + "\n" + match.question +
                    "\n匹配 " + Math.round(match.score * 100) + "%");
        }
    }

    private void showOverlay(String initialText) {
        mainHandler.post(() -> {
            if (overlayView != null) return;
            windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
            overlayView = new TextView(this);
            overlayView.setText(initialText);
            overlayView.setTextColor(Color.WHITE);
            overlayView.setTextSize(17);
            overlayView.setGravity(Gravity.CENTER_VERTICAL);
            overlayView.setPadding(dp(16), dp(10), dp(16), dp(10));
            overlayView.setMinWidth(dp(260));
            overlayView.setMaxWidth(dp(620));
            GradientDrawable background = new GradientDrawable();
            background.setColor(Color.argb(224, 61, 41, 112));
            background.setCornerRadius(dp(14));
            background.setStroke(dp(1), Color.argb(150, 255, 255, 255));
            overlayView.setBackground(background);

            int type = Build.VERSION.SDK_INT >= 26
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            overlayParams = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT);
            overlayParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            overlayParams.x = 0;
            overlayParams.y = dp(28);
            enableDrag(overlayView);
            overlayView.setOnLongClickListener(view -> {
                stopSelf();
                return true;
            });
            windowManager.addView(overlayView, overlayParams);
        });
    }

    private void enableDrag(View view) {
        view.setOnTouchListener(new View.OnTouchListener() {
            float downX;
            float downY;
            int startX;
            int startY;
            long downAt;

            @Override
            public boolean onTouch(View target, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX();
                        downY = event.getRawY();
                        startX = overlayParams.x;
                        startY = overlayParams.y;
                        downAt = System.currentTimeMillis();
                        return false;
                    case MotionEvent.ACTION_MOVE:
                        overlayParams.gravity = Gravity.TOP | Gravity.START;
                        overlayParams.x = startX + Math.round(event.getRawX() - downX);
                        overlayParams.y = startY + Math.round(event.getRawY() - downY);
                        if (windowManager != null && overlayView != null) {
                            windowManager.updateViewLayout(overlayView, overlayParams);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        return System.currentTimeMillis() - downAt > 250L;
                    default:
                        return false;
                }
            }
        });
    }

    private void updateOverlay(String text) {
        mainHandler.post(() -> {
            if (overlayView != null) overlayView.setText(text);
        });
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "屏幕识题", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("深情助手本地题库屏幕识别运行状态");
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        processing.set(true);
        if (virtualDisplay != null) virtualDisplay.release();
        if (imageReader != null) imageReader.close();
        if (mediaProjection != null) mediaProjection.stop();
        if (recognizer != null) recognizer.close();
        if (captureThread != null) captureThread.quitSafely();
        mainHandler.post(() -> {
            if (windowManager != null && overlayView != null) {
                try {
                    windowManager.removeView(overlayView);
                } catch (Exception ignored) {
                }
                overlayView = null;
            }
        });
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
