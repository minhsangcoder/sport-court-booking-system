package com.sporthub.identity;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class RefundPermissionMigrationTest {
    @Test void upgradeDoesNotGrantRefundOrChangePendingInvitations() {
        try (var database = new PostgreSQLContainer<>("postgres:16-alpine")) {
            database.start();
            var source = new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
            var jdbc = new JdbcTemplate(source);
            Flyway.configure().dataSource(source).target("12").load().migrate();
            var user = UUID.randomUUID(); var facility = UUID.randomUUID();
            var binding = UUID.randomUUID(); var profile = UUID.randomUUID(); var invitation = UUID.randomUUID();
            jdbc.update("INSERT INTO users(id,email,password_hash,status,email_verified) VALUES(?,?,'unused','ACTIVE',true)",user,user+"@example.test");
            jdbc.update("INSERT INTO user_profiles(user_id,full_name) VALUES(?,'Existing Staff')",user);
            jdbc.update("INSERT INTO user_roles(user_id,role) VALUES(?,'STAFF')",user);
            jdbc.update("INSERT INTO staff_facility_bindings(id,staff_user_id,facility_id,assigned_by) VALUES(?,?,?,?)",binding,user,facility,user);
            jdbc.update("INSERT INTO staff_permissions(binding_id,permission) VALUES(?,'BOOKING_READ')",binding);
            jdbc.update("INSERT INTO staff_profiles(id,facility_id,full_name,binding_id,created_by) VALUES(?,?,'Existing Staff',?,?)",profile,facility,binding,user);
            jdbc.update("""
                    INSERT INTO staff_invitations(id,profile_id,facility_id,target_user_id,invited_by,permissions,expires_at)
                    VALUES(?,?,?,?,?,ARRAY['BOOKING_READ'],NOW()+INTERVAL '1 day')
                    """,invitation,profile,facility,user,user);
            var latest = Flyway.configure().dataSource(source).load();
            latest.migrate(); latest.validate();
            assertThat(latest.info().current().getVersion().toString()).isEqualTo("16");
            assertThat(jdbc.queryForList("SELECT permission FROM staff_permissions WHERE binding_id=?",String.class,binding)).containsExactly("BOOKING_READ");
            assertThat(jdbc.queryForObject("SELECT permissions=ARRAY['BOOKING_READ']::text[] AND state='PENDING' FROM staff_invitations WHERE id=?",Boolean.class,invitation)).isTrue();
            jdbc.update("INSERT INTO staff_permissions(binding_id,permission) VALUES(?,'BOOKING_REFUND')",binding);
            jdbc.update("UPDATE staff_invitations SET permissions=ARRAY['BOOKING_READ','BOOKING_REFUND'] WHERE id=?",invitation);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM staff_permissions WHERE permission='TRANSFER_PAYOUT'",Integer.class)).isZero();
            jdbc.update("INSERT INTO staff_permissions(binding_id,permission) VALUES(?,'TRANSFER_PAYOUT')",binding);
            jdbc.update("UPDATE staff_invitations SET permissions=ARRAY['BOOKING_READ','TRANSFER_PAYOUT'] WHERE id=?",invitation);
            assertThatThrownBy(() -> jdbc.update("INSERT INTO staff_permissions(binding_id,permission) VALUES(?,'BOOKING_COMPLETE')",binding)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("UPDATE staff_invitations SET permissions=ARRAY['BOOKING_CANCEL_PENDING'] WHERE id=?",invitation)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
    }
}
