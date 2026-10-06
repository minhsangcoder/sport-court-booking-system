package com.sporthub.booking;

import static org.assertj.core.api.Assertions.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import java.sql.DriverManager;
import java.util.UUID;

class GroupReminderMigrationTest {
    @Test void freshAndV3UpgradePreserveLegacyMembersAndValidateHistory()throws Exception{
        PostgreSQLContainer<?> temporary=null;String url=System.getenv("SPORTHUB_BOOKING_TEST_DB_URL"),user=System.getenv("SPORTHUB_TEST_DB_USER"),password=System.getenv("SPORTHUB_TEST_DB_PASSWORD");
        if(url==null){temporary=new PostgreSQLContainer<>("postgres:16-alpine");temporary.start();url=temporary.getJdbcUrl();user=temporary.getUsername();password=temporary.getPassword();}
        try{for(boolean upgrade:new boolean[]{false,true}){
            String schema="reminder_migration_"+UUID.randomUUID().toString().replace("-","");
            if(upgrade){
                Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema).target("3").load().migrate();
                try(var c=DriverManager.getConnection(url,user,password);var s=c.createStatement()){
                    s.execute("SET search_path TO "+schema+",public");
                    s.execute("INSERT INTO slot_reservations(id,court_id,facility_id,holder_id,starts_at,ends_at,expires_at,state,quote) VALUES('00000000-0000-0000-0000-000000000001',gen_random_uuid(),gen_random_uuid(),gen_random_uuid(),NOW(),NOW()+interval '1 hour',NOW()+interval '10 minutes','HOLD','{}')");
                    s.execute("INSERT INTO bookings(id,reservation_id,facility_id,court_id,created_by,customer_id,current_holder_id,starts_at,ends_at,status,source,amount,currency,price_snapshot,hold_expires_at) SELECT id,id,facility_id,court_id,holder_id,holder_id,holder_id,starts_at,ends_at,'PENDING','ONLINE',100,'VND','{}',expires_at FROM slot_reservations");
                    s.execute("INSERT INTO booking_groups(id,owner_id,name,state,deadline,max_members) SELECT id,customer_id,'Legacy','GROUP_PENDING',hold_expires_at,2 FROM bookings");
                    s.execute("INSERT INTO group_members(id,group_id,user_id,display_name,amount_due,amount_paid,payment_state) SELECT gen_random_uuid(),id,customer_id,'Legacy',100,0,'WAITING_FOR_PAYMENT' FROM bookings");
                }
            }
            var latest=Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema).load();latest.migrate();latest.validate();assertThat(latest.info().current().getVersion().toString()).isEqualTo("4");
            try(var c=DriverManager.getConnection(url,user,password);var s=c.createStatement()){
                try(var r=s.executeQuery("SELECT count(*) FROM "+schema+".flyway_schema_history WHERE NOT success")){r.next();assertThat(r.getInt(1)).isZero();}
                if(upgrade)try(var r=s.executeQuery("SELECT amount_due,amount_paid,payment_state,last_reminder_at FROM "+schema+".group_members")){assertThat(r.next()).isTrue();assertThat(r.getBigDecimal(1)).isEqualByComparingTo("100");assertThat(r.getBigDecimal(2)).isZero();assertThat(r.getString(3)).isEqualTo("WAITING_FOR_PAYMENT");assertThat(r.getTimestamp(4)).isNull();}
            }
        }}finally{if(temporary!=null)temporary.stop();}
    }
}
