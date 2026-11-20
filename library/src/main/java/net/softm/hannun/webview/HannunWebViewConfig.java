package net.softm.hannun.webview;

/** Values owned by each consuming app; this library contains no app URLs or AdMob IDs. */
public final class HannunWebViewConfig {
    public final String title;
    public final String publicWebUrl;
    public final boolean debug;

    public HannunWebViewConfig(String title, String publicWebUrl, boolean debug) {
        if (title == null || title.trim().isEmpty() || !NativeInputPolicy.isHttpsUrl(publicWebUrl)) {
            throw new IllegalArgumentException("An app title and HTTPS public URL are required.");
        }
        this.title = title;
        this.publicWebUrl = publicWebUrl.endsWith("/") ? publicWebUrl : publicWebUrl + "/";
        this.debug = debug;
    }
}
