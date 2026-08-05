package org.tsanet.connector.canvas;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal builders for Canvas Kit response JSON. Kept as maps on purpose: the canvas
 * vocabulary is Intercom's, evolves server-side, and the connector only ever writes it.
 * Input/textarea/single-select shapes follow the Canvas Kit reference; their exact
 * accepted attributes get re-verified against a live workspace render (D8).
 */
public final class CanvasKit {

    private CanvasKit() {
    }

    public static Map<String, Object> canvas(List<Map<String, Object>> components) {
        return Map.of("canvas", Map.of("content", Map.of("components", components)));
    }

    /** Canvas whose stored_data round-trips back to us on the next submit (D8: pointer only). */
    public static Map<String, Object> canvas(List<Map<String, Object>> components, Map<String, Object> storedData) {
        return Map.of("canvas", Map.of("content", Map.of("components", components), "stored_data", storedData));
    }

    public static Map<String, Object> header(String text) {
        return Map.of("type", "text", "text", text, "style", "header");
    }

    public static Map<String, Object> muted(String text) {
        return Map.of("type", "text", "text", text, "style", "muted");
    }

    public static Map<String, Object> error(String text) {
        return Map.of("type", "text", "text", text, "style", "error");
    }

    public static Map<String, Object> button(String id, String label) {
        return Map.of("type", "button", "id", id, "label", label, "action", Map.of("type", "submit"));
    }

    public static Map<String, Object> divider() {
        return Map.of("type", "divider");
    }

    public static Map<String, Object> input(String id, String label, String placeholder, String value) {
        Map<String, Object> component = new LinkedHashMap<>();
        component.put("type", "input");
        component.put("id", id);
        component.put("label", label);
        if (placeholder != null) {
            component.put("placeholder", placeholder);
        }
        if (value != null && !value.isBlank()) {
            component.put("value", value);
        }
        return component;
    }

    public static Map<String, Object> textarea(String id, String label, String placeholder, String value) {
        Map<String, Object> component = new LinkedHashMap<>();
        component.put("type", "textarea");
        component.put("id", id);
        component.put("label", label);
        if (placeholder != null) {
            component.put("placeholder", placeholder);
        }
        if (value != null && !value.isBlank()) {
            component.put("value", value);
        }
        return component;
    }

    public static Map<String, Object> singleSelect(String id, String label, List<String> options, String selected) {
        List<Map<String, Object>> optionComponents = new ArrayList<>();
        for (String option : options) {
            optionComponents.add(Map.of("type", "option", "id", option, "text", option));
        }
        Map<String, Object> component = new LinkedHashMap<>();
        component.put("type", "single-select");
        component.put("id", id);
        component.put("label", label);
        component.put("options", optionComponents);
        if (selected != null && !selected.isBlank()) {
            component.put("value", selected);
        }
        return component;
    }
}
