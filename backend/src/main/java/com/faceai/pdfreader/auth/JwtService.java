package com.faceai.pdfreader.auth;

import com.faceai.pdfreader.config.AuthProperties;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class JwtService {

    private static final Pattern USER_ID_PATTERN = Pattern.compile("\"uid\"\\s*:\\s*(\\d+)");
    private static final Pattern USERNAME_PATTERN = Pattern.compile("\"sub\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern DISPLAY_NAME_PATTERN = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern EXPIRES_PATTERN = Pattern.compile("\"exp\"\\s*:\\s*(\\d+)");

    private final AuthProperties authProperties;

    public JwtService(AuthProperties authProperties) {
        this.authProperties = authProperties;
    }

    public String createToken(AuthUser user) {
        long ttlHours = authProperties.tokenTtlHours() == null ? 168 : authProperties.tokenTtlHours();
        long expiresAt = Instant.now().plusSeconds(ttlHours * 3600).getEpochSecond();
        String header = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
        String payload = "{\"uid\":" + user.id()
                + ",\"sub\":\"" + escape(user.username())
                + "\",\"name\":\"" + escape(user.displayName())
                + "\",\"exp\":" + expiresAt + "}";
        String unsignedToken = base64Url(header) + "." + base64Url(payload);
        return unsignedToken + "." + sign(unsignedToken);
    }

    public AuthUser parseToken(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("登录凭证不能为空");
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw new IllegalArgumentException("登录凭证格式错误");
        }
        String unsignedToken = parts[0] + "." + parts[1];
        if (!constantTimeEquals(sign(unsignedToken), parts[2])) {
            throw new IllegalArgumentException("登录凭证签名无效");
        }
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        long expiresAt = extractLong(payload, EXPIRES_PATTERN);
        // 用 >= 而不是 >：exp 秒本身即视为已过期。用 > 会给出最多 1 秒的额外有效期。
        if (Instant.now().getEpochSecond() >= expiresAt) {
            throw new IllegalArgumentException("登录已过期，请重新登录");
        }
        return new AuthUser(
                extractLong(payload, USER_ID_PATTERN),
                unescape(extractString(payload, USERNAME_PATTERN)),
                unescape(extractString(payload, DISPLAY_NAME_PATTERN))
        );
    }

    private String sign(String unsignedToken) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(unsignedToken.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("生成登录凭证失败", ex);
        }
    }

    private String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private String secret() {
        String secret = authProperties.jwtSecret();
        if (secret == null || secret.length() < 24) {
            throw new IllegalStateException("JWT 密钥长度过短");
        }
        return secret;
    }

    private Long extractLong(String payload, Pattern pattern) {
        return Long.parseLong(extractString(payload, pattern));
    }

    private String extractString(String payload, Pattern pattern) {
        Matcher matcher = pattern.matcher(payload);
        if (!matcher.find()) {
            throw new IllegalArgumentException("登录凭证内容无效");
        }
        return matcher.group(1);
    }

    private boolean constantTimeEquals(String left, String right) {
        byte[] leftBytes = left.getBytes(StandardCharsets.UTF_8);
        byte[] rightBytes = right.getBytes(StandardCharsets.UTF_8);
        if (leftBytes.length != rightBytes.length) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < leftBytes.length; i++) {
            result |= leftBytes[i] ^ rightBytes[i];
        }
        return result == 0;
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String unescape(String value) {
        return value == null ? "" : value.replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
