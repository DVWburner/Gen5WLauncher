package org.json;

import java.util.List;
import java.util.Map;

public class JSONTokener {
    private final String text;

    public JSONTokener(String text) {
        this.text = text;
    }

    public Object nextValue() {
        Object parsed = SimpleJsonParser.parse(text);
        if (parsed instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) parsed;
            return new JSONObject(m);
        }
        if (parsed instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> l = (List<Object>) parsed;
            return new JSONArray(l);
        }
        return parsed;
    }
}
