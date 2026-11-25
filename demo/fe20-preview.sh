#!/bin/sh
set -eu

# This disposable image must only start inside the dedicated Railway environment.
[ "${RAILWAY_ENVIRONMENT_NAME:-}" = fe20-qa ] || exit 1
[ "${PASSKEY_ENVIRONMENT:-}" = fe20-preview ] || exit 1
# School42 is one transaction. A committed school marks a fully loaded fixture.
# Skip the import on restarts so changes made during QA stay in place.
data_sources=file:./demo/seed.sql
qa_sql() {
  MYSQL_PWD="$SPRING_DATASOURCE_PASSWORD" mysql --protocol=TCP --connect-timeout=5 \
    -h fe20-mysql.railway.internal -u "$SPRING_DATASOURCE_USERNAME" \
    -D algorhythm_demo -N -B -e "$1"
}
for attempt in 1 2 3 4 5 6 7 8 9 10 11 12; do
  if qa_sql 'SELECT 1' >/dev/null 2>&1; then break; fi
  sleep 3
done
has_school_table=$(qa_sql "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='school'")
has_fixture=0
if [ "$has_school_table" != 0 ]; then
  has_fixture=$(qa_sql 'SELECT COUNT(*) FROM school WHERE id=540001')
fi
if [ "$has_fixture" = 0 ]; then
  data_sources=$data_sources,file:./demo/school42.sql
fi

redis-server --bind 127.0.0.1 --port 6379 --save '' --appendonly no --dir /tmp &
redis_pid=$!
trap 'kill "$redis_pid" 2>/dev/null || true' EXIT
# Passkey flows consume one-time state with GETDEL. Check it before exposing the API.
for attempt in 1 2 3 4 5; do
  if redis-cli PING >/dev/null 2>&1; then break; fi
  sleep 1
done
redis-cli SET fe20:startup:atomic-read ready EX 10 >/dev/null
if [ "$(redis-cli --raw GETDEL fe20:startup:atomic-read)" != ready ]; then
  echo 'FE20 preview Redis must support GETDEL' >&2
  exit 1
fi
# The public service never accepts development tokens. Redis stays on loopback.
timeout --kill-after=15s 60m java -XX:MaxRAMPercentage=65 -XX:ActiveProcessorCount=1 -jar app.jar \
  --spring.config.additional-location=file:./demo/local.yml \
  --spring.sql.init.data-locations="$data_sources" \
  --app.passkeys.environment=fe20-preview --app.auth.dev-login-enabled=false \
  --server.address=0.0.0.0 --springdoc.swagger-ui.enabled=false --springdoc.api-docs.enabled=false \
  --management.endpoint.health.show-details=never
