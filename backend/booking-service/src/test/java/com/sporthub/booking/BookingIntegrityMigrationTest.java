package com.sporthub.booking;

import static org.assertj.core.api.Assertions.*;

import java.sql.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

class BookingIntegrityMigrationTest {
  @Test
  void upgradePreservesLegacySnapshotDeadlineIdempotencyAndAddsLocalConstraints() throws Exception {
    withDatabase(
        (url, user, password) -> {
          String schema = "booking_integrity_" + UUID.randomUUID().toString().replace("-", "");
          migrate(url, user, password, schema, "5");
          try (var c = DriverManager.getConnection(url, user, password);
              var s = c.createStatement()) {
            s.execute("SET search_path TO " + schema + ",public");
            seed(s, false);
            s.execute(
                "INSERT INTO request_idempotency(actor_id,operation,key,fingerprint,resource_id)"
                    + " SELECT created_by,'BOOKING','legacy-key','"
                    + "0".repeat(64)
                    + "',id FROM bookings");
            var latest =
                Flyway.configure()
                    .dataSource(url, user, password)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .load();
            latest.migrate();
            latest.validate();
            assertThat(latest.info().current().getVersion().toString()).isEqualTo("21");
            try (var r =
                s.executeQuery(
                    "SELECT amount,price_snapshot->>'marker',b.hold_expires_at=r.expires_at FROM"
                        + " bookings b JOIN slot_reservations r ON r.id=b.reservation_id")) {
              assertThat(r.next()).isTrue();
              assertThat(r.getBigDecimal(1)).isEqualByComparingTo("120000.25");
              assertThat(r.getString(2)).isEqualTo("legacy");
              assertThat(r.getBoolean(3)).isTrue();
            }
            try (var r =
                s.executeQuery("SELECT count(*) FROM request_idempotency WHERE key='legacy-key'")) {
              r.next();
              assertThat(r.getInt(1)).isEqualTo(1);
            }
            try (var r = s.executeQuery("SELECT count(*) FROM reservation_history")) {
              r.next();
              assertThat(r.getInt(1)).isZero();
            }
            assertThatThrownBy(() -> s.execute("UPDATE bookings SET status='CONFIRMED'"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("commit consistently");
            s.execute(
                "INSERT INTO"
                    + " slot_reservations(id,court_id,facility_id,holder_id,starts_at,ends_at,expires_at,state,quote)"
                    + " VALUES(gen_random_uuid(),gen_random_uuid(),gen_random_uuid(),gen_random_uuid(),NOW()+interval"
                    + " '4 hours',NOW()+interval '5 hours',NOW()+interval '10"
                    + " minutes','HOLD','{}')");
            assertThatThrownBy(
                    () ->
                        s.execute(
                            "INSERT INTO"
                                + " bookings(id,reservation_id,facility_id,court_id,created_by,customer_id,current_holder_id,starts_at,ends_at,status,source,amount,currency,price_snapshot,hold_expires_at)"
                                + " SELECT"
                                + " gen_random_uuid(),id,facility_id,gen_random_uuid(),holder_id,holder_id,holder_id,starts_at,ends_at,'PENDING','ONLINE',100,'VND','{}',expires_at"
                                + " FROM slot_reservations r WHERE NOT EXISTS(SELECT 1 FROM"
                                + " bookings b WHERE b.reservation_id=r.id)"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("fk_booking_reservation_scope");
            try (var r =
                s.executeQuery(
                    "SELECT contype,pg_get_constraintdef(oid) FROM pg_constraint WHERE"
                        + " conrelid='slot_reservations'::regclass AND contype='x'")) {
              assertThat(r.next()).isTrue();
              assertThat(r.getString(2))
                  .contains("EXCLUDE USING gist", "tstzrange", "HOLD", "BOOKED");
            }
          }
        });
  }

  @Test
  void inconsistentLegacyReservationStopsMigrationWithoutRepairingBooking() throws Exception {
    withDatabase(
        (url, user, password) -> {
          String schema = "booking_bad_" + UUID.randomUUID().toString().replace("-", "");
          migrate(url, user, password, schema, "5");
          try (var c = DriverManager.getConnection(url, user, password);
              var s = c.createStatement()) {
            s.execute("SET search_path TO " + schema + ",public");
            seed(s, true);
            assertThatThrownBy(
                    () ->
                        Flyway.configure()
                            .dataSource(url, user, password)
                            .schemas(schema)
                            .defaultSchema(schema)
                            .load()
                            .migrate())
                .hasMessageContaining("Booking/reservation preflight failed");
            try (var r =
                s.executeQuery(
                    "SELECT b.status,r.state,b.amount FROM bookings b JOIN slot_reservations r ON"
                        + " r.id=b.reservation_id")) {
              r.next();
              assertThat(r.getString(1)).isEqualTo("CONFIRMED");
              assertThat(r.getString(2)).isEqualTo("HOLD");
              assertThat(r.getBigDecimal(3)).isEqualByComparingTo("120000.25");
            }
          }
        });
  }

  private void seed(Statement s, boolean inconsistent) throws SQLException {
    s.execute(
        "INSERT INTO"
            + " slot_reservations(id,court_id,facility_id,holder_id,starts_at,ends_at,expires_at,state,quote)"
            + " VALUES(gen_random_uuid(),gen_random_uuid(),gen_random_uuid(),gen_random_uuid(),NOW()+interval"
            + " '2 hours',NOW()+interval '3 hours',NOW()+interval '10"
            + " minutes','HOLD','{\"marker\":\"legacy\"}')");
    s.execute(
        "INSERT INTO"
            + " bookings(id,reservation_id,facility_id,court_id,created_by,customer_id,current_holder_id,starts_at,ends_at,status,source,amount,currency,price_snapshot,hold_expires_at)"
            + " SELECT"
            + " gen_random_uuid(),id,facility_id,court_id,holder_id,holder_id,holder_id,starts_at,ends_at,'"
            + (inconsistent ? "CONFIRMED" : "PENDING")
            + "','ONLINE',120000.25,'VND',quote,expires_at FROM slot_reservations");
  }

  private void migrate(String url, String user, String password, String schema, String target) {
    Flyway.configure()
        .dataSource(url, user, password)
        .schemas(schema)
        .defaultSchema(schema)
        .target(target)
        .load()
        .migrate();
  }

  private interface DatabaseCheck {
    void run(String url, String user, String password) throws Exception;
  }

  private void withDatabase(DatabaseCheck check) throws Exception {
    PostgreSQLContainer<?> pg = null;
    String url = System.getenv("SPORTHUB_BOOKING_TEST_DB_URL"),
        user = System.getenv("SPORTHUB_TEST_DB_USER"),
        password = System.getenv("SPORTHUB_TEST_DB_PASSWORD");
    if (url == null) {
      pg =
          new PostgreSQLContainer<>("postgres:16-alpine")
              .withInitScript("booking-test-prerequisites.sql");
      pg.start();
      url = pg.getJdbcUrl();
      user = pg.getUsername();
      password = pg.getPassword();
    }
    try {
      check.run(url, user, password);
    } finally {
      if (pg != null) pg.stop();
    }
  }
}
