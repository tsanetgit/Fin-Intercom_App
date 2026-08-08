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
from source needs the Connect API spec from `tsanetgit/Connect-API-Code` on the `beta`
branch (its `develop` branch breaks the current codegen). **SUPERSEDED IN PART BY D11:**
that spec is now vendored in-repo and passed via `-Dconnect.openapi.spec`, so no sibling
checkout and no credential are required, and the `sdk` job is no longer gated — it runs
on every PR. The verbatim-SDK rule above is unaffected.

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

## D11. The API spec is vendored, so CI needs no cross-org credential

`connect-library`'s codegen reads the Connect transport contract through the Maven
property `connect.openapi.spec`, defined in the demo repo's parent pom as a sibling
path into the PRIVATE `tsanetgit/Connect-API-Code` and used exactly once, as
`inputSpec`. Satisfying that sibling in CI meant a cross-org token, and the sdk job
gated every real step on `CONNECT_API_CODE_TOKEN` being present. It never was: the
job skipped everything and **reported success**, so the connect-gateway tests -
including the sanitizer parity guard hardened across three review rounds - had never
executed in CI. A green sdk check meant nothing.

The credential was not obtainable by us either. A fine-grained PAT for an org-owned
repo needs a `tsanetgit` owner's approval (we are a member), and a deploy key needs
admin on `Connect-API-Code` (we have pull). Rather than provision a broad classic PAT
- read AND write across every org repo reachable, in a CI secret, while credential
rotation is still open - the dependency is removed: the spec is one 80KB file, so it
lives at `third_party/connect-api/openapi.yaml`, byte-identical to upstream, and the
build overrides the property to point at it. The sdk job now runs unconditionally on
every PR with no secret at all.

Consequences accepted, each with its control:
- **Drift.** A vendored copy can fall behind `beta`. Connector CI structurally cannot
  detect this (no access, by design), so the check belongs in tsanet-ops, and the
  substrate is already there: `scripts/mirror-github.py` snapshots exactly this repo
  and path on branches including `beta` into `tsanet_ops.api_spec_snapshots`, whose
  `content_hash` is `hashlib.sha256(raw).hexdigest()` - the same algorithm over the
  same bytes as the `sha256` recorded in the sidecar README, so the check is a direct
  equality test, not a diff. Drift is advisory: pinning is what makes codegen
  deterministic, and a mismatch means refresh deliberately.
- **Provenance must not rot.** Source repo, ref, commit, date, size and sha256 live in
  a sidecar README rather than a header inside the YAML, because a comment in the file
  would break the byte-comparison the drift check depends on.
- **Supply chain.** With the gate gone, an unpinned demo checkout would let a push to
  another repo change what our CI compiles, so `connect-sdk-demo` is pinned to a commit
  and bumps are reviewed changes. The pinned demo ref and the vendored spec commit are
  a MATCHED PAIR, verified together by the sdk job: a bump moves both, or says why the
  other did not need to move.
- **Visibility.** The spec was gated behind Connect-API-Code's ACL and is now gated
  behind this repo's, so this repo must not be made public without upstream sign-off,
  and the file persists in git history even if later removed. The widening is modest -
  the public demo repo already ships the SDK that consumes this API - but it is a
  widening, and it is deliberate. Contents were scanned before vendoring: the only
  URLs are the three documented environment hosts plus Entra's login endpoint, and
  every credential-shaped name is a schema property declaration, not a value.

This does not fork the SDK (D6 stands): the spec is a build INPUT, pinned for
reproducibility, and the SDK modules are still consumed verbatim from upstream.

## D12. The link table is durable, on SQLite, and the volume is part of the design

D10 named link-table persistence as a Phase-2 precondition rather than a nice-to-have:
without it a restart loses every token -> ticket route, and no pre-restart case can be
answered. `SqliteSubmissionLinkTable` is that store, behind the unchanged
`SubmissionLinkTable` interface. SQLite because the pilot is one container and this
adds no infrastructure; Postgres at GA is then a DataSource swap rather than a rewrite,
which is why the DAO uses raw JDBC and short-lived connections instead of a shared
`Connection` (also: sqlite-jdbc connections are not safely shared, and the event drain
and canvas threads both write - hence `busy_timeout=5000`).

Three choices carry the durability claim, and each has a cheaper-looking alternative
that quietly breaks it:

- **`synchronous=FULL`**, not NORMAL. Under WAL, NORMAL survives an application crash
  but can lose the last commits to power loss - a `record()` that returned while the
  link vanished, which is precisely the outcome D10 forbids. Write volume is one row
  per case submission, so the fsync is free in practice.
- **`ON CONFLICT(token) DO NOTHING`**, never `INSERT OR IGNORE`. OR IGNORE suppresses
  *every* constraint failure, so a malformed link (null token, null internal case
  number) disappears with no error and the case has no route home. Pinned by
  `aMalformedLinkIsRejectedWithNothingRecorded`, which fails when the statement is
  swapped back (verified by mutation, not by reading).
- **Every `SQLException` surfaces unchecked.** A logged-and-swallowed `record()` failure
  means the partner case exists and nothing points at it; the loud failure at submit
  time is the honest one. Same reasoning for `annotation_state`: it is parsed with
  `valueOf`, so an unreadable value throws rather than reading as NONE and re-running a
  completed annotation.

D3 ("verify effects, never trust a bare 200") does not extend to this store as a
read-back: it exists because a remote mutation can succeed without saying so, and a local
ACID `UPDATE` already answers — the rowcount is the receipt, so a state write that matched
no link warns rather than passing silently. `annotation_state` assumes a single writer
(the drain), which holds because the drain only writes state for a token it has already
found here (`AnnotationHandler` returns `link_missing` otherwise); a multi-threaded drain
would need a compare-and-set instead.

The routing semantics are now pinned by `SubmissionLinkTableContractTest`, which both
stores run: first-record-wins on replay (including annotation progress), newest-first
ordering for the several cases one conversation can anchor, and the FIRST recorded token
owning a duplicated `internalCaseNumber` - two links can share one, that is D2's residual
duplicate window, and reconcile must keep answering with the same case it answered with
before. A replay drops the whole row, so its *new* secondary keys register nowhere; that
is asserted separately from the identical-keys replay, which cannot tell an index that was
skipped from one rewritten to the same value.

A null key is answered rather than thrown on, everywhere a key is taken, because null keys
arrive for real (a webhook payload without its token, a ticketless link with no
conversation) and an NPE there kills the event drain. That rule is normative on
`SubmissionLinkTable` itself rather than restated per store, and the contract pins it
across the whole keyed surface at once. **The reason it is stated that way is that the
narrower version failed twice.** The contract's first run caught `byConversationId(null)`
answering empty in SQL and NPE-ing in the map store; the guard was then applied to the
three lookups, which left both `annotationState` arities NPE-ing on the same input, and QA
measured that in round 2. Guarding a defect class at the sites you happen to be looking at
is how it survives review, so the contract now asks about the surface rather than about a
list of methods.

Two asymmetries worth keeping straight, since "nulls are benign here" is the wrong summary:

- **`record` refuses instead of answering.** A link missing its token or its internal case
  number is rejected with `IllegalStateException` — atomically, nothing left on any index —
  because a link that cannot be found later is a partner case with no route home. The type
  is load-bearing, not decorative: an unguarded map store raises `NullPointerException`
  from its own first `put`, so a test written as `assertThrows(RuntimeException.class)`
  passes on the broken store while measuring nothing. Pinning `IllegalStateException` is
  what distinguishes a deliberate rejection from an accidental one.
- **A null *state* is refused, not stored.** It is not a missing key, it is a value outside
  the enum: the map store's `AtomicReference.set(null)` would answer `null` forever from a
  method contracted to answer `NONE` when unset, where the SQL store throws at
  `state.name()`. Silent corruption on the read the drain uses to decide whether an
  annotation has already run, so both stores now throw.

One property is asserted but not falsifiable against either current store, and is kept
deliberately: that a rejected `record` leaves nothing in the *secondary* indexes. Neither
store can fail it today — the SQL store keeps one row, and every secondary lookup in the
map store resolves through `byToken` (`byInternalCaseNumber` does `flatMap(this::byToken)`,
`byConversationId` maps and drops empties), so a dangling secondary entry is masked by
construction. A mutant that writes the conversation index and then rejects survives the
suite. `byToken` and `size()` are what actually witness a partial record; the secondary
assertions are there for a store that maintains its indexes independently, which is exactly
what the Postgres implementation at GA would be.

Selection is `connector.links.store` (`memory` default, `sqlite` durable), and every way
of reaching production without a durable store is refused at startup rather than
discovered after a restart — **under the prod profile, which the deployment is
responsible for setting.** That scoping is the honest statement: the memory-store refusal
and the absolute-path check are both conditioned on `prod` being active, so a deployment
that forgets `SPRING_PROFILES_ACTIVE=prod` gets neither, and no signal here can detect
that. It is the established convention (the fake gateways gate identically) rather than
anything specific to this store, and the profile itself is the residual assumption the
whole guard rests on. The one check that is *not* profile-gated is the store-name
rejection, since `LinkProperties`' compact constructor runs at binding under every
profile — `aStoreValueThatSelectsNoBeanIsRejectedByName` exercises it without prod.

The memory store refuses the prod profile the way the fake
gateways do. A store value that selects neither bean is rejected by name, because the
alternative is a `NoSuchBeanDefinitionException` that names no cause; the validation
mirrors `@ConditionalOnProperty` exactly - case-insensitive, no trimming, both measured
rather than assumed (`LinkTableConfigurationTest`), so `SQLITE` wires the durable store
while `sqlite ` from an env var is refused. Note for anyone testing this: Spring's
`withPropertyValues` trims its `k=v` strings, so a whitespace case written that way passes
while measuring nothing - inject a raw `MapPropertySource`.

**Deployment precondition, and the slice is theater without it: the SQLite file must
live on storage that outlives the container.** On a container overlay filesystem this
class is exactly as volatile as the maps it replaces, and no code here can detect the
difference - so the absolute path is logged at boot, `store=sqlite` with a blank path
refuses to start, and the Azure Container Apps volume mount is a deployment requirement
rather than a deployment detail. The shipped default `data/links.db` is **dev-only** for
exactly this reason: under the prod profile a relative path is refused, since it resolves
against the container working directory and would otherwise be the one remaining way to
look durable and not be. Named non-goal: this store is single-node. The day the connector
runs more than one replica, SQLite is wrong and the interface exists so that day costs a
new implementation and nothing else.

**Durable storage and WAL-compatible storage are not the same requirement, and on ACA the
only option that satisfies the first may not satisfy the second.** The paragraph above
asks for storage that outlives the container and says nothing about its filesystem
semantics — the same shape of gap it exists to close, raised by QA on #13. Measured
against both vendors' docs rather than recalled:

- Azure Container Apps: container-scoped and replica-scoped (`EmptyDir`) storage are both
  ephemeral; **Azure Files is the only persistent option**, mounted over SMB or NFS
  (Microsoft, *Use storage mounts in Azure Container Apps*).
- SQLite: *"All processes using a database must be on the same host computer; WAL does not
  work over a network filesystem. This is because WAL requires all processes to share a
  small amount of memory"* (sqlite.org/wal.html).

So the one mount that meets the durability precondition is the filesystem class WAL is
documented not to work on, and `SqliteSubmissionLinkTable` sets `journal_mode=WAL`
explicitly.

What the sources do **not** settle is which way an Azure Files mount fails. SQLite says
*"If the VFS does not support shared-memory methods, then the attempt to open a database
that is already in WAL mode, or the attempt to convert a database into WAL mode, will
fail"* — but that condition is on the **VFS**, not on the filesystem being networked, and
the standard Linux unix VFS supplies shared memory by mmap-ing a `-shm` file, which can
succeed on an SMB or NFS mount. So the mount may refuse to boot (loud, the same class of
answer as the blank-path and relative-path checks), or it may open quietly on a filesystem
whose locking is unreliable. The two quotes above are verified; **which branch Azure Files
takes is not, and cannot be established from documentation.** That is why the mount check
below is mandatory rather than advisory: there is no guarantee that startup catches this.

The documented remedy is *"WAL databases can be created, read, and written even if shared
memory is unavailable as long as the locking_mode is set to EXCLUSIVE before the first
attempted access."* It is **not** a one-line change here, and that is why it is not in
this PR: exclusive locking holds the database lock for a connection's lifetime, and this
DAO deliberately uses a short-lived connection per operation with the drain and canvas
threads both writing — so adopting it means re-deciding the connection model (a single
long-lived connection, or serialized access), not setting a pragma. Neither variant can be
validated without a real Azure Files mount to test against.

**Therefore: confirm the actual mount before the first prod deploy**, and if it is Azure
Files over SMB or NFS, decide between `locking_mode=EXCLUSIVE` with a reworked connection
model and a rollback journal (which costs the `synchronous=FULL` analysis above a fresh
look) before that deploy, not after.


## D13. Inbound intake speaks v2 CloudEvents, and refuses what it cannot route

The intake read v1 field names (`payload.eventType`, `payload.requestToken`), so a v2
delivery produced a null type and a null token and was queued anyway: an event no
handler could claim, reporting nothing. The connector would have looked healthy while
dropping every push.

v2 only, deliberately. BETA and PROD both accept v1 and v2 registrations and **v1 is
removed in the first part of 2027** — inside this connector's first year — so v1
parsing would be dead code on arrival. v2 also carries the CloudEvent `id`, the only
event-level idempotency key that exists; v1 has none. Existing members migrate
separately (`tsanetgit/Zendesk_App#101`); that is not a reason for new work to speak
both dialects.

A body that is not a routable v2 event is refused at the door and logged at error with
the keys it did carry, so a wrong-version registration surfaces on the first delivery
instead of as silence. Still answered 200: redelivery cannot fix a shape. Types are
the five in `webhook-asyncapi.yaml` on `beta`, read rather than assumed.

## D14. One inbound case, one ticket: claim before create

Checking the link table and then creating is a TOCTOU race — two deliveries of the same
event both find no link and both create a ticket. That is not hypothetical: it is the
duplicate-ticket defect already seen on the Zendesk connector, where `note.created`
overtook `collaboration-request.created`.

So the token's UNIQUE constraint arbitrates. `record()` reports whether THIS call
inserted; exactly one caller wins and only the winner creates. Pinned by a concurrency
probe, and verified by mutation: reverting to check-then-create fails that probe on
every run and no other test in the class.

Consequences, each deliberate:

- **A failed create releases the claim**, which restores retryability — and as of D16
  that retry actually happens. When this entry was first written it did not: the intake
  answers 200 on every branch so TSANet does not redeliver, the handler catches
  internally so the drain never re-offers, and there was no reconciliation poller, so
  the guarantee was **recoverable, not recovered** and recovery was manual from the
  error-level `inbound_ticket_create_failed` line. `ReconciliationPoller` now re-lists
  the case and drives it back through this path, which is what makes releasing the claim
  load-bearing rather than merely tidy. The audit line remains, because a create that
  keeps failing still needs a human. An earlier draft of this entry argued the trade-off
  "given the queue redelivers", which was false; the QA round on the slice caught it.
- **`releaseClaim` cannot delete an anchored link**, enforced in the SQL `WHERE` clause
  rather than by caller discipline, so misuse degrades to a no-op instead of orphaning
  a live case. The two stores express that guard differently (`ticketAnchored()` versus
  `ticket_id IS NULL`), which made them disagree about a link anchored with a BLANK id:
  the map store released it, the SQL store refused. In prod that means the token stays
  claimed forever and no ticket is ever created — the silent loss the release exists to
  prevent, in the store that actually runs. Fixed at the choke point instead of by
  reconciling two predicates: `anchor()` refuses a blank id (and is write-once) in both
  stores, so the guards agree by construction. Pinned by a contract case.
- **Direction is checked before anything is claimed.** Push fires for cases in both
  directions; a case we submitted already has its anchor, and unrecognised direction
  values map to UNKNOWN rather than a guess, since a wrong guess creates a wrong ticket.
- **Inbound links carry `inbound:<token>` as their internalCaseNumber.** A
  partner-created case has none of ours, and both stores reject a null one for a good
  reason (a link with no routing key has no route home). This is a **local, provisional
  convention**, not estate policy.
- **An unset ticket type refuses to create.** The type carries the attributes every
  later slice reads; a ticket of the wrong type is worse than no ticket.

Only `collaboration-request.created` is claimed. The other four push types belong to
later slices and are deliberately left unclaimed, which the drain logs.

## D15. Answering a partner: state first, then the API, and ambiguity means ambiguity

The three response actions (Accept / Decline / Request information) are agent-driven
writes the PARTNER sees, so they inherit every rule submit has.

**The panel renders from recorded state, not from what was clicked.** The link carries
a `CaseState`, and a response moves it by compare-and-set BEFORE calling the API. Two
panels open on one ticket cannot both answer: the loser sees the guard fail and
re-renders what actually holds. Answered cases offer no buttons.

**Failure handling follows the state, not the other way round.** A definitive
rejection gives the state back (the agent fixes and retries); an ambiguous outcome
keeps the state moved and says the result is unknown, because re-offering the action
is precisely what would answer a partner twice.

**A 5xx on a response action is ambiguous — and was not.** The gateway's ambiguity
rule was gated on `op.equals("submit")`, so a 500 on approve surfaced as a plain
`GatewayException`, which the panel reads as definitively rejected: state given back,
agent invited to answer a partner who may already have been answered. Caught by the
response-action probe, not by review. The gate is now a `MUTATING_OPS` set covering
every operation the partner can see. **Any future partner-visible write must be added
to that set**, and the reason it is a set rather than a boolean on the call site is so
that omission is visible in one place.

**The engineer identity is prefilled and editable.** The API rejects an address off
the member's registered domain, no status code conveys that, and the connector cannot
know the registered domains — the validated problem+json `detail` is the only source.
So the acting teammate is a default, not a lock, and the rejection renders beside the
email field with the typed values preserved: one correction and a resubmit.

**`caseNumber` is the ticket number, not the ticket id.** Probed live: an Intercom
ticket carries `id` (`215475361003303`, the API handle) and `ticket_id`
(`121556608`, the `#`-number an agent sees and can search). The partner quotes our
case number back, so it must be the searchable one.

The number is captured at ticket-create time, from the create response, and recorded
on the link — it is not re-derived later and never defaults to the anchor. **The first
version of this shipped the decision in prose and the defect in code**: the panel fell
back to `link.ticketId()`, which holds the handle, and the test asserted that value, so
the suite was green on exactly the property this entry exists to protect (#17 QA
round). Accept now REFUSES when no searchable number was captured, because quoting the
handle fails silently — on a phone call, days later — while refusing is recoverable.

That refusal has one permanent case. A row created before `case_number` existed is
migrated with it NULL, the number is only ever captured at ticket-create time, and
nothing backfills it — so accept is refused on that row forever, with no recovery short
of manual intervention. It is fail-safe and the message says so, and the real population
is approximately zero because inbound create itself only landed one slice earlier.
Recorded rather than fixed: a backfill needs a ticket read per orphaned row, which
belongs with the reconciliation poller if it is ever worth doing (#17 QA round 2).

**Ambiguity is durable for outbound and transient here, and that asymmetry is a known
gap.** An ambiguous response tells the agent the outcome is unknown, but the recorded
state has already moved, so a later render (second agent, second tab, after a restart)
shows a cleanly answered case. Keeping the state moved is right — re-offering is what
answers twice — but recording it as clean is not. The outbound flow has an UNRESOLVED
status for exactly this; inbound has no equivalent yet. Named rather than papered over;
it lands with the reconciliation poller, which is what can actually resolve it.

**Still open after D16.** The poller now exists, and it does NOT close this: it recovers
missing TICKETS, not missing state. A case whose response outcome was ambiguous already
has a link, so the poller's prefilter skips it by design. Resolving the ambiguity needs
reading the case's status back and reconciling it against `CaseState`, which is the
status-sync work still blocked on the estate decisions. Named here so this entry is not
read as closed by the poller's arrival.

**A ticket with several cases currently shows no panel at all.** `byTicketId` refuses a
multiple match (correctly), `render()` returns empty, and the canvas falls through to
the outbound partner search — so an agent sees no indication that two partner cases are
attached and cannot answer either. Refusing to pick is right; rendering nothing is the
gap. A "this ticket has N collaboration cases" panel fits the existing shape and is the
follow-up.

**One ticket can anchor several cases**, so `byTicketId` refuses a multiple match
instead of picking. An agent may escalate one customer problem to two partners; a
unique index on `ticket_id` looked correct and would have made that impossible — the
existing contract tests caught it.

Out of scope and deliberately unclaimed: `information-response` (the reply leg, lands
with notes), notes both directions, closure, and lifecycle sync.

## D16. Reconciliation: re-drive what push dropped, and never advance past a failure

Push is a single attempt from our side. The intake answers 200 on every branch — it has
to, because TSANet treats `4xx` as permanent and never retries it, so answering 4xx on
anything transient would guarantee the loss rather than risk it — and the event drain
catches internally so it never re-offers. Everything between those two was permanently
lost. `ReconciliationPoller` lists INBOUND cases on an `updatedAt` cursor and drives
anything with no link back through the same guard-create path.

**Cursoring on `updatedAt` was verified, not assumed.** All 23 cases on BETA have
`updatedAt` strictly later than `createdAt` — most by exactly 24h, stamped ~10s after
`respondBy` elapses by a server-side SLA sweep. Read naively that says `updatedAt` only
moves on modification, which would mean this cursor misses precisely the newly-created
cases the poller exists to catch, for 24h. So a case was created live: `updatedAt` came
back EQUAL to `createdAt` to the nanosecond, and a filter set 60s earlier returned it.
If that ever changes, this decision is what breaks.

**It calls the handler directly and does not use the EventQueue.** The queue is bounded
and `offer()` drops on full, so a recovery batch could evict live webhook events — the
poller would cause the loss it exists to repair. The drain is also a single thread shared
with live intake. Calling `InboundRequestHandler.handle` directly is safe by
construction: `record()` arbitrates between the poller thread and the drain exactly as it
arbitrates between two deliveries.

**The cursor advances only after a fully clean cycle**, and only to an `updatedAt` that
was actually observed. Advancing past a case that failed to become a ticket loses it
permanently, which is the worst outcome available here. There is no partial advance to
"newest handled" — that is only sound if rows are ordered by `updatedAt`, which is
unspecified. An empty window advances nothing: moving to `now` would step over any case
whose `updatedAt` lands between the query and the write. A wedged cursor degrades safely
— new cases still arrive each cycle because the window extends to now — so the cost is a
widening query and loud logs, never lost work.

**Success is judged by an ANCHORED link, not by a row existing.** `byToken()` is true for
a bare claim as well as a finished ticket, and the handler returns normally when it LOSES
the `record()` race to the live drain. Counting that as success let the cursor advance
past a case whose create then failed and released the claim — leaving it with no link,
outside the window, unrecoverable: the exact loss this entry forbids, produced by the
rule meant to prevent it (#22 QA). An unanchored row is now read as IN FLIGHT: not a
failure, so it does not touch the failure counter and does not use the dirty-cycle line —
it emits its own `reconcile_cycle_held` at warn — but the cursor holds, because an unknown
outcome is not one to advance past.

**An orphaned claim therefore wedges the cursor until someone intervenes.** A crash
between `record()` and `anchor()` leaves an unanchored row that no longer has a thread
behind it, and every subsequent cycle reads it as in-flight and holds. That is
deliberately preferred to the alternative — advancing past it is the silent permanent loss
this entry exists to prevent — and it is loud rather than silent: a `reconcile_cycle_held`
line every cycle, naming the token. But it does NOT self-heal, and `consecutiveFailures`
stays at zero because nothing failed, so an alert keyed on that counter will not fire.
Claims have no expiry today; an age-based reclaim is the follow-up
(`tsanetgit/Fin-Intercom_App#26`).

**The cursor is persisted BEFORE the cycle is declared healthy.** The store throws on a
SQL failure by design, refusing to hide a broken volume (D12). The first version reset
the failure counter and then persisted outside any try, so on a broken volume every cycle
reported a healthy counter, never reached the clean audit line, and let the exception
escape into the scheduler's handler in non-audit format — the store promised loudness and
the caller undid it, in exactly the D12 failure mode this was supposed to cover (#22 QA).

**Success is judged by outcome, not by absence of an exception.** `InboundRequestHandler`
catches its own create failures, because the drain shares it and an escaping exception
would kill that thread — so a failed create returns NORMALLY. The first version of the
cycle counted that as success and advanced the cursor over a case that never became a
ticket; the test caught it. The poller now asks the link table whether a link exists,
which is the only honest witness.

**The cursor is durable but the link table's durability matters more.** A lost cursor
costs one wider re-scan, absorbed by the prefilter; lost links orphan every open case.
That asymmetry is why `PollerStateStore` is allowed an in-memory implementation under
prod while the link table is not. It lives in its own table on the same SQLite file: one
volume, and a cursor that cannot outlive the links it is a cursor over.

**Bounded windows, deliberately.** The list endpoint has NO pagination, so window size is
the only load control: a first run asks for `now - 24h`, never epoch. A 120s overlap is
subtracted from the stored cursor on every query, absorbing the mixed granularity of
`updatedAt` (creation sub-second, SLA sweep whole-second) and the unspecified
inclusive/exclusive boundary. Re-listing is free; missing one is not.

**Single instance only.** Two containers would race the cursor. They could not create
duplicate tickets — the token's unique constraint still arbitrates — but they would
duplicate the work and fight over the mark.

Out of scope and deliberately unclaimed: status sync, notes, and closure. This slice
recovers missing tickets and nothing else.

**Orphaned claims: surfacing, not reclaim (ruled 2026-08-08, closes the question
`tsanetgit/Fin-Intercom_App#26` left open).** A crash between `record()` and `anchor()`
leaves an unanchored claim nothing will ever anchor; every later cycle reads it as
in-flight and holds the cursor, while `consecutiveFailures` stays 0 — so an alert keyed
on the failure counter never fires. The considered fix was age-based reclaim, and it was
rejected: a threshold short enough to recover quickly can pull the claim out from under
a live-but-slow create, and two creators with the loser already released is exactly the
duplicate-ticket class D14 exists to prevent — machinery with its own failure modes, to
cover a crash inside a millisecond window that is already loud in every cycle's logs.
Instead the condition is made alertable: `consecutiveHeld` counts consecutive completed
listings that observed an unanchored claim (climbing while `consecutiveFailures` sits at
0 is the orphan signature), and the held cycle line carries the oldest claim's age — a
live create resolves within its gateway deadline, so an age far past it means orphan.
The one subclass the counter structurally cannot see — an orphaned claim on a case that
then went TERMINAL, which the #28 prefilter advances past rather than holding on — is
named by `reconcile_orphaned_claim_terminal` at the moment the prefilter passes it,
because after that the case leaves the window and the stale row goes invisible.
The counter measures claim staleness, not cycle health: a dirty cycle or a
cursor-persist-failure cycle that observed no claims still resets it, on purpose — do
not "fix" that. Releasing an orphan stays a human action, taken by someone who can see
whether the ticket exists.

## D17. Case status to ticket state: the mapping, and the two statuses we refuse to map

Promoted from the Phase 0 spike, where it lived only as a note. Nothing in the code
mapped a Connect status onto an Intercom ticket state — `CaseState` is deliberately the
connector's own view of *our* response, not a mirror of the lifecycle — so closure and
status sync had no agreed target to write to.

### The mapping

| Connect `status` | Intercom ticket state | Why |
|---|---|---|
| `OPEN` | **Submitted** | The partner asked; nobody here has answered yet. |
| `ACCEPTED` | **In progress** | We took it. |
| `INFORMATION` | **Waiting on customer** | We asked the partner for more and are blocked on them. |
| `REJECTED` | **Resolved** | Terminal for us. |
| `CLOSED` | **Resolved** | Terminal. |

**"Waiting on customer" is Intercom's vocabulary, not ours, and its external label reads
"Waiting on you".** On a Back-office ticket that text is not partner-visible, so the
mismatch is cosmetic today — but it stops being cosmetic if these tickets ever gain a
customer-facing surface. Renaming the state in the workspace is the fix, not remapping.

**Two terminal statuses collapse onto one state.** `REJECTED` and `CLOSED` are both
Resolved, so the ticket state alone cannot distinguish "we declined" from "it finished".
The distinction has to live on a ticket attribute; anything reading only the state will
conflate them.

### Deliberately unmapped

- **`PENDINGACTION`** — undocumented in BOTH contracts, verified rather than assumed: it
  is a bare enum member in `third_party/connect-api/openapi.yaml` with no description
  (the transport contract), and a GitBook RAG search returns zero results (the business
  contract). We cannot map a status nobody has defined. **Open question for TSANet.**
- **`UNKNOWN`** — an unrecognised wire value by construction, so mapping it would be
  guessing at exactly the moment the wire has drifted.

For both: **the connector must not move the ticket state.** Leaving the state stale is
visibly wrong and recoverable; moving it to a guessed value is invisibly wrong. Same
discipline as `direction()` returning UNKNOWN rather than picking.

### Mechanism, verified live 2026-08-07 against the dev workspace

**Read and write are asymmetric, like `testSubmission`/`testCase` before them.**

- **Read**: a ticket carries `ticket_state` as a plain STRING (`"submitted"`), plus
  `ticket_state_internal_label`, `ticket_state_external_label`, and an `open` boolean.
- **Write**: `PUT /tickets/{id}` requires `ticket_state_id` — a numeric id, not the
  string it reads back. Nothing lets you set the state by name.

**The ids are per-workspace, and enumerating them is a trap.** For this dev workspace's
"TSANet Collaboration" type (4561712):

```
Submitted            9619796
In progress          9619797
Waiting on customer  9619798
Resolved             9619799
```

Those came back **only under `Intercom-Version: Unstable`**. Versions 2.8, 2.9, 2.10 and
2.11 all omit `ticket_states` from the ticket-type object, and `GET /ticket_states`
returns zero rows on every version tried. So the sole route found to the ids an
implementation needs is an API version Intercom explicitly labels unstable.

Consequences for whoever implements status sync:

1. **Resolve the ids at configuration time, not per request.** They are per-workspace, so
   the pilot's ids are not these, and a pinned constant would silently write the wrong
   state in another workspace — the same per-workspace trap as
   `collaboration-ticket-type-id`.
2. **Do not put `Intercom-Version: Unstable` in the runtime.** Obtain the ids out of band
   (this call, or the admin UI) and configure them. An unstable version in a live request
   path is a dependency on something with no compatibility promise.
3. **Verify the effect (D3).** These writes are exactly where a silent-200 would hide.

### Scope

One direction only: Connect status to ticket state. The reverse — an agent resolving the
ticket closing the partner's case — is the outbound half of closure and needs its own
decision about which Intercom state transitions are meant to propagate.

## D18. Inbound closure: mirror the partner's close, and do not pretend to recover a missed one

The inbound half of "closure from either side", built on D17's mapping. A
`collaboration-request.closed` CloudEvent resolves the ticket that mirrors the case.

**The harm it removes is specific:** before this, a partner closing a case left the
Intercom ticket open forever with the panel still offering Accept and Decline, so an
agent could answer a partner who had gone.

**Platform first, then the record — the opposite order to D15**, and the difference is
the point. A response flow moves the recorded state BEFORE calling the partner, because
that state is the guard against answering twice. Closure sends nothing to anyone; it
mirrors a fact. So the risk inverts: recording CLOSED before the ticket resolves would
tell an agent the case is closed while the ticket sits open in their queue. A failed
resolve therefore records nothing, which is what leaves a redelivery able to fix it.

**Compare-and-set from whatever state holds, not from PENDING.** A partner can close a
case we already accepted, so the panel's `PENDING -> X` shape does not apply. Closure
reads the current state and moves from it, retrying once if an agent won the race
in between. If it keeps losing, the ticket is still resolved — which is what the agent
sees — and only our record of why is stale, so it logs rather than looping.

**`CaseState.CLOSED` is the first state no agent action can produce.** The panel renders
it as "The partner closed this case", distinct from "answered", because an agent who did
not do this would otherwise wonder why their options vanished.

**The resolved-state id is configuration with no default, for the reason D17 gives:**
state ids are per-workspace and only enumerable under `Intercom-Version: Unstable`.
Unset REFUSES. A guessed id may not 404 — it may name a different state that exists in
that workspace — so the failure would be a ticket moved somewhere wrong rather than an
error.

**What this does NOT do, stated because the temptation is to imply otherwise.** Nothing
re-drives a MISSED closure. The reconciliation poller (D16) claims
`collaboration-request.created` only, so a closure event lost in transit leaves the
ticket open with no second chance — exactly the gap inbound create had before the poller
existed. Extending the poller to reconcile STATUS is the status-sync box on
`tsanetgit/Member-Integrations#51`, and it is blocked on
`tsanetgit/Fin-Intercom_App#24`. This slice recovers nothing; it only handles what
arrives.

**The inverse interaction is guarded (#28): a missed CREATE plus a partner close must
not manufacture a dead ticket.** The closure handler declines to create for an unlinked
case (`closure_no_link`) — but the poller's listing is status-blind, so it would later
re-drive that same case through the create path and produce the exact open PENDING
ticket the closure handler refused: response actions offered on a partner who has gone,
permanently, since the closure event is consumed. So the create path now refuses
terminal cases in BOTH entry points — `InboundCase.terminal()` (CLOSED and REJECTED,
which D17 already maps to Resolved; PENDINGACTION deliberately excluded) is checked by
the handler before claiming, and by the poller as a prefilter that still advances the
cursor. The prefilter runs before the claim lookup on purpose: an orphaned claim on a
terminal case would otherwise read as in-flight and hold the cursor forever. Skip was
ruled over create-resolved (2026-08-08): a closed case needs no agent action, and the
TSANet web app remains the record for cases that never had a ticket.

**Operational note:** `closure_unconfigured` and `closure_ticket_resolve_failed` are
one-shot permanent losses (a duplicate delivery is the only automatic second chance),
not routine noise — whatever log monitoring the deployment gets should alert on both.

**Outbound closure is not built.** An agent resolving the ticket does not close the
partner's case. That needs a decision this repo has not taken: which Intercom state
transitions are meant to propagate, whether resolving a never-accepted case is valid to
the API at all, and what to do about tickets resolved for reasons unrelated to the
collaboration. The `/closure` endpoint documents only 200/400, so an invalid transition
is not distinguishable from any other bad request — which makes guessing the policy
expensive to get wrong.


## D19. The outbound submitter identity is prefilled and editable, like the engineer identity

D15 records why the inbound engineer identity is a default, not a lock: the Connect
API rejects submitter and engineer emails off the authenticated member's registered
domain, no status code conveys the rule, and the connector cannot know the registered
domains — the problem+json `detail` is the only signal. The outbound submitter had
shipped without that lesson: `draftFrom` read `submitterContactDetails` straight from
the canvas payload's `admin` object, so the submitter was locked to the acting
teammate's Intercom login. Any teammate whose login is off the member's domain got a
rejection whose message says "fix and resubmit" over a form with nothing editable
controlling the rejected value. Found by the first live off-domain click (2026-08-07,
shawn@tsanet.org against the sdk1 test member); no earlier run had hit it because the
exit E2E injects an on-domain address as the canvas admin.

So the outbound form now renders submitter name and email, prefilled from the acting
teammate, editable, and required — resolved as typed-wins-over-default at every use
site (render prefill, validation, draft), reading the typed value from server-side
state rather than the request's inputs so it also rides `retry_submit`, whose canvas
has no input fields to echo it. The single failure-notice slot renders directly above
the identity fields, so the domain rejection lands beside what fixes it. The identity
deliberately survives a partner re-select (`clearFieldValues()` drops only `f_*` keys;
who is submitting does not change with the partner), and the `f_` filter in
`draftFrom` keeps the identity keys out of partner custom fields. The submission
link's `adminEmail` still records the acting teammate's login, on purpose: the link
audits who drove the submission; the draft carries who TSANet shows to the partner.
On `retry_submit`, or a status check that lands the case, that is the teammate whose
request LANDED it — who can differ from both the original clicker and the drafted
submitter. That divergence is the audit meaning, not a bug.
