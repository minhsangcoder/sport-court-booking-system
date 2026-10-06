package com.sporthub.facility;
import static org.assertj.core.api.Assertions.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import java.sql.DriverManager;
import java.util.UUID;

/** Runs both empty-schema installation and upgrade without touching the demo schema. */
class OwnerApplicationMigrationTest {
 @Test void contactEmailUpgradeKeepsExistingFacilityAndLegacySnapshotUnchanged() throws Exception {
  PostgreSQLContainer<?> temporary=null;String url=System.getenv("SPORTHUB_FACILITY_TEST_DB_URL"),user=System.getenv("SPORTHUB_TEST_DB_USER"),password=System.getenv("SPORTHUB_TEST_DB_PASSWORD");
  if(url==null){temporary=new PostgreSQLContainer<>("postgres:16-alpine");temporary.start();url=temporary.getJdbcUrl();user=temporary.getUsername();password=temporary.getPassword();}
  try{
   String schema="contact_upgrade_"+UUID.randomUUID().toString().replace("-","");
   var before=Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema).target("7").load();before.migrate();
   UUID facility=UUID.randomUUID(),owner=UUID.randomUUID(),review=UUID.randomUUID();
   try(var c=DriverManager.getConnection(url,user,password);var st=c.createStatement()){
    st.execute("SET search_path TO "+schema);
    st.execute("INSERT INTO facilities(id,owner_id,name,phone,address_line,province,district,ward,timezone) VALUES('"+facility+"','"+owner+"','Legacy','phone','address','p','d','w','Asia/Ho_Chi_Minh')");
    st.execute("INSERT INTO facility_reviews(id,facility_id,owner_id,state,snapshot) VALUES('"+review+"','"+facility+"','"+owner+"','PENDING_APPROVAL','{\"facility\":{\"name\":\"Legacy\"}}')");
   }
   var latest=Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema).load();latest.migrate();latest.validate();assertThat(latest.info().current().getVersion().toString()).isEqualTo("8");
   try(var c=DriverManager.getConnection(url,user,password);var st=c.createStatement();var rs=st.executeQuery("SELECT f.contact_email,r.snapshot->'facility' ? 'contactEmail', (SELECT count(*) FROM "+schema+".flyway_schema_history WHERE NOT success) FROM "+schema+".facilities f JOIN "+schema+".facility_reviews r ON r.facility_id=f.id")){rs.next();assertThat(rs.getString(1)).isNull();assertThat(rs.getBoolean(2)).isFalse();assertThat(rs.getInt(3)).isZero();}
  }finally{if(temporary!=null)temporary.stop();}
 }
 @Test void emptyInstallAndPreviousVersionUpgradeValidateWithoutFailedHistory() throws Exception {
  PostgreSQLContainer<?> temporary=null;String url=System.getenv("SPORTHUB_FACILITY_TEST_DB_URL"),user=System.getenv("SPORTHUB_TEST_DB_USER"),password=System.getenv("SPORTHUB_TEST_DB_PASSWORD");
  if(url==null){temporary=new PostgreSQLContainer<>("postgres:16-alpine");temporary.start();url=temporary.getJdbcUrl();user=temporary.getUsername();password=temporary.getPassword();}
  try {
   for(boolean upgrade:new boolean[]{false,true}){
    String schema="owner_migration_"+UUID.randomUUID().toString().replace("-","");
    if(upgrade){var before=Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema).target("6").load();before.migrate();assertThat(before.info().current().getVersion().toString()).isEqualTo("6");}
    var latest=Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema).load();latest.migrate();latest.validate();
    assertThat(latest.info().current().getVersion().toString()).isEqualTo("8");
    try(var connection=DriverManager.getConnection(url,user,password);var statement=connection.createStatement();var results=statement.executeQuery("SELECT count(*) FROM "+schema+".flyway_schema_history WHERE NOT success")){results.next();assertThat(results.getInt(1)).isZero();}
   }
  }finally{if(temporary!=null)temporary.stop();}
 }
}
