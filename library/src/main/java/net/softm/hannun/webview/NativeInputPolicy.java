package net.softm.hannun.webview;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Pure Java validation shared by the WebView bridge and its boundary tests. */
public final class NativeInputPolicy {
    public static final String ASSET_ORIGIN = "https://appassets.androidplatform.net";
    public static final String ASSET_URL = ASSET_ORIGIN + "/assets/index.html";
    public static final int MAX_FILE_BYTES = 5 * 1024 * 1024;
    public static final int MAX_MESSAGE_CHARS = 12 * 1024 * 1024;

    private NativeInputPolicy() {}

    public static boolean isTrustedOrigin(String url) {
        try {
            URI uri = URI.create(url);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && "appassets.androidplatform.net".equalsIgnoreCase(uri.getHost())
                    && (uri.getPort() == -1 || uri.getPort() == 443)
                    && uri.getUserInfo() == null;
        } catch (RuntimeException ignored) { return false; }
    }

    public static boolean isTrustedDocument(String url) {
        if (!isTrustedOrigin(url)) return false;
        try {
            String path = URI.create(url).getPath();
            return path != null && path.startsWith("/assets/")
                    && !path.contains("/../") && !path.contains("/./");
        } catch (RuntimeException ignored) { return false; }
    }

    public static boolean isHttpsUrl(String url) {
        try {
            URI uri = URI.create(url);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && uri.getHost() != null && !uri.getHost().trim().isEmpty()
                    && uri.getUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443);
        } catch (RuntimeException ignored) { return false; }
    }

    public static boolean isAppShareUrl(String candidate, String base) {
        if (!isHttpsUrl(candidate) || candidate.length() > 12000) return false;
        try {
            URI link = URI.create(candidate);
            URI root = URI.create(base);
            String path = link.getPath();
            return root.getHost().equalsIgnoreCase(link.getHost())
                    && path != null && path.startsWith(root.getPath())
                    && !path.contains("/../") && !path.contains("/./");
        } catch (RuntimeException ignored) { return false; }
    }

    public static boolean isAdFitRequest(String url) {
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
            return host.equals("adfit.kakao.com") || host.endsWith(".adfit.kakao.com")
                    || host.equals("ad.daum.net") || host.endsWith(".ad.daum.net")
                    || host.equals("kas.daum.net") || host.endsWith(".kas.daum.net")
                    || (host.equals("t1.daumcdn.net")
                    && (path.startsWith("/kas/") || path.startsWith("/adfit/")));
        } catch (RuntimeException ignored) { return false; }
    }

    public static String validatedFilename(String name, String mime) {
        if (!"application/json".equals(mime) && !"text/plain".equals(mime)) {
            throw new IllegalArgumentException("JSON 또는 텍스트 파일만 저장할 수 있습니다.");
        }
        if (name == null || name.trim().isEmpty() || name.length() > 100
                || name.startsWith(".") || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0
                || name.codePoints().anyMatch(Character::isISOControl)
                || name.matches(".*[:*?\"<>|].*")) {
            throw new IllegalArgumentException("올바른 파일 이름이 필요합니다.");
        }
        String extension = "application/json".equals(mime) ? ".json" : ".txt";
        if (!name.toLowerCase(Locale.ROOT).endsWith(extension)) {
            throw new IllegalArgumentException("파일 이름과 형식이 일치하지 않습니다.");
        }
        return name;
    }

    public static byte[] validatedBytes(String text) {
        if (text == null || text.length() > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("파일은 5 MB 이하만 저장할 수 있습니다.");
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("파일은 5 MB 이하만 저장할 수 있습니다.");
        }
        return bytes;
    }
}
