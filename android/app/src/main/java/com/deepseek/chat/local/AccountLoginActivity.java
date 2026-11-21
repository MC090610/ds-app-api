package com.deepseek.chat.local;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;
import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebExtension;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Hosts DeepSeek Platform's official sign-in page in a GeckoView engine. The
 * bundled content script reads Platform's persisted login token after sign-in
 * and forwards it to the native layer without exposing or storing it.
 */
public final class AccountLoginActivity extends Activity {
    public static final String RESULT_PREFS = "deepseek_account_login_result";
    public static final String RESULT_OK = "ok";
    public static final String RESULT_API_KEY = "api_key";
    public static final String RESULT_ERROR = "error";
    public static final String RESULT_CANCELLED = "cancelled";

    private static final String TAG = "DeepSeekAccountLogin";
    private static final String PLATFORM_ORIGIN = "https://platform.deepseek.com";
    private static final String SIGN_IN_URL = PLATFORM_ORIGIN + "/sign_in";
    private static final String API_ORIGIN = "https://api.deepseek.com";
    private static final String EXTENSION_URI = "resource://android/assets/account-bridge/";
    private static final String EXTENSION_ID = "deepseek-account-bridge@local";
    private static final String NATIVE_APP = "browser";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private GeckoRuntime runtime;
    private GeckoSession session;
    private GeckoView geckoView;
    private FrameLayout root;
    private LinearLayout progressOverlay;
    private LinearLayout errorOverlay;
    private TextView progressLabel;
    private TextView errorLabel;

    private boolean provisioning;
    private boolean completed;
    private boolean canGoBack;
    private String currentUrl = SIGN_IN_URL;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        root = new FrameLayout(this);
        progressOverlay = createProgressOverlay();
        root.addView(
            progressOverlay,
            new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        );

        runtime = GeckoRuntimeProvider.get(this);

        GeckoSessionSettings sessionSettings = new GeckoSessionSettings.Builder()
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .allowJavascript(true)
            .build();
        session = new GeckoSession(sessionSettings);
        session.setProgressDelegate(new PlatformProgressDelegate());
        session.setNavigationDelegate(new PlatformNavigationDelegate());
        session.setContentDelegate(new PlatformContentDelegate());
        session.open(runtime);

        geckoView = new GeckoView(this);
        geckoView.setSession(session);
        root.addView(
            geckoView,
            new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        );

        errorOverlay = createErrorOverlay();
        root.addView(
            errorOverlay,
            new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        );
        setContentView(root);

        installAccountBridge();
        session.loadUri(SIGN_IN_URL);
    }

    private LinearLayout createProgressOverlay() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(dp(32), dp(32), dp(32), dp(32));
        layout.setBackgroundColor(Color.WHITE);

        ProgressBar progress = new ProgressBar(this);
        progressLabel = new TextView(this);
        progressLabel.setText("正在打开 DS-API 开放平台…");
        progressLabel.setTextColor(Color.rgb(24, 24, 27));
        progressLabel.setTextSize(16);
        progressLabel.setGravity(Gravity.CENTER);
        progressLabel.setPadding(0, dp(20), 0, 0);

        layout.addView(progress);
        layout.addView(progressLabel);
        return layout;
    }

    private LinearLayout createErrorOverlay() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(dp(32), dp(32), dp(32), dp(32));
        layout.setBackgroundColor(Color.WHITE);
        layout.setVisibility(View.GONE);

        TextView title = new TextView(this);
        title.setText("登录页加载失败");
        title.setTextColor(Color.rgb(24, 24, 27));
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER);

        errorLabel = new TextView(this);
        errorLabel.setTextColor(Color.rgb(107, 114, 128));
        errorLabel.setTextSize(15);
        errorLabel.setGravity(Gravity.CENTER);
        errorLabel.setPadding(0, dp(12), 0, dp(20));

        Button retryButton = new Button(this);
        retryButton.setText("重试");
        retryButton.setAllCaps(false);
        retryButton.setOnClickListener(view -> {
            errorOverlay.setVisibility(View.GONE);
            progressLabel.setText("正在重新打开 DS-API 开放平台…");
            progressOverlay.setVisibility(View.VISIBLE);
            session.loadUri(SIGN_IN_URL);
        });

        layout.addView(title);
        layout.addView(errorLabel);
        layout.addView(retryButton);
        return layout;
    }

    private void installAccountBridge() {
        WebExtension.MessageDelegate delegate = new WebExtension.MessageDelegate() {
            @Override
            public GeckoResult<Object> onMessage(
                String nativeApp,
                Object message,
                WebExtension.MessageSender sender
            ) {
                if (!NATIVE_APP.equals(nativeApp) ||
                    !sender.isTopLevel() ||
                    !isPlatformUrl(sender.url) ||
                    !(message instanceof JSONObject)) {
                    return GeckoResult.fromValue(null);
                }
                JSONObject payload = (JSONObject) message;
                if (!"platform-token".equals(payload.optString("type", ""))) {
                    return GeckoResult.fromValue(null);
                }
                String token = payload.optString("token", "").trim();
                if (!token.isEmpty()) {
                    provisionsApiKey(token);
                }
                return GeckoResult.fromValue(null);
            }
        };

        runtime.getWebExtensionController()
            .ensureBuiltIn(EXTENSION_URI, EXTENSION_ID)
            .accept(
                extension -> session.getWebExtensionController()
                    .setMessageDelegate(extension, delegate, NATIVE_APP),
                error -> {
                    Log.e(TAG, "Unable to install account bridge", error);
                    showLoadError("登录组件初始化失败，请返回后重试。");
                }
            );
    }

    private void provisionsApiKey(String platformToken) {
        if (provisioning || completed) {
            return;
        }
        provisioning = true;
        PlatformTokenStore.save(this, platformToken);
        showProgress("已登录，正在自动创建 API Key…");
        executor.execute(() -> {
            try {
                String apiKey = createApiKey(platformToken);
                verifyApiKey(apiKey);
                finishSuccess(apiKey);
            } catch (Exception error) {
                finishFailure(messageOf(error));
            }
        });
    }

    private String createApiKey(String platformToken) throws Exception {
        JSONObject body = new JSONObject();
        body.put("action", "create");
        body.put("name", "DS-API App");
        body.put("redacted_key", JSONObject.NULL);
        body.put("created_at", JSONObject.NULL);
        body.put("tracking_id", JSONObject.NULL);
        body.put("is_exp", false);
        HttpResult created = requestJson(
            PLATFORM_ORIGIN + "/api/v0/users/edit_api_keys",
            "POST",
            body.toString(),
            platformToken,
            true
        );
        JSONObject envelope = new JSONObject(created.body).getJSONObject("data");
        int bizCode = envelope.optInt("biz_code", -1);
        switch (bizCode) {
            case 0:
                break;
            case 1:
                throw new IllegalStateException("该账号的 API Key 数量已达上限");
            case 2:
                throw new IllegalStateException("API Key 名称过长");
            case 8:
                throw new IllegalStateException("账号尚未完成实名认证，暂时没有创建 API Key");
            default:
                throw new IllegalStateException("自动创建 API Key 失败，平台返回码 " + bizCode);
        }
        JSONObject data = envelope.getJSONObject("biz_data");
        String value = data.getJSONObject("api_key").optString("sensitive_id", "").trim();
        if (value.isEmpty()) {
            throw new IllegalStateException("平台没有返回 API Key");
        }
        return value;
    }

    private void verifyApiKey(String apiKey) throws Exception {
        HttpResult verified = requestJson(API_ORIGIN + "/models", "GET", null, apiKey, false);
        if (verified.status < 200 || verified.status >= 300) {
            throw new IllegalStateException("新建的 API Key 校验失败");
        }
    }

    private HttpResult requestJson(
        String endpoint,
        String method,
        String body,
        String bearer,
        boolean platformClient
    ) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(30_000);
        connection.setReadTimeout(30_000);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty(
            "User-Agent",
            "DSAPIAndroid/" + BuildConfig.VERSION_NAME
        );
        if (platformClient) {
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
        }
        if (bearer != null) {
            connection.setRequestProperty("Authorization", "Bearer " + bearer);
        }
        if (body != null) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
        }
        int status = connection.getResponseCode();
        InputStream stream = status >= 200 && status < 300
            ? connection.getInputStream()
            : connection.getErrorStream();
        String responseBody = stream == null ? "" : readAll(stream);
        connection.disconnect();
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("平台请求失败（HTTP " + status + "）");
        }
        return new HttpResult(status, responseBody);
    }

    private void showProgress(String message) {
        mainHandler.post(() -> {
            if (completed) {
                return;
            }
            progressLabel.setText(message);
            progressOverlay.setVisibility(View.VISIBLE);
            errorOverlay.setVisibility(View.GONE);
        });
    }

    private void showLoadError(String message) {
        mainHandler.post(() -> {
            if (completed) {
                return;
            }
            progressOverlay.setVisibility(View.GONE);
            errorLabel.setText(message);
            errorOverlay.setVisibility(View.VISIBLE);
        });
    }

    private void finishSuccess(String apiKey) {
        storeResult(true, apiKey, null, false);
        completed = true;
        mainHandler.post(this::returnToApp);
    }

    private void finishFailure(String message) {
        if (completed) {
            return;
        }
        completed = true;
        storeResult(false, null, message, false);
        mainHandler.post(() -> {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            returnToApp();
        });
    }

    private void returnToApp() {
        Intent resultIntent = new Intent(this, MainActivity.class)
            .setData(Uri.parse("deepseekapp://auth-complete"))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        startActivity(resultIntent);
        finish();
    }

    private void storeResult(boolean ok, String apiKey, String error, boolean cancelled) {
        SharedPreferences.Editor editor = getSharedPreferences(RESULT_PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .putBoolean(RESULT_OK, ok);
        if (apiKey != null) editor.putString(RESULT_API_KEY, apiKey);
        if (error != null) editor.putString(RESULT_ERROR, error);
        if (cancelled) editor.putBoolean(RESULT_CANCELLED, true);
        editor.commit();
    }

    private final class PlatformProgressDelegate implements GeckoSession.ProgressDelegate {
        @Override
        public void onPageStart(GeckoSession session, String url) {
            if (!completed && !provisioning) {
                progressLabel.setText("正在打开 DS-API 开放平台…");
                errorOverlay.setVisibility(View.GONE);
                progressOverlay.setVisibility(View.VISIBLE);
            }
        }

        @Override
        public void onPageStop(GeckoSession session, boolean success) {
            if (completed || provisioning) {
                return;
            }
            if (success) {
                progressOverlay.setVisibility(View.GONE);
            } else {
                showLoadError("无法连接 DS-API 开放平台，请检查网络后重试。");
            }
        }
    }

    private final class PlatformNavigationDelegate implements GeckoSession.NavigationDelegate {
        @Override
        public void onCanGoBack(GeckoSession session, boolean canGoBack) {
            AccountLoginActivity.this.canGoBack = canGoBack;
        }

        @Override
        public void onLocationChange(
            GeckoSession session,
            String url,
            java.util.List<GeckoSession.PermissionDelegate.ContentPermission> perms,
            Boolean hasUserGesture
        ) {
            if (url != null && isPlatformUrl(url)) {
                currentUrl = url;
            }
        }

        @Override
        public GeckoResult<AllowOrDeny> onLoadRequest(
            GeckoSession session,
            LoadRequest request
        ) {
            String url = request.uri;
            if (isPlatformUrl(url)) {
                return null;
            }
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme();
            if ("http".equals(scheme) || "https".equals(scheme) ||
                "weixin".equals(scheme) || "alipays".equals(scheme)) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception ignored) {
                    Toast.makeText(
                        AccountLoginActivity.this,
                        "没有可打开此链接的应用",
                        Toast.LENGTH_SHORT
                    ).show();
                }
                return GeckoResult.deny();
            }
            return null;
        }
    }

    private final class PlatformContentDelegate implements GeckoSession.ContentDelegate {
        @Override
        public void onCrash(GeckoSession session) {
            showLoadError("登录页渲染失败，请重试。");
        }
    }

    private static boolean isPlatformUrl(String url) {
        if (url == null) {
            return false;
        }
        try {
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            return ("https".equals(scheme) || "http".equals(scheme)) &&
                host != null &&
                ("deepseek.com".equals(host) || host.endsWith(".deepseek.com"));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String readAll(InputStream stream) throws Exception {
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(stream, StandardCharsets.UTF_8)
        )) {
            char[] buffer = new char[4096];
            int count;
            while ((count = reader.read(buffer)) >= 0) output.append(buffer, 0, count);
        }
        return output.toString();
    }

    private static String messageOf(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isEmpty() ? "账号登录失败，请重试" : message;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onBackPressed() {
        if (session != null && canGoBack && !isSignInEntry(currentUrl)) {
            session.goBack();
            return;
        }
        if (!completed) {
            completed = true;
            storeResult(false, null, null, true);
            returnToApp();
            return;
        }
        super.onBackPressed();
    }

    private static boolean isSignInEntry(String url) {
        if (url == null) {
            return true;
        }
        try {
            String path = Uri.parse(url).getPath();
            return path == null || "/".equals(path) || "/sign_in".equals(path);
        } catch (Exception ignored) {
            return true;
        }
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        if (geckoView != null) {
            geckoView.releaseSession();
            geckoView = null;
        }
        if (session != null) {
            session.close();
            session = null;
        }
        super.onDestroy();
    }

    private static final class HttpResult {
        final int status;
        final String body;

        HttpResult(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }
}
