package com.haoverlay;

import org.json.JSONArray;

import java.util.Locale;

/** Per-domain knowledge: default icons, what counts as "on", how to toggle, and how to show a state. */
final class Domains {

    private Domains() {
    }

    static String domainOf(String entityId) {
        int dot = entityId.indexOf('.');
        return dot > 0 ? entityId.substring(0, dot) : "";
    }

    static String defaultIcon(String domain, String state) {
        switch (domain) {
            case "light": return "lightbulb";
            case "switch": return "on".equals(state) ? "toggle-switch" : "toggle-switch-off";
            case "input_boolean": return "on".equals(state) ? "toggle-switch-outline" : "toggle-switch-off-outline";
            case "fan": return "fan";
            case "cover": return "open".equals(state) || "opening".equals(state) ? "window-shutter-open" : "window-shutter";
            case "lock": return "locked".equals(state) ? "lock" : "lock-open-variant";
            case "climate": return "thermostat";
            case "water_heater": return "water-boiler";
            case "sensor": return "eye";
            case "binary_sensor": return "on".equals(state) ? "checkbox-marked-circle" : "checkbox-blank-circle-outline";
            case "media_player": return "cast";
            case "scene": return "palette";
            case "script": return "script-text";
            case "automation": return "robot";
            case "camera": return "cctv";
            case "person": return "account";
            case "device_tracker": return "account";
            case "button": case "input_button": return "gesture-tap-button";
            case "vacuum": return "robot-vacuum";
            case "alarm_control_panel": return "shield-home";
            case "weather": return "weather-partly-cloudy";
            case "sun": return "white-balance-sunny";
            case "number": case "input_number": return "ray-vertex";
            case "select": case "input_select": return "format-list-bulleted";
            case "siren": return "bullhorn";
            case "humidifier": return "air-humidifier";
            case "zone": return "map-marker-radius";
            case "update": return "package-up";
            case "valve": return "valve";
            case "lawn_mower": return "robot-mower";
            case "group": return "google-circles-communities";
            default: return "home-assistant";
        }
    }

    static boolean isActiveState(String domain, String state) {
        if (state == null) return false;
        switch (state) {
            case "on": case "open": case "opening": case "closing": case "unlocked": case "unlocking":
            case "playing": case "home": case "cleaning": case "heat": case "cool": case "heat_cool":
            case "auto": case "dry": case "fan_only": case "mowing": case "triggered":
                return !"sensor".equals(domain);
            default:
                return state.startsWith("armed_");
        }
    }

    /** Domains whose entities can be switched with homeassistant.turn_on / turn_off. */
    static boolean isToggleable(String domain) {
        switch (domain) {
            case "light": case "switch": case "fan": case "input_boolean": case "automation":
            case "siren": case "humidifier": case "group": case "remote":
                return true;
            default:
                return false;
        }
    }

    /** Whether the card's long-press "main action" can do something with this entity. */
    static boolean hasPrimaryAction(String domain) {
        switch (domain) {
            case "cover": case "lock": case "scene": case "script": case "button": case "input_button":
            case "media_player": case "vacuum": case "valve":
                return true;
            default:
                return isToggleable(domain);
        }
    }

    static boolean lightSupportsBrightness(EntityState s) {
        JSONArray modes = s.attrs.optJSONArray("supported_color_modes");
        if (modes == null) return s.attrs.has("brightness");
        for (int i = 0; i < modes.length(); i++) {
            String m = modes.optString(i);
            if (!"onoff".equals(m) && !m.isEmpty()) return true;
        }
        return false;
    }

    static String formatState(EntityState s) {
        if (s == null) return "Not found";
        if ("unavailable".equals(s.state)) return "Unavailable";
        if ("unknown".equals(s.state)) return "Unknown";
        String domain = s.domain();
        switch (domain) {
            case "light": {
                if ("on".equals(s.state) && s.attrs.has("brightness")) {
                    int pct = (int) Math.round(s.attrs.optDouble("brightness", 0) / 255.0 * 100.0);
                    return "On · " + Math.max(1, pct) + "%";
                }
                return pretty(s.state);
            }
            case "climate": {
                StringBuilder b = new StringBuilder(pretty(s.state));
                if (s.attrs.has("current_temperature")) {
                    b.append(" · ").append(number(s.attrs.optDouble("current_temperature"))).append("°");
                }
                if (s.attrs.has("temperature") && !s.attrs.isNull("temperature")) {
                    b.append(" → ").append(number(s.attrs.optDouble("temperature"))).append("°");
                }
                return b.toString();
            }
            case "cover": {
                if (s.attrs.has("current_position")) {
                    return pretty(s.state) + " · " + s.attrs.optInt("current_position") + "%";
                }
                return pretty(s.state);
            }
            case "media_player": {
                String title = s.attrs.optString("media_title", "");
                return title.isEmpty() ? pretty(s.state) : pretty(s.state) + " · " + title;
            }
            case "sensor": case "number": case "input_number": {
                String unit = s.unit();
                String value = s.state;
                try {
                    value = number(Double.parseDouble(s.state));
                } catch (NumberFormatException ignored) {
                    if (unit.isEmpty()) value = pretty(s.state);
                }
                return unit.isEmpty() ? value : value + " " + unit;
            }
            case "binary_sensor":
                return "on".equals(s.state) ? "Detected" : "Clear";
            case "scene": case "button": case "input_button":
                return "Ready";
            default:
                return pretty(s.state);
        }
    }

    static String number(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-9) return String.valueOf((long) Math.rint(v));
        return String.format(Locale.getDefault(), "%.1f", v);
    }

    static String pretty(String state) {
        if (state == null || state.isEmpty()) return "";
        String s = state.replace('_', ' ');
        return s.substring(0, 1).toUpperCase(Locale.getDefault()) + s.substring(1);
    }
}
