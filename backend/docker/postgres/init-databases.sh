#!/bin/sh
set -eu

echo "SportHub: initializing service databases"

psql -v ON_ERROR_STOP=1 -v db_owner="$POSTGRES_USER" \
    --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE DATABASE identity_db WITH OWNER = :"db_owner" ENCODING = 'UTF8';
    CREATE DATABASE facility_db WITH OWNER = :"db_owner" ENCODING = 'UTF8';
    CREATE DATABASE schedule_db WITH OWNER = :"db_owner" ENCODING = 'UTF8';
    CREATE DATABASE booking_db WITH OWNER = :"db_owner" ENCODING = 'UTF8';
    CREATE DATABASE payment_db WITH OWNER = :"db_owner" ENCODING = 'UTF8';
    CREATE DATABASE transfer_db WITH OWNER = :"db_owner" ENCODING = 'UTF8';

    GRANT ALL PRIVILEGES ON DATABASE identity_db TO :"db_owner";
    GRANT ALL PRIVILEGES ON DATABASE facility_db TO :"db_owner";
    GRANT ALL PRIVILEGES ON DATABASE schedule_db TO :"db_owner";
    GRANT ALL PRIVILEGES ON DATABASE booking_db TO :"db_owner";
    GRANT ALL PRIVILEGES ON DATABASE payment_db TO :"db_owner";
    GRANT ALL PRIVILEGES ON DATABASE transfer_db TO :"db_owner";
EOSQL

echo "SportHub: identity_db, facility_db, schedule_db, booking_db, payment_db and transfer_db created"
