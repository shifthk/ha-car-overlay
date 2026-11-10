package com.haoverlay;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.Map;

/**
 * One pinned card floating on screen as its own window. A round icon sits on the card's top right
 * corner; tapping it hides the card (leaving just the icon, in the same place) or shows it again.
 * The card is drawn by Home Assistant's web interface (its own {@link WebCard}, loaded the first time
 * the card is shown), with the built-in renderer as the fallback. Hold a finger still on the icon or
 * the card for a moment, then drag, to move it.
 */
final class CardWindow implements WebCard.Host, CardRenderer.Controller {

    interface Owner {
        Map<String, EntityState> states();

        boolean online();

        void callService(String entityId, String domain, String service, JSONObject data);
    }

    private static final String TAG = "CardWindow";
    private static final String POS_PREFIX = "win:";
    /** Shorter than Home Assistant's own 500 ms hold, so holding to move never fires a card's hold action. */
    private static final long HOLD_MS = 400;

    private final Context ui;
    private final WindowManager wm;
    private final Store store;
    private final Owner owner;
    private final CardRenderer renderer;
    private final Frame frame;
    private final FrameLayout content;
    private final FloatingCardButton icon;
    private final WindowManager.LayoutParams lp;
    private final int iconSize;

    private CardInfo card;
    private WebCard web;
    private boolean useWeb;
    private boolean collapsed;
    private int fullWidth;
    private int screenW, screenH;
    private boolean added;
    private boolean destroyed;
    private boolean interacting;
    private boolean renderPending;
    private View loading;

    CardWindow(Context ui, WindowManager wm, Store store, Owner owner, CardInfo card) {
        this.ui = ui;
        this.wm = wm;
        this.store = store;
        this.owner = owner;
        this.card = card;
        this.renderer = new CardRenderer(ui, this);
        this.iconSize = dp(48);
        this.collapsed = store.cardCollapsed(card.id);

        lp = new WindowManager.LayoutParams(
                iconSize, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;

        frame = new Frame(ui);
        content = new FrameLayout(ui);
        // Room at the top and right so the icon sits on the card's corner instead of over its controls.
        content.setPadding(dp(6), dp(20), dp(20), dp(6));
        frame.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        frame.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (b - t != ob - ot) frame.post(this::keepOnScreen);
        });

        icon = new FloatingCardButton(ui);
        icon.setColors(0xF21F2428, Color.WHITE, 0x40FFFFFF);
        icon.setContentDescription(card.title);
        icon.setOnClickListener(v -> toggle());
        frame.addView(icon, new FrameLayout.LayoutParams(iconSize, iconSize, Gravity.TOP | Gravity.END));
        refreshIcon();
    }

    String id() {
        return card.id;
    }

    boolean showsEntity(String entityId) {
        for (CardInfo.EntityRef r : card.entities) {
            if (r.entityId.equals(entityId)) return true;
        }
        return false;
    }

    /** Puts the window on screen. {@code index} only picks the starting spot for a card never moved before. */
    void open(int screenW, int screenH, int index, boolean useWeb, long webLoadDelayMs) {
        this.screenW = screenW;
        this.screenH = screenH;
        this.useWeb = useWeb;
        fullWidth = Math.min(dp(store.panelWidthDp()), screenW - dp(16));
        applyCollapsed();
        place(index, false);
        if (!collapsed) render();
        try {
            wm.addView(frame, lp);
            added = true;
        } catch (RuntimeException e) {
            Log.e(TAG, "Couldn't show card", e);
            return;
        }
        if (!collapsed) ensureWeb(webLoadDelayMs);
        frame.post(this::keepOnScreen);
    }

    /** The card's settings, the screen or the app's settings may have changed. */
    void refresh(CardInfo latest, int screenW, int screenH, boolean useWeb) {
        this.screenW = screenW;
        this.screenH = screenH;
        boolean configChanged = !latest.config.toString().equals(card.config.toString());
        card = latest;
        icon.setContentDescription(card.title);
        refreshIcon();

        int width = Math.min(dp(store.panelWidthDp()), screenW - dp(16));
        if (width != fullWidth) {
            if (collapsed) lp.x += fullWidth - width;   // keep the icon where it is
            fullWidth = width;
            applyCollapsed();
            update();
        }
        this.useWeb = useWeb;
        if (!useWeb && web != null) {
            web.release();
            web = null;
        }
        if (configChanged && web != null) web.show(card.config);
        if (!collapsed) {
            ensureWeb(0);
            render();
        }
        frame.post(this::keepOnScreen);
    }

    void resetPosition(int index) {
        place(index, true);
        update();
    }

    /** Entity states changed; only the built-in drawing needs redrawing (the web page updates itself). */
    void onStates() {
        refreshIcon();
        if (!collapsed && (web == null || !web.isReady())) render();
    }

    void onConnected() {
        if (web != null && web.hasFailed()) web.load();   // the network is back: try the page again
    }

    void destroy() {
        destroyed = true;
        frame.removeCallbacks(frame.arm);
        if (web != null) web.release();
        web = null;
        if (added) {
            try {
                wm.removeView(frame);
            } catch (RuntimeException ignored) {
            }
            added = false;
        }
    }

    // ------------------------------------------------------------------ show / hide

    private void toggle() {
        if (destroyed) return;
        int shift = fullWidth - iconSize;
        collapsed = !collapsed;
        store.setCardCollapsed(card.id, collapsed);
        // The icon is the window's top right corner either way, so it stays put while the card comes and goes.
        lp.x += collapsed ? shift : -shift;
        applyCollapsed();
        if (!collapsed) {
            ensureWeb(0);
            render();
            clamp();
        }
        update();
        frame.post(this::keepOnScreen);
    }

    private void applyCollapsed() {
        content.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        lp.width = collapsed ? iconSize : fullWidth;
    }

    private void refreshIcon() {
        icon.setGlyph(MdiIcons.glyphOf(ui, card.iconName(owner.states()), "mdi:home-assistant"));
        icon.setOffline(!owner.online());
    }

    // ------------------------------------------------------------------ content

    /** Starts Home Assistant's page for this card if it should have one and doesn't yet. */
    private void ensureWeb(long delayMs) {
        if (!useWeb || web != null || destroyed) return;
        final WebCard w = new WebCard(ui, store, this);
        web = w;
        w.show(card.config);
        // Loading the page is the heavy part: let the window appear first.
        frame.postDelayed(() -> {
            if (w == web && !destroyed) w.load();
        }, Math.max(0, delayMs));
    }

    private void render() {
        if (destroyed || collapsed) return;
        if (interacting) {
            renderPending = true;
            return;
        }
        renderPending = false;
        int maxHeight = (int) (screenH * 0.9f) - dp(26);
        if (web != null && web.isReady()) {
            View v = web.view();
            int height = web.desiredHeight(maxHeight);
            if (v.getParent() != content) {
                content.removeAllViews();
                if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
                content.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));
            } else if (v.getLayoutParams().height != height) {
                v.getLayoutParams().height = height;
                v.requestLayout();
            }
        } else if (web != null && !web.hasFailed()) {
            // Home Assistant's page is still loading: a small placeholder until the real card is ready.
            if (content.getChildCount() != 1 || content.getChildAt(0) != loading) {
                content.removeAllViews();
                content.addView(loadingView());
            }
        } else {
            content.removeAllViews();
            content.addView(renderer.build(card, owner.states(), owner.online(), maxHeight));
        }
    }

    @Override
    public void onWebCardReady() {
        render();
        frame.post(this::keepOnScreen);
    }

    @Override
    public void onWebCardSize() {
        render();
        frame.post(this::keepOnScreen);
    }

    @Override
    public void onWebCardFailed() {
        render();
    }

    @Override
    public void call(String entityId, String domain, String service, JSONObject data) {
        owner.callService(entityId, domain, service, data);
    }

    /** The built-in card's own close button: hide the card. */
    @Override
    public void close() {
        if (!collapsed) toggle();
    }

    @Override
    public void setInteracting(boolean on) {
        interacting = on;
        if (!on && renderPending) frame.post(this::render);
    }

    // ------------------------------------------------------------------ position

    /**
     * The saved position is always the card's top left when shown, so a hidden card's icon and the
     * same card shown share one remembered spot. Cards never moved start stacked down the right edge.
     */
    private void place(int index, boolean forceDefault) {
        int[] saved = forceDefault ? null : store.fabPosition(POS_PREFIX + card.id);
        int x, y;
        if (saved != null) {
            x = saved[0];
            y = saved[1];
        } else {
            x = screenW - fullWidth - dp(24);
            y = dp(24) + Math.max(0, index) * (iconSize + dp(16));
        }
        lp.x = collapsed ? x + fullWidth - iconSize : x;
        lp.y = y;
        clamp();
    }

    private void savePosition() {
        int x = collapsed ? lp.x - (fullWidth - iconSize) : lp.x;
        store.setFabPosition(POS_PREFIX + card.id, x, lp.y);
    }

    private void clamp() {
        int h = collapsed ? iconSize : frame.getHeight() > iconSize ? frame.getHeight() : dp(160);
        lp.x = Math.max(0, Math.min(Math.max(0, screenW - lp.width), lp.x));
        lp.y = Math.max(0, Math.min(Math.max(0, screenH - h), lp.y));
    }

    private void keepOnScreen() {
        if (destroyed || frame.armed) return;
        int x = lp.x, y = lp.y;
        clamp();
        if (x != lp.x || y != lp.y) update();
    }

    private void update() {
        if (!added) return;
        try {
            wm.updateViewLayout(frame, lp);
        } catch (RuntimeException ignored) {
        }
    }

    private View loadingView() {
        if (loading == null) {
            TextView t = new TextView(ui);
            t.setText(card.title + " · loading…");
            t.setTextSize(16);
            t.setTextColor(Color.WHITE);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(20), dp(28), dp(20), dp(28));
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(20));
            bg.setColor(0xE6202428);
            t.setBackground(bg);
            loading = t;
        }
        return loading;
    }

    private int dp(float v) {
        return Math.round(v * ui.getResources().getDisplayMetrics().density);
    }

    /**
     * The window's root. Touches go to the icon and the card as usual; a finger held still for
     * {@link #HOLD_MS} takes over the gesture and drags the window instead.
     */
    private final class Frame extends FrameLayout {
        private final int slop;
        private float downX, downY;
        private int startX, startY;
        private boolean down;
        boolean armed;

        final Runnable arm = this::startDrag;

        Frame(Context c) {
            super(c);
            slop = ViewConfiguration.get(c).getScaledTouchSlop();
        }

        private void startDrag() {
            if (!down || destroyed) return;
            armed = true;
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            setAlpha(0.8f);
            // Tell the card its touch is over, so nothing in it is pressed or held when the drag ends.
            long t = SystemClock.uptimeMillis();
            MotionEvent cancel = MotionEvent.obtain(t, t, MotionEvent.ACTION_CANCEL, 0, 0, 0);
            super.dispatchTouchEvent(cancel);
            cancel.recycle();
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down = true;
                    armed = false;
                    downX = ev.getRawX();
                    downY = ev.getRawY();
                    startX = lp.x;
                    startY = lp.y;
                    removeCallbacks(arm);
                    postDelayed(arm, HOLD_MS);
                    super.dispatchTouchEvent(ev);
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (armed) {
                        lp.x = startX + (int) (ev.getRawX() - downX);
                        lp.y = startY + (int) (ev.getRawY() - downY);
                        clamp();
                        update();
                        return true;
                    }
                    if (Math.abs(ev.getRawX() - downX) > slop || Math.abs(ev.getRawY() - downY) > slop) {
                        removeCallbacks(arm);   // the finger is scrolling or sliding inside the card
                    }
                    super.dispatchTouchEvent(ev);
                    return true;

                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    down = false;
                    removeCallbacks(arm);
                    if (armed) {
                        armed = false;
                        setAlpha(1f);
                        savePosition();
                        return true;
                    }
                    super.dispatchTouchEvent(ev);
                    return true;

                default:
                    if (armed) return true;
                    removeCallbacks(arm);   // a second finger: not a hold
                    super.dispatchTouchEvent(ev);
                    return true;
            }
        }
    }
}
