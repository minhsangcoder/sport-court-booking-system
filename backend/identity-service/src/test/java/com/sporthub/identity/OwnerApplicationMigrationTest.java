package com.sporthub.identity;
import static org.assertj.core.api.Assertions.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import java.sql.DriverManager;
import java.util.UUID;

/** Runs both empty-schema installation and upgrade without touching the demo schema. */
class OwnerApplicationMigrationTest {
 @Test void twoLevelUpgradeRemovesDistrictPreservesSnapshotsAndRestoresImmutability() throws Exception {
  PostgreSQLContainer<?> temporary=null;String url=System.getenv("SPORTHUB_TEST_DB_URL"),user=System.getenv("SPORTHUB_TEST_DB_USER"),password=System.getenv("SPORTHUB_TEST_DB_PASSWORD");
  if(url==null){temporary=new PostgreSQLContainer<>("postgres:16-alpine");temporary.start();url=temporary.getJdbcUrl();user=temporary.getUsername();password=temporary.getPassword();}
  try{
   String schema="address_upgrade_"+UUID.randomUUID().toString().replace("-","");var before=Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema).target("8").load();before.migrate();
   UUID applicant=UUID.randomUUID(),application=UUID.randomUUID(),submission=UUID.randomUUID();
   try(var c=DriverManager.getConnection(url,user,password);var st=c.createStatement()){
    st.execute("SET search_path TO "+schema);
    st.execute("INSERT INTO users(id,email,password_hash) VALUES('"+applicant+"','"+applicant+"@example.test','unused')");
    st.execute("INSERT INTO owner_applications(id,user_id,facility_id,business_name,facility_name,private_payload,initial_facility) VALUES('"+application+"','"+applicant+"','"+UUID.randomUUID()+"','Legacy business','Legacy facility','private','{\"addressLine\":\"123 Street\",\"province\":\"p\",\"district\":\"d\",\"ward\":\"w\"}')");
    st.execute("INSERT INTO owner_application_submissions(id,application_id,private_snapshot,facility_snapshot) VALUES('"+submission+"','"+application+"','private','{\"facility\":{\"province\":\"p\",\"district\":\"d\",\"ward\":\"w\",\"latitude\":21},\"hours\":[{}]}')");
   }
   var latest=Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema).load();latest.migrate();latest.validate();
   try(var c=DriverManager.getConnection(url,user,password);var st=c.createStatement()){
    try(var rs=st.executeQuery("SELECT initial_facility = '{\"addressLine\":\"123 Street\",\"province\":\"p\",\"ward\":\"w\"}'::jsonb FROM "+schema+".owner_applications")){rs.next();assertThat(rs.getBoolean(1)).isTrue();}
    try(var rs=st.executeQuery("SELECT facility_snapshot = '{\"facility\":{\"province\":\"p\",\"ward\":\"w\",\"latitude\":21},\"hours\":[{}]}'::jsonb FROM "+schema+".owner_application_submissions")){rs.next();assertThat(rs.getBoolean(1)).isTrue();}
    assertThatThrownBy(()->st.execute("UPDATE "+schema+".owner_application_submissions SET facility_snapshot='{}'::jsonb WHERE id='"+submission+"'")).isInstanceOf(java.sql.SQLException.class);
   }
  }finally{if(temporary!=null)temporary.stop();}
 }
 @Test void emptyInstallAndPreviousVersionUpgradeValidateWithoutFailedHistory() throws Exception {
  PostgreSQLContainer<?> temporary=null;String url=System.getenv("SPORTHUB_TEST_DB_URL"),user=System.getenv("SPORTHUB_TEST_DB_USER"),password=System.getenv("SPORTHUB_TEST_DB_PASSWORD");
  if(url==null){temporary=new PostgreSQLContainer<>("postgres:16-alpine");temporary.start();url=temporary.getJdbcUrl();user=temporary.getUsername();password=temporary.getPassword();}
  try {
   // Extension functions must be shared across the two isolated schema installations.
   try(var connection=DriverManager.getConnection(url,user,password);var statement=connection.createStatement()){
    statement.execute("CREATE EXTENSION IF NOT EXISTS \"uuid-ossp\" WITH SCHEMA public");
   }
   for(boolean upgrade:new boolean[]{false,true}){
    String schema="owner_migration_"+UUID.randomUUID().toString().replace("-","");
    if(upgrade){var before=Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema).target("7").load();before.migrate();assertThat(before.info().current().getVersion().toString()).isEqualTo("7");}
    var latest=Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema).load();latest.migrate();latest.validate();
    assertThat(latest.info().current().getVersion().toString()).isEqualTo("16");
    try(var connection=DriverManager.getConnection(url,user,password);var statement=connection.createStatement();var results=statement.executeQuery("SELECT count(*) FROM "+schema+".flyway_schema_history WHERE NOT success")){results.next();assertThat(results.getInt(1)).isZero();}
   }
  }finally{if(temporary!=null)temporary.stop();}
 }
 @Test void staffUpgradePreservesExistingBindingsAndEnforcesLocalScopeAndCatalog() throws Exception {
  PostgreSQLContainer<?> temporary=null;String url=System.getenv("SPORTHUB_TEST_DB_URL"),user=System.getenv("SPORTHUB_TEST_DB_USER"),password=System.getenv("SPORTHUB_TEST_DB_PASSWORD");
  if(url==null){temporary=new PostgreSQLContainer<>("postgres:16-alpine");temporary.start();url=temporary.getJdbcUrl();user=temporary.getUsername();password=temporary.getPassword();}
  try{
   String schema="staff_upgrade_"+UUID.randomUUID().toString().replace("-","");Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema).target("9").load().migrate();UUID staff=UUID.randomUUID(),binding=UUID.randomUUID(),facility=UUID.randomUUID();
   try(var c=DriverManager.getConnection(url,user,password);var st=c.createStatement()){
    st.execute("SET search_path TO "+schema);st.execute("INSERT INTO users(id,email,password_hash,status,email_verified) VALUES('"+staff+"','"+staff+"@example.test','unused','ACTIVE',true)");st.execute("INSERT INTO user_profiles(user_id,full_name) VALUES('"+staff+"','Legacy Staff')");st.execute("INSERT INTO user_roles(user_id,role) VALUES('"+staff+"','STAFF')");st.execute("INSERT INTO staff_facility_bindings(id,staff_user_id,facility_id,assigned_by) VALUES('"+binding+"','"+staff+"','"+facility+"','"+staff+"')");st.execute("INSERT INTO staff_permissions(binding_id,permission) VALUES('"+binding+"','BOOKING_READ')");st.execute("INSERT INTO audit_log(user_id,action,entity_type,entity_id) VALUES('"+staff+"','STAFF_ASSIGNED','STAFF_BINDING','"+binding+"')");
   }
   var latest=Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema).load();latest.migrate();latest.validate();
   try(var c=DriverManager.getConnection(url,user,password);var st=c.createStatement()){
    st.execute("SET search_path TO "+schema);
    try(var rs=st.executeQuery("SELECT full_name,binding_id,facility_id,is_active FROM staff_profiles")){assertThat(rs.next()).isTrue();assertThat(rs.getString(1)).isEqualTo("Legacy Staff");assertThat(rs.getObject(2,UUID.class)).isEqualTo(binding);assertThat(rs.getObject(3,UUID.class)).isEqualTo(facility);assertThat(rs.getBoolean(4)).isTrue();assertThat(rs.next()).isFalse();}
    assertThatThrownBy(()->st.execute("UPDATE staff_profiles SET facility_id='"+UUID.randomUUID()+"' WHERE binding_id='"+binding+"'")).isInstanceOf(java.sql.SQLException.class);
    assertThatThrownBy(()->st.execute("INSERT INTO staff_permissions(binding_id,permission) VALUES('"+binding+"','OWNER')")).isInstanceOf(java.sql.SQLException.class);
    assertThatThrownBy(()->st.execute("INSERT INTO staff_invitations(profile_id,facility_id,target_user_id,invited_by,permissions,expires_at) SELECT id,'"+UUID.randomUUID()+"','"+staff+"','"+staff+"',ARRAY['BOOKING_READ'],NOW()+INTERVAL '1 day' FROM staff_profiles")).isInstanceOf(java.sql.SQLException.class);
    try(var rs=st.executeQuery("SELECT count(*) FROM audit_log WHERE action='STAFF_ASSIGNED'")){rs.next();assertThat(rs.getInt(1)).isEqualTo(1);}
   }
  }finally{if(temporary!=null)temporary.stop();}
 }

}
