package org.json;

import hmgapi.engine.Json;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class JSONObject {
    public static final Object NULL = new Object();
    final Map<String, Object> map;

    public JSONObject() {
        this.map = new LinkedHashMap<String, Object>();
    }

    public JSONObject(String json) {
        this.map = Json.object(json);
    }

    JSONObject(Map<String, Object> map) {
        this.map = map;
    }

    public boolean has(String name) {
        return map.containsKey(name);
    }

    public boolean isNull(String name) {
        return !map.containsKey(name) || map.get(name) == null || map.get(name) == NULL;
    }

    public Object opt(String name) {
        Object v = map.get(name);
        if (v instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) v;
            return new JSONObject(m);
        }
        if (v instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> l = (List<Object>) v;
            return new JSONArray(l);
        }
        return v;
    }

    public JSONObject put(String name, Object value) {
        map.put(name, value);
        return this;
    }

    public String optString(String name) {
        return optString(name, "");
    }

    public String optString(String name, String fallback) {
        Object v = map.get(name);
        return v == null ? fallback : String.valueOf(v);
    }

    public int optInt(String name, int fallback) {
        Object v = map.get(name);
        return v instanceof Number ? ((Number) v).intValue() : fallback;
    }

    public long optLong(String name, long fallback) {
        Object v = map.get(name);
        return v instanceof Number ? ((Number) v).longValue() : fallback;
    }

    public double optDouble(String name, double fallback) {
        Object v = map.get(name);
        return v instanceof Number ? ((Number) v).doubleValue() : fallback;
    }

    public boolean optBoolean(String name, boolean fallback) {
        Object v = map.get(name);
        if (v instanceof Boolean) return ((Boolean) v).booleanValue();
        if (v instanceof String) {
            if ("true".equalsIgnoreCase((String) v)) return true;
            if ("false".equalsIgnoreCase((String) v)) return false;
        }
        return fallback;
    }

    public JSONObject optJSONObject(String name) {
        Object v = map.get(name);
        if (v instanceof JSONObject) return (JSONObject) v;
        if (v instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) v;
            return new JSONObject(m);
        }
        return null;
    }

    public JSONArray optJSONArray(String name) {
        Object v = map.get(name);
        if (v instanceof JSONArray) return (JSONArray) v;
        if (v instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> l = (List<Object>) v;
            return new JSONArray(l);
        }
        return null;
    }

    public String toString(int indentFactor) {
        return Json.write(map);
    }

    @Override
    public String toString() {
        return Json.write(map);
    }
}
