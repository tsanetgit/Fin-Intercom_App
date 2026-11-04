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
- `connect-gateway` (Maven profile `sdk`): the Connect SDK binding. Consumes the
  published `org.tsanet.connector:gateway-engine` and `com.tsanet:connect-library`
  artifacts from GitHub Packages; carries no engine or SDK sources of its own.

## Build and run

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -B clean verify
```

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@21 mvn -B -P sdk clean verify
```

The `sdk` profile builds nothing from source. It resolves two published artifacts,
`gateway-engine` from the `tsanetgit/Connect_Gateway` registry and `connect-library`
from `tsanetgit/Connect_SDK`. GitHub Packages requires a token even for reads, so
`~/.m2/settings.xml` needs both server ids the poms declare:

```xml
<settings><servers>
  <server><id>github</id><username>YOUR_GITHUB_USER</username><password>YOUR_PAT</password></server>
  <server><id>github-sdk</id><username>YOUR_GITHUB_USER</username><password>YOUR_PAT</password></server>
</servers></settings>
```

The PAT must be a classic one with `read:packages`; the Maven registry accepts no
fine-grained tokens, and `repo` scope is not needed. No
sibling checkout, no codegen, and no `Connect-API-Code` access are involved. CI does
the same thing with the `PACKAGES_READ_TOKEN` secret.

Plain `mvn verify` builds only `runtime-core` and needs no credential at all; it is
always green on a fresh clone.

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

`ci.yml` builds and tests `runtime-core`, and the `sdk` job resolves the published
`gateway-engine` and `connect-library` from GitHub Packages with the `PACKAGES_READ_TOKEN`
secret, then runs the `sdk` profile. **Both jobs run on every PR; `core` needs no secret,
and `sdk` fails at its first step when the token is missing instead of skipping.** The `sdk` job was
previously gated on `CONNECT_API_CODE_TOKEN` for a private checkout; that secret was
never configured, so the job skipped every real step while reporting success — the
gateway tests had never run in CI.

`PACKAGES_READ_TOKEN` is a classic PAT owned by `shawn-tsanet`, with `read:packages` and
no other scope. It has three consumers: the `sdk` job here, the `-Psdk` build in
`image.yml`, and Dependabot (the registries in `dependabot.yml`, and CI on the PRs it
opens), which reads its own copy from the Dependabot secret store. The token expires;
the current expiry date is recorded on
[#56](https://github.com/tsanetgit/Fin-Intercom_App/issues/56). To rotate it,
regenerate it as a classic PAT with `read:packages` only, set it in both the Actions and
the Dependabot secret stores, and run `ci` on `main` (`gh workflow run ci.yml --ref main`);
the `sdk` job must pass. That proves the token: the Maven cache `sdk` restores is the one
`core` saved (`core` finishes first, and `sdk` needs it), which holds none of the private
packages, so `sdk` fetches them from GitHub Packages with the token.

## Status

Phase 0 skeleton (2026-08-04): endpoints, signatures, queue, registry seam, tests, CI.
Phase 1 adds the Intercom API client (with the verify-effects rule), the partner
search/form canvas flows, and the first `TsanetGateway` implementation wiring.
