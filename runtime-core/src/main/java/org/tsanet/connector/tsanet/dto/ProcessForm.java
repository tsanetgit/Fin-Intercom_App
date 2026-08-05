package org.tsanet.connector.tsanet.dto;

import java.util.List;

/** A partner's process form (GET /v1/forms/...); documentId is required on submission. */
public record ProcessForm(String documentId, String adminNote, String escalationInstructions,
        List<FormField> fields) {

    public List<FormField> unsupportedFields() {
        return fields.stream().filter(f -> !f.supported()).toList();
    }
}
