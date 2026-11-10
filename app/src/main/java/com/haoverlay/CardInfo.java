package com.haoverlay;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One card from a Lovelace dashboard, reduced to what the overlay needs: a title, an icon,
 * the entities it shows and its raw config (for the tap action). Stored as JSON when pinned,
 * so floating buttons keep working even if the dashboard is not reachable.
 */
final class CardInfo {

    static final class EntityRef {
        final String entityId;
        /** Name override from the card config, or {@code ""}. */
        final String name;
        /** Icon override from the card config, or {@code ""}. */
        final String icon;

        EntityRef(String entityId, String name, String icon) {
            this.entityId = entityId;
            this.name = name == null ? "" : name;
            this.icon = icon == null ? "" : icon;
        }

        String displayName(EntityState s) {
            if (!name.isEmpty()) return name;
            return s == null ? entityId : s.friendlyName();
        }
    }

    /** Stable while the card's config is unchanged, so pins survive re-ordering the dashboard. */
    final String id;
    final String type;
    final String title;
    /** Card-level icon, e.g. {@code mdi:sofa}, or {@code ""}. */
    final String icon;
    /** Where it lives, e.g. "Home · Living room". */
    final String location;
    final List<EntityRef> entities;
    final JSONObject config;

    private CardInfo(String id, String type, String title, String icon, String location,
                     List<EntityRef> entities, JSONObject config) {
        this.id = id;
        this.type = type;
        this.title = title;
        this.icon = icon;
        this.location = location;
        this.entities = entities;
        this.config = config;
    }

    // ------------------------------------------------------------------ display helpers

    String subtitle() {
        StringBuilder b = new StringBuilder(typeLabel(type));
        int n = entities.size();
        b.append(" · ").append(n).append(n == 1 ? " entity" : " entities");
        if (!location.isEmpty()) b.append(" · ").append(location);
        return b.toString();
    }

    /** Icon name to show for this card: its own icon, else its first entity's icon, else the domain default. */
    String iconName(Map<String, EntityState> states) {
        if (!icon.isEmpty()) return icon;
        String byContent = contentIcon();
        if (byContent != null) return byContent;
        if (entities.isEmpty()) return "mdi:home-assistant";
        EntityRef first = entities.get(0);
        if (!first.icon.isEmpty()) return first.icon;
        EntityState s = states == null ? null : states.get(first.entityId);
        if (s != null && !s.icon().isEmpty()) return s.icon();
        return "mdi:" + Domains.defaultIcon(Domains.domainOf(first.entityId), s == null ? "" : s.state);
    }

    /** Icon, then the words that call for it. Earlier rows win, so the more specific subjects come first. */
    private static final String[][] ICON_WORDS = {
            {"chart-bar", "apexcharts", "chart", "graph", "history", "per week", "per month", "statistics"},
            {"chart-box-outline", "stats", "statistic"},
            {"ev-station", "charger", "charging", "evcc", "wallbox"},
            {"car-electric", "car", "vehicle", "zeekr", "tesla", "odometer"},
            {"home-battery", "battery", "foxess", "foxbattery", "powerwall", "inverter"},
            {"solar-power", "solar", "pv"},
            {"lightbulb-group", "light", "lamp", "downlight"},
            {"air-conditioner", "air conditioning", "air con", "aircon", "climate", "daikin", "thermostat", "heating", "hvac"},
            {"doorbell-video", "doorbell", "camera"},
            {"door", "door", "lock", "gate"},
            {"garage", "garage"},
            {"television", "tv", "television", "media"},
            {"speaker", "speaker", "music", "sonos"},
            {"weather-partly-cloudy", "weather", "forecast"},
            {"robot-vacuum", "vacuum"},
            {"fan", "fan", "purifier"},
            {"blinds", "blind", "curtain", "shutter", "cover"},
            {"shield-home", "alarm", "security"},
            {"washing-machine", "washer", "washing", "dryer"},
            {"water-boiler", "hot water", "boiler"},
            {"thermometer", "temperature"},
    };

    /** An icon chosen from what the card is about: its name first, then its type and entities. */
    private String contentIcon() {
        StringBuilder about = new StringBuilder(type);
        for (EntityRef r : entities) about.append(' ').append(r.entityId);
        for (String text : new String[]{title, about.toString()}) {
            String t = text.toLowerCase(Locale.ROOT).replace('_', ' ').replace('.', ' ').replace('-', ' ').replace(':', ' ');
            for (String[] row : ICON_WORDS) {
                for (int i = 1; i < row.length; i++) {
                    if (java.util.regex.Pattern.compile("(?<![a-z])" + row[i] + "(s|es)?(?![a-z])").matcher(t).find()) {
                        return "mdi:" + row[0];
                    }
                }
            }
        }
        return null;
    }

    static String typeLabel(String type) {
        if (type.startsWith("custom:")) {
            return Domains.pretty(type.substring(7).replace('-', ' '));
        }
        switch (type) {
            case "tile": return "Tile";
            case "button": return "Button";
            case "entity": return "Entity";
            case "entities": return "Entities";
            case "glance": return "Glance";
            case "light": return "Light";
            case "thermostat": return "Thermostat";
            case "media-control": return "Media";
            case "sensor": return "Sensor";
            case "gauge": return "Gauge";
            case "vertical-stack": case "horizontal-stack": return "Stack";
            case "grid": return "Grid";
            case "area": return "Area";
            default: return Domains.pretty(type.replace('-', ' '));
        }
    }

    // ------------------------------------------------------------------ persistence

    JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id).put("type", type).put("title", title).put("icon", icon).put("location", location);
            JSONArray ents = new JSONArray();
            for (EntityRef r : entities) {
                ents.put(new JSONObject().put("entity", r.entityId).put("name", r.name).put("icon", r.icon));
            }
            o.put("entities", ents).put("config", config);
        } catch (JSONException ignored) {
        }
        return o;
    }

    static CardInfo fromJson(JSONObject o) {
        String id = o.optString("id", "");
        if (id.isEmpty()) return null;
        List<EntityRef> refs = new ArrayList<>();
        JSONArray ents = o.optJSONArray("entities");
        if (ents != null) {
            for (int i = 0; i < ents.length(); i++) {
                JSONObject e = ents.optJSONObject(i);
                if (e != null && !e.optString("entity").isEmpty()) {
                    refs.add(new EntityRef(e.optString("entity"), e.optString("name"), e.optString("icon")));
                }
            }
        }
        JSONObject cfg = o.optJSONObject("config");
        // Cards pinned by an older version were saved under a made-up name; prefer the card's own heading.
        String own = ownTitle(cfg, 0);
        return new CardInfo(id, o.optString("type", "card"), own != null ? own : o.optString("title", "Card"), o.optString("icon", ""),
                o.optString("location", ""), refs, cfg == null ? new JSONObject() : cfg);
    }

    // ------------------------------------------------------------------ dashboard parsing

    static final class Dashboard {
        final List<CardInfo> cards;
        /** True for auto-generated dashboards (no cards in their config). */
        final boolean generated;

        Dashboard(List<CardInfo> cards, boolean generated) {
            this.cards = cards;
            this.generated = generated;
        }
    }

    private static final Set<String> SKIP_KEYS = new HashSet<>(Arrays.asList(
            "conditions", "visibility", "state_filter", "tap_action", "hold_action", "double_tap_action",
            "icon_tap_action", "card_mod", "badges", "features"));

    private static final Set<String> NO_ENTITY_TYPES = new HashSet<>(Arrays.asList(
            "heading", "markdown", "iframe", "map", "logbook", "calendar", "todo-list", "shopping-list"));

    /** Flattens a Lovelace config (classic views, sections views, stacks) into pin-able cards. */
    static Dashboard parse(JSONObject config, Map<String, EntityState> states) {
        if (config == null) return new Dashboard(Collections.emptyList(), false);
        JSONArray views = config.optJSONArray("views");
        if (views == null) return new Dashboard(Collections.emptyList(), config.has("strategy"));

        List<CardInfo> out = new ArrayList<>();
        Map<String, Integer> seenIds = new HashMap<>();
        boolean generated = config.has("strategy");
        for (int vi = 0; vi < views.length(); vi++) {
            JSONObject view = views.optJSONObject(vi);
            if (view == null) continue;
            if (view.has("strategy")) generated = true;
            String viewTitle = view.optString("title", "").trim();
            if (viewTitle.isEmpty()) viewTitle = "View " + (vi + 1);

            JSONArray cards = view.optJSONArray("cards");
            if (cards != null) {
                for (int ci = 0; ci < cards.length(); ci++) {
                    add(out, seenIds, cards.optJSONObject(ci), viewTitle, states);
                }
            }
            JSONArray sections = view.optJSONArray("sections");
            if (sections != null) {
                for (int si = 0; si < sections.length(); si++) {
                    JSONObject section = sections.optJSONObject(si);
                    if (section == null) continue;
                    String heading = section.optString("title", "").trim();
                    JSONArray sc = section.optJSONArray("cards");
                    if (sc == null) continue;
                    for (int ci = 0; ci < sc.length(); ci++) {
                        JSONObject card = sc.optJSONObject(ci);
                        if (card == null) continue;
                        if ("heading".equals(card.optString("type"))) {
                            heading = card.optString("heading", heading).trim();
                            continue;
                        }
                        add(out, seenIds, card, heading.isEmpty() ? viewTitle : viewTitle + " · " + heading, states);
                    }
                }
            }
        }
        return new Dashboard(out, generated && out.isEmpty());
    }

    private static void add(List<CardInfo> out, Map<String, Integer> seenIds, JSONObject card,
                            String location, Map<String, EntityState> states) {
        if (card == null) return;
        String type = card.optString("type", "");
        if (type.isEmpty() || NO_ENTITY_TYPES.contains(type)) return;

        // A state-switch is a slot that shows one of several cards. List those cards themselves, once
        // each, rather than the slot (the same set is usually repeated in every slot of a dashboard).
        JSONObject options = "custom:state-switch".equals(type) ? card.optJSONObject("states") : null;
        if (options != null && options.names() != null) {
            JSONArray keys = options.names();
            for (int i = 0; i < keys.length(); i++) {
                String key = keys.optString(i);
                JSONObject inner = options.optJSONObject(key);
                if (inner == null || seenIds.containsKey("option:" + key)) continue;
                seenIds.put("option:" + key, 0);
                add(out, seenIds, inner, location, states, Domains.pretty(key));
            }
            return;
        }
        add(out, seenIds, card, location, states, null);
    }

    private static void add(List<CardInfo> out, Map<String, Integer> seenIds, JSONObject card,
                            String location, Map<String, EntityState> states, String fallbackTitle) {
        String type = card.optString("type", "");
        if (type.isEmpty() || NO_ENTITY_TYPES.contains(type)) return;

        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<EntityRef> refs = new ArrayList<>();
        collect(card, refs, seen);
        if (refs.isEmpty()) return;

        String baseId = Integer.toHexString(card.toString().hashCode());
        int n = seenIds.containsKey(baseId) ? seenIds.get(baseId) + 1 : 0;
        seenIds.put(baseId, n);
        String id = n == 0 ? baseId : baseId + "-" + n;

        String title = ownTitle(card, 0);
        if (title == null) title = fallbackTitle;
        if (title == null) title = deriveTitle(card, type, refs, states);
        out.add(new CardInfo(id, type, title, card.optString("icon", ""), location, refs, card));
    }

    /** The small heading custom button-cards print at the top of their body, e.g. {@code <div style='${variables.eb}'>Home battery</div>}. */
    private static final java.util.regex.Pattern EYEBROW =
            java.util.regex.Pattern.compile("variables\\.eb[^>]*>([^<]{2,60})<");

    /** A name the card states for itself (or one of the cards inside it does); {@code null} if it has none. */
    private static String ownTitle(JSONObject card, int depth) {
        if (card == null || depth > 4) return null;
        for (String key : new String[]{"title", "name", "heading", "primary"}) {
            Object v = card.opt(key);
            if (v instanceof String && isPlain((String) v)) return ((String) v).trim();
        }
        JSONObject header = card.optJSONObject("header");
        if (header != null && isPlain(header.optString("title", ""))) return header.optString("title").trim();

        JSONObject fields = card.optJSONObject("custom_fields");
        Object body = fields == null ? null : fields.opt("body");
        if (body instanceof String) {
            java.util.regex.Matcher m = EYEBROW.matcher((String) body);
            while (m.find()) {
                String t = m.group(1).replace("&middot;", "·").replace("&amp;", "&").replace("&nbsp;", " ").trim();
                if (isPlain(t) && !t.contains("&")) return t;
            }
        }
        String t = ownTitle(card.optJSONObject("card"), depth + 1);
        if (t != null) return t;
        JSONArray children = card.optJSONArray("cards");
        if (children != null) {
            for (int i = 0; i < children.length(); i++) {
                t = ownTitle(children.optJSONObject(i), depth + 1);
                if (t != null) return t;
            }
        }
        return null;
    }

    /** Real text, not a template that only makes sense once Home Assistant has filled it in. */
    private static boolean isPlain(String s) {
        String t = s.trim();
        return !t.isEmpty() && !t.contains("{{") && !t.contains("[[[") && !t.contains("${") && !t.contains("`");
    }

    private static String deriveTitle(JSONObject card, String type, List<EntityRef> refs,
                                      Map<String, EntityState> states) {
        if ("area".equals(type) && !card.optString("area").isEmpty()) {
            return Domains.pretty(card.optString("area"));
        }
        JSONObject layout = card.optJSONObject("view_layout");
        String area = layout == null ? "" : layout.optString("grid-area", "").trim();
        if (!area.isEmpty()) return Domains.pretty(area);
        EntityRef first = refs.get(0);
        EntityState s = states == null ? null : states.get(first.entityId);
        String name = first.displayName(s);
        if (refs.size() > 1) return String.format(Locale.getDefault(), "%s + %d more", name, refs.size() - 1);
        return name;
    }

    /** Collects every entity a card (and any cards nested in it) shows, in order, without duplicates. */
    private static void collect(Object node, List<EntityRef> out, Set<String> seen) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            Object ent = o.opt("entity");
            if (ent instanceof String && ((String) ent).contains(".")) {
                addRef(out, seen, (String) ent, o.optString("name", ""), o.optString("icon", ""));
            }
            JSONArray names = o.names();
            if (names == null) return;
            for (int i = 0; i < names.length(); i++) {
                String key = names.optString(i);
                if (SKIP_KEYS.contains(key) || "entity".equals(key)) continue;
                Object v = o.opt(key);
                if ("entities".equals(key) && v instanceof JSONArray) {
                    JSONArray arr = (JSONArray) v;
                    for (int j = 0; j < arr.length(); j++) {
                        Object item = arr.opt(j);
                        if (item instanceof String && ((String) item).contains(".")) {
                            addRef(out, seen, (String) item, "", "");
                        } else if (item instanceof JSONObject) {
                            collect(item, out, seen);
                        }
                    }
                } else if (v instanceof JSONObject || v instanceof JSONArray) {
                    collect(v, out, seen);
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) collect(a.opt(i), out, seen);
        }
    }

    private static void addRef(List<EntityRef> out, Set<String> seen, String id, String name, String icon) {
        if (seen.add(id)) {
            out.add(new EntityRef(id, name.contains("{{") ? "" : name, icon));
        }
    }
}
