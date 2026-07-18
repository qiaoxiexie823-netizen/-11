package com.ruisi.changanmatch;

import android.content.Context;
import android.provider.Settings;

import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.Dns;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

final class LicenseClient {
    private static final String API_URL =
            "https://shenqing-api.qiaoxiexie823.workers.dev/api/license";
    private static final MediaType JSON =
            MediaType.parse("application/json; charset=utf-8");

    /**
     * 部分中国大陆移动网络会返回 IPv6 地址，但 IPv6 路由无法稳定连接。
     * 这里优先尝试 IPv4，失败后仍会保留 IPv6 作为备用。
     */
    private static final Dns IPV4_FIRST_DNS = hostname -> {
        List<InetAddress> resolved = new ArrayList<>(Dns.SYSTEM.lookup(hostname));
        Collections.sort(resolved, (left, right) -> {
            boolean leftIsIpv4 = left instanceof Inet4Address;
            boolean rightIsIpv4 = right instanceof Inet4Address;
            if (leftIsIpv4 == rightIsIpv4) return 0;
            return leftIsIpv4 ? -1 : 1;
        });
        return resolved;
    };

    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .dns(IPV4_FIRST_DNS)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .writeTimeout(12, TimeUnit.SECONDS)
            .callTimeout(22, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build();

    interface Callback {
        void onResult(boolean success, String message, String expiresAt);
    }

    private LicenseClient() { }

    static void verify(Context context, String licenseKey, Callback callback) {
        String normalizedKey = licenseKey == null
                ? ""
                : licenseKey.trim().toUpperCase(Locale.US);

        new Thread(() -> {
            try {
                JSONObject jsonBody = new JSONObject();
                jsonBody.put("license_key", normalizedKey);
                jsonBody.put("device_id", stableDeviceId(context));

                RequestBody requestBody = RequestBody.create(jsonBody.toString(), JSON);
                Request request = new Request.Builder()
                        .url(API_URL)
                        .header("Accept", "application/json")
                        .header("Cache-Control", "no-cache")
                        .header("User-Agent", "ShenqingAssistant/1.3.1 Android")
                        .post(requestBody)
                        .build();

                try (Response response = HTTP_CLIENT.newCall(request).execute()) {
                    ResponseBody body = response.body();
                    String responseText = body == null ? "{}" : body.string();

                    JSONObject responseJson = new JSONObject(responseText);
                    boolean success = responseJson.optBoolean("success", false);
                    String message = responseJson.optString(
                            "message", success ? "卡密验证成功" : "卡密验证失败");
                    String expiresAt = "";
                    JSONObject data = responseJson.optJSONObject("data");
                    if (data != null) {
                        expiresAt = data.optString("expires_at", "");
                    }

                    callbackOnMain(context, callback, success, message, expiresAt);
                }
            } catch (Exception ignored) {
                callbackOnMain(
                        context,
                        callback,
                        false,
                        "暂时无法连接卡密服务器，请切换 Wi-Fi 或移动数据后重试。此版本不需要 VPN。",
                        "");
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
}
