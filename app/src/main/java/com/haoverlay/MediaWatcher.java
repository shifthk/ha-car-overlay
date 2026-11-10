package com.haoverlay;

import android.service.notification.NotificationListenerService;

/**
 * Does nothing with notifications. Android only lets an app see what other apps are playing
 * (their media sessions) if it owns an enabled notification listener, so this exists purely
 * to hold that permission for {@link NowPlaying}.
 */
public class MediaWatcher extends NotificationListenerService {
}
