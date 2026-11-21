package com.deepseek.chat.local;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebExtension;

import org.json.JSONObject;

import java.util.List;

/** Displays authenticated DeepSeek Platform pages inside the app. */
public final class PlatformPageActivity extends Activity {
    public static final String EXTRA_PAGE = "page";
    public static final String PAGE_USAGE = "usage";
    public static final String PAGE_TOP_UP = "top_up";

    private static final String PLATFORM_ORIGIN = "https://platform.deepseek.com";
    private static final String TAG = "DeepSeekPlatformPage";
    private static final String EXTENSION_URI = "resource://android/assets/account-bridge/";
    private static final String EXTENSION_ID = "deepseek-account-bridge@local";
    private static final String NATIVE_APP = "browser";

    private GeckoRuntime runtime;
    private GeckoSession session;
    private GeckoView geckoView;
    private FrameLayout webContainer;
    private LinearLayout progressOverlay;
    private TextView progressLabel;
    private TextView titleView;
    private boolean canGoBack;
    private String currentUrl;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String page = pageFromIntent(getIntent());
        String path = page.equals(PAGE_TOP_UP) ? "/top_up" : "/usage";
        String title = page.equals(PAGE_TOP_UP) ? "充值" : "用量与余额";
        currentUrl = PLATFORM_ORIGIN + path;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.addView(createToolbar(title), new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(56)
        ));

        webContainer = new FrameLayout(this);
        root.addView(webContainer, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ));
        setContentView(root);

        runtime = GeckoRuntimeProvider.get(this);
        GeckoSessionSettings settings = new GeckoSessionSettings.Builder()
            .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
            .allowJavascript(true)
            .build();
        session = new GeckoSession(settings);
        session.setProgressDelegate(new PlatformProgressDelegate());
        session.setNavigationDelegate(new PlatformNavigationDelegate());
        session.setContentDelegate(new PlatformContentDelegate());
        session.open(runtime);
        installTokenBridge();

        geckoView = new GeckoView(this);
        geckoView.setSession(session);
        webContainer.addView(geckoView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));
        progressOverlay = createProgressOverlay();
        webContainer.addView(progressOverlay, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ));

        session.loadUri(currentUrl);
    }

    private void installTokenBridge() {
        WebExtension.MessageDelegate delegate = new WebExtension.MessageDelegate() {
            @Override
            public GeckoResult<Object> onMessage(
                String nativeApp,
                Object message,
                WebExtension.MessageSender sender
            ) {
                if (!NATIVE_APP.equals(nativeApp) ||
                    !sender.isTopLevel() ||
                    sender.url == null ||
                    !sender.url.startsWith(PLATFORM_ORIGIN) ||
                    !(message instanceof JSONObject)) {
                    return GeckoResult.fromValue(null);
                }
                JSONObject payload = (JSONObject) message;
                if (!"platform-token".equals(payload.optString("type", ""))) {
                    return GeckoResult.fromValue(null);
                }
                String token = payload.optString("token", "").trim();
                if (!token.isEmpty()) {
                    PlatformTokenStore.save(PlatformPageActivity.this, token);
                }
                return GeckoResult.fromValue(null);
            }
        };
        runtime.getWebExtensionController()
            .ensureBuiltIn(EXTENSION_URI, EXTENSION_ID)
            .accept(
                extension -> session.getWebExtensionController()
                    .setMessageDelegate(extension, delegate, NATIVE_APP),
                error -> Log.e(TAG, "Unable to install token bridge", error)
            );
    }

    private View createToolbar(String title) {
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(8), 0, dp(8), 0);
        toolbar.setBackgroundColor(Color.WHITE);

        Button back = new Button(this);
        back.setText("返回");
        back.setTextSize(15);
        back.setAllCaps(false);
        back.setBackgroundColor(Color.TRANSPARENT);
        back.setTextColor(Color.rgb(24, 24, 27));
        back.setOnClickListener(view -> onBackPressed());

        titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextSize(17);
        titleView.setTextColor(Color.rgb(24, 24, 27));
        titleView.setGravity(Gravity.CENTER);

        Button close = new Button(this);
        close.setText("关闭");
        close.setTextSize(15);
        close.setAllCaps(false);
        close.setBackgroundColor(Color.TRANSPARENT);
        close.setTextColor(Color.rgb(24, 24, 27));
        close.setOnClickListener(view -> finish());

        toolbar.addView(back);
        toolbar.addView(titleView, new LinearLayout.LayoutParams(0, dp(56), 1f));
        toolbar.addView(close);
        return toolbar;
    }

    private LinearLayout createProgressOverlay() {
        LinearLayout overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setGravity(Gravity.CENTER);
        overlay.setBackgroundColor(Color.WHITE);

        ProgressBar progress = new ProgressBar(this);
        progressLabel = new TextView(this);
        progressLabel.setText("正在打开 DS-API 开放平台…");
        progressLabel.setTextSize(15);
        progressLabel.setTextColor(Color.rgb(75, 85, 99));
        progressLabel.setGravity(Gravity.CENTER);
        progressLabel.setPadding(dp(32), dp(16), dp(32), 0);
        overlay.addView(progress);
        overlay.addView(progressLabel);
        return overlay;
    }

    private final class PlatformProgressDelegate implements GeckoSession.ProgressDelegate {
        @Override
        public void onPageStart(GeckoSession session, String url) {
            progressLabel.setText("正在打开 DS-API 开放平台…");
            progressOverlay.setVisibility(View.VISIBLE);
        }

        @Override
        public void onPageStop(GeckoSession session, boolean success) {
            if (success) {
                progressOverlay.setVisibility(View.GONE);
            } else {
                progressLabel.setText("页面加载失败，请返回后重试。");
            }
        }
    }

    private final class PlatformNavigationDelegate implements GeckoSession.NavigationDelegate {
        @Override
        public void onCanGoBack(GeckoSession session, boolean canGoBack) {
            PlatformPageActivity.this.canGoBack = canGoBack;
        }

        @Override
        public void onLocationChange(
            GeckoSession session,
            String url,
            List<GeckoSession.PermissionDelegate.ContentPermission> perms,
            Boolean hasUserGesture
        ) {
            if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                currentUrl = url;
            }
        }

        @Override
        public GeckoResult<AllowOrDeny> onLoadRequest(
            GeckoSession session,
            LoadRequest request
        ) {
            String uriText = request.uri;
            Uri uri = Uri.parse(uriText);
            String scheme = uri.getScheme();
            if ("http".equals(scheme) || "https".equals(scheme)) {
                if (request.target == TARGET_WINDOW_NEW) {
                    session.loadUri(uriText);
                    return GeckoResult.deny();
                }
                return null;
            }
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, uri));
            } catch (Exception ignored) {
                Toast.makeText(
                    PlatformPageActivity.this,
                    "没有可处理此支付方式的 App",
                    Toast.LENGTH_SHORT
                ).show();
            }
            return GeckoResult.deny();
        }
    }

    private final class PlatformContentDelegate implements GeckoSession.ContentDelegate {
        @Override
        public void onTitleChange(GeckoSession session, String title) {
            if (title != null && !title.trim().isEmpty()) {
                titleView.setText(title.trim().replace("DeepSeek", "DS-API"));
            }
        }

        @Override
        public void onCrash(GeckoSession session) {
            progressOverlay.setVisibility(View.VISIBLE);
            progressLabel.setText("页面渲染失败，请返回后重试。");
        }
    }

    private static String pageFromIntent(Intent intent) {
        String value = intent == null ? null : intent.getStringExtra(EXTRA_PAGE);
        return PAGE_TOP_UP.equals(value) ? PAGE_TOP_UP : PAGE_USAGE;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onBackPressed() {
        if (session != null && canGoBack && !isEntryPage(currentUrl)) {
            session.goBack();
        } else {
            super.onBackPressed();
        }
    }

    private static boolean isEntryPage(String url) {
        if (url == null) {
            return true;
        }
        Uri uri = Uri.parse(url);
        String path = uri.getPath();
        return PLATFORM_ORIGIN.equals(uri.getScheme() + "://" + uri.getHost()) &&
            ("/usage".equals(path) || "/top_up".equals(path));
    }

    @Override
    protected void onDestroy() {
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
}
