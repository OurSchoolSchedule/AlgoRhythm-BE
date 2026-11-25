#!/bin/sh
set -eu

# This disposable image must only start inside the dedicated Railway environment.
[ "${RAILWAY_ENVIRONMENT_NAME:-}" = fe20-qa ] || exit 1
[ "${PASSKEY_ENVIRONMENT:-}" = fe20-preview ] || exit 1
redis-server --bind 127.0.0.1 --port 6379 --save '' --appendonly no --dir /tmp &
redis_pid=$!
trap 'kill "$redis_pid" 2>/dev/null || true' EXIT
# The public service never accepts development tokens. Redis stays on loopback.
timeout --kill-after=15s 60m java -XX:MaxRAMPercentage=65 -XX:ActiveProcessorCount=1 -jar app.jar \
  --spring.config.additional-location=file:./demo/local.yml \
  --spring.sql.init.data-locations=file:./demo/seed.sql,file:./demo/school42.sql \
  --app.passkeys.environment=fe20-preview --app.auth.dev-login-enabled=false \
  --server.address=0.0.0.0 --springdoc.swagger-ui.enabled=false --springdoc.api-docs.enabled=false \
  --management.endpoint.health.show-details=never
