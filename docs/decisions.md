# Design decisions and spike findings

Runtime-verified findings from the 2026-08-04 Phase 0 spike (evidence:
`_TSANet/TSANet_Proposals/fin-spike-evidence-2026-08-04/`; report:
`TSANet_Connect_Fin_Intercom_Spike_Report_DRAFT_v0.1`). Tests encode the behavior;
this file records the why and the measured numbers.

## D1. Signature schemes (three, all keyed on secrets we hold)

| Path | Header | Algorithm | Format | Status |
|---|---|---|---|---|
| `/canvas/*` | `X-Body-Signature` | HMAC-SHA256 (Intercom app client secret) | bare lowercase hex | byte-verified live 2026-08-04 |
| `/webhooks/intercom` | `X-Hub-Signature` | HMAC-SHA1 (same client secret) | `sha1=<hex>` | byte-verified live 2026-08-04 |
| `/webhooks/tsanet` | `x-hub-signature-256` | HMAC-SHA256 (TSANet webhook secret) | `sha256=<hex>` assumed; verifier accepts optional prefix | **confirm byte-exact against the first live TSANet delivery in Phase 2** |

All comparisons are constant-time (`MessageDigest.isEqual`). `/healthz` is the only
unauthenticated route. Missing/empty secrets fail closed (401), never open.

## D2. Canvas latency budget: <2s, structurally enforced

Measured 2026-08-04: an 8s canvas response renders; a 10s response fails. Failure UX is
asymmetric: slow initialize shows an error card with a working "Try again"; slow submit
strands the agent on a spinner with no error. Therefore canvas handlers perform **zero
outbound calls**: verify, read context, enqueue if needed, respond. Anything slower than
that budget goes through the event queue and reaches the panel via a later render.
`TsanetGateway` is deliberately not referenced by any canvas handler in this skeleton.

## D3. Verify effects; never trust a bare 200 (Intercom API)

Observed 2026-08-04: `PUT /tickets/{id}` with an unknown field (`admin_assignee_id`)
returns 200 while silently ignoring the field (the working parameter is `assignee_id`).
Rule for every Intercom mutation the connector will make: read the object back and assert
the change landed. This rule lives here and in `TsanetGateway`'s javadoc so it survives
into Phase 1 client code.

## D4. Webhook intake: fast-ack, dedupe, queue; polling stays the source of truth

Intercom delivery contract is weak (5s timeout, one retry, 429 throttling), so intake
acks immediately and hands off to a queue. Dedupe is by Intercom notification id in a
bounded LRU (default 10,000 entries): replay of an id evicted past the bound would be
re-processed, an accepted risk because downstream handling must be idempotent anyway
(guard-create). TSANet-side payloads are thin (`eventType`, `requestToken`, optional
`noteToken`) with no obvious unique id; TSANet dedupe strategy is a Phase 2 decision and
the reconciliation poller remains the source of truth in both directions.

## D5. Other platform facts the code relies on

- Ticket context detection: canvas payloads include a top-level `ticket` object iff the
  panel is rendered on a ticket; conversation renders omit it. One placement config
  covers both surfaces.
- Tickets require exactly one contact (even Back-office); inbound cases use a synthetic
  partner contact. Tickets are conversation-backed (ticket id == conversation id).
- List-type ticket attributes: `list_items` is a comma-separated string; an array 500s.
  Option labels therefore cannot contain commas.
- `POST /conversations` returns a message id, not the conversation id.

## D6. Module split and the SDK pin

`runtime-core` has no Connect SDK dependency and must never import `com.tsanet.*`.
`connect-gateway` carries `com.tsanet:connect-library:0.0.1-SNAPSHOT` and is included
only under the `sdk` Maven profile, so a fresh clone builds green without the SDK in the
local repository. The SDK stack is consumed verbatim from `tsanetgit/Connect_SDK` branch
`oauth` (commit `0f57fac`) via the connect-sdk-demo modules until that branch merges;
do not fork-fix the library here, raise changes upstream. Building `connect-library`
from source requires `tsanetgit/Connect-API-Code` checked out as a sibling directory on
the `beta` branch (its `develop` branch breaks the current codegen); CI encodes this in
the gated `sdk` job.

## D7. Skeleton non-goals

No Spring Security (the HMAC filter is the whole auth story). No async executors (the
budget is enforced by doing no slow work at all). No persistence (registry and queue are
in-memory until Phase 1-2 give them real backing). No Intercom API client yet (Phase 1,
and it arrives with the D3 rule baked in).
