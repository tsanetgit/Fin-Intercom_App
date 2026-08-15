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

    /** Canvas Kit's select-component bound, identical on dropdown and single-select. */
    public static final int MIN_SELECT_OPTIONS = 2;
    public static final int MAX_SELECT_OPTIONS = 11;

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

    /**
     * Canvas Kit allows a select component a minimum of 2 options and a maximum of 11 —
     * the same bound on both {@code dropdown} and {@code single-select}, checked against
     * the reference for each. The options are the PARTNER's, editable without telling us,
     * and Dell | Global Support's Escalation Type already sits at exactly 11.
     *
     * <p>Detection only, deliberately: what Intercom does when the bound is violated —
     * reject the whole canvas, drop the component, or render it anyway — is not yet
     * observed, and refusing a form Intercom would have rendered fine would break a
     * working partner (#49). The render path logs this and renders on.
     */
    public boolean optionCountOutOfRange() {
        if (!selectShaped()) {
            return false;
        }
        int count = options == null ? 0 : options.size();
        return count < MIN_SELECT_OPTIONS || count > MAX_SELECT_OPTIONS;
    }

    public int optionCount() {
        return options == null ? 0 : options.size();
    }

    public boolean textareaShaped() {
        return "TEXT".equals(normalized());
    }

    private String normalized() {
        return type == null ? "" : type.toUpperCase(Locale.ROOT);
    }
}
