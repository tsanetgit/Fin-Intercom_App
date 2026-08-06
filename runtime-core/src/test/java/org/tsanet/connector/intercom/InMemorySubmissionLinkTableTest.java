package org.tsanet.connector.intercom;

/** The in-memory store against the shared link-table contract. */
class InMemorySubmissionLinkTableTest extends SubmissionLinkTableContractTest {

    private final SubmissionLinkTable table = new InMemorySubmissionLinkTable();

    @Override
    protected SubmissionLinkTable table() {
        return table;
    }
}
