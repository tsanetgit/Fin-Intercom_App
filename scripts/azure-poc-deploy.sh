#!/usr/bin/env bash
# Deploy the connector to Azure Container Apps as an ON-DEMAND proof of concept.
#
# PoC, NOT production (docs/AZURE_POC.md is the companion checklist):
#   - scale-to-zero (min replicas 0): free-grant friendly, but the app cold-starts
#     on the first request after idle, and the reconciliation poller only runs
#     while a replica is warm;
#   - the reconciliation poller is DISABLED here (CONNECTOR_RECONCILE_ENABLED=false):
#     a second poller against the same BETA member as the Mac-mini rig would race
#     it and double-create tickets in the dev workspace;
#   - the link stores live on /tmp inside the replica: ABSOLUTE paths (the prod
#     profile's guard holds) but EPHEMERAL — links are lost on scale-to-zero.
#     Stated trade, accepted for a PoC; a real deployment needs a mounted volume
#     or a different store, which is exactly the SQLite-on-Azure-Files question
#     the pilot has to answer anyway.
#
# Everything else keeps the prod posture: SPRING_PROFILES_ACTIVE=prod stays baked
# into the image (fake gateways refuse to start), mode=sdk against TSANet BETA as
# sdk1, mode=rest against the Intercom dev workspace.
#
# Prereqs (the checklist covers them): az CLI logged into the target subscription;
# the exec jar built (mvn -Psdk -DskipTests package); tsanet-ops .env present.
set -euo pipefail

cd "$(dirname "$0")/.."
REPO="$PWD"

# ---- parameters (override via env) ------------------------------------------
RG="${AZ_RG:-tsanet-fin-poc}"
LOC="${AZ_LOCATION:-westus2}"
# ACR names are GLOBALLY unique. A stable default keeps re-runs idempotent
# (create is an upsert); if the name is taken, override with AZ_ACR=<yours>.
ACR="${AZ_ACR:-tsanetfinpoc}"
ACA_ENV="${AZ_ACA_ENV:-fin-poc-env}"
APP="${AZ_APP:-fin-gateway-poc}"
IMAGE_TAG="fin-gateway:poc"

die() { echo "FATAL: $*" >&2; exit 1; }

# ---- pre-flight -------------------------------------------------------------
command -v az >/dev/null || die "az CLI not installed (brew install azure-cli)"
az account show >/dev/null 2>&1 || die "not logged in — run: az login"

JAR="connect-gateway/target/connect-gateway-0.1.0-SNAPSHOT-exec.jar"
[ -f "$JAR" ] || die "$JAR not built. Run: mvn -Psdk -DskipTests package"
# Same check run-local.sh and the Dockerfile make: a filename is not evidence.
jar_listing="$(unzip -l "$JAR" 2>/dev/null || true)"
case "$jar_listing" in
  *SdkTsanetGateway*) ;;
  *) die "$JAR contains no SdkTsanetGateway — that is the runtime-core jar" ;;
esac

ENV_FILE="${ENV_FILE:-/Users/shawnray/code/tsanet-ops/.env}"
[ -f "$ENV_FILE" ] || die "no .env at $ENV_FILE (set ENV_FILE=... to override)"
set +u; set -a; . "$ENV_FILE"; set +a; set -u

need() { [ -n "${!1:-}" ] || die "$1 is empty in $ENV_FILE"; }
need TSANET_SDK1_API_URL; need TSANET_SDK1_API_USER; need TSANET_SDK1_API_PASSWORD
need INTERCOM_DEV_ACCESS_TOKEN; need INTERCOM_DEV_CLIENT_SECRET

# The PoC should get its OWN Intercom app (docs/AZURE_POC.md step 3) so it never
# steals the live rig's canvas registration. Until that app exists these default
# to the dev app's credentials so the deploy can be smoke-tested end to end.
POC_INTERCOM_ACCESS_TOKEN="${POC_INTERCOM_ACCESS_TOKEN:-$INTERCOM_DEV_ACCESS_TOKEN}"
POC_INTERCOM_CLIENT_SECRET="${POC_INTERCOM_CLIENT_SECRET:-$INTERCOM_DEV_CLIENT_SECRET}"

# ---- provision --------------------------------------------------------------
echo "== resource group $RG ($LOC)"
az group create -n "$RG" -l "$LOC" -o none

echo "== registering providers (idempotent; first run on a fresh subscription)"
az provider register -n Microsoft.App --wait -o none
az provider register -n Microsoft.ContainerRegistry --wait -o none
az provider register -n Microsoft.OperationalInsights --wait -o none

echo "== container registry $ACR (Basic — the PoC's one steady cost, ~\$5/mo)"
az acr create -g "$RG" -n "$ACR" --sku Basic --admin-enabled true -o none

# az acr build is NOT used: ACR Tasks are blocked on free/trial subscriptions
# (TasksOperationsNotAllowed, hit live 2026-08-08) — an anti-abuse measure, and the
# suggested support ticket is not worth a PoC's time. Build locally for linux/amd64
# (the RUN steps are trivial enough that QEMU emulation on Apple Silicon is fine)
# and push. Requires Docker Desktop running.
echo "== building image locally (linux/amd64) and pushing to $ACR"
command -v docker >/dev/null || die "docker not installed"
docker info >/dev/null 2>&1 || die "docker daemon not running — start Docker Desktop"
az acr login -n "$ACR"
docker build --platform linux/amd64 -t "$ACR.azurecr.io/$IMAGE_TAG" .
docker push "$ACR.azurecr.io/$IMAGE_TAG"

echo "== container apps environment $ACA_ENV"
az containerapp env create -g "$RG" -n "$ACA_ENV" -l "$LOC" -o none

echo "== container app $APP (on-demand: min 0 / max 1)"
az containerapp create -g "$RG" -n "$APP" \
  --environment "$ACA_ENV" \
  --image "$ACR.azurecr.io/$IMAGE_TAG" \
  --registry-server "$ACR.azurecr.io" \
  --ingress external --target-port 8792 \
  --min-replicas 0 --max-replicas 1 \
  --cpu 0.5 --memory 1Gi \
  --secrets \
      tsanet-password="$TSANET_SDK1_API_PASSWORD" \
      intercom-token="$POC_INTERCOM_ACCESS_TOKEN" \
      intercom-secret="$POC_INTERCOM_CLIENT_SECRET" \
  --env-vars \
      CONNECTOR_TSANET_MODE=sdk \
      CONNECTOR_INTERCOM_MODE=rest \
      TSANET_API_URL="$TSANET_SDK1_API_URL" \
      TSANET_API_USER="$TSANET_SDK1_API_USER" \
      TSANET_API_PASSWORD=secretref:tsanet-password \
      INTERCOM_ACCESS_TOKEN=secretref:intercom-token \
      INTERCOM_DEV_CLIENT_SECRET=secretref:intercom-secret \
      INTERCOM_COLLABORATION_TICKET_TYPE_ID="${INTERCOM_COLLABORATION_TICKET_TYPE_ID:-4561712}" \
      INTERCOM_RESOLVED_STATE_ID="${INTERCOM_RESOLVED_STATE_ID:-9619799}" \
      CONNECTOR_LINKS_STORE=sqlite \
      CONNECTOR_LINKS_SQLITE_PATH=/tmp/links.db \
      TSANET_SDK_SQLITE_PATH=/tmp/connect-sdk.db \
      CONNECTOR_RECONCILE_ENABLED=false \
  -o none

FQDN="$(az containerapp show -g "$RG" -n "$APP" --query properties.configuration.ingress.fqdn -o tsv)"
echo
echo "== deployed: https://$FQDN"
echo "== waking it (cold start on 0.5 vCPU takes a while — this also proves 0 -> 1):"
# || true: a slow first start exceeding the timeout must not fail the script —
# the deploy already succeeded and the instructions below must still print.
time curl -s --max-time 180 "https://$FQDN/healthz" || true; echo
echo
echo "Next: docs/AZURE_POC.md step 3 — create the 'tsanet-connect-poc' Intercom app"
echo "and point its canvas/webhook URLs at https://$FQDN"
