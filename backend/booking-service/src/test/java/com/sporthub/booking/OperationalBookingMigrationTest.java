package com.sporthub.booking;

import static org.assertj.core.api.Assertions.*;

import java.sql.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

class OperationalBookingMigrationTest {
  @Test
  void upgradeAddsCodesWithoutRewritingLegacyHistorySnapshotMoneyOrOwnership() throws Exception {
    database(
        (url, user, password) -> {
          String schema = "operations_upgrade_" + UUID.randomUUID().toString().replace("-", "");
          Flyway.configure()
              .dataSource(url, user, password)
              .schemas(schema)
              .defaultSchema(schema)
              .target("8")
              .load()
              .migrate();
          try (var c = DriverManager.getConnection(url, user, password);
              var s = c.createStatement()) {
            s.execute("SET search_path TO " + schema + ",public");
            s.execute(
                "INSERT INTO"
                    + " slot_reservations(id,court_id,facility_id,holder_id,starts_at,ends_at,expires_at,state,quote)"
                    + " VALUES(gen_random_uuid(),gen_random_uuid(),gen_random_uuid(),gen_random_uuid(),NOW()+interval"
                    + " '2 days',NOW()+interval '2 days 1 hour',NOW()+interval '10"
                    + " minutes','HOLD','{\"legacy\":true}')");
            s.execute(
                "INSERT INTO"
                    + " bookings(id,reservation_id,facility_id,court_id,created_by,customer_id,current_holder_id,starts_at,ends_at,status,source,amount,currency,price_snapshot,hold_expires_at)"
                    + " SELECT"
                    + " gen_random_uuid(),id,facility_id,court_id,holder_id,holder_id,holder_id,starts_at,ends_at,'PENDING','ONLINE',120000.25,'VND',quote,expires_at"
                    + " FROM slot_reservations");
            s.execute(
                "INSERT INTO booking_history(id,booking_id,action,details) SELECT"
                    + " gen_random_uuid(),id,'BOOKING_CREATED','{\"legacy\":true}' FROM bookings");
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
                    "SELECT booking_code,amount,price_snapshot,customer_id=current_holder_id AND"
                        + " current_holder_id=created_by FROM bookings")) {
              r.next();
              assertThat(r.getString(1)).matches("[0-9A-F]{10}");
              assertThat(r.getBigDecimal(2)).isEqualByComparingTo("120000.25");
              assertThat(r.getString(3)).isEqualTo("{\"legacy\": true}");
              assertThat(r.getBoolean(4)).isTrue();
            }
            try (var r = s.executeQuery("SELECT details FROM booking_history")) {
              r.next();
              assertThat(r.getString(1)).isEqualTo("{\"legacy\": true}");
            }
            try (var r =
                s.executeQuery(
                    "SELECT (SELECT count(*) FROM booking_status_audit),(SELECT count(*) FROM"
                        + " counter_booking_guests)")) {
              r.next();
              assertThat(r.getInt(1)).isZero();
              assertThat(r.getInt(2)).isZero();
            }
            assertThatThrownBy(() -> s.execute("UPDATE booking_history SET details='{}'"))
                .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> s.execute("UPDATE bookings SET booking_code='0000000000'"))
                .isInstanceOf(SQLException.class);
          }
        });
  }

  @Test
  void directSqlTransitionsAreAuditedWithoutInventingAnActorAndRollbackWithPairFailure()
      throws Exception {
    database(
        (url, user, password) -> {
          String schema = "operations_sql_" + UUID.randomUUID().toString().replace("-", "");
          Flyway.configure()
              .dataSource(url, user, password)
              .schemas(schema)
              .defaultSchema(schema)
              .load()
              .migrate();
          try (var c = DriverManager.getConnection(url, user, password);
              var s = c.createStatement()) {
            s.execute("SET search_path TO " + schema + ",public");
            s.execute(
                "INSERT INTO"
                    + " slot_reservations(id,court_id,facility_id,holder_id,starts_at,ends_at,expires_at,state,quote)"
                    + " VALUES(gen_random_uuid(),gen_random_uuid(),gen_random_uuid(),gen_random_uuid(),NOW()+interval"
                    + " '2 days',NOW()+interval '2 days 1 hour',NOW()+interval '10"
                    + " minutes','HOLD','{}')");
            s.execute(
                "INSERT INTO"
                    + " bookings(id,reservation_id,facility_id,court_id,created_by,customer_id,current_holder_id,starts_at,ends_at,status,source,amount,currency,price_snapshot,hold_expires_at)"
                    + " SELECT"
                    + " gen_random_uuid(),id,facility_id,court_id,holder_id,holder_id,holder_id,starts_at,ends_at,'PENDING','ONLINE',100,'VND',quote,expires_at"
                    + " FROM slot_reservations");
            assertThatThrownBy(() -> s.execute("UPDATE bookings SET status='CANCELLED'"))
                .isInstanceOf(SQLException.class);
            try (var r = s.executeQuery("SELECT count(*) FROM booking_status_audit")) {
              r.next();
              assertThat(r.getInt(1)).isEqualTo(1);
            }
            c.setAutoCommit(false);
            s.execute("UPDATE slot_reservations SET state='RELEASED'");
            s.execute("UPDATE bookings SET status='CANCELLED',version=version+1");
            c.commit();
            c.setAutoCommit(true);
            try (var r =
                s.executeQuery(
                    "SELECT previous_status,status,actor_id,change_source FROM booking_status_audit"
                        + " ORDER BY id DESC LIMIT 1")) {
              r.next();
              assertThat(r.getString(1)).isEqualTo("PENDING");
              assertThat(r.getString(2)).isEqualTo("CANCELLED");
              assertThat(r.getObject(3)).isNull();
              assertThat(r.getString(4)).isEqualTo("DATABASE");
            }
            assertThatThrownBy(() -> s.execute("DELETE FROM booking_status_audit"))
                .isInstanceOf(SQLException.class);
          }
        });
  }

  private interface Check {
    void run(String url, String user, String password) throws Exception;
  }

  private void database(Check check) throws Exception {
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
