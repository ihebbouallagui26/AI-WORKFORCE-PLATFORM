#!/usr/bin/env bash
# Idempotently provisions the two Keycloak service accounts of the AI execution flow (C0 + C1).
#
#   ai-orchestrator       orchestrator -> Core API   (audience: ai-workforce-api)
#                         secret: ORCHESTRATOR_CLIENT_SECRET
#   ai-workforce-core-api Core API -> orchestrator   (audience: ai-orchestrator)
#                         secret: CORE_API_SERVICE_CLIENT_SECRET
#
# Both are confidential, client-credentials ONLY (no browser login, no password grant, no implicit flow),
# one per direction, so neither service can act as the other. Secrets come from infra/docker/.env
# (never committed, never printed). Works on a fresh realm and on an existing Keycloak volume (the realm
# import only runs once and the realm file must contain no secret).
# Run from the repository root while the Keycloak container is up:
#   bash infra/keycloak/scripts/provision-orchestrator-client.sh
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
env_file="$root/infra/docker/.env"
compose=(docker compose -f "$root/infra/docker/compose.yaml" --env-file "$env_file")

get() { grep -E "^$1=" "$env_file" | head -1 | cut -d= -f2-; }
admin_user="$(get KEYCLOAK_ADMIN_USERNAME)"
admin_password="$(get KEYCLOAK_ADMIN_PASSWORD)"
orchestrator_secret="$(get ORCHESTRATOR_CLIENT_SECRET)"
core_api_secret="$(get CORE_API_SERVICE_CLIENT_SECRET)"
: "${admin_user:?KEYCLOAK_ADMIN_USERNAME missing in infra/docker/.env}"
: "${admin_password:?KEYCLOAK_ADMIN_PASSWORD missing in infra/docker/.env}"
: "${orchestrator_secret:?ORCHESTRATOR_CLIENT_SECRET missing: run bash infra/docker/scripts/ensure-env.sh}"
: "${core_api_secret:?CORE_API_SERVICE_CLIENT_SECRET missing: run bash infra/docker/scripts/ensure-env.sh}"

# Secrets travel as environment variables of the exec session, never as command-line arguments.
"${compose[@]}" exec -T \
  -e KC_CLI_USER="$admin_user" -e KC_CLI_PASSWORD="$admin_password" \
  -e ORCH_SECRET="$orchestrator_secret" -e CORE_SECRET="$core_api_secret" \
  keycloak bash -s <<'REMOTE'
set -euo pipefail
kcadm=/opt/keycloak/bin/kcadm.sh
realm=ai-workforce
$kcadm config credentials --server http://localhost:8080 --realm master --user "$KC_CLI_USER" --password "$KC_CLI_PASSWORD" >/dev/null

# provision_client <clientId> <display name> <secret> <audience>
provision_client() {
  local client_id="$1" display="$2" secret="$3" audience="$4" id
  id="$($kcadm get clients -r "$realm" -q "clientId=$client_id" --fields id --format csv --noquotes | head -1)"
  local settings=(
    -s "clientId=$client_id"
    -s "name=$display"
    -s enabled=true
    -s publicClient=false
    -s serviceAccountsEnabled=true
    -s standardFlowEnabled=false
    -s implicitFlowEnabled=false
    -s directAccessGrantsEnabled=false
    -s clientAuthenticatorType=client-secret
    -s "secret=$secret"
    -s 'attributes."access.token.lifespan"=300'
  )
  if [ -z "$id" ]; then
    id="$($kcadm create clients -r "$realm" "${settings[@]}" -i)"
    echo "Created client $client_id"
  else
    $kcadm update "clients/$id" -r "$realm" "${settings[@]}"
    echo "Updated client $client_id"
  fi
  # The audience the token is presented to; replace the mapper to stay idempotent.
  local mapper
  mapper="$($kcadm get "clients/$id/protocol-mappers/models" -r "$realm" --fields id,name --format csv --noquotes | grep ',target-audience$' | cut -d, -f1 || true)"
  [ -n "$mapper" ] && $kcadm delete "clients/$id/protocol-mappers/models/$mapper" -r "$realm"
  $kcadm create "clients/$id/protocol-mappers/models" -r "$realm" \
    -s name=target-audience -s protocol=openid-connect -s protocolMapper=oidc-audience-mapper \
    -s "config.\"included.custom.audience\"=$audience" \
    -s 'config."access.token.claim"=true' -s 'config."id.token.claim"=false' >/dev/null
  echo "  audience mapper: $audience"
}

# Remove the C0 mapper name, replaced by the generic one (idempotent clean-up).
old="$($kcadm get clients -r "$realm" -q clientId=ai-orchestrator --fields id --format csv --noquotes | head -1)"
if [ -n "$old" ]; then
  legacy="$($kcadm get "clients/$old/protocol-mappers/models" -r "$realm" --fields id,name --format csv --noquotes | grep ',core-api-audience$' | cut -d, -f1 || true)"
  [ -n "$legacy" ] && $kcadm delete "clients/$old/protocol-mappers/models/$legacy" -r "$realm"
fi

provision_client ai-orchestrator "AI Orchestrator (service account)" "$ORCH_SECRET" ai-workforce-api
provision_client ai-workforce-core-api "Core API (service account to orchestrator)" "$CORE_SECRET" ai-orchestrator
REMOTE
echo "Keycloak service accounts are provisioned. No client secret was printed."
