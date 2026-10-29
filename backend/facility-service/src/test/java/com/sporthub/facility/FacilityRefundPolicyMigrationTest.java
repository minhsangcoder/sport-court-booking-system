package com.sporthub.facility;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class FacilityRefundPolicyMigrationTest {
    @Test void freshAndUpgradeRemoveRefundPolicyTablesAndPreserveFacilities() {
        try (var database = new PostgreSQLContainer<>("postgres:16-alpine")) {
            database.start();
            for (boolean upgrade : new boolean[]{false, true}) {
                String schema = "refund_policy_" + UUID.randomUUID().toString().replace("-", "");
                var source = new DriverManagerDataSource(database.getJdbcUrl() + (database.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=" + schema,
                        database.getUsername(), database.getPassword());
                var jdbc = new JdbcTemplate(source);
                var facility = UUID.randomUUID();
                if (upgrade) {
                    Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).target("14").load().migrate();
                    jdbc.update("""
                            INSERT INTO facilities(id,owner_id,name,phone,address_line,province,ward,timezone)
                            VALUES(?,?,'Legacy facility','+84900000000','Address','Province','Ward','Asia/Ho_Chi_Minh')
                            """, facility, UUID.randomUUID());
                }
                var latest = Flyway.configure().dataSource(source).schemas(schema).defaultSchema(schema).load();
                latest.migrate();
                latest.validate();
                assertThat(latest.info().current().getVersion().toString()).isEqualTo("17");
                assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM information_schema.tables WHERE table_schema=?
                        AND table_name IN('facility_refund_policy_versions','facility_refund_policy_heads')
                        """, Integer.class, schema)).isZero();
                if (upgrade) assertThat(jdbc.queryForObject("SELECT name FROM facilities WHERE id=?", String.class, facility)).isEqualTo("Legacy facility");
            }
        }
    }
}
