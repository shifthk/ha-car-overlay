package com.haoverlay;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.slider.Slider;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Home screen after login: choose the dashboard (the "Home Hub" one is picked automatically),
 * pin cards as floating buttons, and control the overlay.
 */
public class MainActivity extends AppCompatActivity implements HaSocket.Listener {

    private static final int REQ_NOTIFICATIONS = 7;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Store store;
    private HaSocket socket;
    private Map<String, EntityState> states = new HashMap<>();

    /** Parallel lists: dashboard url_path ("" = Overview) and title. */
    private final List<String> dashPaths = new ArrayList<>();
    private final List<String> dashTitles = new ArrayList<>();
    private List<CardInfo> currentCards = new ArrayList<>();
    private JSONObject currentConfig;

    private MaterialToolbar toolbar;
    private LinearProgressIndicator progress;
    private TextView overlayStatus;
    private MaterialButton grantOverlay;
    private MaterialButton startStop;
    private MaterialAutoCompleteTextView dashboardInput;
    private TextView cardsMessage;
    private LinearLayout cardsContainer;
    private TextView otherTitle;
    private LinearLayout otherContainer;

    private boolean pendingStart;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new Store(this);
        if (!store.isLoggedIn()) {
            openLogin();
            return;
        }
        setContentView(R.layout.activity_main);

        toolbar = findViewById(R.id.toolbar);
        progress = findViewById(R.id.progress);
        overlayStatus = findViewById(R.id.overlay_status);
        grantOverlay = findViewById(R.id.grant_overlay);
        startStop = findViewById(R.id.start_stop);
        dashboardInput = findViewById(R.id.dashboard_input);
        cardsMessage = findViewById(R.id.cards_message);
        cardsContainer = findViewById(R.id.cards_container);
        otherTitle = findViewById(R.id.other_title);
        otherContainer = findViewById(R.id.other_container);

        toolbar.setSubtitle(store.serverUrl());
        toolbar.inflateMenu(R.menu.main);
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_refresh) {
                refresh();
                return true;
            }
            if (item.getItemId() == R.id.action_logout) {
                confirmLogout();
                return true;
            }
            return false;
        });

        grantOverlay.setOnClickListener(v -> requestOverlayPermission());
        startStop.setOnClickListener(v -> {
            if (OverlayService.running) stopOverlay();
            else startOverlay();
        });
        dashboardInput.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= dashPaths.size()) return;
            store.setDashboard(dashPaths.get(position), dashTitles.get(position));
            showCachedConfig();
            loadConfig();
        });

        setupSettings();
        showCachedDashboards();
        showCachedConfig();

        socket = new HaSocket(store, this, false);
        progress.setVisibility(View.VISIBLE);
        socket.connect();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (store == null || !store.isLoggedIn() || overlayStatus == null) return;
        if (pendingStart && Settings.canDrawOverlays(this)) {
            pendingStart = false;
            startOverlay();
        } else if (store.overlayEnabled() && !OverlayService.running && Settings.canDrawOverlays(this)
                && !store.selectedCards().isEmpty()) {
            // The buttons were on but the service was killed (e.g. force-stopped): bring them back.
            OverlayService.start(this);
            main.postDelayed(this::updateOverlayUi, 800);
        }
        updateOverlayUi();
    }

    @Override
    protected void onDestroy() {
        if (socket != null) socket.close();
        main.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ------------------------------------------------------------------ HaSocket.Listener

    @Override
    public void onConnected() {
        toolbar.setSubtitle(store.serverUrl());
        loadEverything();
    }

    @Override
    public void onDisconnected(String reason) {
        progress.setVisibility(View.INVISIBLE);
        toolbar.setSubtitle("Offline · " + reason);
    }

    @Override
    public void onAuthFailed() {
        Toast.makeText(this, "Your Home Assistant login expired. Please sign in again.", Toast.LENGTH_LONG).show();
        OverlayService.stop(this);
        openLogin();
    }

    // ------------------------------------------------------------------ loading

    private void refresh() {
        if (socket.isConnected()) {
            loadEverything();
        } else {
            progress.setVisibility(View.VISIBLE);
            socket.connect();
            socket.reconnectNow();
        }
    }

    private void loadEverything() {
        progress.setVisibility(View.VISIBLE);
        try {
            socket.send(new JSONObject().put("type", "get_states"), (ok, result, error) -> {
                if (ok && result instanceof JSONArray) {
                    JSONArray arr = (JSONArray) result;
                    Map<String, EntityState> map = new HashMap<>(arr.length() * 2);
                    for (int i = 0; i < arr.length(); i++) {
                        EntityState s = EntityState.from(arr.optJSONObject(i));
                        if (s != null) map.put(s.entityId, s);
                    }
                    states = map;
                }
                loadDashboards();
            });
        } catch (JSONException e) {
            loadDashboards();
        }
    }

    private void loadDashboards() {
        try {
            socket.send(new JSONObject().put("type", "lovelace/dashboards/list"), (ok, result, error) -> {
                if (ok && result instanceof JSONArray) {
                    store.setCachedDashboards(result.toString());
                    applyDashboards((JSONArray) result);
                } else if (dashPaths.isEmpty()) {
                    applyDashboards(new JSONArray());
                }
                loadConfig();
            });
        } catch (JSONException e) {
            loadConfig();
        }
    }

    private void loadConfig() {
        final String path = store.dashboardPath();
        JSONObject cmd = new JSONObject();
        try {
            cmd.put("type", "lovelace/config");
            if (!path.isEmpty()) cmd.put("url_path", path);
        } catch (JSONException ignored) {
        }
        progress.setVisibility(View.VISIBLE);
        socket.send(cmd, (ok, result, error) -> {
            progress.setVisibility(View.INVISIBLE);
            if (!path.equals(store.dashboardPath())) return;   // user switched dashboards meanwhile
            if (ok && result instanceof JSONObject) {
                store.setCachedConfig(path, result.toString());
                renderCards((JSONObject) result);
            } else if (currentConfig == null) {
                showMessage("Couldn't load this dashboard: " + error);
                renderCards(null);
            } else {
                Snackbar.make(cardsContainer, "Couldn't refresh the dashboard: " + error, Snackbar.LENGTH_LONG).show();
            }
        });
    }

    private void showCachedDashboards() {
        String raw = store.cachedDashboards();
        JSONArray arr = new JSONArray();
        if (!raw.isEmpty()) {
            try {
                arr = new JSONArray(raw);
            } catch (JSONException ignored) {
            }
        }
        applyDashboards(arr);
    }

    private void showCachedConfig() {
        String raw = store.cachedConfig(store.dashboardPath());
        if (raw.isEmpty()) {
            currentConfig = null;
            cardsContainer.removeAllViews();
            showMessage("Loading cards…");
            return;
        }
        try {
            renderCards(new JSONObject(raw));
        } catch (JSONException e) {
            renderCards(null);
        }
    }

    /** Fills the dashboard dropdown and picks the saved one, else one named like "Home Hub" / "mobile". */
    private void applyDashboards(JSONArray arr) {
        dashPaths.clear();
        dashTitles.clear();
        dashPaths.add("");
        dashTitles.add("Overview");
        for (int i = 0; i < arr.length(); i++) {
            JSONObject d = arr.optJSONObject(i);
            if (d == null) continue;
            String path = d.optString("url_path", "");
            if (path.isEmpty()) continue;
            String title = d.optString("title", path);
            dashPaths.add(path);
            dashTitles.add(title.isEmpty() ? path : title);
        }
        dashboardInput.setSimpleItems(dashTitles.toArray(new String[0]));

        boolean realList = arr.length() > 0;
        int index = store.hasChosenDashboard() ? dashPaths.indexOf(store.dashboardPath()) : -1;
        if (index >= 0) {
            dashboardInput.setText(dashTitles.get(index), false);
            return;
        }
        if (store.hasChosenDashboard() && !realList) {
            // Chosen earlier, but the dashboard list isn't loaded yet: keep the saved choice.
            dashboardInput.setText(store.dashboardTitle(), false);
            return;
        }
        index = findDashboard("hub");
        if (index < 0) index = findDashboard("mobile");
        if (index < 0) index = 0;
        // Remember an automatic pick only once the real list is known, so "Home Hub" can still win.
        if (realList) store.setDashboard(dashPaths.get(index), dashTitles.get(index));
        dashboardInput.setText(dashTitles.get(index), false);
    }

    private int findDashboard(String word) {
        for (int i = 1; i < dashPaths.size(); i++) {
            if (dashTitles.get(i).toLowerCase(Locale.ROOT).contains(word)
                    || dashPaths.get(i).toLowerCase(Locale.ROOT).contains(word)) {
                return i;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------ card list

    private void renderCards(JSONObject config) {
        currentConfig = config;
        CardInfo.Dashboard d = CardInfo.parse(config, states);
        currentCards = d.cards;

        Set<String> pinned = new HashSet<>();
        for (CardInfo c : store.selectedCards()) pinned.add(c.id);

        cardsContainer.removeAllViews();
        if (config == null) {
            // message already shown by the caller
        } else if (d.generated) {
            showMessage(getString(R.string.strategy_dashboard));
        } else if (d.cards.isEmpty()) {
            showMessage(getString(R.string.no_cards));
        } else {
            cardsMessage.setVisibility(View.GONE);
        }
        for (CardInfo card : d.cards) {
            cardsContainer.addView(cardRow(cardsContainer, card, pinned.contains(card.id)));
        }
        renderOtherPinned();
    }

    private void renderOtherPinned() {
        Set<String> here = new HashSet<>();
        for (CardInfo c : currentCards) here.add(c.id);
        otherContainer.removeAllViews();
        int n = 0;
        for (CardInfo c : store.selectedCards()) {
            if (here.contains(c.id)) continue;
            otherContainer.addView(cardRow(otherContainer, c, true));
            n++;
        }
        otherTitle.setVisibility(n > 0 ? View.VISIBLE : View.GONE);
    }

    private View cardRow(LinearLayout parent, CardInfo card, boolean checked) {
        MaterialCardView row = (MaterialCardView) LayoutInflater.from(this).inflate(R.layout.item_card, parent, false);
        TextView icon = row.findViewById(R.id.card_icon);
        TextView title = row.findViewById(R.id.card_title);
        TextView subtitle = row.findViewById(R.id.card_subtitle);
        MaterialSwitch sw = row.findViewById(R.id.card_switch);

        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(MaterialColors.getColor(row, com.google.android.material.R.attr.colorSecondaryContainer));
        icon.setBackground(circle);
        icon.setTypeface(MdiIcons.typeface(this));
        icon.setText(MdiIcons.glyphOf(this, card.iconName(states), "mdi:home-assistant"));
        title.setText(card.title);
        subtitle.setText(card.subtitle());

        row.setCheckedIcon(null);
        row.setChecked(checked);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener((button, on) -> {
            row.setChecked(on);
            setPinned(card, on);
        });
        row.setOnClickListener(v -> sw.toggle());
        return row;
    }

    private void setPinned(CardInfo card, boolean on) {
        List<CardInfo> sel = store.selectedCards();
        for (int i = sel.size() - 1; i >= 0; i--) {
            if (sel.get(i).id.equals(card.id)) sel.remove(i);
        }
        if (on) sel.add(card);
        store.setSelectedCards(sel);
        if (OverlayService.running) {
            if (sel.isEmpty()) stopOverlay();
            else OverlayService.refresh(this);
        }
        updateOverlayUi();
    }

    private void showMessage(String text) {
        cardsMessage.setText(text);
        cardsMessage.setVisibility(View.VISIBLE);
    }

    // ------------------------------------------------------------------ overlay control

    private void startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            pendingStart = true;
            requestOverlayPermission();
            return;
        }
        if (store.selectedCards().isEmpty()) {
            Snackbar.make(cardsContainer, "Pin at least one card first.", Snackbar.LENGTH_LONG).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATIONS);
        }
        store.setOverlayEnabled(true);
        OverlayService.start(this);
        main.postDelayed(this::updateOverlayUi, 800);
        updateOverlayUi();
    }

    private void stopOverlay() {
        store.setOverlayEnabled(false);
        OverlayService.stop(this);
        main.postDelayed(this::updateOverlayUi, 400);
        updateOverlayUi();
    }

    private void updateOverlayUi() {
        boolean allowed = Settings.canDrawOverlays(this);
        int pinned = store.selectedCards().size();
        grantOverlay.setVisibility(allowed ? View.GONE : View.VISIBLE);
        boolean running = OverlayService.running;
        startStop.setText(running ? R.string.stop_overlay : R.string.start_overlay);

        String status;
        if (!allowed) {
            status = "Allow “display over other apps” so the buttons can float above other apps.";
        } else if (running) {
            status = "Showing " + pinned + (pinned == 1 ? " floating button." : " floating buttons.");
        } else if (pinned == 0) {
            status = "Pin a card below to get started.";
        } else {
            status = pinned + (pinned == 1 ? " card pinned" : " cards pinned") + " · not showing.";
        }
        if (!running && !OverlayService.lastError.isEmpty()) status += "\n" + OverlayService.lastError;
        overlayStatus.setText(status);
    }

    /** Tries the standard permission page, then the app's settings page; some head units have neither. */
    private void requestOverlayPermission() {
        Intent[] tries = {
                new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())),
                new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
                new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())),
        };
        for (Intent i : tries) {
            try {
                startActivity(i);
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
                // try the next one
            }
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.overlay_help_title)
                .setMessage(R.string.overlay_help)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    // ------------------------------------------------------------------ settings

    private void setupSettings() {
        Slider fabSize = findViewById(R.id.fab_size);
        TextView fabLabel = findViewById(R.id.fab_size_label);
        Slider panelWidth = findViewById(R.id.panel_width);
        TextView panelLabel = findViewById(R.id.panel_width_label);
        MaterialSwitch closeOutside = findViewById(R.id.close_outside);
        MaterialSwitch startOnBoot = findViewById(R.id.start_on_boot);
        MaterialButton resetPositions = findViewById(R.id.reset_positions);

        fabSize.setValue(snap(store.fabSizeDp(), fabSize));
        fabLabel.setText(getString(R.string.button_size) + ": " + (int) fabSize.getValue() + " dp");
        fabSize.addOnChangeListener((slider, value, fromUser) -> {
            store.setFabSizeDp((int) value);
            fabLabel.setText(getString(R.string.button_size) + ": " + (int) value + " dp");
        });
        fabSize.addOnSliderTouchListener(refreshOnRelease());

        panelWidth.setValue(snap(store.panelWidthDp(), panelWidth));
        panelLabel.setText(getString(R.string.panel_width) + ": " + (int) panelWidth.getValue() + " dp");
        panelWidth.addOnChangeListener((slider, value, fromUser) -> {
            store.setPanelWidthDp((int) value);
            panelLabel.setText(getString(R.string.panel_width) + ": " + (int) value + " dp");
        });
        panelWidth.addOnSliderTouchListener(refreshOnRelease());

        closeOutside.setChecked(store.closeOnOutside());
        closeOutside.setOnCheckedChangeListener((b, on) -> {
            store.setCloseOnOutside(on);
            if (OverlayService.running) OverlayService.refresh(this);
        });
        startOnBoot.setChecked(store.startOnBoot());
        startOnBoot.setOnCheckedChangeListener((b, on) -> store.setStartOnBoot(on));

        resetPositions.setOnClickListener(v -> {
            store.resetPositions();
            if (OverlayService.running) OverlayService.resetPositions(this);
            Snackbar.make(v, "Buttons moved back to the right edge.", Snackbar.LENGTH_SHORT).show();
        });
    }

    private Slider.OnSliderTouchListener refreshOnRelease() {
        return new Slider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(Slider slider) {
            }

            @Override
            public void onStopTrackingTouch(Slider slider) {
                if (OverlayService.running) OverlayService.refresh(MainActivity.this);
            }
        };
    }

    private static float snap(int value, Slider s) {
        float step = s.getStepSize();
        float v = Math.max(s.getValueFrom(), Math.min(s.getValueTo(), value));
        if (step > 0) v = s.getValueFrom() + Math.round((v - s.getValueFrom()) / step) * step;
        return v;
    }

    // ------------------------------------------------------------------ logout

    private void confirmLogout() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.log_out)
                .setMessage(R.string.log_out_confirm)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.log_out, (d, w) -> logout())
                .show();
    }

    private void logout() {
        store.setOverlayEnabled(false);
        OverlayService.stop(this);
        socket.close();
        new Thread(() -> {
            HaAuth.revoke(store);
            store.clearAuth();
            main.post(this::openLogin);
        }, "ha-logout").start();
    }

    private void openLogin() {
        startActivity(new Intent(this, LoginActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
        finish();
    }
}
