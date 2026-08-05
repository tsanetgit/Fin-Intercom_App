package org.tsanet.connector.tsanet.dto;

import java.util.List;

/**
 * One custom field from a partner's process form. `type` is the Connect API's value
 * (STRING, SELECT, ...); the canvas layer maps supported types to components and fails
 * loudly on anything else rather than silently dropping a required field.
 */
public record FormField(String fieldId, String label, String type, boolean required, String section,
        List<String> options) {

    public boolean supported() {
        return "STRING".equalsIgnoreCase(type) || "SELECT".equalsIgnoreCase(type);
    }
}
