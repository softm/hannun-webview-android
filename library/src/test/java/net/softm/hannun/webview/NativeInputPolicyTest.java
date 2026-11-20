package net.softm.hannun.webview;

import org.junit.Test;
import static org.junit.Assert.*;

public final class NativeInputPolicyTest {
    @Test public void nativeBridgeAcceptsOnlyLocalHttpsDocuments() {
        assertTrue(NativeInputPolicy.isTrustedDocument(NativeInputPolicy.ASSET_URL));
        assertTrue(NativeInputPolicy.isTrustedDocument(NativeInputPolicy.ASSET_URL + "#invite=test"));
        assertFalse(NativeInputPolicy.isTrustedDocument("http://appassets.androidplatform.net/assets/index.html"));
        assertFalse(NativeInputPolicy.isTrustedDocument("https://appassets.androidplatform.net.evil.test/assets/index.html"));
        assertFalse(NativeInputPolicy.isTrustedDocument("https://user@appassets.androidplatform.net/assets/index.html"));
        assertFalse(NativeInputPolicy.isTrustedDocument("https://appassets.androidplatform.net:444/assets/index.html"));
        assertFalse(NativeInputPolicy.isTrustedDocument("https://appassets.androidplatform.net/assets/%2e%2e/private"));
        assertFalse(NativeInputPolicy.isTrustedDocument("file:///android_asset/index.html"));
    }

    @Test public void exportRejectsExecutableAndTraversalNames() {
        assertEquals("그리움-백업.json", NativeInputPolicy.validatedFilename("그리움-백업.json", "application/json"));
        assertEquals("너에게, 오늘 🌙.txt", NativeInputPolicy.validatedFilename("너에게, 오늘 🌙.txt", "text/plain"));
        assertThrows(IllegalArgumentException.class, () -> NativeInputPolicy.validatedFilename("../backup.json", "application/json"));
        assertThrows(IllegalArgumentException.class, () -> NativeInputPolicy.validatedFilename("page.html", "text/html"));
        assertThrows(IllegalArgumentException.class, () -> NativeInputPolicy.validatedFilename("note.txt.exe", "text/plain"));
        assertThrows(IllegalArgumentException.class, () -> NativeInputPolicy.validatedFilename("line\nbreak.json", "application/json"));
    }

    @Test public void exportLimitCountsUtf8Bytes() {
        assertEquals(6, NativeInputPolicy.validatedBytes("한눈").length);
        assertEquals(NativeInputPolicy.MAX_FILE_BYTES,
                NativeInputPolicy.validatedBytes("x".repeat(NativeInputPolicy.MAX_FILE_BYTES)).length);
        assertThrows(IllegalArgumentException.class, () ->
                NativeInputPolicy.validatedBytes("한".repeat(NativeInputPolicy.MAX_FILE_BYTES / 3 + 1)));
    }

    @Test public void adFitIsBlockedWithoutBlockingOrdinaryCdnAssets() {
        assertTrue(NativeInputPolicy.isAdFitRequest("https://t1.daumcdn.net/kas/static/ba.min.js"));
        assertTrue(NativeInputPolicy.isAdFitRequest("https://display.ad.daum.net/foo"));
        assertFalse(NativeInputPolicy.isAdFitRequest("https://t1.daumcdn.net/mapjsapi/bundle.js"));
        assertFalse(NativeInputPolicy.isAdFitRequest("https://adfit.kakao.com.evil.test/file.js"));
    }

    @Test public void shareLinksCannotLeaveTheAppsPublicPath() {
        String base = "https://softm.github.io/hannunae-web/";
        assertTrue(NativeInputPolicy.isAppShareUrl(base + "#invite=abc", base));
        assertFalse(NativeInputPolicy.isAppShareUrl("https://softm.github.io/other/", base));
        assertFalse(NativeInputPolicy.isAppShareUrl("https://softm.github.io/hannunae-web/%2e%2e/other/", base));
        assertFalse(NativeInputPolicy.isAppShareUrl("javascript:alert(1)", base));
    }
}
