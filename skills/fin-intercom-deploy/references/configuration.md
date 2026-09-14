# Configuration reference and verification

The connector is configured entirely through the environment (see
`runtime-core/src/main/resources/application.yml`, which is the authority if this table
ever drifts). Empty secrets fail closed; per-workspace ids have no defaults on purpose.

## Required for a real deployment

| Variable | Value / notes |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `prod` — arms every refuse-rather-than-pretend guard. Without it, fake modes and volatile stores start silently. |
| `CONNECTOR_TSANET_MODE` | `sdk` (the fixture gateway refuses prod) |
| `CONNECTOR_INTERCOM_MODE` | `rest` (the fixture refuses prod; rest mode fails fast without a token) |
| `INTERCOM_DEV_CLIENT_SECRET` | the private app's client secret — verifies canvas `X-Body-Signature` and webhook `X-Hub-Signature`. The `DEV` in the name is historical; it is *the* client secret binding for every environment. |
| `INTERCOM_ACCESS_TOKEN` | the private app's access token — tickets, annotations, notes, state moves |
| `INTERCOM_COLLABORATION_TICKET_TYPE_ID` | per-workspace Back-office ticket type id; unset = inbound cases refused |
| `TSANET_API_URL` | `https://connect2.tsanet.net` (BETA) or `https://connect2.tsanet.org` (production) |
| `TSANET_AUTH_MODE` | `password` or `client_credentials` |
| `TSANET_API_USER` | username/email (password mode) or Entra client id |
| `TSANET_API_PASSWORD` | password or Entra client secret |
| `TSANET_WEBHOOK_SECRET` | minted by the Connect API when the v2 subscription is created; verifies `x-hub-signature-256` |
| `CONNECTOR_LINKS_STORE` | `sqlite` (the `memory` default is refused under prod) |
| `CONNECTOR_LINKS_SQLITE_PATH` | ABSOLUTE path on storage that outlives the container (relative paths are refused under prod; the boot log prints the resolved path — check it) |

## Required conditionally

| Variable | When |
|---|---|
| `TSANET_ENTRA_TENANT_ID`, `TSANET_ENTRA_AUDIENCE` | `client_credentials` mode only. The audience differs between BETA and production. |
| `INTERCOM_RESOLVED_STATE_ID` | for inbound closure (partner closed → ticket Resolved); unset refuses closure |
| `INTERCOM_IN_PROGRESS_STATE_ID`, `INTERCOM_WAITING_STATE_ID` | for partner-response surfacing on outbound cases; each unset id disables only its statuses |
| `TSANET_SDK_SQLITE_PATH` | the SDK session store; same storage rules as the link store (the reference deployment keeps both files side by side and backs both up) |

## Optional, with meaningful defaults

| Variable | Default | Notes |
|---|---|---|
| `TSANET_TEST_SUBMISSION` | `true` | real partner cases are an explicit opt-in at pilot time |
| `INTERCOM_ADMIN_ID` | blank | blank = resolved once via `GET /me` at wiring, which makes Intercom reachability a startup dependency; set it to remove that dependency |
| `CONNECTOR_RECONCILE_ENABLED` | `true` | the recovery poller; leave on (turning it off has to be typed, by design) |
| `CONNECTOR_RECONCILE_INTERVAL_MS` / `_INITIAL_DELAY_MS` | 300000 / 60000 | cycle cadence, measured from cycle end |
| `CONNECTOR_RECONCILE_LOOKBACK` / `_OVERLAP` | `24h` / `120s` | first-run window (the list endpoint has no pagination — this is the only load control) and the cursor overlap |
| `CONNECTOR_ADMIN_API_SECRET` | blank | blank = no admin plane at all (fail closed). TSANet-internal console secret, never a member credential. |
| `CONNECTOR_PUBLIC_BASE_URL` | blank | feeds `/admin/urls`; unset falls back to forwarded headers |
| `CONNECTOR_TICKETS_ONLY` | `false` | hardened profile (#65): `true` refuses new outbound collaborations from plain conversations, so the Intercom token can drop "Write conversations". Deploy-time, per deployment. |
| `CONNECTOR_SDK_CACHE_RETENTION` | blank | blank = off, and the sweep bean does not register. Set a duration (`30d`, `720h` or ISO-8601) to evict CLOSED/REJECTED cases and their note/response/attachment-config rows older than the window from `connect-sdk.db` (#66); OPEN cases never age out and `attachment_forward_result` is never evicted. Cadence: `CONNECTOR_SDK_CACHE_SWEEP_INTERVAL` (`PT6H`) and `CONNECTOR_SDK_CACHE_SWEEP_INITIAL_DELAY` (`PT10M`). |

Port: `8792` (`server.port`). `/healthz` is the only unauthenticated route.

## Verification checklist (run after every deploy, not just the first)

1. **Boot log**: prints the absolute link-store path — confirm it is the durable
   location, not a container-local default.
2. **`GET /healthz`** answers 200 immediately (the replica should be always-warm).
3. **Outbound smoke**: open a ticket → panel → search a partner → submit the form with
   a submitter email on the member's registered domain. Proof is the "Case submitted"
   card with a token, plus a `tsanet_audit event=case_submitted` line and the three
   ticket attributes landing (annotation FULL).
4. **Inbound smoke**: have a partner/test member open a (test-flagged) case naming this
   member. Proof is a ticket of the configured type within seconds (push) or within one
   poller cycle (recovery), carrying `TSANet Token`, `Partner`, `Respond By`, and a
   panel offering Accept / Request information / Decline.
5. **Signature negative check**: an unsigned POST to `/canvas/initialize` and to both
   webhook paths answers 401.

## Audit lines a deployment should alert on

The connector's audit convention is structured log lines prefixed `tsanet_audit`,
carrying routing facts only (never case content). Wire the deployment's log monitoring
to alert on:

| Line | Meaning |
|---|---|
| `closure_unconfigured`, `closure_ticket_resolve_failed` | one-shot permanent losses — a duplicate delivery is the only automatic second chance |
| `annotation_failed`, `link_missing`, `annotation_enqueue_failed` | the annotation's retry budget is exhausted or the route home is gone; the audit line plus the link table are the manual re-drive path |
| `reconcile_cycle_held` recurring, with `consecutiveHeld` climbing while `consecutiveFailures` stays 0 | the orphaned-claim signature — a claim with no thread behind it is holding the poller cursor; releasing it is a deliberate human action |
| `inbound_ticket_create_failed` | a create that keeps failing needs a human even though the poller keeps retrying |
| `tsanet_gateway_timeout` counter climbing | deadline pressure against the Connect API — validates or refutes the 1500ms read budget for this member's traffic |
