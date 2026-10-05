package com.sporthub.identity;
import static org.assertj.core.api.Assertions.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import java.sql.DriverManager;
import java.util.UUID;

/** Runs both empty-schema installation and upgrade without touching the demo schema. */
class OwnerApplicationMigrationTest {
 @Test void emptyInstallAndPreviousVersionUpgradeValidateWithoutFailedHistory() throws Exception {
  PostgreSQLContainer<?> temporary=null;String url=System.getenv("SPORTHUB_TEST_DB_URL"),user=System.getenv("SPORTHUB_TEST_DB_USER"),password=System.getenv("SPORTHUB_TEST_DB_PASSWORD");
  if(url==null){temporary=new PostgreSQLContainer<>("postgres:16-alpine");temporary.start();url=temporary.getJdbcUrl();user=temporary.getUsername();password=temporary.getPassword();}
  try {
   for(boolean upgrade:new boolean[]{false,true}){
    String schema="owner_migration_"+UUID.randomUUID().toString().replace("-","");
    if(upgrade){var before=Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema).target("5").load();before.migrate();assertThat(before.info().current().getVersion().toString()).isEqualTo("5");}
    var latest=Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema).load();latest.migrate();latest.validate();
    assertThat(latest.info().current().getVersion().toString()).isEqualTo("6");
    try(var connection=DriverManager.getConnection(url,user,password);var statement=connection.createStatement();var results=statement.executeQuery("SELECT count(*) FROM "+schema+".flyway_schema_history WHERE NOT success")){results.next();assertThat(results.getInt(1)).isZero();}
   }
  }finally{if(temporary!=null)temporary.stop();}
 }
}
