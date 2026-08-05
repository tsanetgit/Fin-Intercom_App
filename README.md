# tsanet-intercom-connector

Headless runtime behind the "TSANet Connect" sidebar app in the Fin/Intercom Inbox.
Agents never see this service; they see the panel it feeds. This is an integration,
not a replacement: Fin's UI stays Fin's UI. Background: the scoping doc
(TSANet_Connect_Fin_Intercom_Connector_Scoping_DRAFT_v0.2) and the hosted-service
design note; decisions and measured platform facts: [docs/decisions.md](docs/decisions.md).

## Modules

- `runtime-core`: Spring Boot service. Canvas endpoints (`/canvas/initialize`,
  `/canvas/submit`), webhook intake (`/webhooks/intercom`, `/webhooks/tsanet`),
  signature verification for all three schemes, bounded dedupe + event queue,
  workspace registry seam, `/healthz`. No Connect SDK dependency, by rule.
- `connect-gateway` (Maven profile `sdk`): the Connect SDK binding
  (`com.tsanet:connect-library`, consumed verbatim from the `oauth` branch stack).

## Build and run

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -B clean verify
```

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -B -P sdk clean verify
```

The `sdk` profile needs `connect-library` in the local Maven repository (built from
`shawn-tsanet/connect-sdk-demo`, which needs `tsanetgit/Connect-API-Code` as a sibling
checkout on the `beta` branch). Plain `mvn verify` is always green on a fresh clone.

Run locally:

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -pl runtime-core spring-boot:run
```

Port 8792. Configuration comes from the environment:

| Env var | Purpose |
|---|---|
| `INTERCOM_DEV_CLIENT_SECRET` | Verifies canvas `X-Body-Signature` and Intercom webhook `X-Hub-Signature` |
| `TSANET_WEBHOOK_SECRET` (falls back to `TSANET_BETA_WEBHOOK_SECRET`) | Verifies TSANet `x-hub-signature-256` |

Missing secrets fail closed: signed endpoints return 401 until configured. `/healthz`
is the only open route.

## CI

`ci.yml` builds and tests `runtime-core` on every push (always green, no secrets
needed). The `sdk` job bootstraps the sibling checkouts and runs the `sdk` profile only
when the `CONNECT_API_CODE_TOKEN` secret is configured (Connect-API-Code is private).

## Status

Phase 0 skeleton (2026-08-04): endpoints, signatures, queue, registry seam, tests, CI.
Phase 1 adds the Intercom API client (with the verify-effects rule), the partner
search/form canvas flows, and the first `TsanetGateway` implementation wiring.
