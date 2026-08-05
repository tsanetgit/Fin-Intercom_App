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

All comparisons are constant-time (`MessageDigest.isEqual`), case-insensitive on the
hex. `/healthz` is the only unauthenticated route, and the filter is **default-deny**:
a path matching no scheme is rejected 401 rather than passed through, so encoded or
parameterized path spellings (`/%63anvas/...`, `/canvas;x=y/...`) cannot reach a
controller unsigned, and any future route is signed-or-rejected by default. Matching
runs on a decoded, semicolon-stripped path; bypass probes run against a real container
in CI because MockMvc does not reproduce Tomcat's decode-before-mapping. Missing/empty
secrets fail closed (401), never open.

## D2. Canvas latency budget: <2s, structurally enforced

Measured 2026-08-04: an 8s canvas response renders; a 10s response fails. Failure UX is
asymmetric: slow initialize shows an error card with a working "Try again"; slow submit
strands the agent on a spinner with no error.

**Amended for Phase 1 (advisor-reviewed):** canvas handlers may make **at most one**
gateway call per response under a hard total deadline (1500ms for idempotent reads,
4000ms for submit; both are total-call deadlines, not read timeouts), with **no
in-handler retries**: a read timeout renders a retry canvas and the agent decides.
Case submission is not idempotent, so a submit timeout never blind-retries: the flow
reconciles through findSubmission keyed on internalCaseNumber (the Connect API filters
on it, making it our idempotency key) and only offers retry when the lookup confirms
nothing landed. Timeout rate gets a counter the day the SDK gateway lands (the fake is
instant, so there is nothing to measure before then); the counter validates the 1500ms
figure against real BETA partners. Anything heavier than one bounded call still goes
through the event queue. Deadline enforcement itself is an SDK-gateway (slice 2)
obligation with a contract-test hook.

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

## D8. Canvas flow state: opaque pointer, and gateway-first development

stored_data carries only `{flow_id}`; the real flow state (search hits, selected
partner, form, entered values, submission result) lives server-side in a bounded,
TTL'd store. This sidesteps the canvas payload size limit and the
does-stored_data-transit-the-client question entirely (nothing tamperable rides in it;
ids arriving from it are still treated as untrusted, and the Connect API remains the
final validator). An expired or evicted flow restarts cleanly from the home canvas.
The flow id doubles as the submission's internalCaseNumber, one per flow by
construction.

The canvas flow was built against a failure-injectable FakeTsanetGateway whose
fixtures mirror the Connect API contract, with an abstract gateway contract test that
the SDK-backed gateway must also pass in slice 2; the fake refuses to start under the
prod profile. Component shapes for input/textarea/single-select follow the Canvas Kit
reference and get re-verified on a live workspace render before slice 1 is called
done; `input_values` parsing is likewise asserted live (our spike canvases had no
inputs, so those payload keys are documented-but-not-yet-observed).
