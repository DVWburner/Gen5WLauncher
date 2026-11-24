package org.json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class JSONArray {
    final List<Object> list;

    public JSONArray() {
        this.list = new ArrayList<Object>();
    }

    public JSONArray(String json) {
        this.list = SimpleJsonParser.parseArray(json);
    }

    JSONArray(List<Object> list) {
        this.list = list;
    }

    public int length() {
        return list.size();
    }

    public Object opt(int index) {
        return (index >= 0 && index < list.size()) ? list.get(index) : null;
    }

    public String optString(int index) {
        return optString(index, "");
    }

    public String optString(int index, String fallback) {
        Object v = opt(index);
        return v == null ? fallback : String.valueOf(v);
    }

    public int optInt(int index, int fallback) {
        Object v = opt(index);
        return v instanceof Number ? ((Number) v).intValue() : fallback;
    }

    public double optDouble(int index, double fallback) {
        Object v = opt(index);
        return v instanceof Number ? ((Number) v).doubleValue() : fallback;
    }

    public JSONObject optJSONObject(int index) {
        Object v = opt(index);
        if (v instanceof JSONObject) return (JSONObject) v;
        if (v instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) v;
            return new JSONObject(m);
        }
        return null;
    }
}
