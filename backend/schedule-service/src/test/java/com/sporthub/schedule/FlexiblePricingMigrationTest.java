package com.sporthub.schedule;

import static org.assertj.core.api.Assertions.*;

import java.sql.DriverManager;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

class FlexiblePricingMigrationTest {
  @Test
  void freshAndUpgradePreserveLegacyPricesAndEnforceHourlyPolicy() throws Exception {
    try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
      postgres.start();
      String url = postgres.getJdbcUrl(), user = postgres.getUsername(), password = postgres.getPassword();
      String schema = "hourly_" + UUID.randomUUID().toString().replace("-", "");
      UUID facility = UUID.randomUUID(), rule = UUID.randomUUID();
      Flyway.configure().dataSource(url, user, password).schemas(schema).defaultSchema(schema)
          .target("5").load().migrate();
      try (var connection = DriverManager.getConnection(url, user, password);
          var statement = connection.createStatement()) {
        statement.execute("SET search_path TO " + schema + ",public");
        statement.execute("INSERT INTO pricing_rules(id,facility_id,day_of_week,start_time,end_time,price_per_slot) VALUES('"
            + rule + "','" + facility + "',1,'15:00','16:00',100000.25)");
        statement.execute("INSERT INTO pricing_rule_versions(rule_id,version,snapshot) VALUES('" + rule + "',0,'{\"pricePerSlot\":100000.25}')");
      }
      var latest = Flyway.configure().dataSource(url, user, password).schemas(schema)
          .defaultSchema(schema).load();
      latest.migrate();
      latest.validate();
      try (var connection = DriverManager.getConnection(url, user, password);
          var statement = connection.createStatement()) {
        statement.execute("SET search_path TO " + schema + ",public");
        try (var rows = statement.executeQuery("SELECT pricing_unit,price_per_slot,price_per_hour FROM pricing_rules")) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getString(1)).isEqualTo("LEGACY_SLOT");
          assertThat(rows.getBigDecimal(2)).isEqualByComparingTo("100000.25");
          assertThat(rows.getObject(3)).isNull();
        }
        try (var rows = statement.executeQuery("SELECT snapshot->>'pricePerSlot' FROM pricing_rule_versions")) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getString(1)).isEqualTo("100000.25");
        }
        assertThatThrownBy(() -> statement.execute("UPDATE pricing_rules SET pricing_unit='HOURLY'"))
            .isInstanceOf(java.sql.SQLException.class);
        assertThatThrownBy(() -> statement.execute("UPDATE pricing_rule_versions SET snapshot='{}'"))
            .isInstanceOf(java.sql.SQLException.class);
        statement.execute("INSERT INTO facility_booking_policies(facility_id,updated_by) VALUES('" + facility + "',gen_random_uuid())");
        try (var rows = statement.executeQuery("SELECT enabled,selection_step_minutes,minimum_minutes,maximum_minutes FROM facility_booking_policies")) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getBoolean(1)).isFalse();
          assertThat(rows.getInt(2)).isEqualTo(30);
          assertThat(rows.getInt(3)).isEqualTo(30);
          assertThat(rows.getObject(4)).isNull();
        }
        assertThatThrownBy(() -> statement.execute("UPDATE facility_booking_policies SET minimum_minutes=60"))
            .isInstanceOf(java.sql.SQLException.class);
        assertThatThrownBy(() -> statement.execute("UPDATE facility_booking_policies SET maximum_minutes=240"))
            .isInstanceOf(java.sql.SQLException.class);
      }
      // Extensions are database-wide; a fresh install needs its own database, not a second schema.
      try (var connection = DriverManager.getConnection(url, user, password);
          var statement = connection.createStatement()) {
        statement.execute("CREATE DATABASE fresh_hourly");
      }
      String freshUrl = url.substring(0, url.lastIndexOf('/') + 1) + "fresh_hourly";
      Flyway.configure().dataSource(freshUrl, user, password).load().migrate();
    }
  }
}
