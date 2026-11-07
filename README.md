# HA Overlay

Pin cards from your Home Assistant dashboard as floating buttons on an Android car screen (built for the Zeekr 7X head unit, works on any Android 9+ device).

- **Sign in** with your normal Home Assistant login (OAuth, inside the app), or paste a long-lived access token.
- **Pick cards** from any dashboard. A dashboard with "hub" in its name (e.g. *Home Hub*) is selected automatically, then one with "mobile", otherwise *Overview*.
- **Floating buttons**: each pinned card becomes a round button that floats over every app. The button lights up when the card's entities are on (amber for lights).
  - **Tap**: opens a Material 3 popup of the card with live states and controls: switches, brightness and fan sliders, cover open/stop/close and position, lock/unlock, scene/script/button, thermostat −/+, media controls, vacuum, number sliders and select menus.
  - **Long-press**: runs the card's main action (its `tap_action` if that performs an action; otherwise toggles or activates its first entity).
  - **Drag**: moves the button; its position is remembered.
- **Persistence**: server, login, chosen dashboard, a cached copy of the card list, pinned cards, button positions and settings all survive restarts. The overlay comes back after the car boots (can be turned off).

## How the floating button works

This is the same technique used by [zeekr-shortcut-car](https://github.com/dts88/zeekr-shortcut-car) and [CarPiano](https://github.com/jlunnn/CarPiano). A foreground `Service` adds views straight to the `WindowManager` as `TYPE_APPLICATION_OVERLAY` windows with `FLAG_NOT_FOCUSABLE`, so touches outside a button pass through to the app underneath. Moving a button just updates its `LayoutParams.x/y` with `updateViewLayout`. It needs the **Display over other apps** (`SYSTEM_ALERT_WINDOW`) permission.

No code was copied from either project (zeekr-shortcut-car is GPL-3.0).

## Build

Requirements: JDK 17+ (Android Studio's bundled JBR works) and the Android SDK with platform 35.

```bash
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. `assembleRelease` also works and is signed with the debug key, which is fine for sideloading.

## Install on the car

1. Copy the APK to a USB stick and install it with the head unit's file manager, or use `adb install app-debug.apk` over the car's developer/ADB connection.
2. Open **HA Overlay**, enter your Home Assistant URL and sign in. The car is usually on mobile data, so use an address that works from outside your home (Nabu Casa `https://xxxx.ui.nabu.casa` or your external URL). `homeassistant.local` will only work on your home Wi-Fi.
3. Tap **Allow display over other apps**. If the head unit has no such settings page, grant it from a computer:

   ```bash
   adb shell appops set com.haoverlay SYSTEM_ALERT_WINDOW allow
   ```

4. Turn on the cards you want, then tap **Start floating buttons**.

If the buttons vanish after the car sleeps, allow HA Overlay to auto-start / run in the background in the car's app settings, if it has those options.

## Project layout

| File | What it does |
| --- | --- |
| `LoginActivity` | Server URL, OAuth sign-in through HA's login page (redirect intercepted in the WebView), or long-lived token |
| `MainActivity` | Dashboard picker, card list with pin switches, overlay controls and settings |
| `OverlayService` | Foreground service: floating buttons, card popup, live WebSocket connection, long-press actions |
| `CardRenderer` | Builds the native Material 3 card popup and its per-domain controls |
| `CardInfo` | Parses Lovelace configs (classic views, sections, stacks) into pinnable cards |
| `HaAuth` / `HaSocket` | Token exchange and refresh; WebSocket API client with auto-reconnect |
| `Store` | All persisted state (SharedPreferences) |
| `MdiIcons` | Bundled Material Design Icons font, the same icons HA uses (`mdi:*`) |

## Notes

- Tokens are stored in the app's private storage. Logging out revokes the refresh token on the server.
- Custom cards (Mushroom, button-card and so on) are supported by extracting the entities they reference. They are drawn with native controls, not with the card's own styling.
- Icons come from [Material Design Icons](https://pictogrammers.com/library/mdi/) 7.4.47 (Pictogrammers, Apache 2.0).
