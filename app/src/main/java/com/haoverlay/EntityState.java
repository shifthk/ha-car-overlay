package com.haoverlay;

import org.json.JSONObject;

/** One entity's current state, as sent by Home Assistant. */
final class EntityState {

    final String entityId;
    final String state;
    final JSONObject attrs;

    private EntityState(String entityId, String state, JSONObject attrs) {
        this.entityId = entityId;
        this.state = state;
        this.attrs = attrs;
    }

    static EntityState from(JSONObject o) {
        if (o == null) return null;
        String id = o.optString("entity_id", "");
        if (id.isEmpty()) return null;
        JSONObject a = o.optJSONObject("attributes");
        return new EntityState(id, o.optString("state", "unknown"), a == null ? new JSONObject() : a);
    }

    String domain() {
        return Domains.domainOf(entityId);
    }

    String friendlyName() {
        String n = attrs.optString("friendly_name", "");
        return n.isEmpty() ? entityId : n;
    }

    /** The entity's own icon attribute, e.g. {@code mdi:lamp}, or {@code ""}. */
    String icon() {
        return attrs.optString("icon", "");
    }

    String unit() {
        return attrs.optString("unit_of_measurement", "");
    }

    boolean isUnavailable() {
        return "unavailable".equals(state) || "unknown".equals(state);
    }

    boolean isActive() {
        return Domains.isActiveState(domain(), state);
    }
}
