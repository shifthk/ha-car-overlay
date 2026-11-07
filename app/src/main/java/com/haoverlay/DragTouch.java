package com.haoverlay;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;

/**
 * Tap / long-press / drag on an overlay window. Dragging moves the window via its LayoutParams;
 * the listener clamps and applies the position and saves it when the finger lifts.
 */
final class DragTouch implements View.OnTouchListener {

    interface Listener {
        void onTap();

        void onLongPress();

        /** Move the window's top-left to (x, y) — clamp to the screen and call updateViewLayout. */
        void onMove(int x, int y);

        void onDragEnd();
    }

    private static final long LONG_PRESS_MS = 600;   // a little longer than the system default: this is pressed in a car

    private final WindowManager.LayoutParams lp;
    private final Listener listener;
    private final int slop;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private float downX, downY;
    private int startX, startY;
    private boolean dragging, longFired;
    private View pressedView;

    private final Runnable longPress = new Runnable() {
        @Override
        public void run() {
            longFired = true;
            if (pressedView != null) {
                pressedView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                pressedView.setPressed(false);
            }
            listener.onLongPress();
        }
    };

    DragTouch(Context context, WindowManager.LayoutParams lp, Listener listener) {
        this.lp = lp;
        this.listener = listener;
        this.slop = ViewConfiguration.get(context).getScaledTouchSlop() * 2;
    }

    @Override
    public boolean onTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getRawX();
                downY = e.getRawY();
                startX = lp.x;
                startY = lp.y;
                dragging = false;
                longFired = false;
                pressedView = v;
                v.setPressed(true);
                handler.postDelayed(longPress, LONG_PRESS_MS);
                return true;

            case MotionEvent.ACTION_MOVE: {
                int dx = (int) (e.getRawX() - downX);
                int dy = (int) (e.getRawY() - downY);
                if (!dragging && !longFired && (Math.abs(dx) > slop || Math.abs(dy) > slop)) {
                    dragging = true;
                    handler.removeCallbacks(longPress);
                    v.setPressed(false);
                }
                if (dragging) listener.onMove(startX + dx, startY + dy);
                return true;
            }

            case MotionEvent.ACTION_UP:
                handler.removeCallbacks(longPress);
                v.setPressed(false);
                if (dragging) listener.onDragEnd();
                else if (!longFired) {
                    v.playSoundEffect(android.view.SoundEffectConstants.CLICK);
                    listener.onTap();
                }
                pressedView = null;
                return true;

            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(longPress);
                v.setPressed(false);
                if (dragging) listener.onDragEnd();
                pressedView = null;
                return true;

            default:
                return false;
        }
    }
}
