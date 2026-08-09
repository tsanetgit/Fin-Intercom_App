#!/usr/bin/env bash
# Deploy the connector's Azure TEST environment — the default environment for PoC and
# demo purposes, full inbound + outbound capability. NOT production: the storage story
# is a 5-minute-RPO backup loop, the subscription is personal, and the committee demo
# this environment exists for is what decides a real production deployment.
#
# Shape produced (docs/AZURE_DEPLOYMENT.md has the architecture and diagrams;
# deploy/containerapp.yaml.tmpl is the exact spec):
#   - always-warm single replica (min = max = 1), app + backup sidecar
#   - SQLite on an in-replica EmptyDir; Azure Files as restore-source/backup-target
#     (WAL cannot run on SMB — probed live, SQLITE_BUSY at first connection)
#   - reconciliation poller ENABLED; TSANet v2 webhook registered idempotently, with
#     the server-minted secret captured into the app's config
#
# Idempotent throughout: ARM upserts for every az resource, list-then-create for the
# webhook. Run it again to update the image or config.
#
# Prereqs (docs/AZURE_POC.md): az CLI logged in, Docker Desktop RUNNING (ACR Tasks are
# blocked on free/trial subscriptions, so the image builds locally), the exec jar
# built, tsanet-ops .env present.
set -euo pipefail

cd "$(dirname "$0")/.."
REPO="$PWD"

# ---- parameters (override via env) ------------------------------------------
RG="${AZ_RG:-tsanet-fin-poc}"
LOC="${AZ_LOCATION:-westus2}"
# ACR names are GLOBALLY unique. Stable default keeps re-runs idempotent; if the
# name is taken on a fresh setup, override with AZ_ACR=<yours>.
ACR="${AZ_ACR:-tsanetfinpoc}"
ACA_ENV="${AZ_ACA_ENV:-fin-poc-env}"
APP="${AZ_APP:-fin-gateway-poc}"
IMAGE_TAG="fin-gateway:poc"
SHARE="findata"

die() { echo "FATAL: $*" >&2; exit 1; }

# ---- pre-flight -------------------------------------------------------------
command -v az >/dev/null || die "az CLI not installed (brew install azure-cli)"
az account show >/dev/null 2>&1 || die "not logged in — run: az login"
command -v docker >/dev/null || die "docker not installed"
docker info >/dev/null 2>&1 || die "docker daemon not running — start Docker Desktop"

JAR="connect-gateway/target/connect-gateway-0.1.0-SNAPSHOT-exec.jar"
[ -f "$JAR" ] || die "$JAR not built. Run: mvn -Psdk -DskipTests package"
# A filename is not evidence: the deployable must actually carry the SDK gateway.
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

# ---- provision --------------------------------------------------------------
echo "== resource group $RG ($LOC)"
az group create -n "$RG" -l "$LOC" -o none

echo "== registering providers (idempotent)"
for prov in Microsoft.App Microsoft.ContainerRegistry Microsoft.OperationalInsights Microsoft.Storage; do
  az provider register -n "$prov" --wait -o none
done

echo "== container registry $ACR"
az acr create -g "$RG" -n "$ACR" --sku Basic --admin-enabled true -o none

echo "== building image locally (linux/amd64) and pushing"
az acr login -n "$ACR"
docker build --platform linux/amd64 -t "$ACR.azurecr.io/$IMAGE_TAG" .
docker push "$ACR.azurecr.io/$IMAGE_TAG"

echo "== container apps environment $ACA_ENV"
az containerapp env create -g "$RG" -n "$ACA_ENV" -l "$LOC" -o none

echo "== storage: account + file share + environment link"
SUB_PREFIX="$(az account show --query id -o tsv | cut -c1-4)"
STG="${AZ_STORAGE:-tsanetfinpocstg$SUB_PREFIX}"
az storage account create -g "$RG" -n "$STG" -l "$LOC" --sku Standard_LRS --kind StorageV2 -o none
az storage share-rm create --storage-account "$STG" -g "$RG" -n "$SHARE" --quota 1 -o none
STG_KEY="$(az storage account keys list -g "$RG" -n "$STG" --query "[0].value" -o tsv)"
az containerapp env storage set -g "$RG" -n "$ACA_ENV" --storage-name "$SHARE" \
  --azure-file-account-name "$STG" --azure-file-account-key "$STG_KEY" \
  --azure-file-share-name "$SHARE" --access-mode ReadWrite -o none

# ---- TSANet v2 webhook: list-then-create, secret captured at creation --------
# The v2 API MINTS the subscription secret and returns it ONLY on creation
# (openapi: WebhookSubscriptionV2ResponseDTO.secret). An existing subscription for
# our URL is kept as-is; its secret is already in the app config from the run that
# created it. Registration happens BEFORE the app render so a newly minted secret
# rides this same deploy.
UA="tsanet-azure-deploy/1.0"
echo "== TSANet v2 webhook registration (as sdk1, idempotent)"
TSANET_TOKEN="$(curl -s -A "$UA" -X POST "$TSANET_SDK1_API_URL/v1/login" \
  -H 'content-type: application/json' \
  -d "{\"username\":\"$TSANET_SDK1_API_USER\",\"password\":\"$TSANET_SDK1_API_PASSWORD\"}" \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['accessToken'])")"
# The FQDN only exists after the first app create; on a fresh setup the webhook is
# registered on the SECOND run, or pass AZ_FQDN explicitly. On an existing setup the
# app already exists and this resolves.
FQDN="${AZ_FQDN:-$(az containerapp show -g "$RG" -n "$APP" \
  --query properties.configuration.ingress.fqdn -o tsv 2>/dev/null || true)}"
WEBHOOK_SECRET="${TSANET_WEBHOOK_SECRET_OVERRIDE:-}"
if [ -n "$FQDN" ]; then
  CALLBACK="https://$FQDN/webhooks/tsanet"
  EXISTING="$(curl -s -A "$UA" "$TSANET_SDK1_API_URL/v2/webhooks" \
    -H "Authorization: Bearer $TSANET_TOKEN")"
  # Three-valued on purpose: a list that cannot be parsed must NOT read as
  # "already exists" — that would skip registration while reporting success.
  HAS="$(printf '%s' "$EXISTING" | python3 -c "
import json,sys
try:
    subs=json.load(sys.stdin)
    print(any(s.get('callbackUrl')=='$CALLBACK' and s.get('active') for s in subs))
except Exception:
    print('PARSE_FAILED')")"
  case "$HAS" in
    True|False) ;;
    *) die "could not list v2 webhooks (unparseable response); refusing to guess: $EXISTING" ;;
  esac
  if [ "$HAS" = "False" ]; then
    WEBHOOK_SECRET="$(curl -s -A "$UA" -X POST "$TSANET_SDK1_API_URL/v2/webhooks" \
      -H "Authorization: Bearer $TSANET_TOKEN" -H 'content-type: application/json' \
      -d "{\"callbackUrl\":\"$CALLBACK\"}" \
      | python3 -c "import json,sys; print(json.load(sys.stdin)['secret'])")"
    echo "   registered new subscription (secret captured)"
  else
    echo "   subscription for $CALLBACK already active — keeping its secret"
  fi
else
  echo "   no FQDN yet (first run): re-run after this deploy to register the webhook"
fi
# When no new secret was minted this run, keep whatever the app already holds.
if [ -z "$WEBHOOK_SECRET" ]; then
  WEBHOOK_SECRET="$(az containerapp secret list -g "$RG" -n "$APP" --show-values \
    --query "[?name=='tsanet-webhook-secret'].value | [0]" -o tsv 2>/dev/null || true)"
fi

# ---- render the app spec and apply ------------------------------------------
echo "== rendering app spec and applying (ARM upsert)"
ENVIRONMENT_ID="$(az containerapp env show -g "$RG" -n "$ACA_ENV" --query id -o tsv)"
ACR_PASSWORD="$(az acr credential show -n "$ACR" --query "passwords[0].value" -o tsv)"
SPEC="$(mktemp)"; chmod 600 "$SPEC"
trap 'rm -f "$SPEC"' EXIT
ENVIRONMENT_ID="$ENVIRONMENT_ID" \
LOCATION="$LOC" APP_NAME="$APP" \
IMAGE="$ACR.azurecr.io/$IMAGE_TAG" \
ACR_SERVER="$ACR.azurecr.io" ACR_USER="$ACR" ACR_PASSWORD="$ACR_PASSWORD" \
TSANET_API_URL="$TSANET_SDK1_API_URL" TSANET_API_USER="$TSANET_SDK1_API_USER" \
TSANET_PASSWORD="$TSANET_SDK1_API_PASSWORD" \
INTERCOM_TOKEN="$INTERCOM_DEV_ACCESS_TOKEN" \
INTERCOM_SECRET="$INTERCOM_DEV_CLIENT_SECRET" \
TSANET_WEBHOOK_SECRET_VALUE="${WEBHOOK_SECRET:-unset}" \
TICKET_TYPE_ID="${INTERCOM_COLLABORATION_TICKET_TYPE_ID:-4561712}" \
RESOLVED_STATE_ID="${INTERCOM_RESOLVED_STATE_ID:-9619799}" \
python3 - "$REPO/deploy/containerapp.yaml.tmpl" > "$SPEC" <<'EOF'
import os, string, sys
sys.stdout.write(string.Template(open(sys.argv[1]).read()).substitute(os.environ))
EOF
# create --yaml chokes on this spec ("System.Boolean at Path $" — a CLI
# serialization quirk, reproduced with both properties-only and full-resource
# shapes), while update --yaml applies the identical file cleanly. So: bootstrap a
# minimal app only when absent, then ALWAYS apply the full spec via update.
if ! az containerapp show -g "$RG" -n "$APP" -o none 2>/dev/null; then
  echo "   app absent — bootstrapping minimal shape first"
  az containerapp create -g "$RG" -n "$APP" --environment "$ACA_ENV" \
    --image "$ACR.azurecr.io/$IMAGE_TAG" --registry-server "$ACR.azurecr.io" \
    --ingress external --target-port 8792 --min-replicas 1 --max-replicas 1 \
    --cpu 0.5 --memory 1Gi -o none
fi
az containerapp update -g "$RG" -n "$APP" --yaml "$SPEC" -o none

FQDN="$(az containerapp show -g "$RG" -n "$APP" --query properties.configuration.ingress.fqdn -o tsv)"
echo
echo "== deployed: https://$FQDN"
echo "== health (always-warm, so this answers immediately):"
curl -s --max-time 60 "https://$FQDN/healthz" || true; echo
echo
echo "If this was a FIRST deploy: re-run once to register the TSANet webhook against"
echo "the new FQDN, then point your Intercom app's canvas/webhook URLs at it"
echo "(docs/AZURE_POC.md step 3)."
