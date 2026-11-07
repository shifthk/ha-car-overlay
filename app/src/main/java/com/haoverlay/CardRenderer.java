package com.haoverlay;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.slider.Slider;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Map;

/**
 * Draws a pinned card as a native Material 3 popup: a header with the card's icon and title,
 * then one row per entity with live state and the control that fits its domain.
 */
final class CardRenderer {

    interface Controller {
        void call(String entityId, String domain, String service, JSONObject data);

        void close();

        /** True while a slider is being dragged, so live updates don't rebuild it under the finger. */
        void setInteracting(boolean interacting);
    }

    /** ScrollView that never grows taller than {@code maxHeight}. */
    static final class MaxHeightScrollView extends ScrollView {
        int maxHeight;

        MaxHeightScrollView(Context c) {
            super(c);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            if (maxHeight > 0) {
                heightMeasureSpec = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST);
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

    private final Context ui;
    private final Controller ctl;
    private final Typeface mdi;

    CardRenderer(Context ui, Controller ctl) {
        this.ui = ui;
        this.ctl = ctl;
        this.mdi = MdiIcons.typeface(ui);
    }

    View build(CardInfo card, Map<String, EntityState> states, boolean online, int maxHeightPx) {
        FrameLayout wrapper = new FrameLayout(ui);
        wrapper.setPadding(dp(8), dp(8), dp(8), dp(12));

        MaterialCardView root = new MaterialCardView(ui);
        root.setRadius(dp(28));
        root.setCardElevation(dp(6));
        root.setStrokeWidth(0);
        root.setCardBackgroundColor(color(com.google.android.material.R.attr.colorSurfaceContainerHigh, 0xFF252B2E));
        wrapper.addView(root, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout col = new LinearLayout(ui);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(16), dp(12), dp(16));
        root.addView(col);

        // ---- header ----
        LinearLayout header = new LinearLayout(ui);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        boolean anyActive = false;
        for (CardInfo.EntityRef r : card.entities) {
            EntityState s = states.get(r.entityId);
            if (s != null && s.isActive()) {
                anyActive = true;
                break;
            }
        }
        boolean lightCard = !card.entities.isEmpty() && "light".equals(Domains.domainOf(card.entities.get(0).entityId));
        header.addView(iconBadge(card.iconName(states), anyActive, lightCard, 44), new LinearLayout.LayoutParams(dp(44), dp(44)));

        LinearLayout titles = new LinearLayout(ui);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(card.title, com.google.android.material.R.style.TextAppearance_Material3_TitleLarge,
                com.google.android.material.R.attr.colorOnSurface);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(title);
        String sub = online ? card.location : "Offline · reconnecting…";
        if (!sub.isEmpty()) {
            TextView subtitle = text(sub, com.google.android.material.R.style.TextAppearance_Material3_BodySmall,
                    online ? com.google.android.material.R.attr.colorOnSurfaceVariant : com.google.android.material.R.attr.colorError);
            subtitle.setSingleLine(true);
            subtitle.setEllipsize(TextUtils.TruncateAt.END);
            titles.addView(subtitle);
        }
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(14);
        tlp.rightMargin = dp(4);
        header.addView(titles, tlp);
        header.addView(iconButton("close", true, ctl::close), new LinearLayout.LayoutParams(dp(48), dp(48)));
        col.addView(header);

        // ---- entity rows ----
        MaxHeightScrollView scroll = new MaxHeightScrollView(ui);
        scroll.maxHeight = Math.max(dp(120), maxHeightPx - dp(110));
        scroll.setVerticalScrollBarEnabled(true);
        LinearLayout rows = new LinearLayout(ui);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(0, dp(8), dp(8), 0);
        for (CardInfo.EntityRef ref : card.entities) {
            rows.addView(entityRow(ref, states.get(ref.entityId), online));
        }
        scroll.addView(rows);
        col.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return wrapper;
    }

    // ------------------------------------------------------------------ one entity

    private View entityRow(CardInfo.EntityRef ref, EntityState s, boolean online) {
        final String id = ref.entityId;
        final String domain = Domains.domainOf(id);
        final boolean enabled = online && s != null && !"unavailable".equals(s.state);
        final String state = s == null ? "" : s.state;

        LinearLayout block = new LinearLayout(ui);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(0, dp(6), 0, dp(6));

        LinearLayout line = new LinearLayout(ui);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.setMinimumHeight(dp(56));

        String iconName = !ref.icon.isEmpty() ? ref.icon
                : (s != null && !s.icon().isEmpty() ? s.icon() : "mdi:" + Domains.defaultIcon(domain, state));
        line.addView(iconBadge(iconName, s != null && s.isActive(), "light".equals(domain), 40),
                new LinearLayout.LayoutParams(dp(40), dp(40)));

        LinearLayout labels = new LinearLayout(ui);
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(ref.displayName(s), com.google.android.material.R.style.TextAppearance_Material3_TitleMedium,
                com.google.android.material.R.attr.colorOnSurface);
        name.setMaxLines(2);
        name.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(name);
        TextView value = text(Domains.formatState(s), com.google.android.material.R.style.TextAppearance_Material3_BodyMedium,
                com.google.android.material.R.attr.colorOnSurfaceVariant);
        value.setMaxLines(2);
        value.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(value);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        llp.leftMargin = dp(14);
        llp.rightMargin = dp(8);
        line.addView(labels, llp);

        View extra = null;   // a second line under the row (sliders)

        if (Domains.isToggleable(domain)) {
            MaterialSwitch sw = new MaterialSwitch(ui);
            sw.setChecked("on".equals(state));
            sw.setEnabled(enabled);
            sw.setOnCheckedChangeListener((b, on) -> ctl.call(id, "homeassistant", on ? "turn_on" : "turn_off", null));
            line.addView(sw);
            if ("light".equals(domain) && "on".equals(state) && s != null && Domains.lightSupportsBrightness(s)) {
                int pct = (int) Math.round(s.attrs.optDouble("brightness", 255) / 255.0 * 100.0);
                extra = slider(1, 100, 1, clamp(pct, 1, 100), enabled, "%",
                        v -> ctl.call(id, "light", "turn_on", json("brightness_pct", Math.round(v))));
            } else if ("fan".equals(domain) && "on".equals(state) && s != null && s.attrs.has("percentage")
                    && !s.attrs.isNull("percentage")) {
                extra = slider(0, 100, 1, clamp(s.attrs.optInt("percentage"), 0, 100), enabled, "%",
                        v -> ctl.call(id, "fan", "set_percentage", json("percentage", Math.round(v))));
            }
        } else {
            switch (domain) {
                case "cover":
                    line.addView(iconButton("arrow-up", enabled, () -> ctl.call(id, "cover", "open_cover", null)), square(48));
                    line.addView(iconButton("stop", enabled, () -> ctl.call(id, "cover", "stop_cover", null)), square(48));
                    line.addView(iconButton("arrow-down", enabled, () -> ctl.call(id, "cover", "close_cover", null)), square(48));
                    if (s != null && s.attrs.has("current_position")) {
                        extra = slider(0, 100, 1, clamp(s.attrs.optInt("current_position"), 0, 100), enabled, "%",
                                v -> ctl.call(id, "cover", "set_cover_position", json("position", Math.round(v))));
                    }
                    break;
                case "valve":
                    line.addView(iconButton("valve-open", enabled, () -> ctl.call(id, "valve", "open_valve", null)), square(48));
                    line.addView(iconButton("valve-closed", enabled, () -> ctl.call(id, "valve", "close_valve", null)), square(48));
                    break;
                case "lock": {
                    boolean locked = "locked".equals(state);
                    line.addView(pillButton(locked ? "Unlock" : "Lock", enabled,
                            () -> ctl.call(id, "lock", locked ? "unlock" : "lock", null)));
                    break;
                }
                case "scene":
                    line.addView(pillButton("Activate", enabled, () -> ctl.call(id, "scene", "turn_on", null)));
                    break;
                case "script":
                    line.addView(pillButton("on".equals(state) ? "Running" : "Run", enabled,
                            () -> ctl.call(id, "script", "turn_on", null)));
                    break;
                case "button":
                case "input_button":
                    line.addView(pillButton("Press", enabled, () -> ctl.call(id, domain, "press", null)));
                    break;
                case "climate":
                    addClimate(line, id, s, enabled);
                    break;
                case "media_player": {
                    line.addView(iconButton("skip-previous", enabled,
                            () -> ctl.call(id, "media_player", "media_previous_track", null)), square(48));
                    line.addView(iconButton("playing".equals(state) ? "pause" : "play", enabled,
                            () -> ctl.call(id, "media_player", "media_play_pause", null)), square(48));
                    line.addView(iconButton("skip-next", enabled,
                            () -> ctl.call(id, "media_player", "media_next_track", null)), square(48));
                    break;
                }
                case "vacuum":
                    line.addView(iconButton("play", enabled, () -> ctl.call(id, "vacuum", "start", null)), square(48));
                    line.addView(iconButton("home-import-outline", enabled,
                            () -> ctl.call(id, "vacuum", "return_to_base", null)), square(48));
                    break;
                case "number":
                case "input_number":
                    if (s != null) extra = numberSlider(id, domain, s, enabled);
                    break;
                case "select":
                case "input_select":
                    if (s != null) line.addView(selectButton(id, domain, s, enabled));
                    break;
                default:
                    break;
            }
        }

        block.addView(line);
        if (extra != null) {
            LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            elp.leftMargin = dp(46);
            block.addView(extra, elp);
        }
        return block;
    }

    private void addClimate(LinearLayout line, String id, EntityState s, boolean enabled) {
        if (s == null || !s.attrs.has("temperature") || s.attrs.isNull("temperature")) return;
        final double target = s.attrs.optDouble("temperature");
        double stepAttr = s.attrs.optDouble("target_temp_step", Double.NaN);
        final double step = !Double.isNaN(stepAttr) && stepAttr > 0 ? stepAttr
                : (s.attrs.optString("temperature_unit", "").contains("F") ? 1.0 : 0.5);
        final double min = s.attrs.optDouble("min_temp", -100);
        final double max = s.attrs.optDouble("max_temp", 100);

        line.addView(iconButton("minus", enabled, () ->
                ctl.call(id, "climate", "set_temperature", json("temperature", Math.max(min, target - step)))), square(48));
        TextView t = text(Domains.number(target) + "°", com.google.android.material.R.style.TextAppearance_Material3_TitleMedium,
                com.google.android.material.R.attr.colorOnSurface);
        t.setGravity(Gravity.CENTER);
        line.addView(t, new LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT));
        line.addView(iconButton("plus", enabled, () ->
                ctl.call(id, "climate", "set_temperature", json("temperature", Math.min(max, target + step)))), square(48));
    }

    private View numberSlider(String id, String domain, EntityState s, boolean enabled) {
        double min = s.attrs.optDouble("min", 0);
        double max = s.attrs.optDouble("max", 100);
        double step = s.attrs.optDouble("step", 1);
        if (!(max > min)) return null;
        double cur;
        try {
            cur = Double.parseDouble(s.state);
        } catch (NumberFormatException e) {
            cur = min;
        }
        final double fMin = min, fStep = step > 0 ? step : 1;
        // Continuous slider (stepSize 0) avoids float-step validation crashes; we snap before sending.
        return slider((float) min, (float) max, 0f, (float) Math.max(min, Math.min(max, cur)), enabled, s.unit(), v -> {
            double snapped = fMin + Math.round((v - fMin) / fStep) * fStep;
            ctl.call(id, domain, "set_value", json("value", snapped));
        });
    }

    private View selectButton(String id, String domain, EntityState s, boolean enabled) {
        MaterialButton b = pillButton(Domains.pretty(s.state), enabled, null);
        JSONArray options = s.attrs.optJSONArray("options");
        b.setOnClickListener(v -> {
            if (options == null) return;
            PopupMenu menu = new PopupMenu(ui, v);
            for (int i = 0; i < options.length(); i++) {
                menu.getMenu().add(Menu.NONE, i, i, options.optString(i));
            }
            menu.setOnMenuItemClickListener(item -> {
                ctl.call(id, domain, "select_option", json("option", options.optString(item.getItemId())));
                return true;
            });
            try {
                menu.show();
            } catch (RuntimeException ignored) {
                // Some head units refuse popups from overlay windows; the state is still shown.
            }
        });
        return b;
    }

    // ------------------------------------------------------------------ widgets

    interface FloatAction {
        void run(float value);
    }

    private View slider(float from, float to, float step, float value, boolean enabled, String unit, FloatAction onRelease) {
        Slider sl = new Slider(ui);
        sl.setValueFrom(from);
        sl.setValueTo(to);
        sl.setStepSize(step);
        sl.setTickVisible(false);
        sl.setValue(Math.max(from, Math.min(to, value)));
        sl.setEnabled(enabled);
        sl.setLabelFormatter(v -> Domains.number(v) + (unit.isEmpty() ? "" : (unit.equals("%") ? "%" : " " + unit)));
        sl.addOnSliderTouchListener(new Slider.OnSliderTouchListener() {
            @Override
            public void onStartTrackingTouch(Slider slider) {
                ctl.setInteracting(true);
            }

            @Override
            public void onStopTrackingTouch(Slider slider) {
                ctl.setInteracting(false);
                onRelease.run(slider.getValue());
            }
        });
        return sl;
    }

    private View iconBadge(String iconName, boolean active, boolean light, int sizeDp) {
        TextView t = new TextView(ui);
        t.setTypeface(mdi);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(sizeDp) * 0.55f);
        t.setText(MdiIcons.glyphOf(ui, iconName, "mdi:home-assistant"));
        int bg, fg;
        if (active && light) {
            bg = ui.getColor(R.color.ha_light_on);
            fg = 0xFF3B2F00;
        } else if (active) {
            bg = color(com.google.android.material.R.attr.colorPrimaryContainer, 0xFF004C6C);
            fg = color(com.google.android.material.R.attr.colorOnPrimaryContainer, 0xFFC7E7FF);
        } else {
            bg = color(com.google.android.material.R.attr.colorSurfaceContainerHighest, 0xFF303539);
            fg = color(com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFFC1C7CE);
        }
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(bg);
        t.setBackground(circle);
        t.setTextColor(fg);
        return t;
    }

    private TextView iconButton(String iconName, boolean enabled, Runnable onClick) {
        TextView t = new TextView(ui);
        t.setTypeface(mdi);
        t.setGravity(Gravity.CENTER);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        t.setText(MdiIcons.glyphOf(ui, iconName));
        t.setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFFC1C7CE));
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(0xFFFFFFFF);
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33888888), null, mask));
        t.setEnabled(enabled);
        t.setAlpha(enabled ? 1f : 0.38f);
        t.setClickable(true);
        t.setFocusable(false);
        t.setOnClickListener(v -> onClick.run());
        return t;
    }

    private MaterialButton pillButton(String label, boolean enabled, Runnable onClick) {
        MaterialButton b = new MaterialButton(ui);
        b.setText(label);
        b.setAllCaps(false);
        b.setMinHeight(dp(48));
        b.setBackgroundTintList(ColorStateList.valueOf(
                color(com.google.android.material.R.attr.colorSecondaryContainer, 0xFF374955)));
        b.setTextColor(color(com.google.android.material.R.attr.colorOnSecondaryContainer, 0xFFD2E5F5));
        b.setEnabled(enabled);
        b.setAlpha(enabled ? 1f : 0.38f);
        if (onClick != null) b.setOnClickListener(v -> onClick.run());
        return b;
    }

    private TextView text(String s, int appearance, int colorAttr) {
        TextView t = new TextView(ui);
        t.setTextAppearance(appearance);
        t.setTextColor(color(colorAttr, 0xFFDFE3E7));
        t.setText(s);
        return t;
    }

    // ------------------------------------------------------------------ helpers

    private LinearLayout.LayoutParams square(int sizeDp) {
        return new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp));
    }

    private int color(int attr, int fallback) {
        return MaterialColors.getColor(ui, attr, fallback);
    }

    private int dp(float v) {
        return Math.round(v * ui.getResources().getDisplayMetrics().density);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static JSONObject json(String key, Object value) {
        try {
            return new JSONObject().put(key, value);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }
}
