package org.tsanet.connector.canvas;

import java.util.List;
import java.util.Map;

/**
 * Minimal builders for Canvas Kit response JSON. Kept as maps on purpose: the canvas
 * vocabulary is Intercom's, evolves server-side, and the connector only ever writes it.
 */
public final class CanvasKit {

    private CanvasKit() {
    }

    public static Map<String, Object> canvas(List<Map<String, Object>> components) {
        return Map.of("canvas", Map.of("content", Map.of("components", components)));
    }

    public static Map<String, Object> header(String text) {
        return Map.of("type", "text", "text", text, "style", "header");
    }

    public static Map<String, Object> muted(String text) {
        return Map.of("type", "text", "text", text, "style", "muted");
    }

    public static Map<String, Object> button(String id, String label) {
        return Map.of("type", "button", "id", id, "label", label, "action", Map.of("type", "submit"));
    }
}
