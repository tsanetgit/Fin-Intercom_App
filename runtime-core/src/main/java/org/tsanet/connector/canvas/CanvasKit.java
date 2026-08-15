package org.tsanet.connector.canvas;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal builders for Canvas Kit response JSON. Kept as maps on purpose: the canvas
 * vocabulary is Intercom's, evolves server-side, and the connector only ever writes it.
 * Input/textarea/dropdown shapes follow the Canvas Kit reference; their exact
 * accepted attributes get re-verified against a live workspace render (D8).
 */
public final class CanvasKit {

    private CanvasKit() {
    }

    /**
     * Canvas whose stored_data round-trips back to us on the next submit (D8: pointer
     * only). Deliberately the ONLY canvas builder: a no-stored_data overload sat here
     * unused until QA flagged it as a footgun — anything picking it up would silently
     * drop the flow pointer, the exact echo-forward bug OutboundFlowTest guards.
     */
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

    /**
     * Select-shaped fields render as a dropdown, NOT a single-select. A single-select lays
     * its options out as one horizontal row that neither wraps nor scales to the panel, so
     * past about three options every label clips to a few characters and the agent cannot
     * read the choices before picking one — observed live on Dell | Global Support, whose
     * 11-option Escalation Type rendered as "Compelle...", "Break...", "Comp..." (#7). A
     * dropdown collapses the same options into a menu with no per-option horizontal budget.
     *
     * <p>The option structure and the {@code input_values} contract are identical between
     * the two components, so the chosen option id still comes back under this component's
     * id and no caller of the submitted values changes.
     */
    public static Map<String, Object> dropdown(String id, String label, List<String> options, String selected) {
        List<Map<String, Object>> optionComponents = new ArrayList<>();
        for (String option : options) {
            optionComponents.add(Map.of("type", "option", "id", option, "text", option));
        }
        Map<String, Object> component = new LinkedHashMap<>();
        component.put("type", "dropdown");
        component.put("id", id);
        component.put("label", label);
        component.put("options", optionComponents);
        if (selected != null && !selected.isBlank()) {
            component.put("value", selected);
        }
        return component;
    }
}
