package net.softm.hannun.webview;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.browser.customtabs.CustomTabsIntent;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebMessageCompat;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Reusable local HTML host. Apps retain their own content, advertising IDs and Play update manager.
 * Native privileges are exposed only to the main frame of the HTTPS appassets origin.
 */
public abstract class HannunWebViewActivity extends ComponentActivity {
    private static final String BRIDGE = "HannunNative";
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private WebView webView;
    private WebViewAssetLoader assetLoader;
    private HannunWebViewConfig config;
    private ValueCallback<Uri[]> fileCallback;
    private byte[] pendingDownload;
    private JavaScriptReplyProxy downloadReply;
    private boolean destroyed;
    private boolean bridgeAvailable;
    private long documentGeneration;

    protected abstract HannunWebViewConfig webConfig();
    protected void onWebViewCreated(WebView view, LinearLayout nativeAdHost) {}
    protected void onCheckForUpdateRequested() {}
    protected void onPrivacyOptionsRequested() {
        toast("현재 이 앱의 광고가 비활성화되어 있습니다.");
    }
    protected void onExitRequested() { showPlainExitConfirmation(); }

    private final ActivityResultLauncher<Intent> openFile = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
        ValueCallback<Uri[]> callback = fileCallback;
        fileCallback = null;
        if (callback == null) return;
        Uri uri = result.getResultCode() == RESULT_OK && result.getData() != null
                ? result.getData().getData() : null;
        if (uri == null || !"content".equals(uri.getScheme())) {
            callback.onReceiveValue(null);
            return;
        }
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)
                    && cursor.getLong(0) > NativeInputPolicy.MAX_FILE_BYTES) {
                toast("5 MB 이하의 백업 파일을 선택해 주세요.");
                callback.onReceiveValue(null);
                return;
            }
        } catch (RuntimeException ignored) {
            // Some document providers do not report size; the web app also validates import size.
        }
        callback.onReceiveValue(new Uri[]{uri});
    });

    private final ActivityResultLauncher<Intent> createFile = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
        byte[] bytes = pendingDownload;
        JavaScriptReplyProxy reply = downloadReply;
        pendingDownload = null;
        downloadReply = null;
        Uri uri = result.getResultCode() == RESULT_OK && result.getData() != null
                ? result.getData().getData() : null;
        if (uri == null || bytes == null || !"content".equals(uri.getScheme())) {
            respond(reply, "downloadResult", false, true, "파일 저장을 취소했습니다.");
            return;
        }
        long generation = documentGeneration;
        io.execute(() -> {
            boolean success = false;
            try (OutputStream stream = getContentResolver().openOutputStream(uri, "wt")) {
                if (stream == null) throw new IllegalStateException("No output stream");
                stream.write(bytes);
                stream.flush();
                success = true;
            } catch (Exception ignored) {}
            boolean ok = success;
            runOnUiThread(() -> {
                if (destroyed) return;
                toast(ok ? "파일을 저장했습니다." : "파일을 저장하지 못했습니다.");
                if (generation == documentGeneration) {
                    respond(reply, "downloadResult", ok, false,
                            ok ? "파일을 저장했습니다." : "파일을 저장하지 못했습니다.");
                }
            });
        });
    });

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        config = webConfig();
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(255, 253, 248));
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets system = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            Insets keyboard = insets.getInsets(WindowInsetsCompat.Type.ime());
            view.setPadding(system.left, system.top, system.right, Math.max(system.bottom, keyboard.bottom));
            return insets;
        });
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(14), 0, dp(8), 0);
        TextView title = new TextView(this);
        title.setText(config.title);
        title.setTextSize(17);
        title.setTextColor(Color.rgb(48, 51, 49));
        bar.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
        title.setGravity(Gravity.CENTER_VERTICAL);
        Button menu = new Button(this);
        menu.setText("앱 메뉴");
        menu.setContentDescription("앱 메뉴, 업데이트와 개인정보 설정");
        menu.setMinHeight(dp(48));
        menu.setOnClickListener(this::showAppMenu);
        bar.addView(menu, new LinearLayout.LayoutParams(-2, dp(48)));
        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(52)));

        webView = new WebView(this);
        webView.setId(View.generateViewId());
        webView.setBackgroundColor(Color.rgb(255, 253, 248));
        root.addView(webView, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout adHost = new LinearLayout(this);
        adHost.setOrientation(LinearLayout.VERTICAL);
        adHost.setVisibility(View.GONE);
        root.addView(adHost, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
        ViewCompat.requestApplyInsets(root);

        assetLoader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();
        configureWebView();
        onWebViewCreated(webView, adHost);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack();
                else onExitRequested();
            }
        });
        if (state == null || webView.restoreState(state) == null
                || !NativeInputPolicy.isTrustedDocument(webView.getUrl())) {
            webView.loadUrl(NativeInputPolicy.ASSET_URL);
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @SuppressWarnings("deprecation")
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true); // Needed only for user-selected SAF input files.
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(true);
        settings.setMediaPlaybackRequiresUserGesture(true);
        if (!settings.getUserAgentString().contains("AdMobNative/1")) {
            settings.setUserAgentString(settings.getUserAgentString() + " AdMobNative/1");
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);
        WebView.setWebContentsDebuggingEnabled(config.debug);

        bridgeAvailable = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER);
        if (bridgeAvailable) {
            WebViewCompat.addWebMessageListener(webView, BRIDGE,
                    Collections.singleton(NativeInputPolicy.ASSET_ORIGIN),
                    (view, message, sourceOrigin, isMainFrame, reply) -> {
                if (!isMainFrame || !NativeInputPolicy.isTrustedOrigin(sourceOrigin.toString())
                        || !NativeInputPolicy.isTrustedDocument(view.getUrl())
                        || message.getType() != WebMessageCompat.TYPE_STRING) return;
                handleMessage(message.getData(), reply);
            });
        }
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return !NativeInputPolicy.isTrustedDocument(request.getUrl().toString());
                String url = request.getUrl().toString();
                if (NativeInputPolicy.isTrustedDocument(url)) return false;
                if (request.hasGesture()) openExternal(url);
                return true;
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (NativeInputPolicy.isAdFitRequest(url)) return emptyResponse();
                if (NativeInputPolicy.isTrustedOrigin(url)) {
                    WebResourceResponse asset = assetLoader.shouldInterceptRequest(request.getUrl());
                    return asset == null ? emptyResponse() : asset;
                }
                // Bundled app documents need no remote scripts or tracking. External links use the browser.
                return emptyResponse();
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                documentGeneration++;
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (NativeInputPolicy.isTrustedDocument(url)) installWebFallbacks();
            }
            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) toast("화면을 열지 못했습니다. 앱을 다시 열어 주세요.");
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                                       FileChooserParams params) {
                if (!NativeInputPolicy.isTrustedDocument(view.getUrl())) return false;
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("*/*")
                        .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "application/octet-stream"});
                try { openFile.launch(intent); }
                catch (ActivityNotFoundException error) {
                    fileCallback = null;
                    callback.onReceiveValue(null);
                    toast("파일 선택 앱을 찾지 못했습니다.");
                }
                return true;
            }
            @Override public boolean onCreateWindow(WebView view, boolean dialog, boolean gesture,
                                                     android.os.Message resultMessage) {
                if (!gesture || !NativeInputPolicy.isTrustedDocument(view.getUrl())) return false;
                String url = view.getHitTestResult().getExtra();
                if (NativeInputPolicy.isHttpsUrl(url)) { openExternal(url); return false; }
                return false;
            }
        });
        webView.setDownloadListener((url, agent, disposition, mime, length) -> {
            if (!NativeInputPolicy.isTrustedDocument(webView.getUrl())) return;
            if (url != null && url.startsWith("blob:" + NativeInputPolicy.ASSET_ORIGIN + "/")) {
                toast("화면의 내보내기 버튼을 다시 눌러 주세요.");
            } else if (NativeInputPolicy.isHttpsUrl(url)) {
                openExternal(url);
            }
        });
    }

    private void installWebFallbacks() {
        String script = "(function(){"
                + "if(window.__hannunNativeInstalled)return;window.__hannunNativeInstalled=true;"
                + "var removeAds=function(){document.querySelectorAll('ins.kakao_ad_area,[data-adfit],.adfit-slot,.adfit-container,.adfit-banner,[id^=\"adfit-\"]').forEach(function(e){e.remove();});};"
                + "removeAds();new MutationObserver(removeAds).observe(document.documentElement,{childList:true,subtree:true});"
                + "if(window.HannunNative){"
                + "window.print=function(){HannunNative.postMessage(JSON.stringify({type:'print'}));};"
                + "document.addEventListener('click',function(e){var a=e.target.closest&&e.target.closest('a[download]');"
                + "if(!a||!a.href.startsWith('blob:" + NativeInputPolicy.ASSET_ORIGIN + "/'))return;"
                + "e.preventDefault();e.stopImmediatePropagation();"
                + "fetch(a.href).then(function(r){return r.blob();}).then(function(b){"
                + "if(b.size>5242880)throw Error('size');return b.text().then(function(t){"
                + "var m=b.type.split(';')[0];if(m!=='application/json'&&m!=='text/plain')throw Error('type');"
                + "HannunNative.postMessage(JSON.stringify({type:'downloadText',name:a.download,mime:m,text:t}));});"
                + "}).catch(function(){alert('파일을 저장하지 못했습니다. 내보내기를 다시 실행해 주세요.');});"
                + "},true);}})();";
        webView.evaluateJavascript(script, null);
    }

    private void handleMessage(String raw, JavaScriptReplyProxy reply) {
        if (raw == null || raw.length() > NativeInputPolicy.MAX_MESSAGE_CHARS) return;
        try {
            JSONObject message = new JSONObject(raw);
            String type = message.optString("type");
            switch (type) {
                case "downloadText":
                    saveText(message, reply);
                    break;
                case "print":
                    printDocument(reply);
                    break;
                case "share":
                    share(message.optString("title", config.title), message.optString("text"),
                            message.optString("url", config.publicWebUrl));
                    break;
                default:
                    respond(reply, "error", false, false, "지원하지 않는 요청입니다.");
            }
        } catch (Exception error) {
            respond(reply, "downloadResult", false, false, "요청을 처리하지 못했습니다.");
        }
    }

    private void saveText(JSONObject message, JavaScriptReplyProxy reply) throws Exception {
        if (pendingDownload != null) {
            respond(reply, "downloadResult", false, false, "먼저 열려 있는 파일 저장을 마쳐 주세요.");
            return;
        }
        String mime = message.getString("mime");
        String name = NativeInputPolicy.validatedFilename(message.getString("name"), mime);
        byte[] bytes = NativeInputPolicy.validatedBytes(message.getString("text"));
        // Backups may preserve damaged source data for recovery; do not reinterpret their contents.
        pendingDownload = bytes;
        downloadReply = reply;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(mime)
                .putExtra(Intent.EXTRA_TITLE, name);
        try { createFile.launch(intent); }
        catch (RuntimeException error) {
            pendingDownload = null;
            downloadReply = null;
            respond(reply, "downloadResult", false, false, "파일 저장 앱을 찾지 못했습니다.");
        }
    }

    private void printDocument(JavaScriptReplyProxy reply) {
        if (!NativeInputPolicy.isTrustedDocument(webView.getUrl())) return;
        PrintManager manager = (PrintManager) getSystemService(PRINT_SERVICE);
        if (manager == null) {
            respond(reply, "printResult", false, false, "인쇄를 지원하지 않는 기기입니다.");
            return;
        }
        try {
            PrintDocumentAdapter delegate = webView.createPrintDocumentAdapter(config.title);
            PrintDocumentAdapter adapter = new PrintDocumentAdapter() {
                @Override public void onStart() { delegate.onStart(); }
                @Override public void onLayout(PrintAttributes oldAttributes, PrintAttributes newAttributes,
                        CancellationSignal signal, LayoutResultCallback callback, Bundle extras) {
                    delegate.onLayout(oldAttributes, newAttributes, signal, callback, extras);
                }
                @Override public void onWrite(PageRange[] pages, ParcelFileDescriptor destination,
                        CancellationSignal signal, WriteResultCallback callback) {
                    delegate.onWrite(pages, destination, signal, callback);
                }
                @Override public void onFinish() {
                    delegate.onFinish();
                    respond(reply, "printResult", true, false, "인쇄 화면을 닫았습니다.");
                }
            };
            manager.print(config.title, adapter, new PrintAttributes.Builder().build());
        } catch (RuntimeException error) {
            respond(reply, "printResult", false, false, "인쇄 화면을 열지 못했습니다.");
        }
    }

    private void share(String title, String text, String url) {
        if (title.length() > 120 || text.length() > 5000
                || !NativeInputPolicy.isAppShareUrl(url, config.publicWebUrl)) {
            toast("공유 링크를 확인해 주세요.");
            return;
        }
        Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, title)
                .putExtra(Intent.EXTRA_TEXT, text.isEmpty() ? url : text + "\n" + url);
        try { startActivity(Intent.createChooser(share, "공유할 앱 선택")); }
        catch (ActivityNotFoundException error) { toast("공유할 앱을 찾지 못했습니다."); }
    }

    private void showAppMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "앱 업데이트 확인");
        menu.getMenu().add(0, 2, 1, "광고 개인정보 설정");
        menu.getMenu().add(0, 4, 3, "웹으로 열기");
        menu.getMenu().add(0, 5, 4, "서비스 공유");
        menu.getMenu().add(0, 6, 5, "앱 종료");
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: onCheckForUpdateRequested(); break;
                case 2: onPrivacyOptionsRequested(); break;
                case 4: openExternal(config.publicWebUrl); break;
                case 5: share(config.title, "", config.publicWebUrl); break;
                case 6: onExitRequested(); break;
                default: return false;
            }
            return true;
        });
        menu.show();
    }

    private void openExternal(String url) {
        if (!NativeInputPolicy.isHttpsUrl(url) || NativeInputPolicy.isTrustedOrigin(url)) {
            toast("HTTPS 웹 링크만 열 수 있습니다.");
            return;
        }
        Uri uri = Uri.parse(url);
        try { new CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(this, uri); }
        catch (ActivityNotFoundException error) {
            try { startActivity(new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)); }
            catch (ActivityNotFoundException ignored) { toast("웹 브라우저를 찾지 못했습니다."); }
        }
    }

    protected final void showPlainExitConfirmation() {
        new AlertDialog.Builder(this)
                .setTitle("앱을 종료하시겠습니까?")
                .setMessage("저장한 기록은 다음에 다시 볼 수 있습니다.")
                .setNegativeButton("계속 이용", null)
                .setPositiveButton("종료", (dialog, which) -> finish())
                .show();
    }

    protected final WebView getHannunWebView() { return webView; }
    protected final void toast(String message) {
        if (!destroyed) Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void respond(JavaScriptReplyProxy reply, String type, boolean ok, boolean cancelled, String message) {
        if (reply == null || destroyed) return;
        try {
            JSONObject result = new JSONObject().put("type", type).put("ok", ok)
                    .put("cancelled", cancelled).put("message", message);
            reply.postMessage(result.toString());
        } catch (Exception ignored) {}
    }

    private static WebResourceResponse emptyResponse() {
        return new WebResourceResponse("text/plain", "UTF-8", 204, "No Content",
                Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override protected void onSaveInstanceState(Bundle outState) {
        if (webView != null) webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override protected void onPause() {
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        pendingDownload = null;
        downloadReply = null;
        if (fileCallback != null) { fileCallback.onReceiveValue(null); fileCallback = null; }
        io.shutdown();
        if (webView != null) {
            if (bridgeAvailable) WebViewCompat.removeWebMessageListener(webView, BRIDGE);
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(new WebViewClient());
            webView.destroy();
        }
        super.onDestroy();
    }
}
