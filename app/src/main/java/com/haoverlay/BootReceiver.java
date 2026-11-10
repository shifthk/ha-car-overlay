package com.haoverlay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

/** Brings the floating buttons back after the car (head unit) boots or the app is updated. */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Store store = new Store(context);
        boolean updated = Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction());
        if (!store.overlayEnabled() || (!updated && !store.startOnBoot())) return;
        if (!store.isLoggedIn() || !store.hasButtons()) return;
        if (!Settings.canDrawOverlays(context)) return;
        OverlayService.start(context);
    }
}
