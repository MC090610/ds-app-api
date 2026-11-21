package com.deepseek.chat.local;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.View;
import android.view.Window;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;
import org.json.JSONArray;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.WebExtension;

import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.HttpURLConnection;
import java.net.URLDecoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String APP_ORIGIN = "https://appassets.androidplatform.net";
    private static final String APP_URL = APP_ORIGIN + "/index.html";
    private static final int FILE_CHOOSER_REQUEST = 4101;
    private static final int MICROPHONE_REQUEST = 4102;

    private WebView webView;
    private final ExecutorService accountExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest microphoneRequest;
    private boolean pageReady;
    private boolean accountResultPending;
    private GeckoSession tokenCaptureSession;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        webView.setBackgroundColor(Color.WHITE);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSupportMultipleWindows(false);
        settings.setDefaultTextEncodingName("UTF-8");
        settings.setTextZoom(100);
        settings.setUserAgentString(
            settings.getUserAgentString() + " DSAPIAndroid/" + BuildConfig.VERSION_NAME
        );

        webView.addJavascriptInterface(new AndroidFileBridge(), "DeepSeekAndroid");
        webView.setWebViewClient(new LocalWebViewClient());
        webView.setWebChromeClient(new LocalWebChromeClient());
        webView.setDownloadListener(new LocalDownloadListener());

        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
        applySystemBars(false);
        accountResultPending = isAccountReturnIntent(getIntent());
        if (savedInstanceState == null) {
            webView.loadUrl(APP_URL);
        } else {
            webView.restoreState(savedInstanceState);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
        webView.resumeTimers();
        if (pageReady) {
            webView.evaluateJavascript(
                "window.dispatchEvent(new CustomEvent('deepseek-platform-page-returned'))",
                null
            );
        }
    }

    @Override
    protected void onPause() {
        webView.onPause();
        webView.pauseTimers();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.removeJavascriptInterface("DeepSeekAndroid");
            webView.destroy();
        }
        accountExecutor.shutdownNow();
        mainHandler.removeCallbacksAndMessages(null);
        closeTokenCaptureSession();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    private void applySystemBars(boolean dark) {
        Window window = getWindow();
        int backgroundColor = dark ? Color.rgb(24, 24, 27) : Color.WHITE;
        window.setStatusBarColor(backgroundColor);
        window.setNavigationBarColor(backgroundColor);
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE;
        if (!dark) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        window.getDecorView().setSystemUiVisibility(flags);
    }

    private final class LocalWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            String scheme = uri.getScheme();
            if ("https".equals(scheme) && APP_ORIGIN.equals(uri.getScheme() + "://" + uri.getHost())) {
                return false;
            }
            if ("http".equals(scheme) || "https".equals(scheme)) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (ActivityNotFoundException ignored) {
                    Toast.makeText(MainActivity.this, "没有可打开此链接的浏览器", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
            return false;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri uri = request.getUrl();
            if (!APP_ORIGIN.equals(uri.getScheme() + "://" + uri.getHost())) {
                return null;
            }
            String assetPath = uri.getPath();
            if (assetPath == null || "/".equals(assetPath) || assetPath.isEmpty()) {
                assetPath = "/index.html";
            }
            try {
                assetPath = URLDecoder.decode(assetPath, StandardCharsets.UTF_8.name());
            } catch (Exception ignored) {
                return notFound();
            }
            if (!assetPath.startsWith("/") || assetPath.contains("..")) {
                return notFound();
            }
            try {
                InputStream stream = getAssets().open("web" + assetPath);
                Map<String, String> headers = new HashMap<>();
                headers.put("Access-Control-Allow-Origin", "*");
                headers.put("Cache-Control", "no-cache");
                return new WebResourceResponse(mimeType(assetPath), "UTF-8", 200, "OK", headers, stream);
            } catch (IOException error) {
                return notFound();
            }
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            pageReady = true;
            deliverPendingAccountResult();
            view.evaluateJavascript(
                "(function(){" +
                    "if(window.__deepseekAndroidTheme)return;" +
                    "window.__deepseekAndroidTheme=true;" +
                    "var apply=function(){if(window.DeepSeekAndroid){" +
                        "window.DeepSeekAndroid.setDarkTheme(document.body.classList.contains('dark'));" +
                    "}};" +
                    "new MutationObserver(apply).observe(document.body,{attributes:true,attributeFilter:['class']});" +
                    "apply();" +
                "})();",
                null
            );
        }
    }

    private final class LocalWebChromeClient extends WebChromeClient {
        @Override
        public boolean onShowFileChooser(
            WebView webView,
            ValueCallback<Uri[]> callback,
            FileChooserParams params
        ) {
            if (fileCallback != null) {
                fileCallback.onReceiveValue(null);
            }
            fileCallback = callback;
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            }
            try {
                startActivityForResult(Intent.createChooser(intent, "选择文件"), FILE_CHOOSER_REQUEST);
            } catch (ActivityNotFoundException error) {
                fileCallback = null;
                callback.onReceiveValue(null);
                Toast.makeText(MainActivity.this, "没有可用的文件选择器", Toast.LENGTH_SHORT).show();
                return false;
            }
            return true;
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            boolean audioOnly = true;
            for (String resource : request.getResources()) {
                if (!PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                    audioOnly = false;
                    break;
                }
            }
            String origin = request.getOrigin().toString();
            if (!audioOnly || !origin.startsWith(APP_ORIGIN)) {
                request.deny();
                return;
            }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                request.grant(request.getResources());
                return;
            }
            if (microphoneRequest != null) {
                microphoneRequest.deny();
            }
            microphoneRequest = request;
            requestPermissions(new String[] { Manifest.permission.RECORD_AUDIO }, MICROPHONE_REQUEST);
        }
    }

    private final class LocalDownloadListener implements DownloadListener {
        @Override
        public void onDownloadStart(
            String url,
            String userAgent,
            String contentDisposition,
            String mimetype,
            long contentLength
        ) {
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme();
            if ("http".equals(scheme) || "https".equals(scheme)) {
                DownloadManager.Request request = new DownloadManager.Request(uri);
                request.setMimeType(mimetype);
                request.addRequestHeader("User-Agent", userAgent);
                request.setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                );
                DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                manager.enqueue(request);
                return;
            }
            Toast.makeText(MainActivity.this, "该文件正在通过应用保存", Toast.LENGTH_SHORT).show();
        }
    }

    private final class AndroidFileBridge {
        private Uri pendingUri;
        private OutputStream pendingStream;
        private long written;
        private long expected;

        @JavascriptInterface
        public synchronized boolean beginFile(String name, String mime, long size) {
            abortFile();
            String displayName = sanitizeName(name);
            String displayMime = mime == null || mime.isEmpty()
                ? "application/octet-stream"
                : mime;
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, displayName);
            values.put(MediaStore.Downloads.MIME_TYPE, displayMime);
            values.put(
                MediaStore.Downloads.RELATIVE_PATH,
                Environment.DIRECTORY_DOWNLOADS + "/DS-API"
            );
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            pendingUri = getContentResolver().insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values
            );
            if (pendingUri == null) {
                return false;
            }
            try {
                pendingStream = getContentResolver().openOutputStream(pendingUri, "w");
                expected = size;
                written = 0;
                return pendingStream != null;
            } catch (IOException error) {
                abortFile();
                return false;
            }
        }

        @JavascriptInterface
        public synchronized boolean writeFile(String base64Chunk) {
            if (pendingStream == null) {
                return false;
            }
            try {
                byte[] bytes = android.util.Base64.decode(base64Chunk, android.util.Base64.DEFAULT);
                pendingStream.write(bytes);
                written += bytes.length;
                return true;
            } catch (Exception error) {
                abortFile();
                return false;
            }
        }

        @JavascriptInterface
        public synchronized void finishFile() {
            if (pendingStream == null || pendingUri == null) {
                return;
            }
            try {
                pendingStream.flush();
                pendingStream.close();
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.IS_PENDING, 0);
                values.put(MediaStore.Downloads.SIZE, written);
                getContentResolver().update(pendingUri, values, null, null);
                if (expected > 0 && written != expected) {
                    Toast.makeText(MainActivity.this, "文件已保存，但大小与预期不同", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(MainActivity.this, "已保存到 下载/DS-API", Toast.LENGTH_SHORT).show();
                }
            } catch (IOException error) {
                if (pendingUri != null) {
                    getContentResolver().delete(pendingUri, null, null);
                }
                Toast.makeText(MainActivity.this, "保存文件失败", Toast.LENGTH_SHORT).show();
            } finally {
                pendingUri = null;
                pendingStream = null;
                written = 0;
                expected = 0;
            }
        }

        @JavascriptInterface
        public synchronized void abortFile() {
            if (pendingStream != null) {
                try {
                    pendingStream.close();
                } catch (IOException ignored) {
                    // The URI is removed below whenever it exists.
                }
            }
            if (pendingUri != null) {
                getContentResolver().delete(pendingUri, null, null);
            }
            pendingUri = null;
            pendingStream = null;
            written = 0;
            expected = 0;
        }

        @JavascriptInterface
        public void notify(String message) {
            runOnUiThread(() ->
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show()
            );
        }

        @JavascriptInterface
        public void setDarkTheme(boolean dark) {
            runOnUiThread(() -> applySystemBars(dark));
        }

        @JavascriptInterface
        public void loginWithDeepSeek() {
            runOnUiThread(() ->
                startActivity(new Intent(MainActivity.this, AccountLoginActivity.class))
            );
        }

        @JavascriptInterface
        public void openPlatformPage(String page) {
            final String destination = PlatformPageActivity.PAGE_TOP_UP.equals(page)
                ? PlatformPageActivity.PAGE_TOP_UP
                : PlatformPageActivity.PAGE_USAGE;
            runOnUiThread(() -> {
                Intent intent = new Intent(MainActivity.this, PlatformPageActivity.class);
                intent.putExtra(PlatformPageActivity.EXTRA_PAGE, destination);
                startActivity(intent);
            });
        }

        @JavascriptInterface
        public void requestPlatformBalance(String requestId) {
            final String id = requestId == null ? "" : requestId;
            runOnUiThread(() -> {
                String token = PlatformTokenStore.load(MainActivity.this);
                if (token != null && !token.isEmpty()) {
                    requestPlatformBalanceWithToken(id, token);
                } else {
                    capturePlatformTokenForBalance(id);
                }
            });
        }
    }

    private void requestPlatformBalanceWithToken(String requestId, String token) {
        accountExecutor.execute(() -> {
            JSONObject result = new JSONObject();
            try {
                result.put("requestId", requestId);
                result.put("ok", true);
                result.put("wallets", fetchPlatformBalance(token));
            } catch (Exception error) {
                try {
                    result.put("requestId", requestId);
                    result.put("ok", false);
                    result.put(
                        "needsLogin",
                        PlatformTokenStore.load(MainActivity.this) == null
                    );
                    result.put("error", messageOf(error));
                } catch (Exception ignored) {
                    return;
                }
            }
            deliverPlatformBalanceResult(result);
        });
    }

    private void capturePlatformTokenForBalance(String requestId) {
        if (tokenCaptureSession != null) {
            deliverPlatformBalanceError(requestId, "正在读取开放平台账户信息", false);
            return;
        }
        GeckoRuntime runtime = GeckoRuntimeProvider.get(this);
        GeckoSession session = new GeckoSession(
            new GeckoSessionSettings.Builder()
                .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
                .allowJavascript(true)
                .build()
        );
        tokenCaptureSession = session;
        final boolean[] completed = { false };
        final Runnable[] timeout = new Runnable[1];

        Runnable finish = () -> {
            if (completed[0]) {
                return;
            }
            completed[0] = true;
            mainHandler.removeCallbacks(timeout[0]);
            closeTokenCaptureSession();
            String token = PlatformTokenStore.load(this);
            if (token == null || token.isEmpty()) {
                deliverPlatformBalanceError(
                    requestId,
                    "请先登录 DS-API 开放平台",
                    true
                );
            } else {
                requestPlatformBalanceWithToken(requestId, token);
            }
        };
        timeout[0] = finish;

        WebExtension.MessageDelegate delegate = new WebExtension.MessageDelegate() {
            @Override
            public GeckoResult<Object> onMessage(
                String nativeApp,
                Object message,
                WebExtension.MessageSender sender
            ) {
                if ("browser".equals(nativeApp) &&
                    sender.isTopLevel() &&
                    sender.url != null &&
                    sender.url.startsWith("https://platform.deepseek.com") &&
                    message instanceof JSONObject) {
                    JSONObject payload = (JSONObject) message;
                    if ("platform-token".equals(payload.optString("type", ""))) {
                        String token = payload.optString("token", "").trim();
                        if (!token.isEmpty()) {
                            PlatformTokenStore.save(MainActivity.this, token);
                            finish.run();
                        }
                    }
                }
                return GeckoResult.fromValue(null);
            }
        };

        session.open(runtime);
        runtime.getWebExtensionController()
            .ensureBuiltIn(
                "resource://android/assets/account-bridge/",
                "deepseek-account-bridge@local"
            )
            .accept(
                extension -> session.getWebExtensionController()
                    .setMessageDelegate(extension, delegate, "browser"),
                error -> {
                    if (!completed[0]) {
                        completed[0] = true;
                        closeTokenCaptureSession();
                        deliverPlatformBalanceError(
                            requestId,
                            "开放平台账户读取失败",
                            false
                        );
                    }
                }
            );
        session.loadUri("https://platform.deepseek.com/usage");
        mainHandler.postDelayed(timeout[0], 10_000);
    }

    private void deliverPlatformBalanceError(String requestId, String error, boolean needsLogin) {
        try {
            JSONObject result = new JSONObject();
            result.put("requestId", requestId);
            result.put("ok", false);
            result.put("needsLogin", needsLogin);
            result.put("error", error);
            deliverPlatformBalanceResult(result);
        } catch (Exception ignored) {
            // The frontend will time out and expose its retry action.
        }
    }

    private JSONArray fetchPlatformBalance(String token) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(
            "https://platform.deepseek.com/api/v0/users/get_user_summary"
        ).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(30_000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("x-client-bundle-id", "com.deepseek.chat");
        connection.setRequestProperty("x-client-platform", "web");
        connection.setRequestProperty("x-client-version", "1.0.1");
        connection.setRequestProperty(
            "x-client-locale",
            Locale.getDefault().getLanguage().equalsIgnoreCase("zh") ? "zh_CN" : "en_US"
        );
        connection.setRequestProperty(
            "x-client-timezone-offset",
            String.valueOf(TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000)
        );
        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 300
            ? connection.getInputStream()
            : connection.getErrorStream();
        String responseBody = stream == null ? "" : readResponse(stream);
        connection.disconnect();
        if (status == 401 || status == 403) {
            PlatformTokenStore.clear(this);
            throw new IllegalStateException("开放平台登录已失效，请重新登录");
        }
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("余额请求失败（HTTP " + status + "）");
        }

        JSONObject root = new JSONObject(responseBody);
        if (root.optInt("code", -1) != 0) {
            throw new IllegalStateException(root.optString("msg", "余额请求失败"));
        }
        JSONObject envelope = root.getJSONObject("data");
        if (envelope.optInt("biz_code", -1) != 0) {
            throw new IllegalStateException(
                envelope.optString("biz_msg", "余额接口业务校验失败")
            );
        }
        JSONObject data = envelope.optJSONObject("biz_data");
        if (data == null) {
            throw new IllegalStateException("余额接口缺少数据");
        }

        Map<String, BigDecimal[]> wallets = new LinkedHashMap<>();
        readWallets(data.optJSONArray("normal_wallets"), wallets, 0);
        readWallets(data.optJSONArray("bonus_wallets"), wallets, 1);
        JSONArray result = new JSONArray();
        for (Map.Entry<String, BigDecimal[]> item : wallets.entrySet()) {
            JSONObject wallet = new JSONObject();
            wallet.put("currency", item.getKey());
            wallet.put("recharge", formatAmount(item.getValue()[0]));
            wallet.put("bonus", formatAmount(item.getValue()[1]));
            wallet.put("total", formatAmount(item.getValue()[0].add(item.getValue()[1])));
            result.put(wallet);
        }
        return result;
    }

    private void closeTokenCaptureSession() {
        if (tokenCaptureSession != null) {
            tokenCaptureSession.close();
            tokenCaptureSession = null;
        }
    }

    private static void readWallets(
        JSONArray source,
        Map<String, BigDecimal[]> target,
        int index
    ) {
        if (source == null) {
            return;
        }
        for (int i = 0; i < source.length(); i++) {
            JSONObject item = source.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String currency = item.optString("currency", "CNY").toUpperCase(Locale.ROOT);
            BigDecimal amount;
            try {
                amount = new BigDecimal(item.optString("balance", "0"));
            } catch (NumberFormatException ignored) {
                amount = BigDecimal.ZERO;
            }
            BigDecimal[] wallet = target.get(currency);
            if (wallet == null) {
                wallet = new BigDecimal[] { BigDecimal.ZERO, BigDecimal.ZERO };
                target.put(currency, wallet);
            }
            wallet[index] = wallet[index].add(amount);
        }
    }

    private static String formatAmount(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private void deliverPlatformBalanceResult(JSONObject result) {
        if (webView == null) {
            return;
        }
        String script = "window.__deepSeekPlatformBalanceResult&&"
            + "window.__deepSeekPlatformBalanceResult("
            + JSONObject.quote(result.toString())
            + ")";
        runOnUiThread(() -> {
            if (webView != null) {
                webView.evaluateJavascript(script, null);
            }
        });
    }

    private static String readResponse(InputStream stream) throws Exception {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(stream, StandardCharsets.UTF_8)
        )) {
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) >= 0) {
                output.append(buffer, 0, count);
            }
        }
        return output.toString();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_CHOOSER_REQUEST || fileCallback == null) {
            return;
        }
        ArrayList<Uri> uris = new ArrayList<>();
        if (resultCode == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int count = data.getClipData().getItemCount();
                for (int index = 0; index < count; index++) {
                    uris.add(data.getClipData().getItemAt(index).getUri());
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
        }
        fileCallback.onReceiveValue(uris.isEmpty() ? null : uris.toArray(new Uri[0]));
        fileCallback = null;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (isAccountReturnIntent(intent)) {
            accountResultPending = true;
            deliverPendingAccountResult();
        }
    }

    private static boolean isAccountReturnIntent(Intent intent) {
        Uri data = intent == null ? null : intent.getData();
        return data != null &&
            "deepseekapp".equals(data.getScheme()) &&
            "auth-complete".equals(data.getHost());
    }

    private void deliverPendingAccountResult() {
        if (!pageReady || !accountResultPending) {
            return;
        }
        accountResultPending = false;
        android.content.SharedPreferences preferences = getSharedPreferences(
            AccountLoginActivity.RESULT_PREFS,
            Context.MODE_PRIVATE
        );
        if (!preferences.contains(AccountLoginActivity.RESULT_OK)) {
            deliverAccountLoginResult(false, null, "登录结果已失效，请重试", false);
            return;
        }
        boolean ok = preferences.getBoolean(AccountLoginActivity.RESULT_OK, false);
        String apiKey = preferences.getString(AccountLoginActivity.RESULT_API_KEY, null);
        String error = preferences.getString(AccountLoginActivity.RESULT_ERROR, null);
        boolean cancelled = preferences.getBoolean(AccountLoginActivity.RESULT_CANCELLED, false);
        preferences.edit().clear().apply();
        deliverAccountLoginResult(ok, apiKey, error, cancelled);
    }

    private void deliverAccountLoginResult(
        boolean ok,
        String apiKey,
        String error,
        boolean cancelled
    ) {
        if (webView == null) {
            return;
        }
        try {
            JSONObject result = new JSONObject();
            result.put("ok", ok);
            if (cancelled) {
                result.put("cancelled", true);
            }
            if (ok && apiKey != null) {
                result.put("apiKey", apiKey);
            } else if (error != null) {
                result.put("error", error);
            }
            String script = "window.__deepSeekAccountLoginResult&&"
                + "window.__deepSeekAccountLoginResult(" + JSONObject.quote(result.toString()) + ")";
            webView.evaluateJavascript(script, null);
        } catch (Exception failure) {
            webView.evaluateJavascript(
                "window.__deepSeekAccountLoginResult&&window.__deepSeekAccountLoginResult("
                    + JSONObject.quote("{\"ok\":false,\"error\":\"登录结果读取失败\"}")
                    + ")",
                null
            );
        }
    }

    @Override
    public void onRequestPermissionsResult(
        int requestCode,
        String[] permissions,
        int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != MICROPHONE_REQUEST || microphoneRequest == null) {
            return;
        }
        if (
            grantResults.length > 0 &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            microphoneRequest.grant(microphoneRequest.getResources());
        } else {
            microphoneRequest.deny();
            Toast.makeText(this, "需要麦克风权限才能使用语音输入", Toast.LENGTH_SHORT).show();
        }
        microphoneRequest = null;
    }

    private static String sanitizeName(String name) {
        String fallback = name == null ? "" : name.trim();
        if (fallback.isEmpty()) {
            fallback = "deepseek-download";
        }
        return fallback.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private static String messageOf(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isEmpty() ? "请求失败，请重试" : message;
    }

    private static String mimeType(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".html")) return "text/html";
        if (lower.endsWith(".js")) return "application/javascript";
        if (lower.endsWith(".css")) return "text/css";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".woff2")) return "font/woff2";
        if (lower.endsWith(".woff")) return "font/woff";
        if (lower.endsWith(".map")) return "application/json";
        return "application/octet-stream";
    }

    private static WebResourceResponse notFound() {
        return new WebResourceResponse(
            "text/plain",
            "UTF-8",
            404,
            "Not Found",
            new HashMap<>(),
            new ByteArrayInputStream("Not Found".getBytes(StandardCharsets.UTF_8))
        );
    }
}
