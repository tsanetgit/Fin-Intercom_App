package org.tsanet.connector.tsanet;

/**
 * The seam to TSANet Connect. Implemented in the connect-gateway module on top of
 * com.tsanet:connect-library, which is consumed VERBATIM from tsanetgit/Connect_SDK
 * branch `oauth` (commit 0f57fac) until that branch merges; changes go upstream, never
 * into a local fork (docs/decisions.md D6).
 *
 * Rules that bind every implementation:
 * - NOT called from any canvas handler in Phase 0/1 request paths that render UI: the
 *   canvas budget is <2s and the platform timeout is ~10s (docs/decisions.md D2).
 *   Case work runs behind the event queue.
 * - Verify effects on every mutation (docs/decisions.md D3): the Intercom side of the
 *   runtime must read back what it wrote, and this gateway's implementations must
 *   surface enough state to make TSANet-side writes checkable too (write
 *   testSubmission, read back testCase).
 */
public interface TsanetGateway {

    /** Cheap credential/reachability check for boot and health surfaces. */
    boolean healthy();
}
