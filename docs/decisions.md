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

**Amended for Phase 1 (advisor-reviewed; reconcile wording corrected after PR #1 QA):**
canvas handlers may make **at most one** gateway call per response under a hard total
deadline (1500ms for idempotent reads, 4000ms for submit; both are total-call deadlines,
not read timeouts), with **no in-handler retries**: a read timeout renders a retry
canvas and the agent decides. Case submission is not idempotent and the Connect API
provides no idempotency: `internalCaseNumber` is a `required: false` query filter on
the list/read operations, and `createCollaborationRequest` documents 200/400/401 only —
no conflict or duplicate rejection — so a second POST carrying the same value simply
creates a second case. Reconciliation through findSubmission(internalCaseNumber) is
therefore **best-effort lookup, not an idempotency guarantee**: a submit timeout parks
the flow UNRESOLVED, the UNRESOLVED -> DRAFT transition on a lookup that found nothing
is the server-side marker that re-arms retry (client-supplied component ids are never
trusted for this), and an empty lookup cannot prove the original request is not still
in flight — that residual duplicate window is accepted and named in the agent-facing
copy. The same taxonomy binds every gateway implementation (enforced by
`TsanetGateway.submit`'s javadoc contract): plain GatewayException means
definitively-rejected-before-create and re-arms editing; every ambiguous outcome
(deadline expiry, reset mid-response, 5xx after send) must surface as
GatewayTimeoutException and park the flow UNRESOLVED. SUBMITTING/UNRESOLVED flows are
exempt from store eviction and TTL expiry — severing one orphans the case — so the
reconcile window is bounded only by process lifetime; in-memory state is a D7 non-goal
until Phase 1-2 persistence. Timeout rate gets a counter the day the SDK gateway lands
(the fake is instant, so there is nothing to measure before then); the counter
validates the 1500ms figure against real BETA partners. Anything heavier than one
bounded call still goes through the event queue. Deadline enforcement is an SDK-gateway
(slice 2) obligation verified by that module's own deadline tests; the shared contract
test covers timeout-reconcile semantics via the injectable fake, not wall-clock
budgets.

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
- Ticket custom attributes are keyed by attribute NAME in `ticket_attributes` (write
  and read); datetime attributes take EPOCH SECONDS (probed live 2026-08-05,
  round-tripped). Attribute identity is therefore name-keyed: a workspace-side rename
  surfaces as the annotation's D3 read-back failing, not as an error on the write.
- The top-level `ticket` key rides EVERY canvas request type from a ticket surface
  (initialize and submit both, per the spike hit log), so the landing request's
  payload always carries the anchor.
- Real Connect process forms use more field types than STRING/SELECT: sdk2's live
  BETA form carries required EMAIL plus PHONE (found by the Phase-1 exit E2E when the
  original supported-set rejected it into the unsupported-type dead end). Text-shaped
  types (STRING/EMAIL/PHONE/INTEGER/URL) render as inputs with the Connect API as the
  format validator, TEXT as a textarea, HIDDEN is submitted from the form template and
  never rendered; genuinely unrenderable types (FILES etc.) keep the loud failure.

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
reference. All three were verified on a live workspace render 2026-08-05/06:
Microsoft | Standard rendered input and textarea fields, Dell | Global Support
rendered all 4 single-selects, and `input_values` was observed on the wire
(`{"q": "microsoft"}` on the search submit). The same check settled the formatting
question: canvas text renders markdown links and bold only, never HTML. Partner
notes arrive as partner-authored HTML and are converted to canvas markdown at the
presentation boundary (#6), with literal and entity-encoded link syntax neutralized
so generated links are the only live link syntax. Known rendering caveat: #7
(single-select option labels truncate at default panel width on option-heavy forms).

## D9. SDK gateway: generated client, per-member pairing, bounded and sanitized

The slice-2 `SdkTsanetGateway` consumes the Connect SDK's **public generated client**
(`com.tsanet.api.generated.*`) rather than its facades, because the facade layer cannot
honor the gateway contract: its DTOs drop `respondBy`/`testCase` (connect-sdk-demo#7),
`createRequest` hardcodes `testSubmission=true` (#6) and cannot set
`submitterContactDetails`, the thin form template drops `adminNote`/
`escalationInstructions`, and nothing exposes the `internalCaseNumber` list filter that
findSubmission stands on. The SDK stays verbatim (D6); both gaps remain upstream.

Mechanics, each pinned by a unit probe:
- **Per-member (session + gateway + ApiClient) pairing** in MemberSessionRegistry
  (SHA-256 credential fingerprint, rebuild-on-change — the demo's EnvironmentService
  pattern). ApiClient is stateful; sharing one across members would bleed credentials.
- **Bearer per request** via interceptor (never per-call client mutation, which races);
  OAuth pre-refreshes on a 30s expiry skew, password mode has no known expiry and
  relies on the one-shot 401 re-auth retry.
- **Deadlines**: every op runs under future.get(1500ms reads / 4000ms submit) on
  virtual threads; expiry throws GatewayTimeoutException and bumps a per-op counter
  (structured log key `tsanet_gateway_timeout`, D2's counter).
- **Sanitization at this boundary**: agent-visible messages carry op + HTTP status at
  most, never URLs or response bodies; SDK validation strings pass through verbatim.
  On submit, ONLY parsed 400/401/403/404/422 map to plain GatewayException
  (definitively rejected before create); 5xx, transport faults, token-less 200s (D3)
  and unmapped exceptions surface as GatewayTimeoutException = ambiguous, parking the
  flow UNRESOLVED per the TsanetGateway.submit contract.
- **findSubmission trusts nothing**: rows must actually match `submitterCaseNumber`
  (an ignored server filter reads as not-found, never a false match), and >1 match —
  the D2 residual window made real — throws rather than silently confirming one case.
- **testSubmission is a config flag, default true**: creating real partner cases is a
  deliberate per-deployment opt-in at pilot time, replacing the facade's hardcode.
- SELECT options parse newline-first with comma fallback — the production ZAF rule.

Live receipt: SdkTsanetGatewayBetaContractTest (env-gated; CI skips it green) passed
5/5 against BETA with the sdk1/sdk2 members on 2026-08-05, 2.8s wall clock for login +
search + form + create + reconcile — comfortably inside the D2 budgets.


## D10. Annotation pipeline: queued writes, a never-evicting link table, honest audit

Landing a submission records a SubmissionLink — the token <-> ticket routing substrate,
indexed by token, internalCaseNumber and conversation id, because Phase-2 inbound
routes from TSANet by token and from Intercom by conversation. Links are precious and
never evicted; the table is in-memory for Phase 1, which makes **persistence a
PRECONDITION for Phase 2 inbound, not a nice-to-have**: a restart loses the table, a
queued annotation then logs `link_missing`, and no pre-restart case can be routed.

Annotation runs on the event drain (the D2 budget forbids Intercom calls inside a
canvas response) through the new handler seam on EventQueue — the same seam Phase-2
inbound processing plugs into. `InboundEvent`'s name now undersells it (it also
carries Source.CONNECTOR work); renaming is deferred deliberately. One drain thread on
purpose: handlers make bounded blocking calls, and at pilot volume webhook events
queueing behind an annotation is acceptable — revisit with Phase 2.

Idempotency and honesty rules: the handler is idempotent by the link's tri-state
annotation status (NONE/DEGRADED/FULL — a boolean would report a degraded write as
done and hide the exact wire-format signal); retries re-offer through a scheduler
(5s/20s backoff, 3 attempts, never sleeping the drain; re-offers bypass webhook-intake
dedupe by construction since dedupe lives in the intake controller); exhaustion and
missing links are audited loudly (`annotation_failed`, `link_missing`) because the
audit line plus the link table ARE the manual re-drive path until the reconciliation
poller exists. Audit lines (`tsanet_audit event=case_submitted|annotation_done|
annotation_degraded|annotation_retry|annotation_failed|link_missing`) carry routing
facts only — never case content.

problem+json (in the SDK gateway, D9's sanitization rule): the Connect API's legacy
default error is a bare 500; the gateway now opts in via Accept and surfaces the RFC
7807 `detail` string into agent-visible submit-rejection messages under mechanical
guards (submit 400/422 only; content type must be application/problem+json; `detail`
must be a JSON string; control chars stripped, angle brackets neutralized, 200-char
cap). The guards are the justification: the email-domain rule rejection is the
canonical case an agent cannot act on from a status code alone.

Two deliberate trade-offs, named: rest mode with a blank admin-id resolves it via
GET /me at wiring time, which makes Intercom reachability a startup dependency
(configure admin-id to remove it); and a note POST that times out client-side after
the server processed it can duplicate the note on retry - bounded by the 3-attempt
cap and pilot-acceptable, revisit with the reconciliation poller. A full event queue
drops the annotation event; both offer sites audit `annotation_enqueue_failed` with
the token so the manual re-drive path holds even then. One more honest gap: HIDDEN
form fields are ASSUMED to be filled server-side from the form template (no live
receipt yet - sdk2's form has none); if the API instead expects the client to echo
template values, submission fails loud and problem+json detail names the field.
