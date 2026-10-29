package com.sporthub.payment;

import static org.assertj.core.api.Assertions.*;
import java.sql.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

class BankActivationMigrationTest {
  @Test
  void stagedAndActivatedCommandsPreserveEncryptedRevisionsAndLegacyRequests() throws Exception {
    try (var pg = new PostgreSQLContainer<>("postgres:16-alpine")) {
      pg.start();
      Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()).target("15").load().migrate();
      try (var connection = pg.createConnection(""); var sql = connection.createStatement()) {
        sql.execute("CREATE TEMP TABLE ids AS SELECT gen_random_uuid() principal,gen_random_uuid() revision");
        sql.execute("""
          INSERT INTO customer_bank_destination_revisions(id,principal_id,bank_bin,bank_name,account_ciphertext,masked_account,destination_hash,consent_reference,created_by)
          SELECT revision,principal,'970436','VCB','encrypted-original','***0123',repeat('a',64),'consent',principal FROM ids
          """);
        sql.execute("INSERT INTO bank_destination_requests SELECT principal,'legacy',repeat('b',64),revision FROM ids");
        var latest = Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()).load();
        latest.migrate(); latest.validate();
        try (var rows = sql.executeQuery("SELECT state,activation_version,account_ciphertext FROM bank_destination_requests JOIN customer_bank_destination_revisions r ON revision_id=r.id")) {
          rows.next();
          assertThat(rows.getString(1)).isEqualTo("LEGACY");
          assertThat(rows.getObject(2)).isNull();
          assertThat(rows.getString(3)).isEqualTo("encrypted-original");
        }
        sql.execute("""
          INSERT INTO bank_destination_requests(actor_id,idempotency_key,fingerprint,revision_id,state,expected_profile_version)
          SELECT principal,'new-command',repeat('c',64),revision,'STAGED',0 FROM ids
          """);
        assertThatThrownBy(() -> sql.execute("UPDATE bank_destination_requests SET state='ACTIVATED' WHERE state='STAGED'"))
            .isInstanceOf(SQLException.class);
        sql.execute("UPDATE bank_destination_requests SET state='ACTIVATED',activation_version=1,decision='{\"decision\":\"ACTIVATED\"}' WHERE state='STAGED'");
        assertThatThrownBy(() -> sql.execute("UPDATE bank_destination_requests SET state='REJECTED' WHERE state='ACTIVATED'"))
            .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> sql.execute("UPDATE customer_bank_destination_revisions SET account_ciphertext='replaced'"))
            .isInstanceOf(SQLException.class);
      }
      try (var connection = pg.createConnection(""); var sql = connection.createStatement()) { sql.execute("CREATE DATABASE bank_fresh"); }
      var fresh = Flyway.configure().dataSource(pg.getJdbcUrl().replace(pg.getDatabaseName(), "bank_fresh"), pg.getUsername(), pg.getPassword()).load();
      fresh.migrate(); fresh.validate();
      assertThat(fresh.info().current().getVersion().toString()).isEqualTo("19");
    }
  }
}
