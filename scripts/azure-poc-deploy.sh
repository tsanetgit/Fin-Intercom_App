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
#   - image pulls authenticate as a user-assigned identity holding AcrPull, never the
#     registry admin credential (#55); scripts/azure-poc-migrate-pull-identity.sh
#     retires the admin user once a deploy has pulled by identity
#
# Idempotent throughout: ARM upserts for every az resource, list-then-create for the
# webhook. Run it again to update the image or config.
#
# Prereqs (docs/AZURE_POC.md): az CLI logged in as a principal that may create role
# assignments on the registry (Owner or User Access Administrator: the AcrPull grant),
# Docker Desktop RUNNING (ACR Tasks are blocked on free/trial subscriptions, so the
# image builds locally), the exec jar built, tsanet-ops .env present.
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
PULL_IDENTITY="${AZ_PULL_IDENTITY:-fin-gateway-poc-pull}"
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
# Since the Fin swap (#42) the engine rides as a nested artifact, so the marker is
# the gateway-engine jar in BOOT-INF/lib, not the SDK gateway class name.
jar_listing="$(unzip -l "$JAR" 2>/dev/null || true)"
case "$jar_listing" in
  *BOOT-INF/lib/gateway-engine-*) ;;
  *) die "$JAR carries no gateway-engine artifact — wrong jar, or a pre-swap build" ;;
esac

ENV_FILE="${ENV_FILE:-/Users/shawnray/code/tsanet-ops/.env}"
[ -f "$ENV_FILE" ] || die "no .env at $ENV_FILE (set ENV_FILE=... to override)"
set +u; set -a; . "$ENV_FILE"; set +a; set -u

need() { [ -n "${!1:-}" ] || die "$1 is empty in $ENV_FILE"; }
need TSANET_SDK1_API_URL   # Connect API base (member-agnostic value); sdk1 naming is historical
need TSANET_CONNECT_GATEWAY_ENTRA_CLIENT_ID; need TSANET_CONNECT_GATEWAY_ENTRA_CLIENT_SECRET
need TSANET_BETA_ENTRA_TENANT_ID; need TSANET_BETA_ENTRA_AUDIENCE; need TSANET_BETA_ENTRA_SCOPE
need INTERCOM_DEV_ACCESS_TOKEN; need INTERCOM_DEV_CLIENT_SECRET

# ---- provision --------------------------------------------------------------
echo "== resource group $RG ($LOC)"
az group create -n "$RG" -l "$LOC" -o none

echo "== registering providers (idempotent)"
for prov in Microsoft.App Microsoft.ContainerRegistry Microsoft.OperationalInsights Microsoft.Storage; do
  az provider register -n "$prov" --wait -o none
done

echo "== container registry $ACR"
# Created without the admin user (#55). An existing registry is left as it is:
# disabling its admin user is a one-time, verified step
# (scripts/azure-poc-migrate-pull-identity.sh), never a side effect of a deploy.
if ! az acr show -n "$ACR" -o none 2>/dev/null; then
  az acr create -g "$RG" -n "$ACR" --sku Basic -o none
fi
ACR_ID="$(az acr show -n "$ACR" --query id -o tsv)"

echo "== image pull identity $PULL_IDENTITY (AcrPull on $ACR, nothing else)"
if ! az identity show -g "$RG" -n "$PULL_IDENTITY" -o none 2>/dev/null; then
  az identity create -g "$RG" -n "$PULL_IDENTITY" -l "$LOC" -o none
fi
PULL_IDENTITY_ID="$(az identity show -g "$RG" -n "$PULL_IDENTITY" --query id -o tsv)"
PULL_PRINCIPAL_ID="$(az identity show -g "$RG" -n "$PULL_IDENTITY" --query principalId -o tsv)"
[ -n "$PULL_IDENTITY_ID" ] && [ -n "$PULL_PRINCIPAL_ID" ] || die "could not resolve identity $PULL_IDENTITY"
# Matched on principalId over the registry's own assignments: filtering by --assignee
# would make the CLI look the identity up in the directory, where a just-created one
# may not be visible yet.
HAS_PULL="$(az role assignment list --scope "$ACR_ID" \
  --query "length([?principalId=='$PULL_PRINCIPAL_ID' && roleDefinitionName=='AcrPull'])" -o tsv)"
if [ "$HAS_PULL" = "0" ]; then
  # Object id plus principal type skips the same lookup on create; the retry covers
  # the window in which the new principal has not reached the authorization service.
  GRANTED=0
  for _ in 1 2 3 4; do
    if GRANT_ERR="$(az role assignment create --assignee-object-id "$PULL_PRINCIPAL_ID" \
        --assignee-principal-type ServicePrincipal --role AcrPull --scope "$ACR_ID" -o none 2>&1)"; then
      GRANTED=1; break
    fi
    # A missing privilege is not a propagation delay: retrying cannot fix it.
    case "$GRANT_ERR" in
      *AuthorizationFailed*|*"does not have authorization"*)
        die "not allowed to grant AcrPull on $ACR: the az login principal needs Owner or User Access Administrator on the registry (only for this first grant). $GRANT_ERR" ;;
    esac
    echo "   AcrPull grant not accepted yet (new identity still propagating); retrying in 15s"
    sleep 15
  done
  [ "$GRANTED" = 1 ] || die "could not grant AcrPull to $PULL_IDENTITY on $ACR"
  # A new grant can take minutes to reach the registry. Give it a head start before
  # the spec below asks the app to pull with it. If it still is not in effect and the
  # update produces a new revision, the revision wait at the end fails the deploy
  # while the previous revision serves.
  echo "   granted AcrPull; waiting ${AZ_ROLE_PROPAGATION_SECONDS:-120}s for it to take effect"
  sleep "${AZ_ROLE_PROPAGATION_SECONDS:-120}"
fi

echo "== building image locally (linux/amd64) and pushing"
az acr login -n "$ACR"
docker build --platform linux/amd64 -t "$ACR.azurecr.io/$IMAGE_TAG" .
docker push "$ACR.azurecr.io/$IMAGE_TAG"

# Pin the spec to the pushed DIGEST, not the tag. ACA rolls a revision only when the
# template changes and never re-resolves a same-tag reference, so a tag-referenced
# spec turns "pushed new code, config unchanged" into a silent no-op deploy (observed
# live 2026-08-09: a rerun pushed digest 40a01e7c while the app kept running the
# previous digest, invisibly). Resolved from ACR, not docker inspect, so the value is
# what the registry will actually serve.
IMAGE_DIGEST="$(az acr repository show -n "$ACR" --image "$IMAGE_TAG" --query digest -o tsv)"
[ -n "$IMAGE_DIGEST" ] || die "could not resolve the pushed digest for $IMAGE_TAG from ACR"
IMAGE_REF="$ACR.azurecr.io/${IMAGE_TAG%%:*}@$IMAGE_DIGEST"
echo "   image pinned: $IMAGE_REF"

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
echo "== TSANet v2 webhook registration (as the Connect Gateway member, idempotent)"
# Entra client-credentials: this member has no password-mode API login, and the
# subscription must belong to the SAME member the adapter authenticates as, or
# inbound pushes are signed with a secret the adapter does not hold.
TSANET_TOKEN="$(curl -s -A "$UA" -X POST \
  "https://login.microsoftonline.com/$TSANET_BETA_ENTRA_TENANT_ID/oauth2/v2.0/token" \
  --data-urlencode "grant_type=client_credentials" \
  --data-urlencode "client_id=$TSANET_CONNECT_GATEWAY_ENTRA_CLIENT_ID" \
  --data-urlencode "client_secret=$TSANET_CONNECT_GATEWAY_ENTRA_CLIENT_SECRET" \
  --data-urlencode "scope=$TSANET_BETA_ENTRA_SCOPE" \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['access_token'])")"
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

# ---- admin plane secret: keep-existing-else-mint ------------------------------
# Same discipline as the webhook secret: the spec below is a FULL update, so any
# secret not re-rendered into it is wiped. Keep the app's current value across
# runs; mint one only when the app has none (first admin-enabled deploy). The
# admin plane fails closed without it, so this line is what turns the plane on.
ADMIN_API_SECRET="${CONNECTOR_ADMIN_API_SECRET_OVERRIDE:-}"
if [ -z "$ADMIN_API_SECRET" ]; then
  ADMIN_API_SECRET="$(az containerapp secret list -g "$RG" -n "$APP" --show-values \
    --query "[?name=='admin-api-secret'].value | [0]" -o tsv 2>/dev/null || true)"
fi
if [ -z "$ADMIN_API_SECRET" ]; then
  command -v openssl >/dev/null || die "openssl not found (needed to mint the admin secret)"
  ADMIN_API_SECRET="$(openssl rand -hex 32)"
  echo "   minted new admin API secret (never printed; retrieve with:"
  echo "   az containerapp secret list -g $RG -n $APP --show-values --query \"[?name=='admin-api-secret'].value | [0]\" -o tsv)"
fi

# ---- render the app spec and apply ------------------------------------------
echo "== rendering app spec and applying (ARM upsert)"
ENVIRONMENT_ID="$(az containerapp env show -g "$RG" -n "$ACA_ENV" --query id -o tsv)"
SPEC="$(mktemp)"; chmod 600 "$SPEC"
trap 'rm -f "$SPEC"' EXIT
ENVIRONMENT_ID="$ENVIRONMENT_ID" \
LOCATION="$LOC" APP_NAME="$APP" \
IMAGE="$IMAGE_REF" \
ACR_SERVER="$ACR.azurecr.io" PULL_IDENTITY_ID="$PULL_IDENTITY_ID" \
TSANET_API_URL="$TSANET_SDK1_API_URL" TSANET_API_USER="$TSANET_CONNECT_GATEWAY_ENTRA_CLIENT_ID" \
TSANET_PASSWORD="$TSANET_CONNECT_GATEWAY_ENTRA_CLIENT_SECRET" \
TSANET_AUTH_MODE="client_credentials" \
TSANET_ENTRA_TENANT_ID="$TSANET_BETA_ENTRA_TENANT_ID" \
TSANET_ENTRA_AUDIENCE="$TSANET_BETA_ENTRA_AUDIENCE" \
INTERCOM_TOKEN="$INTERCOM_DEV_ACCESS_TOKEN" \
INTERCOM_SECRET="$INTERCOM_DEV_CLIENT_SECRET" \
TSANET_WEBHOOK_SECRET_VALUE="${WEBHOOK_SECRET:-unset}" \
ADMIN_API_SECRET_VALUE="$ADMIN_API_SECRET" \
PUBLIC_BASE_URL="${FQDN:+https://$FQDN}" \
TICKET_TYPE_ID="${INTERCOM_COLLABORATION_TICKET_TYPE_ID:-4561712}" \
RESOLVED_STATE_ID="${INTERCOM_RESOLVED_STATE_ID:-9619799}" \
IN_PROGRESS_STATE_ID="${INTERCOM_IN_PROGRESS_STATE_ID:-9619797}" \
WAITING_STATE_ID="${INTERCOM_WAITING_STATE_ID:-9619798}" \
SDK_CACHE_RETENTION="${CONNECTOR_SDK_CACHE_RETENTION:-}" \
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
    --image "$IMAGE_REF" --registry-server "$ACR.azurecr.io" \
    --user-assigned "$PULL_IDENTITY_ID" --registry-identity "$PULL_IDENTITY_ID" \
    --ingress external --target-port 8792 --min-replicas 1 --max-replicas 1 \
    --cpu 0.5 --memory 1Gi -o none
fi
az containerapp update -g "$RG" -n "$APP" --yaml "$SPEC" -o none

# Wait for the revision this update produced, when it produced one: only a template
# change (a new image digest, say) creates a revision; a configuration-only change
# such as the registry entry does not, and then this passes at once. In
# single-revision mode a new revision that cannot start (for example, an image pull
# the identity is not yet allowed to make) never takes traffic, and the previous one
# keeps serving with no other signal, so the deploy fails here and says so instead of
# reporting success. Proving a pull under a configuration-only change is the
# migration script's restart.
echo "== waiting for the latest revision to become ready (up to 10 minutes)"
# By the time this can fail, the update above has already replaced the app-level
# registry config, so the previous revision is a fallback only until its replica next
# starts and pulls under that config. Say so, and point at the recovery step.
WAIT_FAILED_NOTE="The previous revision is still serving for now, but its next restart pulls with the same identity config. Re-run this script in a few minutes (AcrPull propagation), or restore the admin-credential pull: docs/AZURE_POC.md, 'If a deploy fails at the revision wait'."
REV="$(az containerapp show -g "$RG" -n "$APP" --query properties.latestRevisionName -o tsv)"
READY=""
for _ in $(seq 1 60); do
  READY="$(az containerapp show -g "$RG" -n "$APP" --query properties.latestReadyRevisionName -o tsv)"
  [ "$READY" = "$REV" ] && break
  STATE="$(az containerapp revision show -g "$RG" -n "$APP" --revision "$REV" \
    --query properties.provisioningState -o tsv 2>/dev/null || true)"
  [ "$STATE" = "Failed" ] && die "revision $REV failed to provision. $WAIT_FAILED_NOTE Inspect: az containerapp revision show -g $RG -n $APP --revision $REV"
  sleep 10
done
[ "$READY" = "$REV" ] || die "revision $REV was not ready after 10 minutes. $WAIT_FAILED_NOTE"
echo "   ready: $REV"

FQDN="$(az containerapp show -g "$RG" -n "$APP" --query properties.configuration.ingress.fqdn -o tsv)"
echo
echo "== deployed: https://$FQDN"
echo "== health (always-warm, so this answers immediately):"
curl -s --max-time 60 "https://$FQDN/healthz" || true; echo
echo
echo "If this was a FIRST deploy: re-run once to register the TSANet webhook against"
echo "the new FQDN, then point your Intercom app's canvas/webhook URLs at it"
echo "(docs/AZURE_POC.md step 3)."
