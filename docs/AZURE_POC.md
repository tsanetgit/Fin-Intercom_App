# Azure test environment — runbook

The Azure deployment is the adapter's default environment for PoC, testing and
demos, with **full inbound and outbound capability**. It is not production: its
purpose is to demonstrate the adapter to the technology committee so a production
deployment gets decided, and its storage/subscription posture reflects that (see
"Not production" below). Architecture and diagrams: [AZURE_DEPLOYMENT.md](AZURE_DEPLOYMENT.md).
The deploy script is `scripts/azure-poc-deploy.sh`; the exact container spec it
applies is `deploy/containerapp.yaml.tmpl`.

## 1. One-time, human-only steps

- **Azure free account** (needs a card + identity; one per customer):
  https://azure.microsoft.com/free — $200 credit for 30 days plus 12 months of
  limited free services. The always-warm replica exceeds the Container Apps free
  grant, so after the credit this environment runs roughly $15–25/month.
- `brew install azure-cli` if absent, then `az login` as a principal that may create
  role assignments on the registry (Owner or User Access Administrator): the deploy
  script grants AcrPull to the image-pull identity.
- **Docker Desktop must be running** for every deploy: the image builds locally
  because ACR Tasks (`az acr build`) are blocked on free/trial subscriptions
  (`TasksOperationsNotAllowed`).

## 2. Deploy or update

```bash
cd /Users/shawnray/projects/tsanetgit-Fin-Intercom_App && JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -Psdk -DskipTests package && ./scripts/azure-poc-deploy.sh
```

The script is idempotent — the same command performs first deploy and every later
update (new image, config change). It provisions the resource group, registry (without
its admin user), the image-pull identity `fin-gateway-poc-pull` with AcrPull on the
registry, the Container Apps environment, storage account + file share, registers the
TSANet v2 webhook (list-then-create; the API mints the subscription secret at creation
and the script captures it into the app's config), renders `deploy/containerapp.yaml.tmpl`
and applies it, waits for the new revision to become ready when the update produced
one (and fails, naming the revision, if it does not), then prints the app URL and
checks `/healthz`.

**First-ever deploy only:** the TSANet webhook needs the app's URL, which does not
exist until the app does — run the script a second time to register it, as the
script's closing message says.

**If a deploy fails at the revision wait:** the app's registry config already uses the identity, so the previous revision serves only until its next restart. Re-run the deploy after a few minutes (AcrPull can take that long to take effect), or, while the admin user is still enabled, restore the admin-credential pull with `az containerapp registry set -g tsanet-fin-poc -n fin-gateway-poc --server tsanetfinpoc.azurecr.io --username tsanetfinpoc --password "$(az acr credential show -n tsanetfinpoc --query 'passwords[0].value' -o tsv)"`.

Parameters override via env: `AZ_RG`, `AZ_LOCATION`, `AZ_ACR`, `AZ_APP`,
`AZ_STORAGE`, `AZ_PULL_IDENTITY`, `AZ_ROLE_PROPAGATION_SECONDS` (default 120), `ENV_FILE`.

**One-time, after the first deploy that pulls by identity (#55):** retire the
registry admin user.

```bash
cd /Users/shawnray/projects/tsanetgit-Fin-Intercom_App && ./scripts/azure-poc-migrate-pull-identity.sh
```

It changes nothing unless the app's registry entry names the identity, no password
secret remains and the identity holds AcrPull. The admin user is registry-wide, so it
also refuses, naming them, while any other container app still pulls from the registry
or job with the admin credential (on 2026-10-21: the apps `gateway-admin-console`,
`attachment-receiver` and `mongodb-mock`, and the job `mongodb-mock-smoke`). It then
restarts the active revision,
a short outage of the single replica, to prove the image pulls as the identity. Only
after the revision is healthy again does it disable the admin user. `--no-restart`
skips the restart when a revision has already started since the switch.

## 3. Intercom app configuration

The adapter serves one private Intercom app in the dev workspace. In the
Developer Hub, its URLs point at the app's FQDN (printed by the deploy script):

- Canvas initialize: `https://<fqdn>/canvas/initialize`
- Canvas submit: `https://<fqdn>/canvas/submit`
- Webhook endpoint: `https://<fqdn>/webhooks/intercom`
- Webhook **topics must be subscribed explicitly** (`ticket.created`,
  `ticket.state.updated`, `ticket.attribute.updated`, `ticket.admin.assigned`,
  `ticket.note.created`) — granted permissions subscribe nothing by themselves.

The container verifies canvas and webhook signatures with that app's client secret
(`INTERCOM_DEV_CLIENT_SECRET` from `.env`); its access token drives ticket
annotations. If the app's credentials rotate, update `.env` and re-run the deploy.

## 4. Demo checks

- The replica is always warm — no cold-start warm-up is needed; `/healthz` answers
  immediately and canvas renders are fast.
- **Outbound:** open any ticket's Details panel → the app section → search a
  partner, fill the form (submitter email must be on the acting member's registered
  domain — for the Connect Gateway member that means any `@connectgateway.com` address), Submit. A "Case submitted"
  card with a token is the proof.
- **Inbound:** a partner-created case (test-flagged, from the sdk2 test member)
  becomes a `[TEST]`-titled ticket in the workspace within seconds via push, or
  within one poller cycle (5 min) if push missed. The ticket's panel offers
  Accept / Request information / Decline with an editable engineer identity.

## Precondition: one live adapter per member

This must be the **only** adapter instance running against the Connect Gateway member.
A second instance with its own link table (any other deployment of this repo
pointed at the same credentials) races the poller and double-creates tickets for
inbound cases — D14's unique-token claim protects each instance against its own
duplicates, not two instances against each other. Before enabling this
environment, confirm nothing else polls or receives webhooks as the Connect Gateway member.

## 5. Teardown

```bash
az group delete -n tsanet-fin-poc --yes --no-wait
```

Everything lives in that one resource group, including the auto-created Log
Analytics workspace. If you overrode `AZ_RG`, delete that name instead. Also delete
the TSANet v2 webhook subscription (as the Connect Gateway member: `DELETE /v1/webhooks/{id}` - the delete lives on v1; the v2 path 404s, probed at the 2026-08-11 cutover) so BETA
stops attempting deliveries.

## Not production

Stated so the demo cannot be mistaken for more than it is:

- **Storage is a backup loop, not a database.** SQLite lives on in-replica storage
  (WAL cannot run on Azure Files SMB — probed live); a sidecar copies it to Azure
  Files every 5 minutes and boot restores the latest copy. Worst case a restart
  loses the last 5 minutes of link writes. Production replaces this with a managed
  database — by design a DataSource change, not a rewrite.
- **Personal subscription, secrets through the az CLI** — acceptable for a
  disposable test environment, not for production. Image pulls already use a managed
  identity (#55). The production environment belongs on a TSANet-controlled
  subscription with a key vault for the remaining secrets.
- **One replica by design** (single-writer store and poller cursor) — production
  availability needs the storage answer first.
