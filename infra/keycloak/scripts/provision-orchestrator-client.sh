#!/usr/bin/env bash
# Idempotently provisions the Keycloak service account of the AI orchestrator (Phase C0).
#
#   client      : ai-orchestrator (confidential, service account only)
#   flows       : client credentials ONLY - no browser login, no password grant, no implicit flow
#   audience    : tokens carry the Core API audience so the Core API can verify them
#   secret      : ORCHESTRATOR_CLIENT_SECRET from infra/docker/.env (never committed, never printed)
#
# It works on a fresh realm and on an existing Keycloak volume (the realm import only runs once).
# Run from the repository root while the Keycloak container is up:
#   bash infra/keycloak/scripts/provision-orchestrator-client.sh
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
env_file="$root/infra/docker/.env"
compose=(docker compose -f "$root/infra/docker/compose.yaml" --env-file "$env_file")

get() { grep -E "^$1=" "$env_file" | head -1 | cut -d= -f2-; }
admin_user="$(get KEYCLOAK_ADMIN_USERNAME)"
admin_password="$(get KEYCLOAK_ADMIN_PASSWORD)"
client_secret="$(get ORCHESTRATOR_CLIENT_SECRET)"
: "${admin_user:?KEYCLOAK_ADMIN_USERNAME missing in infra/docker/.env}"
: "${admin_password:?KEYCLOAK_ADMIN_PASSWORD missing in infra/docker/.env}"
: "${client_secret:?ORCHESTRATOR_CLIENT_SECRET missing: run bash infra/docker/scripts/ensure-env.sh}"

# Secrets travel as environment variables of the exec session, never as command-line arguments.
"${compose[@]}" exec -T \
  -e KC_CLI_USER="$admin_user" -e KC_CLI_PASSWORD="$admin_password" -e ORCH_SECRET="$client_secret" \
  keycloak bash -s <<'REMOTE'
set -euo pipefail
kcadm=/opt/keycloak/bin/kcadm.sh
realm=ai-workforce
$kcadm config credentials --server http://localhost:8080 --realm master --user "$KC_CLI_USER" --password "$KC_CLI_PASSWORD" >/dev/null

id="$($kcadm get clients -r "$realm" -q clientId=ai-orchestrator --fields id --format csv --noquotes | head -1)"
settings=(
  -s clientId=ai-orchestrator
  -s name="AI Orchestrator (service account)"
  -s enabled=true
  -s publicClient=false
  -s serviceAccountsEnabled=true
  -s standardFlowEnabled=false
  -s implicitFlowEnabled=false
  -s directAccessGrantsEnabled=false
  -s clientAuthenticatorType=client-secret
  -s "secret=$ORCH_SECRET"
  -s 'attributes."access.token.lifespan"=300'
)
if [ -z "$id" ]; then
  id="$($kcadm create clients -r "$realm" "${settings[@]}" -i)"
  echo "Created client ai-orchestrator"
else
  $kcadm update "clients/$id" -r "$realm" "${settings[@]}"
  echo "Updated client ai-orchestrator"
fi

# Audience of the Core API (the token is presented to it); replace the mapper to stay idempotent.
mapper="$($kcadm get "clients/$id/protocol-mappers/models" -r "$realm" --fields id,name --format csv --noquotes | grep ',core-api-audience$' | cut -d, -f1 || true)"
[ -n "$mapper" ] && $kcadm delete "clients/$id/protocol-mappers/models/$mapper" -r "$realm"
$kcadm create "clients/$id/protocol-mappers/models" -r "$realm" \
  -s name=core-api-audience -s protocol=openid-connect -s protocolMapper=oidc-audience-mapper \
  -s 'config."included.custom.audience"=ai-workforce-api' \
  -s 'config."access.token.claim"=true' -s 'config."id.token.claim"=false' >/dev/null
echo "Audience mapper ai-workforce-api in place"
REMOTE
echo "Keycloak service account is provisioned. The client secret was not printed."
