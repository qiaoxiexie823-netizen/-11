package com.ruisi.changanmatch;

import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Locale;

final class OfflineLicense {
    private static final String PREFIX = "SQ2";
    private static final String PUBLIC_KEY_BASE64 =
            "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE5BkWbuphUm0iSd3E54z+8QBy3HO3/SP11xAP4qKnF6YF/oQmxqUMdDpyjqM9w0sM0Iz9ZNV4IpLZveuz7qyOUA==";

    private OfflineLicense() { }

    static Result verify(String licenseKey, String machineId, long nowMillis) {
        if (licenseKey == null || licenseKey.trim().isEmpty()) {
            return Result.invalid("请输入卡密");
        }

        try {
            String normalizedKey = licenseKey.trim();
            String[] parts = normalizedKey.split("\\.");
            if (parts.length != 5 || !PREFIX.equals(parts[0])) {
                return Result.invalid("卡密格式不正确");
            }

            String type = parts[1].toUpperCase(Locale.US);
            if (!"D".equals(type) && !"U".equals(type)) {
                return Result.invalid("卡密类型不正确");
            }

            String expiryToken = parts[2].toUpperCase(Locale.US);
            String nonce = parts[3].toUpperCase(Locale.US);
            if (!nonce.matches("[A-Z0-9]{6,16}")) {
                return Result.invalid("卡密校验信息不正确");
            }

            boolean permanent = "P".equals(expiryToken);
            long expiresAtMillis = Long.MAX_VALUE;
            if (!permanent) {
                long expirySeconds = Long.parseLong(expiryToken, 36);
                if (expirySeconds <= 0L || expirySeconds > Long.MAX_VALUE / 1000L) {
                    return Result.invalid("卡密有效期不正确");
                }
                expiresAtMillis = expirySeconds * 1000L;
                if (expiresAtMillis <= nowMillis) {
                    return Result.invalid("卡密已到期");
                }
            }

            String normalizedMachineId = normalizeMachineId(machineId);
            if ("D".equals(type) && normalizedMachineId.length() != 16) {
                return Result.invalid("本机号读取失败");
            }

            String target = "D".equals(type) ? normalizedMachineId : "*";
            String payload = PREFIX + "|" + type + "|" + expiryToken + "|" + nonce + "|" + target;

            PublicKey publicKey = loadPublicKey();
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(publicKey);
            verifier.update(payload.getBytes(StandardCharsets.UTF_8));

            byte[] signatureBytes = Base64.decode(parts[4],
                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            if (!verifier.verify(signatureBytes)) {
                return Result.invalid("卡密与本机号不匹配或已损坏");
            }

            return Result.valid(
                    "D".equals(type) ? "本机绑定卡密" : "通用卡密",
                    expiresAtMillis,
                    permanent,
                    "D".equals(type));
        } catch (Exception ignored) {
            return Result.invalid("卡密验证失败");
        }
    }

    static String normalizeMachineId(String value) {
        if (value == null) return "";
        return value.toUpperCase(Locale.US).replaceAll("[^A-Z0-9]", "");
    }

    private static PublicKey loadPublicKey() throws Exception {
        byte[] bytes = Base64.decode(PUBLIC_KEY_BASE64, Base64.DEFAULT);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(bytes);
        return KeyFactory.getInstance("EC").generatePublic(spec);
    }

    static final class Result {
        final boolean valid;
        final String message;
        final String typeLabel;
        final long expiresAtMillis;
        final boolean permanent;
        final boolean deviceBound;

        private Result(boolean valid, String message, String typeLabel,
                       long expiresAtMillis, boolean permanent, boolean deviceBound) {
            this.valid = valid;
            this.message = message;
            this.typeLabel = typeLabel;
            this.expiresAtMillis = expiresAtMillis;
            this.permanent = permanent;
            this.deviceBound = deviceBound;
        }

        static Result invalid(String message) {
            return new Result(false, message, "", 0L, false, false);
        }

        static Result valid(String typeLabel, long expiresAtMillis,
                            boolean permanent, boolean deviceBound) {
            return new Result(true, "卡密验证成功", typeLabel,
                    expiresAtMillis, permanent, deviceBound);
        }
    }
}
