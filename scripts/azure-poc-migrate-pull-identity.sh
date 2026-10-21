#!/usr/bin/env bash
# One-time step of #55: retire the registry's admin user once the app pulls its image
# as the user-assigned identity.
#
# Order, which is the point of this script:
#   1. scripts/azure-poc-deploy.sh has run with the identity-pull spec: the app's
#      registry entry names the identity, no password secret remains, and the identity
#      holds AcrPull on the registry. This script checks all three and changes nothing
#      if any is missing.
#   2. Refuse while any other container app still pulls from this registry with the
#      admin credential: the admin user is registry-wide, and each of them would fail
#      at its next container start.
#   3. Prove a pull as the identity: restart the active revision and wait for it to be
#      healthy. Container Apps pulls the image on every container start (pull policy
#      always), so a healthy restart under identity-only registry config is a pull that
#      worked without the admin credential. The restart is a short outage of the single
#      replica; inbound pushes are retried by TSANet and the reconciliation poller
#      covers the gap. --no-restart skips it when a revision has already started since
#      the switch.
#   4. Disable the admin user and read it back.
#
# Idempotent: on a registry whose admin user is already off it re-checks and exits 0.
# CI's push identity (OIDC, image.yml) does not use the admin user and is unaffected.
#
# Usage: scripts/azure-poc-migrate-pull-identity.sh [--no-restart]
set -euo pipefail

RG="${AZ_RG:-tsanet-fin-poc}"
ACR="${AZ_ACR:-tsanetfinpoc}"
APP="${AZ_APP:-fin-gateway-poc}"
PULL_IDENTITY="${AZ_PULL_IDENTITY:-fin-gateway-poc-pull}"
RESTART=1
case "${1:-}" in
  "") ;;
  --no-restart) RESTART=0 ;;
  *) echo "usage: $0 [--no-restart]" >&2; exit 2 ;;
esac

die() { echo "FATAL: $*" >&2; exit 1; }

command -v az >/dev/null || die "az CLI not installed"
az account show >/dev/null 2>&1 || die "not logged in — run: az login"

echo "== checking that the app pulls as $PULL_IDENTITY"
ACR_ID="$(az acr show -n "$ACR" --query id -o tsv)"
PULL_IDENTITY_ID="$(az identity show -g "$RG" -n "$PULL_IDENTITY" --query id -o tsv 2>/dev/null || true)"
[ -n "$PULL_IDENTITY_ID" ] || die "identity $PULL_IDENTITY not found — run scripts/azure-poc-deploy.sh first"
PULL_PRINCIPAL_ID="$(az identity show -g "$RG" -n "$PULL_IDENTITY" --query principalId -o tsv)"
HAS_PULL="$(az role assignment list --scope "$ACR_ID" \
  --query "length([?principalId=='$PULL_PRINCIPAL_ID' && roleDefinitionName=='AcrPull'])" -o tsv)"
[ "$HAS_PULL" != "0" ] || die "$PULL_IDENTITY has no AcrPull on $ACR — run scripts/azure-poc-deploy.sh first"

# Every registry entry for this ACR must name the identity and carry no credential.
REG_CHECK="$(az containerapp show -g "$RG" -n "$APP" -o json | python3 -c "
import json,sys
app=json.load(sys.stdin); want=sys.argv[1].lower(); server=sys.argv[2]
regs=[r for r in (app['properties']['configuration'].get('registries') or []) if r.get('server')==server]
if not regs: print('no registry entry for '+server); sys.exit()
for r in regs:
    if (r.get('identity') or '').lower()!=want: print('registry entry identity is '+repr(r.get('identity'))); sys.exit()
    if r.get('username') or r.get('passwordSecretRef'): print('registry entry still carries a username/password'); sys.exit()
print('ok')" "$PULL_IDENTITY_ID" "$ACR.azurecr.io")"
[ "$REG_CHECK" = "ok" ] || die "$REG_CHECK — run scripts/azure-poc-deploy.sh first"
SECRETS="$(az containerapp secret list -g "$RG" -n "$APP" --query "[].name" -o tsv)"
case $'\n'"$SECRETS"$'\n' in
  *$'\n'acr-password$'\n'*) die "the app still holds the acr-password secret — run scripts/azure-poc-deploy.sh first" ;;
esac
echo "   ok: registry entry uses the identity, no password secret, AcrPull granted"

wait_ready() {
  local rev="$1" state health
  for _ in $(seq 1 60); do
    state="$(az containerapp revision show -g "$RG" -n "$APP" --revision "$rev" \
      --query properties.runningState -o tsv)"
    health="$(az containerapp revision show -g "$RG" -n "$APP" --revision "$rev" \
      --query properties.healthState -o tsv)"
    case "$state" in
      Running|RunningAtMaxScale) [ "$health" = "Healthy" ] && return 0 ;;
      Failed|Degraded) return 1 ;;
    esac
    sleep 10
  done
  return 1
}

# The admin user belongs to the registry, not to this app: any other container app or
# job that still pulls from it with a username/password would fail at its next start
# (pull policy always). Refuse while one exists, and name them. Scope: Container Apps
# and Container Apps jobs in this subscription. Other kinds of consumer (ACI, App
# Service, AKS pull secrets, a docker login with the admin password) are not scanned;
# none existed on 2026-10-21.
echo "== checking that no other app or job still pulls from $ACR with the admin credential"
OTHERS="$( { az containerapp list -o json; az containerapp job list -o json; } | python3 -c "
import json,sys
server=sys.argv[1]
dec=json.JSONDecoder(); text=sys.stdin.read().strip(); i=0
while i < len(text):
    items,i=dec.raw_decode(text,i)
    while i < len(text) and text[i].isspace(): i+=1
    for a in items:
        for r in (a.get('properties',{}).get('configuration',{}).get('registries') or []):
            if r.get('server')==server and (r.get('username') or r.get('passwordSecretRef')):
                print(a['resourceGroup']+'/'+a['name'])" "$ACR.azurecr.io")"
[ -z "$OTHERS" ] || die "these container apps still pull from $ACR with the admin credential; move them to an identity first, or disabling it breaks them at their next start. The admin user stays ENABLED:
$OTHERS"
echo "   none"

REV="$(az containerapp show -g "$RG" -n "$APP" --query properties.latestReadyRevisionName -o tsv)"
[ -n "$REV" ] || die "the app has no ready revision"
replicas() {
  az containerapp replica list -g "$RG" -n "$APP" --revision "$REV" --query "[].name" -o tsv | sort
}
if [ "$RESTART" = 1 ]; then
  echo "== proving a pull as the identity: restarting $REV (short outage of the single replica)"
  BEFORE="$(replicas)"
  az containerapp revision restart -g "$RG" -n "$APP" --revision "$REV" -o none
  # The restart call can return before the replica cycles, and a health read taken
  # then is the OLD replica's. Anchor on a replica that did not exist before.
  NEW=""
  for _ in $(seq 1 60); do
    NOW="$(replicas)"
    if [ -n "$NOW" ] && ! printf '%s\n' "$NOW" | grep -qxF -f <(printf '%s\n' "$BEFORE"); then
      NEW="$NOW"; break
    fi
    sleep 10
  done
  [ -n "$NEW" ] || die "no new replica of $REV appeared after the restart — the admin user stays ENABLED"
  wait_ready "$REV" || die "$REV did not come back healthy after the restart — the admin user stays ENABLED. Inspect: az containerapp revision show -g $RG -n $APP --revision $REV"
  echo "   $REV restarted and healthy: the image was pulled as $PULL_IDENTITY"
else
  echo "== --no-restart: trusting that $REV started after the switch to identity pulls"
  wait_ready "$REV" || die "$REV is not healthy — the admin user stays ENABLED"
fi

echo "== disabling the registry admin user"
if [ "$(az acr show -n "$ACR" --query adminUserEnabled -o tsv)" = "true" ]; then
  az acr update -n "$ACR" --admin-enabled false -o none
fi
[ "$(az acr show -n "$ACR" --query adminUserEnabled -o tsv)" = "false" ] || die "the admin user is still enabled after the update"
echo "   adminUserEnabled: false"
echo
echo "Done. The admin credential is retired; a re-enable would show in"
echo "'az acr show -n $ACR --query adminUserEnabled'."
