---
name: fin-intercom-deploy
description: >-
  Deploy the TSANet Fin/Intercom connector (this repository's adapter over the Connect
  Gateway engine) for a TSANet member whose support platform is Intercom or Fin, and
  configure both sides: the Intercom workspace (private app, canvas URLs, webhook topics,
  the Back-office ticket type and its attributes, ticket-state ids) and the TSANet side
  (member API credentials, the v2 CloudEvents webhook subscription, the test-submission
  flag). Use this whenever the work involves getting a member live on the Fin/Intercom
  connector, standing up or updating a deployment of tsanet-intercom-connector, wiring an
  Intercom workspace to TSANet Connect, or debugging a deployment that refuses to start or
  refuses inbound cases. Trigger even when the request only names the goal and not the
  machinery, for example "get us set up with TSANet in Intercom", "our Fin workspace needs
  the TSANet panel", "deploy the Intercom adapter for <member>", or "why are partner cases
  not becoming tickets". For building a NEW adapter for a different platform, use the
  Connect_Gateway skills instead; for Connect REST semantics, defer to the tsanet-connect
  skill.
---

# Deploying the Fin/Intercom connector for a member

The connector is one headless Spring Boot service that sits between a member's Intercom
workspace and TSANet Connect. Agents never see it; they see the "TSANet Connect" canvas
panel it feeds in the Inbox ticket Details panel. One deployment serves one member.

Read this file top to bottom once; it is ordered the way a real onboarding runs. The
references hold the exact values and are meant to be read when you reach that step:

- [references/intercom-setup.md](references/intercom-setup.md) — everything created or
  looked up inside the Intercom workspace, and why each piece is shaped the way it is.
- [references/tsanet-setup.md](references/tsanet-setup.md) — member credentials, the
  webhook subscription, environments, and the go-live flag.
- [references/configuration.md](references/configuration.md) — the complete environment
  variable table, what fails closed when unset, and the verification checklist.

## The invariants (read before deploying anything)

These are not preferences. Each one, violated, produces a specific defect that has
already been seen or deliberately designed against:

1. **One live adapter per member.** A second instance running with the same member
   credentials (anyone else's deployment of this repo included) races the reconciliation
   poller and double-creates tickets for inbound cases. The unique-token claim protects
   an instance against its own duplicates, not two instances against each other. Before
   enabling a deployment, confirm nothing else polls or receives webhooks as this member.
2. **Single replica, always warm.** The link store is single-writer and the poller cursor
   assumes one instance. Scale-out is a storage redesign, not a replica-count change.
   Always-warm matters because the connector holds itself to a <2s canvas budget (decision
   D2); Intercom itself fails a canvas response at roughly 10s (measured 2026-08-04: 8s
   renders, 10s fails, and a slow submit strands the agent on a spinner). A cold start blows
   the budget either way.
3. **The link store must survive restarts.** Every open case's only route home is a row
   in the SQLite link table. Under the prod profile the connector refuses to start with
   the in-memory store or a relative SQLite path. Note the platform trap: SQLite WAL
   cannot run on a network filesystem (probed live on Azure Files SMB, instant
   `SQLITE_BUSY`), so the database lives on replica-local disk with a backup loop to
   durable storage. See `docs/AZURE_DEPLOYMENT.md` for the working pattern.
4. **Secrets fail closed.** A missing or blank signing secret means the corresponding
   endpoints answer 401, never open. `/healthz` is the only unauthenticated route. If a
   surface "stopped working" after a config change, check for a blank secret first.
5. **`SPRING_PROFILES_ACTIVE=prod` is what arms the guards.** Fake gateways, the memory
   link store, and relative store paths are refused only under the prod profile. A
   deployment that forgets the profile gets none of these refusals and no signal that it
   is running on sand.

## Deployment order

### 1. Build the deployable

JDK 21, and the `sdk` profile — the deployable is the **connect-gateway exec jar**, not
runtime-core's. runtime-core carries no `com.tsanet.*` classes by rule, so a runtime-core
jar in `sdk` mode finds no TSANet gateway bean and fails at startup.

```bash
JAVA_HOME=<jdk21> mvn -Psdk -DskipTests package
# deployable: connect-gateway/target/connect-gateway-*-exec.jar
```

The `sdk` profile needs `gateway-engine` from the Connect_Gateway GitHub Packages
registry (see the root README for the `~/.m2/settings.xml` shape). The Docker build in
`Dockerfile` does the same thing in a container.

### 2. Provision the TSANet side

Follow [references/tsanet-setup.md](references/tsanet-setup.md): member account with an
API user, credentials (password mode or Entra client-credentials), BETA first. Members
always validate on BETA (`https://connect2.tsanet.net`) before production
(`https://connect2.tsanet.org`).

### 3. Configure the Intercom workspace

Follow [references/intercom-setup.md](references/intercom-setup.md): the private app with
canvas + webhook URLs and explicit topic subscriptions, the Back-office ticket type with
the three exactly-named attributes, and the four per-workspace ticket-state ids resolved
out of band.

### 4. Deploy, then register the TSANet webhook

Deploy the container with the environment from
[references/configuration.md](references/configuration.md). For the Azure reference
environment the whole flow is one idempotent script (`scripts/azure-poc-deploy.sh vX.Y.Z`,
which deploys only the CI image of a release tag on `main`, after verifying its GitHub
attestation; runbook `docs/AZURE_POC.md`); for any other platform, replicate what it does.

The TSANet v2 webhook subscription needs the deployed URL, which does not exist until
the app does — so on a first-ever deploy this is a second pass: deploy, then register
the subscription pointing at `https://<fqdn>/webhooks/tsanet` (the API mints the
subscription secret at creation; capture it into `TSANET_WEBHOOK_SECRET` and update the
deployment). The Azure script automates the list-then-create and the capture; its
closing message tells you when the second run is needed.

For local development and testing, `scripts/run-local.sh` starts the connector in sdk
mode on the durable store and refuses to start half-configured, with an explanation for
every refusal.

### 5. Verify end to end

The checklist at the bottom of
[references/configuration.md](references/configuration.md): `/healthz`, one outbound
submission (mind the registered-domain rule on the submitter email), one inbound test
case, and the audit lines a deployment should alert on.

## What "configure Fin" means here

Fin's UI stays Fin's UI — the connector is an integration, not a replacement. The
member-visible configuration is: the canvas app placed on the ticket (and conversation)
Details panel, the Back-office ticket type that inbound partner cases arrive as, and the
ticket states those cases move through. Fin's conversation-first flow is respected: a
conversation escalates to a ticket when work starts, and the panel rides both surfaces
(the same placement config covers ticket and conversation renders; the payload carries a
top-level `ticket` object only on ticket surfaces, which is how the connector tells them
apart).

## When something refuses to start or refuses to act

That refusal is almost always the design working. The connector prefers refusing loudly
over acting wrongly:

| Symptom | Cause |
|---|---|
| App will not start, names the link store | memory store or relative SQLite path under prod — set `CONNECTOR_LINKS_STORE=sqlite` and an absolute path on storage that outlives the container |
| App will not start, names the gateway | `mode=fake` under prod, or `sdk` mode half-configured (missing TSANet URL/credentials) |
| Canvas or webhook answers 401 | blank/rotated secret for that scheme; check the four schemes in `docs/decisions.md` D1/D20 |
| Inbound cases refused, audit names the ticket type | `INTERCOM_COLLABORATION_TICKET_TYPE_ID` unset — it is per-workspace and has no default on purpose |
| Closure or response surfacing refused, audit names a state id | the per-workspace state id for that status is unset; resolve out of band and configure (never pin `Intercom-Version: Unstable` at runtime) |
| Partner case never became a ticket, then appeared minutes later | push was lost and the reconciliation poller recovered it — that is the safety net working, not a bug |

The design record for every one of these behaviors is `docs/decisions.md` (D1 through
D23). When a decision here surprises you, the "why" is there; do not fight it in
configuration.
