package com.haoverlay;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Updates the app from its GitHub releases: finds the newest release, downloads its APK and hands it to
 * Android's installer (which asks the user to confirm). The repository is public, so no account is needed;
 * if it is ever made private again, the app asks for a read-only GitHub token instead.
 */
final class Updater {

    private static final String TAG = "Updater";
    private static final String REPO = "shifthk/ha-car-overlay";
    private static final String LATEST = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final long AUTO_CHECK_EVERY_MS = 12 * 60 * 60 * 1000L;

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    static final class Release {
        String tag = "";
        String version = "";
        String notes = "";
        String apkName = "";
        String apkApiUrl = "";
        long apkSize;
    }

    /** No token, or GitHub refused it: the user needs to (re-)enter one. */
    static final class NeedsToken extends IOException {
        NeedsToken(String msg) {
            super(msg);
        }
    }

    private final Activity activity;
    private final Store store;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean busy;

    Updater(Activity activity, Store store) {
        this.activity = activity;
        this.store = store;
    }

    void shutdown() {
        io.shutdownNow();
    }

    // ------------------------------------------------------------------ entry points

    /** Quiet check when the app opens: says something only if there is a newer version. */
    void autoCheck() {
        if (System.currentTimeMillis() - store.lastUpdateCheck() < AUTO_CHECK_EVERY_MS) return;
        check(false);
    }

    /** The "Check for updates" menu item. */
    void checkNow() {
        check(true);
    }

    private void check(boolean userAsked) {
        if (busy) return;
        busy = true;
        if (userAsked) toast("Checking for updates…");
        io.execute(() -> {
            try {
                Release r = latest();
                store.setLastUpdateCheck(System.currentTimeMillis());
                String mine = currentVersion();
                main.post(() -> {
                    busy = false;
                    if (activity.isFinishing()) return;
                    if (compare(r.version, mine) > 0) offer(r, mine);
                    else if (userAsked) info("Up to date", "You have the newest version (" + mine + ").");
                });
            } catch (NeedsToken e) {
                main.post(() -> {
                    busy = false;
                    if (userAsked && !activity.isFinishing()) askForToken(e.getMessage());
                });
            } catch (IOException | JSONException e) {
                Log.w(TAG, "Update check failed", e);
                main.post(() -> {
                    busy = false;
                    if (userAsked && !activity.isFinishing()) {
                        info("Couldn't check for updates", e.getMessage() == null ? "No connection?" : e.getMessage());
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------ GitHub

    private Release latest() throws IOException, JSONException {
        Request req = authorized(new Request.Builder().url(LATEST)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28"));
        try (Response resp = HTTP.newCall(req).execute()) {
            ResponseBody b = resp.body();
            String text = b == null ? "" : b.string();
            boolean hasToken = !store.githubToken().isEmpty();
            if (resp.code() == 401) throw new NeedsToken("GitHub didn't accept the saved token (it may have expired).");
            if (resp.code() == 404 && !hasToken) {
                throw new NeedsToken("GitHub can't find the releases; the repository may have been made private.");
            }
            if (resp.code() == 404 && hasToken) {
                throw new NeedsToken("The saved token can't see " + REPO + ". It needs read access to that repository's contents.");
            }
            if (resp.code() == 403) throw new IOException("GitHub is limiting requests right now. Try again later.");
            if (!resp.isSuccessful()) throw new IOException("GitHub answered HTTP " + resp.code() + ".");
            JSONObject j = new JSONObject(text);
            Release r = new Release();
            r.tag = j.optString("tag_name", "");
            r.version = r.tag.replaceFirst("^[vV]", "");
            r.notes = j.optString("body", "").trim();
            JSONArray assets = j.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (a.optString("name", "").toLowerCase().endsWith(".apk")) {
                        r.apkName = a.optString("name");
                        r.apkApiUrl = a.optString("url");
                        r.apkSize = a.optLong("size");
                        break;
                    }
                }
            }
            if (r.apkApiUrl.isEmpty()) throw new IOException("The newest release (" + r.tag + ") has no APK attached.");
            return r;
        }
    }

    /** Downloads the APK into the app's cache, reporting progress (0..1, or -1 when the size is unknown). */
    private java.io.File download(Release r, java.util.function.Consumer<Float> progress) throws IOException {
        Request req = authorized(new Request.Builder().url(r.apkApiUrl)
                .header("Accept", "application/octet-stream"));
        // GitHub redirects to a signed download address; OkHttp drops the token when it follows it.
        java.io.File out = new java.io.File(activity.getCacheDir(), "update.apk");
        try (Response resp = HTTP.newCall(req).execute()) {
            if (resp.code() == 401 || resp.code() == 404) throw new NeedsToken("GitHub refused the download.");
            if (!resp.isSuccessful() || resp.body() == null) throw new IOException("Download failed (HTTP " + resp.code() + ").");
            long total = resp.body().contentLength() > 0 ? resp.body().contentLength() : r.apkSize;
            try (InputStream in = resp.body().byteStream(); OutputStream os = new java.io.FileOutputStream(out)) {
                byte[] buf = new byte[64 * 1024];
                long done = 0, lastReport = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                    done += n;
                    if (done - lastReport > 256 * 1024) {
                        lastReport = done;
                        float f = total > 0 ? (float) done / total : -1f;
                        main.post(() -> progress.accept(f));
                    }
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ dialogs

    private void offer(Release r, String mine) {
        String notes = r.notes.isEmpty() ? "" : "\n\n" + (r.notes.length() > 1200 ? r.notes.substring(0, 1200) + "…" : r.notes);
        new MaterialAlertDialogBuilder(activity)
                .setTitle("Update available: " + r.version)
                .setMessage("You have " + mine + "." + notes)
                .setPositiveButton("Update", (d, w) -> startUpdate(r))
                .setNegativeButton("Later", null)
                .show();
    }

    private void startUpdate(Release r) {
        if (Build.VERSION.SDK_INT >= 26 && !activity.getPackageManager().canRequestPackageInstalls()) {
            new MaterialAlertDialogBuilder(activity)
                    .setTitle("Allow installing updates")
                    .setMessage("Android needs your OK for HA Overlay to install its own updates. "
                            + "Turn on “Allow from this source” on the next screen, come back, and press Update again.")
                    .setPositiveButton("Open settings", (d, w) -> openInstallSettings(r))
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        box.setPadding(pad, dp(8), pad, 0);
        TextView label = new TextView(activity);
        label.setText("Downloading " + r.apkName + "…");
        LinearProgressIndicator bar = new LinearProgressIndicator(activity);
        bar.setIndeterminate(true);
        bar.setMax(1000);
        box.addView(label);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        box.addView(bar, lp);
        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle("Updating to " + r.version)
                .setView(box)
                .setCancelable(false)
                .show();

        io.execute(() -> {
            try {
                java.io.File apk = download(r, f -> {
                    if (f < 0) return;
                    bar.setIndeterminate(false);
                    bar.setProgressCompat(Math.round(f * 1000), true);
                });
                main.post(() -> label.setText("Opening the installer…"));
                install(apk);
                main.post(dialog::dismiss);
            } catch (IOException e) {
                Log.w(TAG, "Update failed", e);
                main.post(() -> {
                    dialog.dismiss();
                    if (e instanceof NeedsToken) askForToken(e.getMessage());
                    else info("Update failed", e.getMessage() == null ? "No connection?" : e.getMessage());
                });
            }
        });
    }

    private void openInstallSettings(Release r) {
        try {
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName())));
        } catch (RuntimeException e) {
            info("Can't install updates here",
                    "This screen has no page to allow it. Download " + r.apkName + " from the GitHub release in a browser instead.");
        }
    }

    /** Asks for a GitHub token (fine-grained, read-only, this one repository). */
    private void askForToken(String problem) {
        TextInputLayout layout = new TextInputLayout(activity, null, com.google.android.material.R.attr.textInputOutlinedStyle);
        layout.setHint("GitHub token");
        TextInputEditText input = new TextInputEditText(layout.getContext());
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setText(store.githubToken());
        layout.addView(input);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), 0);
        box.addView(layout);

        String msg = (problem == null ? "" : problem + "\n\n")
                + "If the GitHub repository is private, updates need a GitHub access token.\n\n"
                + "On a computer: GitHub → Settings → Developer settings → Fine-grained tokens → Generate new token. "
                + "Repository access: only " + REPO + ". Permissions: Contents → Read-only. Then enter it here.";
        new MaterialAlertDialogBuilder(activity)
                .setTitle("Connect to GitHub")
                .setMessage(msg)
                .setView(box)
                .setPositiveButton("Save and check", (d, w) -> {
                    String t = input.getText() == null ? "" : input.getText().toString().trim();
                    store.setGithubToken(t);
                    if (!t.isEmpty()) check(true);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Adds the saved GitHub token, if there is one. */
    private Request authorized(Request.Builder b) {
        String t = store.githubToken();
        if (!t.isEmpty()) b.header("Authorization", "Bearer " + t);
        return b.build();
    }

    private void info(String title, String msg) {
        new MaterialAlertDialogBuilder(activity).setTitle(title).setMessage(msg)
                .setPositiveButton(R.string.ok, null).show();
    }

    // ------------------------------------------------------------------ installing

    /** Blocking. Streams the APK into an install session; Android then shows its own confirm screen. */
    private void install(java.io.File apk) throws IOException {
        PackageInstaller pi = activity.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(activity.getPackageName());
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        int id = pi.createSession(params);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            try (InputStream in = new java.io.FileInputStream(apk); OutputStream out = s.openWrite("base.apk", 0, apk.length())) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                s.fsync(out);
            }
            Intent done = new Intent(activity, InstallResult.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent pending = PendingIntent.getBroadcast(activity, id, done, flags);
            s.commit(pending.getIntentSender());
        } catch (IOException | RuntimeException e) {
            try {
                pi.abandonSession(id);
            } catch (RuntimeException ignored) {
            }
            throw e instanceof IOException ? (IOException) e : new IOException(e.getMessage(), e);
        }
    }

    /** Receives the installer's answer: shows its confirm screen, or reports a failure. */
    public static final class InstallResult extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        context.startActivity(confirm);
                    } catch (RuntimeException e) {
                        Toast.makeText(context, "Couldn't open the installer: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                Toast.makeText(context, "Update not installed" + (msg == null ? "" : ": " + msg), Toast.LENGTH_LONG).show();
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private String currentVersion() {
        try {
            PackageInfo p = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
            return p.versionName == null ? "0" : p.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "0";
        }
    }

    /** Compares dotted versions numerically: "1.10" is newer than "1.9", "1.5.0" equals "1.5". */
    static int compare(String a, String b) {
        String[] x = a.split("[^0-9]+"), y = b.split("[^0-9]+");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length && !x[i].isEmpty() ? Integer.parseInt(x[i]) : 0;
            int q = i < y.length && !y[i].isEmpty() ? Integer.parseInt(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private void toast(String msg) {
        Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show();
    }

    private int dp(float v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }
}
