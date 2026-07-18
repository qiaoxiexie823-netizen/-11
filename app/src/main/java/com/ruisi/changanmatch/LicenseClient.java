package com.ruisi.changanmatch;

import android.content.Context;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

final class LicenseClient {
    private static final String API_URL =
            "https://shenqing-api.qiaoxiexie823.workers.dev/api/license";

    interface Callback {
        void onResult(boolean success, String message, String expiresAt);
    }

    private LicenseClient() { }

    static void verify(Context context, String licenseKey, Callback callback) {
        String normalizedKey = licenseKey == null
                ? ""
                : licenseKey.trim().toUpperCase(Locale.US);

        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(API_URL);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                connection.setRequestProperty("Accept", "application/json");

                JSONObject body = new JSONObject();
                body.put("license_key", normalizedKey);
                body.put("device_id", stableDeviceId(context));

                byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(payload.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(payload);
                }

                int responseCode = connection.getResponseCode();
                InputStream stream = responseCode >= 200 && responseCode < 300
                        ? connection.getInputStream()
                        : connection.getErrorStream();
                String responseText = readStream(stream);

                JSONObject response = new JSONObject(responseText);
                boolean success = response.optBoolean("success", false);
                String message = response.optString(
                        "message", success ? "卡密验证成功" : "卡密验证失败");
                String expiresAt = "";
                JSONObject data = response.optJSONObject("data");
                if (data != null) {
                    expiresAt = data.optString("expires_at", "");
                }

                callbackOnMain(context, callback, success, message, expiresAt);
            } catch (Exception error) {
                String detail = error.getMessage();
                String message = "无法连接卡密服务器，请检查网络后重试";
                if (detail != null && !detail.trim().isEmpty()) {
                    message += "（" + detail + "）";
                }
                callbackOnMain(context, callback, false, message, "");
            } finally {
                if (connection != null) connection.disconnect();
            }
        }, "license-check").start();
    }

    static String shortDeviceId(Context context) {
        String value = stableDeviceId(context);
        return value.length() > 16 ? value.substring(0, 16) : value;
    }

    private static void callbackOnMain(Context context, Callback callback,
                                       boolean success, String message, String expiresAt) {
        if (context instanceof android.app.Activity) {
            ((android.app.Activity) context).runOnUiThread(
                    () -> callback.onResult(success, message, expiresAt));
        }
    }

    private static String stableDeviceId(Context context) {
        String androidId = Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (androidId == null || androidId.trim().isEmpty()) {
            androidId = "unknown-android-device";
        }
        String source = androidId + "|" + context.getPackageName();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte value : bytes) {
                result.append(String.format(Locale.US, "%02x", value & 0xff));
            }
            return result.toString();
        } catch (Exception ignored) {
            return source;
        }
    }

    private static String readStream(InputStream input) throws Exception {
        if (input == null) return "{}";
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line);
            }
        }
        return result.toString();
    }
}
