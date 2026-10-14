#!/bin/sh
set -eu

echo "SportHub: initializing service databases"

# Also usable via compose exec for an existing volume. Never echo credentials.
export PGPASSWORD="$POSTGRES_PASSWORD"
psql -v ON_ERROR_STOP=1 -v db_owner="$POSTGRES_USER" \
    --username "$POSTGRES_USER" --dbname "${POSTGRES_DB:-postgres}" <<-'EOSQL'
    SELECT format('CREATE DATABASE %I WITH OWNER = %I ENCODING = ''UTF8''', name, :'db_owner')
    FROM (VALUES ('identity_db'), ('facility_db'), ('schedule_db'),
                 ('booking_db'), ('payment_db'), ('transfer_db')) AS service(name)
    WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname = service.name)
    \gexec

    GRANT ALL PRIVILEGES ON DATABASE identity_db TO :"db_owner";
    GRANT ALL PRIVILEGES ON DATABASE facility_db TO :"db_owner";
    GRANT ALL PRIVILEGES ON DATABASE schedule_db TO :"db_owner";
    GRANT ALL PRIVILEGES ON DATABASE booking_db TO :"db_owner";
    GRANT ALL PRIVILEGES ON DATABASE payment_db TO :"db_owner";
    GRANT ALL PRIVILEGES ON DATABASE transfer_db TO :"db_owner";
EOSQL

echo "SportHub: all six service databases are available (existing data preserved)"
