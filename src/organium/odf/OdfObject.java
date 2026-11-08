package organium.odf;

import java.util.Collections;
import java.util.Map;

/** One row of an organ definition table: field name to raw text value. */
public final class OdfObject {
    private final String type;
    private final Map<String, String> fields;

    OdfObject(String type, Map<String, String> fields) {
        this.type = type;
        this.fields = Collections.unmodifiableMap(fields);
    }

    public String type() {
        return type;
    }

    public Map<String, String> fields() {
        return fields;
    }

    /** Raw value, or null when the field is absent or empty. */
    public String get(String field) {
        String v = fields.get(field);
        return v == null || v.isEmpty() ? null : v;
    }

    public String get(String field, String fallback) {
        String v = get(field);
        return v != null ? v : fallback;
    }

    public int getInt(String field, int fallback) {
        String v = get(field);
        if (v == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return (int) Double.parseDouble(v); // some integer fields are written as e.g. "1e+1"
        }
    }

    public double getDouble(String field, double fallback) {
        String v = get(field);
        return v != null ? Double.parseDouble(v) : fallback;
    }

    /** Hauptwerk booleans are written as Y/N. */
    public boolean getBool(String field, boolean fallback) {
        String v = get(field);
        return v != null ? v.equalsIgnoreCase("Y") : fallback;
    }

    @Override
    public String toString() {
        return type + fields;
    }
}
