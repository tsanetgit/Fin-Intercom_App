package org.tsanet.connector.tsanet.dto;

import java.util.List;

/** A partner's process form (GET /v1/forms/...); documentId is required on submission. */
public record ProcessForm(String documentId, String adminNote, String escalationInstructions,
        List<FormField> fields) {

    public List<FormField> unsupportedFields() {
        return fields.stream().filter(f -> !f.supported()).toList();
    }

    /**
     * Rendered select fields whose option count is outside Canvas Kit's 2..11 bound.
     * Rendered-only: a HIDDEN field never becomes a component, so its option count
     * cannot violate a component bound (#49).
     */
    public List<FormField> optionBoundViolations() {
        return fields.stream().filter(f -> f.rendered() && f.optionCountOutOfRange()).toList();
    }
}
