#!/usr/bin/env bash
# Invoked by SSM as root. Only non-secret arguments arrive through Run Command.
set -Eeuo pipefail
umask 077
mode=$1 release=$2 image=$3 region=$4 secret=$5 validator=$6
app=algorhythm-api
previous=$app-previous
pending=$app-pending
trust=/etc/algorhythm/rds-truststore.p12

exec 9>/run/algorhythm-deploy.lock
flock -w 20 9
tmp=$(mktemp -d /run/algorhythm-deploy.XXXXXXXX)
trap 'rm -rf -- "$tmp"' EXIT
export DOCKER_CONFIG="$tmp/docker"
mkdir "$DOCKER_CONFIG"

exists() { docker container inspect --format '{{.Id}}' "$1" >/dev/null 2>&1; }
healthy() {
  local attempt
  for ((attempt=0; attempt<60; attempt++)); do
    if curl -fsS --max-time 5 http://127.0.0.1:8080/actuator/health 2>/dev/null | jq -e '.status == "UP"' >/dev/null; then
      return 0
    fi
    sleep 2
  done
  return 1
}
restore() {
  if exists "$app"; then
    local label
    label=$(docker inspect --format '{{index .Config.Labels "org.algorhythm.release"}}' "$app")
    [[ "$label" == "$release" ]] || { echo 'Refusing to roll back a different release'; return 1; }
    docker rm -f "$app" >/dev/null || return 1
  fi
  if exists "$previous"; then
    docker rename "$previous" "$app" && docker start "$app" >/dev/null && healthy || return 1
    echo 'Previous container restored and healthy'
  else
    echo 'First deployment removed; there was no previous container'
  fi
}
if [[ "$mode" == rollback ]]; then restore; exit; fi
[[ "$mode" == deploy && -s "$trust" && -r "$trust" ]]
# A retry after a host interruption recovers the last known container first.
if ! exists "$app" && exists "$previous"; then
  docker rename "$previous" "$app"
  docker start "$app" >/dev/null
  healthy
fi
if exists "$app" && [[ $(docker inspect --format '{{.State.Running}}' "$app") != true ]]; then
  docker start "$app" >/dev/null
  healthy
fi

aws secretsmanager get-secret-value --region "$region" --secret-id "$secret" \
  --query SecretString --output text | python3 -c "$validator" > "$tmp/app.env"
registry=${image%%/*}
aws ecr get-login-password --region "$region" | docker login --username AWS --password-stdin "$registry" >/dev/null
docker pull "$image" >/dev/null
if exists "$pending"; then docker rm -f "$pending" >/dev/null; fi
docker create --name "$pending" --label "org.algorhythm.release=$release" \
  --restart unless-stopped --security-opt no-new-privileges --cap-drop ALL --read-only \
  --tmpfs /tmp:rw,nosuid,nodev,mode=1777 --log-opt max-size=10m --log-opt max-file=3 \
  --mount "type=bind,source=$trust,target=$trust,readonly" --publish 8080:8080 \
  --env-file "$tmp/app.env" -e SPRING_PROFILES_ACTIVE=aws -e PASSKEY_ENVIRONMENT=production \
  -e DEV_LOGIN_ENABLED=false -e PORT=8080 "$image" >/dev/null

if exists "$app"; then
  # Do not discard a known-good predecessor while the current container is unhealthy.
  healthy
  if exists "$previous"; then docker rm "$previous" >/dev/null; fi
  docker rename "$app" "$previous"
  if ! docker stop --time 25 "$previous" >/dev/null; then
    docker rename "$previous" "$app"
    docker start "$app" >/dev/null
    exit 1
  fi
fi
if ! docker rename "$pending" "$app" || ! docker start "$app" >/dev/null || ! healthy; then
  echo 'Candidate failed local health; restoring predecessor'
  restore
  exit 1
fi
echo 'Candidate healthy on EC2'
