package com.sporthub.booking;

import static org.assertj.core.api.Assertions.*;

import java.sql.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

class CounterBookingCreationMigrationTest {
  @Test
  void structuralPreflightRefusesInconsistentLegacyReservationsWithoutRepair() throws Exception {
    database(
        (url, user, password) -> {
          String schema = "counter_structure_" + UUID.randomUUID().toString().replace("-", "");
          Flyway.configure()
              .dataSource(url, user, password)
              .schemas(schema)
              .defaultSchema(schema)
              .target("10")
              .load()
              .migrate();
          try (var c = DriverManager.getConnection(url, user, password);
              var s = c.createStatement()) {
            s.execute("SET search_path TO " + schema + ",public");
            UUID operator = UUID.randomUUID(), wrongHolder = UUID.randomUUID();
            UUID id = insert(c, operator, null, operator, true);
            try (var p =
                c.prepareStatement(
                    "UPDATE slot_reservations SET holder_id=? WHERE id=(SELECT reservation_id FROM"
                        + " bookings WHERE id=?)")) {
              p.setObject(1, wrongHolder);
              p.setObject(2, id);
              p.executeUpdate();
            }
            assertThatThrownBy(
                    () ->
                        Flyway.configure()
                            .dataSource(url, user, password)
                            .schemas(schema)
                            .defaultSchema(schema)
                            .load()
                            .migrate())
                .hasMessageContaining("structural preflight failed");
            try (var p =
                c.prepareStatement(
                    "SELECT b.current_holder_id,r.holder_id FROM bookings b JOIN slot_reservations"
                        + " r ON r.id=b.reservation_id WHERE b.id=?")) {
              p.setObject(1, id);
              try (var r = p.executeQuery()) {
                r.next();
                assertThat(r.getObject(1, UUID.class)).isEqualTo(operator);
                assertThat(r.getObject(2, UUID.class)).isEqualTo(wrongHolder);
              }
            }
          }
        });
  }

  @Test
  void v12PreservesLegacyButRejectsNewStaffFallbackAndMissingSnapshot() throws Exception {
    database(
        (url, user, password) -> {
          String schema = "counter_insert_" + UUID.randomUUID().toString().replace("-", "");
          Flyway.configure()
              .dataSource(url, user, password)
              .schemas(schema)
              .defaultSchema(schema)
              .target("11")
              .load()
              .migrate();
          try (var c = DriverManager.getConnection(url, user, password);
              var s = c.createStatement()) {
            s.execute("SET search_path TO " + schema + ",public");
            UUID operator = UUID.randomUUID();
            UUID legacy = insert(c, operator, null, operator, true);
            UUID noSnapshot = insert(c, operator, null, operator, false);
            String before;
            try (var r =
                s.executeQuery("SELECT jsonb_agg(to_jsonb(b) ORDER BY id)::text FROM bookings b")) {
              r.next();
              before = r.getString(1);
            }
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
                    "SELECT (SELECT count(*) FROM booking_refund_policy_snapshots),(SELECT count(*)"
                        + " FROM booking_refund_policy_adoption_offers)")) {
              r.next();
              assertThat(r.getInt(1)).isZero();
              assertThat(r.getInt(2)).isZero();
            }
            try (var r =
                s.executeQuery("SELECT jsonb_agg(to_jsonb(b) ORDER BY id)::text FROM bookings b")) {
              r.next();
              assertThat(r.getString(1)).isEqualTo(before);
            }
            assertThatThrownBy(() -> insert(c, operator, null, operator, true))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("New counter ownership");
            assertThatThrownBy(() -> insert(c, operator, null, operator, false))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("requires a guest snapshot");
            UUID customer = UUID.randomUUID();
            UUID linked = insert(c, operator, customer, customer, true);
            UUID guest = insert(c, operator, null, null, true);
            UUID selfLinked = insert(c, operator, operator, operator, true);
            assertThat(linked).isNotNull();
            assertThat(guest).isNotNull();
            assertThat(selfLinked).isNotNull();
            // Birth-time link checks must not constrain later legitimate ownership handoffs.
            UUID buyer = UUID.randomUUID();
            c.setAutoCommit(false);
            try (var p =
                c.prepareStatement(
                    "UPDATE slot_reservations SET holder_id=? WHERE id=(SELECT reservation_id FROM"
                        + " bookings WHERE id=?)")) {
              p.setObject(1, buyer);
              p.setObject(2, linked);
              p.executeUpdate();
            }
            try (var p =
                c.prepareStatement(
                    "UPDATE bookings SET customer_id=?,current_holder_id=? WHERE id=?")) {
              p.setObject(1, buyer);
              p.setObject(2, buyer);
              p.setObject(3, linked);
              p.executeUpdate();
            }
            c.commit();
            c.setAutoCommit(true);
            try (var p =
                c.prepareStatement(
                    "SELECT linked_customer_id FROM counter_booking_guests WHERE booking_id=?")) {
              p.setObject(1, linked);
              try (var r = p.executeQuery()) {
                r.next();
                assertThat(r.getObject(1, UUID.class)).isEqualTo(customer);
              }
            }
            try (var p = c.prepareStatement("SELECT customer_id FROM bookings WHERE id IN (?,?)")) {
              p.setObject(1, legacy);
              p.setObject(2, noSnapshot);
              try (var r = p.executeQuery()) {
                while (r.next()) assertThat(r.getObject(1, UUID.class)).isEqualTo(operator);
              }
            }
          }
        });
  }

  private UUID insert(Connection c, UUID operator, UUID linked, UUID holder, boolean snapshot)
      throws SQLException {
    UUID id = UUID.randomUUID(), res = UUID.randomUUID();
    c.setAutoCommit(false);
    try {
      try (var p =
          c.prepareStatement(
              "INSERT INTO"
                  + " slot_reservations(id,court_id,facility_id,holder_id,starts_at,ends_at,expires_at,state,quote)"
                  + " VALUES(?,gen_random_uuid(),gen_random_uuid(),?,now()+interval '2"
                  + " days',now()+interval '2 days 1 hour',now()+interval '10"
                  + " minutes','HOLD','{}')")) {
        p.setObject(1, res);
        p.setObject(2, holder);
        p.executeUpdate();
      }
      try (var p =
          c.prepareStatement(
              "INSERT INTO"
                  + " bookings(id,reservation_id,facility_id,court_id,created_by,customer_id,current_holder_id,starts_at,ends_at,status,source,guest_name,guest_phone,amount,currency,price_snapshot,hold_expires_at)"
                  + " SELECT"
                  + " ?,id,facility_id,court_id,?,?,?,starts_at,ends_at,'PENDING','OFFLINE_COUNTER','Synthetic"
                  + " Guest','0911223344',100000,'VND',quote,expires_at FROM slot_reservations"
                  + " WHERE id=?")) {
        p.setObject(1, id);
        p.setObject(2, operator);
        p.setObject(3, holder);
        p.setObject(4, holder);
        p.setObject(5, res);
        p.executeUpdate();
      }
      if (snapshot)
        try (var p =
            c.prepareStatement(
                "INSERT INTO"
                    + " counter_booking_guests(booking_id,operator_id,linked_customer_id,guest_name,guest_phone)"
                    + " VALUES(?,?,?,'Synthetic Guest','0911223344')")) {
          p.setObject(1, id);
          p.setObject(2, operator);
          p.setObject(3, linked);
          p.executeUpdate();
        }
      c.commit();
      return id;
    } catch (SQLException e) {
      c.rollback();
      throw e;
    } finally {
      c.setAutoCommit(true);
    }
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
