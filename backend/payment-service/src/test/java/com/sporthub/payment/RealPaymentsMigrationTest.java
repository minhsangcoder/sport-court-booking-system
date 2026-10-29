package com.sporthub.payment;

import static org.assertj.core.api.Assertions.*;

import java.sql.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

class RealPaymentsMigrationTest {
  @Test
  void freshAndUpgradePreserveHistoryAndProtectNewEvidence() throws Exception {
    try (var pg = new PostgreSQLContainer<>("postgres:16-alpine")) {
      pg.start();
      var legacy =
          Flyway.configure()
              .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
              .target("14")
              .load();
      legacy.migrate();
      try (var c = pg.createConnection("");
          var s = c.createStatement()) {
        UUID booking = UUID.randomUUID(), payer = UUID.randomUUID(), payment = UUID.randomUUID();
        s.execute(
            "INSERT INTO payment_obligations(booking_id,purpose,subject_id,amount,currency)"
                + " VALUES('"
                + booking
                + "','BOOKING','00000000-0000-0000-0000-000000000000',120000,'VND')");
        s.execute(
            "INSERT INTO"
                + " payment_orders(id,booking_id,payer_id,purpose,amount,currency,provider,provider_reference,status,idempotency_key,expires_at)"
                + " VALUES('"
                + payment
                + "','"
                + booking
                + "','"
                + payer
                + "','BOOKING',120000,'VND','DEMO','DEMO-MIGRATION','PENDING','upgrade',now()+interval"
                + " '1 hour')");
        var all =
            Flyway.configure()
                .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                .load();
        all.migrate();
        all.validate();
        assertThat(all.info().current().getVersion().toString()).isEqualTo("19");
        assertThat(count(s, "payos_channels")).isZero();
        assertThat(count(s, "real_payment_collections")).isZero();
        try (var r = s.executeQuery("SELECT provider,amount,status FROM payment_orders")) {
          r.next();
          assertThat(r.getString(1)).isEqualTo("DEMO");
          assertThat(r.getBigDecimal(2)).isEqualByComparingTo("120000");
          assertThat(r.getString(3)).isEqualTo("PENDING");
        }
        UUID channel = UUID.randomUUID(),
            revision = UUID.randomUUID(),
            facility = UUID.randomUUID();
        s.execute(
            "INSERT INTO payos_channels(id,facility_id,owner_id,status,version,native_channel_hash)"
                + " VALUES('"
                + channel
                + "','"
                + facility
                + "','"
                + payer
                + "','PREPARING',1,'channel-hash')");
        s.execute(
            "INSERT INTO payos_channel_revisions(id,channel_id,revision,credentials_ciphertext)"
                + " VALUES('"
                + revision
                + "','"
                + channel
                + "',1,'ciphertext')");
        s.execute(
            "INSERT INTO"
                + " payos_qr_sessions(payment_id,channel_id,receiver_revision_id,facility_id,receiver_owner_id,order_code,description,state)"
                + " VALUES('"
                + payment
                + "','"
                + channel
                + "','"
                + revision
                + "','"
                + facility
                + "','"
                + payer
                + "',1,'SH0000001','INITIALIZING')");
        assertThatThrownBy(() -> s.execute("UPDATE payos_qr_sessions SET description='SH0000002'"))
            .isInstanceOf(SQLException.class);
        assertThatThrownBy(
                () ->
                    s.execute(
                        "UPDATE payos_channel_revisions SET credentials_ciphertext='changed'"))
            .isInstanceOf(SQLException.class);
        UUID destination = UUID.randomUUID();
        s.execute(
            "INSERT INTO"
                + " customer_bank_destination_revisions(id,principal_id,bank_bin,bank_name,account_ciphertext,masked_account,destination_hash,consent_reference,created_by)"
                + " VALUES('"
                + destination
                + "','"
                + payer
                + "','970436','VCB','encrypted','***0123','hash','consent','"
                + payer
                + "')");
        assertThatThrownBy(() -> s.execute("DELETE FROM customer_bank_destination_revisions"))
            .isInstanceOf(SQLException.class);
        assertThatThrownBy(
                () ->
                    s.execute(
                        "INSERT INTO"
                            + " payos_channel_revisions(id,channel_id,revision,credentials_ciphertext)"
                            + " VALUES(gen_random_uuid(),gen_random_uuid(),1,'orphan')"))
            .isInstanceOf(SQLException.class);
        assertThatThrownBy(
                () ->
                    s.execute(
                        "INSERT INTO payos_channels SELECT"
                            + " gen_random_uuid(),facility_id,owner_id,status,version,NULL,'another-hash',created_at"
                            + " FROM payos_channels"))
            .isInstanceOf(SQLException.class);
      }
      var fresh =
          Flyway.configure()
              .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
              .schemas("fresh_real")
              .defaultSchema("fresh_real")
              .load();
      fresh.migrate();
      fresh.validate();
      assertThat(fresh.info().current().getVersion().toString()).isEqualTo("19");
    }
  }

  private int count(Statement s, String table) throws SQLException {
    try (var r = s.executeQuery("SELECT count(*) FROM " + table)) {
      r.next();
      return r.getInt(1);
    }
  }
}
