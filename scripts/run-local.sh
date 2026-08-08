#!/usr/bin/env bash
# Start the connector locally, in sdk mode, on the durable link store.
#
# WHY THIS EXISTS: every setting below has a default that is WRONG for a real
# run and SILENT when it is wrong.
#
#   - connector.links.store defaults to `memory`. A run without it looks
#     completely healthy and loses every token->ticket link on exit, so a
#     partner response to an open case afterwards is unroutable. That is not
#     hypothetical: it is how four live BETA cases ended up orphaned and had to
#     be backfilled by hand on 2026-08-07.
#   - the deployable is connect-gateway's exec jar, NOT runtime-core's.
#     runtime-core carries no com.tsanet.* classes by rule, so the SDK gateway
#     is physically absent from it and `mode=sdk` finds no TsanetGateway bean.
#   - connector.inbound.collaboration-ticket-type-id has no default on purpose;
#     unset means inbound partner cases are REFUSED rather than created as the
#     wrong ticket type.
#
# Everything here is fail-loud. A missing value stops the script rather than
# starting a connector that appears to work.
set -euo pipefail

cd "$(dirname "$0")/.."
REPO="$PWD"
PORT="${PORT:-8791}"
JAR="connect-gateway/target/connect-gateway-0.1.0-SNAPSHOT-exec.jar"
: "${JAVA_HOME:=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"

die() { echo "FATAL: $*" >&2; exit 1; }

[ -x "$JAVA_HOME/bin/java" ] || die "no JDK at JAVA_HOME=$JAVA_HOME (bare \`java\` is not on PATH on this box)"

# Refuse rather than race an instance that is already serving the tunnel.
if lsof -nP -iTCP:"$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  die "port $PORT is already in use — stop the running connector first (lsof -nP -iTCP:$PORT -sTCP:LISTEN)"
fi

[ -f "$JAR" ] || die "$JAR not built. Run:
  JAVA_HOME=$JAVA_HOME mvn -Psdk -DskipTests package"

# The jar must actually contain the SDK gateway. Checking the FILE rather than
# trusting the path guards the exact substitution that breaks the container
# image: a runtime-core jar copied under a connect-gateway name starts cleanly
# and then cannot reach TSANet at all.
# Listing captured to a variable rather than piped into `grep -q`: under
# `set -o pipefail`, grep -q exits on the first match, unzip takes SIGPIPE and
# exits non-zero, and the pipeline reports FAILURE on the jar that is correct.
# That false positive was real — it rejected the good jar on first run.
jar_listing="$(unzip -l "$JAR" 2>/dev/null || true)"
case "$jar_listing" in
  *SdkTsanetGateway*) ;;
  *) die "$JAR contains no SdkTsanetGateway — that is the runtime-core jar, which cannot run mode=sdk" ;;
esac

ENV_FILE="${ENV_FILE:-/Users/shawnray/code/tsanet-ops/.env}"
[ -f "$ENV_FILE" ] || die "no .env at $ENV_FILE (set ENV_FILE=... to override)"
# `set -u` is suspended across the source deliberately: .env holds values
# containing literal `$` sequences (a `$2` on one line), and under nounset the
# shell expands them as unset positional parameters and aborts. The failure
# names a line number in a secrets file, which is a confusing place to be sent.
set +u
set -a; . "$ENV_FILE"; set +a
set -u

need() { [ -n "${!1:-}" ] || die "$1 is empty in $ENV_FILE"; }
need TSANET_SDK1_API_URL; need TSANET_SDK1_API_USER; need TSANET_SDK1_API_PASSWORD
need INTERCOM_DEV_ACCESS_TOKEN; need INTERCOM_DEV_CLIENT_SECRET

# The member the connector acts as. sdk1/sdk2 are the BETA test companies.
export TSANET_API_URL="$TSANET_SDK1_API_URL"
export TSANET_API_USER="$TSANET_SDK1_API_USER"
export TSANET_API_PASSWORD="$TSANET_SDK1_API_PASSWORD"
export INTERCOM_ACCESS_TOKEN="$INTERCOM_DEV_ACCESS_TOKEN"

# The Back-office ticket type carrying the TSANet attributes, in the dev
# workspace. Per-workspace: the pilot's id will differ.
export INTERCOM_COLLABORATION_TICKET_TYPE_ID="${INTERCOM_COLLABORATION_TICKET_TYPE_ID:-4561712}"

# The ticket state a partner-closed case moves to (docs/decisions.md D17/D18).
# Also per-workspace, and unlike most ids it is not conveniently discoverable: state
# ids are only enumerable under `Intercom-Version: Unstable`, so they are resolved out
# of band. Defaulted here for the SAME reason the ticket type is — unset means inbound
# closure REFUSES every event and leaves tickets open, logging closure_unconfigured,
# which is precisely the quiet-wrong-default this script exists to stop.
# Dev workspace, type 4561712: Submitted 9619796, In progress 9619797,
# Waiting on customer 9619798, Resolved 9619799.
export INTERCOM_RESOLVED_STATE_ID="${INTERCOM_RESOLVED_STATE_ID:-9619799}"

# THE POINT OF THIS SCRIPT. Absolute path, because a relative one resolves
# against the working directory — which in a container is the overlay
# filesystem the durable store exists to avoid (docs/decisions.md D12).
export CONNECTOR_LINKS_STORE=sqlite
export CONNECTOR_LINKS_SQLITE_PATH="${CONNECTOR_LINKS_SQLITE_PATH:-$REPO/data/links.db}"
case "$CONNECTOR_LINKS_SQLITE_PATH" in
  /*) ;;
  *) die "CONNECTOR_LINKS_SQLITE_PATH must be absolute, got '$CONNECTOR_LINKS_SQLITE_PATH'" ;;
esac
mkdir -p "$(dirname "$CONNECTOR_LINKS_SQLITE_PATH")"

LOG="${LOG:-$HOME/Library/Logs/tsanet-ops/fin-gateway.log}"
mkdir -p "$(dirname "$LOG")"

echo "connector    : $JAR"
echo "port         : $PORT"
echo "link store   : sqlite at $CONNECTOR_LINKS_SQLITE_PATH"
echo "ticket type  : $INTERCOM_COLLABORATION_TICKET_TYPE_ID"
echo "resolved st. : $INTERCOM_RESOLVED_STATE_ID"
echo "log          : $LOG"
echo

exec "$JAVA_HOME/bin/java" -jar "$JAR" \
  --connector.tsanet.mode=sdk \
  --connector.intercom.mode=rest \
  --server.port="$PORT" \
  >>"$LOG" 2>&1
