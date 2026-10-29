package com.sporthub.booking;

import static org.assertj.core.api.Assertions.*;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

class CounterBookingOwnershipMigrationTest {

  @Test
  void v11AllowsNullableOwnershipAndEnforcesCounterGuestProtection() throws Exception {
    database(
        (url, user, password) -> {
          String schema = "counter_ownership_" + UUID.randomUUID().toString().replace("-", "");
          // 1. Migrate to v10 first
          Flyway.configure()
              .dataSource(url, user, password)
              .schemas(schema)
              .defaultSchema(schema)
              .target("10")
              .load()
              .migrate();

          // Seed a legacy v10 booking
          UUID legacyOperatorId = UUID.randomUUID();
          UUID legacyResId = UUID.randomUUID();
          UUID legacyBookingId = UUID.randomUUID();
          try (var c = DriverManager.getConnection(url, user, password);
              var s = c.createStatement()) {
            s.execute("SET search_path TO " + schema + ",public");
            s.execute(
                String.format(
                    "INSERT INTO"
                        + " slot_reservations(id,court_id,facility_id,holder_id,starts_at,ends_at,expires_at,state,quote)"
                        + " VALUES('%s',gen_random_uuid(),gen_random_uuid(),'%s',NOW()+interval '2"
                        + " days',NOW()+interval '2 days 1 hour',NOW()+interval '10"
                        + " minutes','HOLD','{}')",
                    legacyResId, legacyOperatorId));
            s.execute(
                String.format(
                    "INSERT INTO"
                        + " bookings(id,reservation_id,facility_id,court_id,created_by,customer_id,current_holder_id,starts_at,ends_at,status,source,amount,currency,price_snapshot,hold_expires_at)"
                        + " SELECT"
                        + " '%s',id,facility_id,court_id,holder_id,holder_id,holder_id,starts_at,ends_at,'PENDING','ONLINE',100,'VND',quote,expires_at"
                        + " FROM slot_reservations WHERE id='%s'",
                    legacyBookingId, legacyResId));
          }

          // 2. Migrate to v11 and validate cleanly
          var latest =
              Flyway.configure()
                  .dataSource(url, user, password)
                  .schemas(schema)
                  .defaultSchema(schema)
                  .load();
          latest.migrate();
          latest.validate();
          assertThat(latest.info().current().getVersion().toString()).isEqualTo("21");

          try (var c = DriverManager.getConnection(url, user, password);
              var s = c.createStatement()) {
            s.execute("SET search_path TO " + schema + ",public");

            // Test A: Direct SQL rejects ONLINE booking with NULL owner
            UUID onlineRes = UUID.randomUUID();
            s.execute(
                String.format(
                    "INSERT INTO"
                        + " slot_reservations(id,court_id,facility_id,holder_id,starts_at,ends_at,expires_at,state,quote)"
                        + " VALUES('%s',gen_random_uuid(),gen_random_uuid(),gen_random_uuid(),NOW()+interval"
                        + " '10 days',NOW()+interval '10 days 1 hour',NOW()+interval '10"
                        + " minutes','HOLD','{}')",
                    onlineRes));
            assertThatThrownBy(
                    () ->
                        s.execute(
                            String.format(
                                "INSERT INTO"
                                    + " bookings(id,reservation_id,facility_id,court_id,created_by,customer_id,current_holder_id,starts_at,ends_at,status,source,amount,currency,price_snapshot,hold_expires_at)"
                                    + " SELECT"
                                    + " gen_random_uuid(),id,facility_id,court_id,holder_id,NULL,NULL,starts_at,ends_at,'PENDING','ONLINE',100,'VND',quote,expires_at"
                                    + " FROM slot_reservations WHERE id='%s'",
                                onlineRes)))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_booking_online_has_owner");

            // Test B: Direct SQL rejects owner pair mismatch (one NULL, one non-NULL)
            assertThatThrownBy(
                    () ->
                        s.execute(
                            String.format(
                                "INSERT INTO"
                                    + " bookings(id,reservation_id,facility_id,court_id,created_by,customer_id,current_holder_id,starts_at,ends_at,status,source,amount,currency,price_snapshot,hold_expires_at)"
                                    + " SELECT"
                                    + " gen_random_uuid(),id,facility_id,court_id,holder_id,holder_id,NULL,starts_at,ends_at,'PENDING','OFFLINE_COUNTER',100,'VND',quote,expires_at"
                                    + " FROM slot_reservations WHERE id='%s'",
                                onlineRes)))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("chk_booking_owner_pair");

            // Test C: Direct SQL rejects unattached reservation with NULL holder
            assertThatThrownBy(
                    () ->
                        s.execute(
                            "INSERT INTO"
                                + " slot_reservations(id,court_id,facility_id,holder_id,starts_at,ends_at,expires_at,state,quote)"
                                + " VALUES(gen_random_uuid(),gen_random_uuid(),gen_random_uuid(),NULL,NOW()+interval"
                                + " '11 days',NOW()+interval '11 days 1 hour',NOW()+interval '10"
                                + " minutes','HOLD','{}')"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("Unattached reservation must have a holder");

            // Test D: Direct SQL rejects walk-in booking without guest snapshot on commit
            c.setAutoCommit(false);
            UUID noSnapRes = UUID.randomUUID();
            UUID noSnapBooking = UUID.randomUUID();
            s.execute(
                String.format(
                    "INSERT INTO"
                        + " slot_reservations(id,court_id,facility_id,holder_id,starts_at,ends_at,expires_at,state,quote)"
                        + " VALUES('%s',gen_random_uuid(),gen_random_uuid(),NULL,NOW()+interval '12"
                        + " days',NOW()+interval '12 days 1 hour',NOW()+interval '10"
                        + " minutes','HOLD','{}')",
                    noSnapRes));
            s.execute(
                String.format(
                    "INSERT INTO"
                        + " bookings(id,reservation_id,facility_id,court_id,created_by,customer_id,current_holder_id,starts_at,ends_at,status,source,amount,currency,price_snapshot,hold_expires_at,guest_name,guest_phone)"
                        + " SELECT"
                        + " '%s',id,facility_id,court_id,gen_random_uuid(),NULL,NULL,starts_at,ends_at,'PENDING','OFFLINE_COUNTER',100,'VND',quote,expires_at,'Ghost','0911222333'"
                        + " FROM slot_reservations WHERE id='%s'",
                    noSnapBooking, noSnapRes));
            assertThatThrownBy(c::commit).isInstanceOf(SQLException.class);
            c.rollback();
            c.setAutoCommit(true);

            // Test E: Legitimate walk-in counter booking transaction with snapshot and null holder
            UUID walkinResId = UUID.randomUUID();
            UUID walkinBookingId = UUID.randomUUID();
            UUID staffOperatorId = UUID.randomUUID();

            c.setAutoCommit(false);
            s.execute(
                String.format(
                    "INSERT INTO"
                        + " slot_reservations(id,court_id,facility_id,holder_id,starts_at,ends_at,expires_at,state,quote)"
                        + " VALUES('%s',gen_random_uuid(),gen_random_uuid(),NULL,NOW()+interval '3"
                        + " days',NOW()+interval '3 days 1 hour',NOW()+interval '10"
                        + " minutes','HOLD','{}')",
                    walkinResId));
            s.execute(
                String.format(
                    "INSERT INTO"
                        + " bookings(id,reservation_id,facility_id,court_id,created_by,customer_id,current_holder_id,starts_at,ends_at,status,source,amount,currency,price_snapshot,hold_expires_at,guest_name,guest_phone)"
                        + " SELECT"
                        + " '%s',id,facility_id,court_id,'%s',NULL,NULL,starts_at,ends_at,'PENDING','OFFLINE_COUNTER',100,'VND',quote,expires_at,'Walkin"
                        + " Guest','0987654321' FROM slot_reservations WHERE id='%s'",
                    walkinBookingId, staffOperatorId, walkinResId));
            s.execute(
                String.format(
                    "INSERT INTO"
                        + " counter_booking_guests(booking_id,operator_id,linked_customer_id,guest_name,guest_phone)"
                        + " VALUES('%s','%s',NULL,'Walkin Guest','0987654321')",
                    walkinBookingId, staffOperatorId));
            c.commit();
            c.setAutoCommit(true);

            // Verify registry received NULL holder_id
            try (var r =
                s.executeQuery(
                    String.format(
                        "SELECT holder_id FROM booking_code_registry WHERE booking_id='%s'",
                        walkinBookingId))) {
              assertThat(r.next()).isTrue();
              assertThat(r.getObject(1)).isNull();
            }

            // Test F: Group booking with NULL owner is rejected at commit time by deferred trigger
            c.setAutoCommit(false);
            s.execute(
                String.format(
                    "INSERT INTO"
                        + " booking_groups(id,owner_id,name,state,deadline,max_members,allocations_locked)"
                        + " VALUES('%s','%s','Null Group','GROUP_PENDING',NOW()+interval '10"
                        + " minutes',2,false)",
                    walkinBookingId, staffOperatorId));
            assertThatThrownBy(c::commit).isInstanceOf(SQLException.class);
            c.rollback();
            c.setAutoCommit(true);

            // Test G: Rotating code is forbidden when current_holder_id is NULL
            assertThatThrownBy(
                    () ->
                        s.execute(
                            String.format(
                                "UPDATE bookings SET booking_code='0123456789' WHERE id='%s'",
                                walkinBookingId)))
                .isInstanceOf(SQLException.class);

            // Test H: Tampering with guest contact or source is rejected
            assertThatThrownBy(
                    () ->
                        s.execute(
                            String.format(
                                "UPDATE bookings SET guest_name='Tampered' WHERE id='%s'",
                                walkinBookingId)))
                .isInstanceOf(SQLException.class);
            assertThatThrownBy(
                    () ->
                        s.execute(
                            String.format(
                                "UPDATE counter_booking_guests SET guest_name='Tampered' WHERE"
                                    + " booking_id='%s'",
                                walkinBookingId)))
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
