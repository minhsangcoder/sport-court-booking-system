package com.sporthub.payment;

import static org.assertj.core.api.Assertions.*;

import java.sql.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

class PaymentReliabilityMigrationTest {
  @Test
  void upgradePreservesVerifiedMoneyAndMarksMissingLegacyOutboxForReview() throws Exception {
    database(
        (url, user, password) -> {
          String schema = "payment_upgrade_" + UUID.randomUUID().toString().replace("-", "");
          before(url, user, password, schema);
          try (var c = DriverManager.getConnection(url, user, password);
              var s = c.createStatement()) {
            s.execute("SET search_path TO " + schema + ",public");
            seed(s, UUID.randomUUID(), "SUCCESS", true);
            var f =
                Flyway.configure()
                    .dataSource(url, user, password)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .load();
            f.migrate();
            f.validate();
            assertThat(f.info().current().getVersion().toString()).isEqualTo("19");
            try (var r =
                s.executeQuery(
                    "SELECT p.status,p.amount,p.paid_at IS NOT"
                        + " NULL,o.settled_payment_id=p.id,s.state,s.last_error_code FROM"
                        + " payment_orders p JOIN payment_obligations o ON"
                        + " o.current_payment_id=p.id JOIN payment_booking_sync s ON"
                        + " s.payment_id=p.id")) {
              r.next();
              assertThat(r.getString(1)).isEqualTo("SUCCESS");
              assertThat(r.getBigDecimal(2)).isEqualByComparingTo("100000.25");
              assertThat(r.getBoolean(3)).isTrue();
              assertThat(r.getBoolean(4)).isTrue();
              assertThat(r.getString(5)).isEqualTo("REQUIRES_REVIEW");
              assertThat(r.getString(6)).isEqualTo("LEGACY_OUTBOX_MISSING");
            }
            assertThatThrownBy(() -> s.execute("UPDATE payment_orders SET amount=1"))
                .isInstanceOf(SQLException.class);
            try (var r = s.executeQuery("SELECT count(*) FROM payment_requests")) {
              r.next();
              assertThat(r.getInt(1)).isEqualTo(1);
            }
          }
        });
  }

  @Test
  void duplicateLegacyLiveObligationFailsPreflightWithoutDeletingOrRepricing() throws Exception {
    database(
        (url, user, password) -> {
          String schema = "payment_bad_" + UUID.randomUUID().toString().replace("-", "");
          before(url, user, password, schema);
          try (var c = DriverManager.getConnection(url, user, password);
              var s = c.createStatement()) {
            s.execute("SET search_path TO " + schema + ",public");
            UUID booking = UUID.randomUUID();
            seed(s, booking, "PENDING", false);
            seed(s, booking, "PENDING", false);
            assertThatThrownBy(
                    () ->
                        Flyway.configure()
                            .dataSource(url, user, password)
                            .schemas(schema)
                            .defaultSchema(schema)
                            .load()
                            .migrate())
                .hasMessageContaining("Payment obligation preflight failed");
            try (var r = s.executeQuery("SELECT count(*),sum(amount) FROM payment_orders")) {
              r.next();
              assertThat(r.getInt(1)).isEqualTo(2);
              assertThat(r.getBigDecimal(2)).isEqualByComparingTo("200000.50");
            }
          }
        });
  }

  @Test
  void legacyFractionalGroupOrderStopsMigrationWithoutChangingItsAmount() throws Exception {
    database(
        (url, user, password) -> {
          String schema =
              "payment_group_precision_" + UUID.randomUUID().toString().replace("-", "");
          before(url, user, password, schema);
          try (var c = DriverManager.getConnection(url, user, password);
              var s = c.createStatement()) {
            s.execute("SET search_path TO " + schema + ",public");
            s.execute(
                "INSERT INTO"
                    + " payment_orders(id,booking_id,payer_id,member_id,purpose,amount,currency,provider,provider_reference,status,idempotency_key,expires_at)"
                    + " VALUES(gen_random_uuid(),gen_random_uuid(),gen_random_uuid(),gen_random_uuid(),'GROUP_CONTRIBUTION',100.25,'VND','DEMO','DEMO-LEGACY','PENDING','legacy-fractional-group',NOW()+interval"
                    + " '10 minutes')");
            Flyway.configure()
                .dataSource(url, user, password)
                .schemas(schema)
                .defaultSchema(schema)
                .target("6")
                .load()
                .migrate();
            assertThatThrownBy(
                    () ->
                        Flyway.configure()
                            .dataSource(url, user, password)
                            .schemas(schema)
                            .defaultSchema(schema)
                            .load()
                            .migrate())
                .hasMessageContaining("Group payment precision preflight failed");
            try (var r =
                s.executeQuery(
                    "SELECT p.amount,o.amount,p.status FROM payment_orders p JOIN"
                        + " payment_obligations o ON o.current_payment_id=p.id")) {
              r.next();
              assertThat(r.getBigDecimal(1)).isEqualByComparingTo("100.25");
              assertThat(r.getBigDecimal(2)).isEqualByComparingTo("100.25");
              assertThat(r.getString(3)).isEqualTo("PENDING");
            }
          }
        });
  }

  private void seed(Statement s, UUID booking, String state, boolean request) throws SQLException {
    UUID id = UUID.randomUUID(), payer = UUID.randomUUID();
    String key = UUID.randomUUID().toString();
    s.execute(
        "INSERT INTO"
            + " payment_orders(id,booking_id,payer_id,purpose,amount,currency,provider,provider_reference,status,idempotency_key,expires_at,paid_at)"
            + " VALUES('"
            + id
            + "','"
            + booking
            + "','"
            + payer
            + "','BOOKING',100000.25,'VND','DEMO','DEMO-"
            + id
            + "','"
            + state
            + "','"
            + key
            + "',NOW()+interval '10 minutes',"
            + (state.equals("SUCCESS") ? "NOW()" : "NULL")
            + ")");
    if (request)
      s.execute(
          "INSERT INTO payment_requests(payer_id,idempotency_key,booking_id,payment_id) VALUES('"
              + payer
              + "','"
              + key
              + "','"
              + booking
              + "','"
              + id
              + "')");
  }

  private void before(String url, String user, String password, String schema) {
    Flyway.configure()
        .dataSource(url, user, password)
        .schemas(schema)
        .defaultSchema(schema)
        .target("5")
        .load()
        .migrate();
  }

  private interface Check {
    void run(String url, String user, String password) throws Exception;
  }

  private void database(Check check) throws Exception {
    PostgreSQLContainer<?> pg = null;
    String url = System.getenv("SPORTHUB_PAYMENT_TEST_DB_URL"),
        user = System.getenv("SPORTHUB_TEST_DB_USER"),
        password = System.getenv("SPORTHUB_TEST_DB_PASSWORD");
    if (url == null) {
      pg = new PostgreSQLContainer<>("postgres:16-alpine");
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
