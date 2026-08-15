package org.tsanet.connector.tsanet.dto;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One custom field from a partner's process form. `type` is the Connect API's value;
 * the canvas layer maps supported types to components and fails loudly on anything
 * else rather than silently dropping a required field.
 *
 * <p>The supported set is what real BETA forms actually use (found by the Phase-1
 * exit E2E: sdk2's form carries required EMAIL and PHONE fields the original
 * STRING/SELECT set rejected): the text-shaped types render as single-line inputs and
 * the Connect API remains the format validator; TEXT renders as a textarea; SELECT as
 * a dropdown (#7, which a single-select truncated); HIDDEN is supported but never
 * rendered — it rides the re-fetched
 * form template with its server-side value untouched.
 */
public record FormField(String fieldId, String label, String type, boolean required, String section,
        List<String> options) {

    private static final Set<String> TEXT_INPUT_TYPES = Set.of("STRING", "EMAIL", "PHONE", "INTEGER", "URL");

    public boolean supported() {
        return type != null && (TEXT_INPUT_TYPES.contains(normalized())
                || "SELECT".equals(normalized())
                || "TEXT".equals(normalized())
                || "HIDDEN".equals(normalized()));
    }

    /** Hidden fields are submitted from the form template, never shown or typed. */
    public boolean rendered() {
        return supported() && !"HIDDEN".equals(normalized());
    }

    public boolean selectShaped() {
        return "SELECT".equals(normalized());
    }

    public boolean textareaShaped() {
        return "TEXT".equals(normalized());
    }

    private String normalized() {
        return type == null ? "" : type.toUpperCase(Locale.ROOT);
    }
}
