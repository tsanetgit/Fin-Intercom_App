package org.tsanet.connector.tsanet.dto;

/** Outcome of a case submission: the token is the primary key for every later call. */
public record SubmissionResult(String token, String respondBy, boolean testCase) {
}
