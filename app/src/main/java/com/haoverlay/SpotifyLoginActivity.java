package com.haoverlay;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Connects the lyrics to Spotify's Web API: asks for the user's Client ID, opens Spotify's sign-in
 * page in a WebView and catches the redirect back.
 */
public class SpotifyLoginActivity extends AppCompatActivity {

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private Store store;

    private ScrollView form;
    private WebView web;
    private TextInputLayout idLayout;
    private TextInputEditText idInput;
    private MaterialButton connect;
    private LinearProgressIndicator progress;
    private TextView error;

    private String verifier;
    private String state;
    private boolean redirectHandled;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new Store(this);
        setTitle("Connect Spotify");

        FrameLayout root = new FrameLayout(this);
        form = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        col.setPadding(pad, pad, pad, pad);
        form.addView(col);

        TextView title = new TextView(this);
        title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall);
        title.setText("Connect Spotify for lyrics");
        col.addView(title);

        TextView steps = new TextView(this);
        steps.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        steps.setTextIsSelectable(true);
        steps.setText("Spotify only lets apps read what you're playing through an app you register yourself. "
                + "It's free and takes two minutes on a phone or computer:\n\n"
                + "1. Go to developer.spotify.com/dashboard and log in with your Spotify account.\n"
                + "2. Press “Create app”. Any name and description will do.\n"
                + "3. Under Redirect URIs add exactly:\n      " + SpotifyApi.REDIRECT_URI + "\n"
                + "4. Tick “Web API”, agree, and Save.\n"
                + "5. Open the app's Settings, copy the Client ID and enter it below.");
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(12);
        col.addView(steps, sp);

        idLayout = new TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle);
        idLayout.setHint("Client ID");
        idInput = new TextInputEditText(idLayout.getContext());
        idInput.setSingleLine(true);
        idInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        idInput.setText(store.spotifyClientId());
        idLayout.addView(idInput);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ip.topMargin = dp(20);
        col.addView(idLayout, ip);

        connect = new MaterialButton(this);
        connect.setText("Log in to Spotify");
        connect.setMinHeight(dp(56));
        connect.setOnClickListener(v -> start());
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = dp(12);
        col.addView(connect, bp);

        TextView hint = new TextView(this);
        hint.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall);
        hint.setText("On Spotify's page, log in with your email and password (or “Log in without password”). "
                + "“Continue with Google/Apple/Facebook” doesn't work inside apps.");
        col.addView(hint, sp);

        progress = new LinearProgressIndicator(this);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        col.addView(progress, sp);

        error = new TextView(this);
        error.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        error.setTextColor(0xFFFFB4AB);
        error.setVisibility(View.GONE);
        col.addView(error, sp);

        web = new WebView(this);
        web.setVisibility(View.GONE);
        root.addView(form);
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        // Keep everything clear of the status bar, navigation bar and keyboard.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets b = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars()
                            | androidx.core.view.WindowInsetsCompat.Type.displayCutout()
                            | androidx.core.view.WindowInsetsCompat.Type.ime());
            v.setPadding(b.left, b.top, b.right, b.bottom);
            return androidx.core.view.WindowInsetsCompat.CONSUMED;
        });
        setupWebView();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (web.getVisibility() == View.VISIBLE) {
                    if (web.canGoBack()) web.goBack();
                    else closeWeb();
                } else {
                    finish();
                }
            }
        });
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        web.destroy();
        super.onDestroy();
    }

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
                if (request.getUrl().toString().startsWith(SpotifyApi.REDIRECT_URI)) return;
                closeWeb();
                showError("Couldn't open Spotify's login page: " + err.getDescription());
            }
        });
    }

    private void start() {
        String id = idInput.getText() == null ? "" : idInput.getText().toString().trim();
        if (!id.matches("[0-9a-fA-F]{32}")) {
            idLayout.setError("A Client ID is 32 letters and numbers (0-9, a-f)");
            return;
        }
        idLayout.setError(null);
        error.setVisibility(View.GONE);
        if (!id.equals(store.spotifyClientId())) store.clearSpotify();
        store.setSpotifyClientId(id);
        verifier = SpotifyApi.newVerifier();
        state = UUID.randomUUID().toString();
        redirectHandled = false;
        form.setVisibility(View.GONE);
        web.setVisibility(View.VISIBLE);
        web.loadUrl(SpotifyApi.authorizeUrl(id, verifier, state));
    }

    private boolean maybeHandleRedirect(Uri uri) {
        if (uri == null || !uri.toString().startsWith(SpotifyApi.REDIRECT_URI)) return false;
        if (redirectHandled) return true;
        redirectHandled = true;
        closeWeb();
        String err = uri.getQueryParameter("error");
        String code = uri.getQueryParameter("code");
        if (code == null || code.isEmpty()) {
            showError("access_denied".equals(err) ? "You didn't allow access." : "Sign-in was cancelled" + (err == null ? "." : " (" + err + ")."));
            return true;
        }
        if (!state.equals(uri.getQueryParameter("state"))) {
            showError("Sign-in reply did not match this request. Please try again.");
            return true;
        }
        setBusy(true);
        final String v = verifier;
        io.execute(() -> {
            try {
                SpotifyApi.exchangeCode(store, code, v);
                runOnUiThread(() -> {
                    CookieManager.getInstance().flush();
                    if (OverlayService.running) OverlayService.refresh(this);
                    setResult(RESULT_OK);
                    finish();
                });
            } catch (IOException e) {
                runOnUiThread(() -> {
                    setBusy(false);
                    showError("Sign-in failed: " + e.getMessage());
                });
            }
        });
        return true;
    }

    private void closeWeb() {
        if (web.getVisibility() != View.VISIBLE) return;
        web.stopLoading();
        web.loadUrl("about:blank");
        web.setVisibility(View.GONE);
        form.setVisibility(View.VISIBLE);
    }

    private void setBusy(boolean busy) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        connect.setEnabled(!busy);
    }

    private void showError(String msg) {
        error.setText(msg);
        error.setVisibility(View.VISIBLE);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
