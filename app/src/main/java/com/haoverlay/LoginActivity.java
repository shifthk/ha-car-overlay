package com.haoverlay;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sign-in screen. The normal path opens Home Assistant's own login page in a WebView and catches
 * the OAuth redirect; the alternative takes a long-lived access token.
 */
public class LoginActivity extends AppCompatActivity {

    private static final String STATE_KEY = "oauth_state";

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private Store store;

    private View formScroll;
    private LinearLayout webGroup;
    private WebView web;
    private TextInputLayout urlLayout;
    private TextInputEditText urlInput;
    private TextInputLayout tokenLayout;
    private TextInputEditText tokenInput;
    private LinearLayout tokenGroup;
    private MaterialButton signIn;
    private MaterialButton tokenConnect;
    private LinearProgressIndicator progress;
    private TextView error;

    private String oauthState;
    private boolean redirectHandled;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);
        store = new Store(this);

        formScroll = findViewById(R.id.form_scroll);
        webGroup = findViewById(R.id.web_group);
        web = findViewById(R.id.web);
        urlLayout = findViewById(R.id.url_layout);
        urlInput = findViewById(R.id.url_input);
        tokenLayout = findViewById(R.id.token_layout);
        tokenInput = findViewById(R.id.token_input);
        tokenGroup = findViewById(R.id.token_group);
        signIn = findViewById(R.id.sign_in);
        tokenConnect = findViewById(R.id.token_connect);
        progress = findViewById(R.id.progress);
        error = findViewById(R.id.error);
        MaterialButton tokenToggle = findViewById(R.id.token_toggle);
        MaterialToolbar webToolbar = findViewById(R.id.web_toolbar);

        TextView logo = findViewById(R.id.logo);
        logo.setTypeface(MdiIcons.typeface(this));
        logo.setText(MdiIcons.glyphOf(this, "home-assistant"));

        urlInput.setText(store.serverUrl());
        if (savedInstanceState != null) oauthState = savedInstanceState.getString(STATE_KEY);

        signIn.setOnClickListener(v -> startOAuth());
        urlInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_GO) {
                startOAuth();
                return true;
            }
            return false;
        });
        tokenToggle.setOnClickListener(v ->
                tokenGroup.setVisibility(tokenGroup.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        tokenConnect.setOnClickListener(v -> connectWithToken());

        webToolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        webToolbar.setNavigationOnClickListener(v -> closeWeb());
        setupWebView();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webGroup.getVisibility() == View.VISIBLE) {
                    if (web.canGoBack()) web.goBack();
                    else closeWeb();
                } else {
                    finish();
                }
            }
        });
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_KEY, oauthState);
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        web.destroy();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ OAuth via the HA login page

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        CookieManager.getInstance().setAcceptCookie(true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return maybeHandleRedirect(request.getUrl());
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (url != null && maybeHandleRedirect(Uri.parse(url))) view.stopLoading();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError err) {
                if (!request.isForMainFrame()) return;
                if (request.getUrl().toString().startsWith(HaAuth.REDIRECT_URI)) return;
                closeWeb();
                showError("Couldn't open the Home Assistant login page: " + err.getDescription());
            }
        });
    }

    private void startOAuth() {
        String base = readUrl();
        if (base == null) return;
        store.setServerUrl(base);
        hideError();
        oauthState = UUID.randomUUID().toString();
        redirectHandled = false;
        formScroll.setVisibility(View.GONE);
        webGroup.setVisibility(View.VISIBLE);
        web.loadUrl(HaAuth.authorizeUrl(store.serverUrl(), oauthState));
    }

    private boolean maybeHandleRedirect(Uri uri) {
        if (uri == null || !uri.toString().startsWith(HaAuth.REDIRECT_URI)) return false;
        if (redirectHandled) return true;   // the same redirect can be reported twice
        redirectHandled = true;
        String code = uri.getQueryParameter("code");
        String state = uri.getQueryParameter("state");
        closeWeb();
        if (code == null || code.isEmpty()) {
            showError("Sign-in was cancelled.");
            return true;
        }
        if (oauthState != null && !oauthState.equals(state)) {
            showError("Sign-in reply did not match this request. Please try again.");
            return true;
        }
        setBusy(true);
        io.execute(() -> {
            try {
                HaAuth.exchangeCode(store, code);
                runOnUiThread(this::loggedIn);
            } catch (IOException e) {
                runOnUiThread(() -> {
                    setBusy(false);
                    showError("Sign-in failed: " + HaSocket.describe(e));
                });
            }
        });
        return true;
    }

    private void closeWeb() {
        if (webGroup.getVisibility() != View.VISIBLE) return;
        web.stopLoading();
        web.loadUrl("about:blank");
        webGroup.setVisibility(View.GONE);
        formScroll.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------------ long-lived token

    private void connectWithToken() {
        String base = readUrl();
        if (base == null) return;
        String token = tokenInput.getText() == null ? "" : tokenInput.getText().toString().trim();
        if (token.isEmpty()) {
            tokenLayout.setError("Paste your token here");
            return;
        }
        tokenLayout.setError(null);
        hideError();
        setBusy(true);
        io.execute(() -> {
            try {
                HaAuth.verifyToken(base, token);
                store.setServerUrl(base);
                store.saveLongLivedToken(token);
                runOnUiThread(this::loggedIn);
            } catch (IOException e) {
                runOnUiThread(() -> {
                    setBusy(false);
                    showError(HaSocket.describe(e));
                });
            }
        });
    }

    // ------------------------------------------------------------------ helpers

    private void loggedIn() {
        CookieManager.getInstance().flush();
        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
        finish();
    }

    private String readUrl() {
        String raw = urlInput.getText() == null ? "" : urlInput.getText().toString();
        String base = Store.normalizeUrl(raw);
        Uri u = Uri.parse(base);
        if (base.isEmpty() || u.getHost() == null || u.getHost().isEmpty()) {
            urlLayout.setError("Enter your Home Assistant address");
            return null;
        }
        urlLayout.setError(null);
        urlInput.setText(base);
        return base;
    }

    private void setBusy(boolean busy) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        signIn.setEnabled(!busy);
        tokenConnect.setEnabled(!busy);
    }

    private void showError(String msg) {
        error.setText(msg);
        error.setVisibility(View.VISIBLE);
    }

    private void hideError() {
        error.setVisibility(View.GONE);
    }
}
