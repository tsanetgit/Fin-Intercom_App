# Azure PoC deployment — on-demand container, not production

Companion to `scripts/azure-poc-deploy.sh`. This deploys the connector to Azure
Container Apps on a free account as a **proof of concept**: scale-to-zero,
ephemeral link storage, poller disabled. What it proves: the image runs off-box,
the outbound canvas flow works against a cloud endpoint, and the deploy path is
scripted. What it deliberately does not prove is listed at the bottom — read that
before drawing conclusions from the PoC.

## 1. One-time, human-only steps

- **Create the Azure free account** (needs a card + identity; one free account per
  customer): https://azure.microsoft.com/free — $200 credit for 30 days plus
  12 months of limited free services. The Container Apps free grant
  (180k vCPU-s / 360k GiB-s / 2M requests per month) is what the on-demand shape
  lives inside.
- `brew install azure-cli` if absent, then `az login` on the Mac mini.
- **Docker Desktop must be running.** The image is built locally and pushed —
  `az acr build` is unavailable because ACR Tasks are blocked on free/trial
  subscriptions (`TasksOperationsNotAllowed`, hit live on this account's first
  deploy) — and the script hard-stops with a clear message if the daemon is down.

## 2. Deploy

```bash
cd /Users/shawnray/projects/tsanetgit-Fin-Intercom_App && JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home mvn -Psdk -DskipTests package && ./scripts/azure-poc-deploy.sh
```

The script is parameterized via `AZ_RG` / `AZ_LOCATION` / `AZ_ACR` / `AZ_APP` env
overrides; defaults create resource group `tsanet-fin-poc` in `westus2`. It ends
by printing the app's HTTPS URL and waking it once through `/healthz`.

## 3. Give the PoC its own Intercom app

Do **not** repoint the live `tsanet-connect` app — its canvas URLs serve the
Mac-mini rig. In the dev workspace's Developer Hub, create a second private app
(suggested name `tsanet-connect-poc`) and configure:

- Canvas initialize URL: `https://<fqdn>/canvas/initialize`
- Canvas submit URL: `https://<fqdn>/canvas/submit`
- Webhook endpoint: `https://<fqdn>/webhooks/intercom`
- **Subscribe the webhook topics explicitly** — permissions being on does NOT
  subscribe anything (trap T2 from the spike; an empty topic list is silent
  no-delivery).

Then feed the new app's credentials to the container (the deploy defaulted to the
dev app's, which verifies signatures for the OLD app only):

```bash
cd /Users/shawnray/projects/tsanetgit-Fin-Intercom_App && POC_INTERCOM_ACCESS_TOKEN=<new app token> POC_INTERCOM_CLIENT_SECRET=<new app client secret> ./scripts/azure-poc-deploy.sh
```

(Re-running the script is idempotent and just updates the app in place.)

Pin the new app on a test conversation/ticket and run the outbound flow. **Warm
the app first before any demo** — `curl https://<fqdn>/healthz` — because a
scaled-to-zero Spring app cold-starts slower than Intercom's ~10s canvas timeout;
the first click on a cold app errors, the second works.

## 4. Teardown

```bash
az group delete -n tsanet-fin-poc --yes --no-wait
```

Everything the script created lives in that one resource group (including the
Log Analytics workspace the environment auto-creates). If you overrode `AZ_RG`,
delete that name instead.

## What this PoC deliberately does NOT do

- **No TSANet push registration, poller disabled.** A second live consumer of the
  same BETA member (sdk1) would race the Mac-mini rig's poller and double-create
  tickets in the shared dev workspace. To demo inbound on the PoC, THREE steps —
  the third is the one that silently no-ops if skipped: stop the mini's gateway
  (`launchctl bootout gui/$UID/com.sray.tsanet-ops.fin-gateway`), redeploy with
  `CONNECTOR_RECONCILE_ENABLED=true` in the script's env block, **and set the app
  warm** (`az containerapp update -g tsanet-fin-poc -n fin-gateway-poc
  --min-replicas 1`) — the poller only runs while a replica exists, so on a
  scaled-to-zero app enabling it does nothing. Reverse all three when done.
- **Links are ephemeral** (`/tmp` inside the replica — absolute paths, so the prod
  profile's guards hold, but the filesystem dies with scale-to-zero). A restarted
  PoC cannot route responses to cases created before the restart. The real
  deployment needs a mounted volume — and the SQLite-on-Azure-Files locking
  question must be answered then; do not extrapolate from this PoC that storage is
  solved.
- **Cold-start latency is real.** The canvas <2s budget (D2) is only honorable on
  a warm replica. Production stays min-replicas ≥ 1, which is why production is
  not free.
- **Not the pilot's home.** The October pilot belongs on a TSANet-controlled
  subscription (the scoping doc puts GA in TSANet's middleware estate); a personal
  free account is for this PoC only.

## Costs

Inside the free grant + $200/30-day credit for everything except the container
registry (Basic, ~$5/month), which is the PoC's one steady line item. Teardown
zeroes it.
