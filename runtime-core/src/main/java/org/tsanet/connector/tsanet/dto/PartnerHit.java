package org.tsanet.connector.tsanet.dto;

/** One row from partner search (GET /v1/partners/{term}). departmentId may be null when a company has no departments. */
public record PartnerHit(String label, String companyName, String departmentName, String companyId,
        String departmentId) {

    public String display() {
        return departmentName == null || departmentName.isBlank()
                ? companyName
                : companyName + " | " + departmentName;
    }
}
