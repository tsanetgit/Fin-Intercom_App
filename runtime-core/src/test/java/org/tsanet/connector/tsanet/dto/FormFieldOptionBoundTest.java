package org.tsanet.connector.tsanet.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * Canvas Kit bounds a select component at 2..11 options — the same limit on
 * {@code dropdown} and {@code single-select}, checked against the reference for each.
 * The options belong to the PARTNER and can change without notice, so the connector
 * detects the violation rather than assuming it cannot happen (#49).
 *
 * <p>Detection only. These tests deliberately pin that an out-of-range field is still
 * {@code supported()} and still {@code rendered()}: refusing a form Intercom might
 * render fine would break a working partner, and what Intercom actually does past the
 * bound has not been observed yet.
 */
class FormFieldOptionBoundTest {

    private static FormField select(int optionCount) {
        return select("Escalation Type", optionCount);
    }

    private static FormField select(String label, int optionCount) {
        List<String> options = IntStream.range(0, optionCount)
                .mapToObj(i -> "Option " + i)
                .toList();
        return new FormField("89", label, "SELECT", true, "PROBLEM_SECTION", options);
    }

    @Test
    void elevenOptionsIsInRangeBecauseALivePartnerFormShipsEleven() {
        // One partner's live form has a field with exactly 11 options, and it renders. The
        // cap is inclusive, and getting that off by one would flag a live partner form.
        assertFalse(select(11).optionCountOutOfRange());
    }

    @Test
    void twelveOptionsIsOutOfRange() {
        assertTrue(select(12).optionCountOutOfRange(),
                "one option past that partner's current form is all it takes");
    }

    @Test
    void theLowerBoundIsGuardedToo() {
        assertFalse(select(2).optionCountOutOfRange());
        assertTrue(select(1).optionCountOutOfRange(), "Canvas Kit requires a minimum of 2");
        assertTrue(select(0).optionCountOutOfRange());
    }

    @Test
    void aNonSelectFieldIsNeverOutOfRange() {
        // Text-shaped fields legitimately carry no options; only a select renders a
        // component whose option count Intercom bounds.
        FormField text = new FormField("88", "Serial Number", "STRING", true,
                "CONTACT_SECTION", List.of());
        assertFalse(text.optionCountOutOfRange());
        assertEquals(0, text.optionCount());
    }

    @Test
    void anOutOfRangeFieldIsStillSupportedAndStillRendered() {
        // The point of the slice: detect, do not refuse. If this ever flips, the form
        // stops rendering for that partner, which is a decision to take deliberately
        // after the render probe — not to arrive at by editing supported().
        FormField field = select(12);
        assertTrue(field.supported());
        assertTrue(field.rendered());
    }

    @Test
    void aFormReportsOnlyItsOffendingRenderedFields() {
        ProcessForm form = new ProcessForm("1042", null, null, List.of(
                new FormField("88", "Serial Number", "STRING", true, "CONTACT_SECTION", List.of()),
                select("Severity", 3),
                select("Escalation Type", 12),
                select("Product", 1)));

        List<FormField> violations = form.optionBoundViolations();

        assertEquals(List.of("Escalation Type", "Product"),
                violations.stream().map(FormField::label).toList());
        assertTrue(form.unsupportedFields().isEmpty(),
                "an option-bound violation is not an unsupported TYPE — routing it into that"
                        + " check would refuse the whole form");
    }

    @Test
    void aHiddenFieldCannotViolateAComponentBound() {
        // HIDDEN rides the form template server-side and never becomes a component, so
        // its option count cannot violate a bound on components.
        ProcessForm form = new ProcessForm("1042", null, null, List.of(
                new FormField("90", "Routing Code", "HIDDEN", true, "PROBLEM_SECTION",
                        IntStream.range(0, 40).mapToObj(i -> "r" + i).toList())));

        assertTrue(form.optionBoundViolations().isEmpty());
    }
}
