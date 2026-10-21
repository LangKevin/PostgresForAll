#!/usr/bin/env bash
# Installs PostgreSQL with Homebrew on the CI Mac and runs it as a launchd job
# (brew services) on port 5444, with password auth over TCP, a 'scheduleme'
# superuser and a seeded 'scheduleme' database — the same shape as the dev box,
# but a native install, so the app's launchd/Homebrew code paths get exercised.
set -euo pipefail

PGV="${PGV:-16}"
PORT=5444
PASS="ci-scheduleme-pass"

brew install "postgresql@${PGV}"
BIN="$(brew --prefix "postgresql@${PGV}")/bin"
DATA="$(brew --prefix)/var/postgresql@${PGV}"

if [ ! -f "$DATA/PG_VERSION" ]; then
  "$BIN/initdb" --locale=C -E UTF-8 -D "$DATA"
fi

cat >> "$DATA/postgresql.conf" <<CONF
port = ${PORT}
logging_collector = on
log_directory = 'log'
CONF

wait_ready() {
  for _ in $(seq 1 60); do
    "$BIN/pg_isready" -h localhost -p "$PORT" -q && return 0
    sleep 1
  done
  echo "PostgreSQL did not come up on $PORT" >&2
  tail -50 "$DATA"/log/* 2>/dev/null || true
  return 1
}

brew services start "postgresql@${PGV}"
wait_ready

# Local socket stays 'trust' for this setup; create the role and database.
"$BIN/psql" -p "$PORT" -d postgres -v ON_ERROR_STOP=1 \
  -c "CREATE ROLE scheduleme LOGIN SUPERUSER PASSWORD '${PASS}'"
"$BIN/createdb" -p "$PORT" -O scheduleme scheduleme
"$BIN/psql" -p "$PORT" -U scheduleme -d scheduleme -v ON_ERROR_STOP=1 -f "$(dirname "$0")/seed.sql"

# TCP logins must really check the password (tests expect 28P01 on a bad one).
sed -i '' -E 's/^(host[[:space:]].*)trust[[:space:]]*$/\1scram-sha-256/' "$DATA/pg_hba.conf"
brew services restart "postgresql@${PGV}"
wait_ready

echo "--- launchd"
launchctl list | grep -i postgres || true
ls ~/Library/LaunchAgents/ | grep -i postgres || true
echo "--- server"
PGPASSWORD="$PASS" "$BIN/psql" -h localhost -p "$PORT" -U scheduleme -d scheduleme -Atc "select version()"
