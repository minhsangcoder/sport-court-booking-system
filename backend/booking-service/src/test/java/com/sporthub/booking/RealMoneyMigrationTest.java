package com.sporthub.booking;

import static org.assertj.core.api.Assertions.*;

import java.sql.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

class RealMoneyMigrationTest {
  @Test
  void realSchemaPreservesLegacyAndEnforcesCapacityAndReceiptIdentity() throws Exception {
    try (var pg = new PostgreSQLContainer<>("postgres:16-alpine")) {
      pg.start();
      try (var c = pg.createConnection("");
          var s = c.createStatement()) {
        s.execute("CREATE EXTENSION IF NOT EXISTS \"uuid-ossp\"");
      }
      Flyway.configure()
          .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
          .target("16")
          .load()
          .migrate();
      try (var c = pg.createConnection("");
          var s = c.createStatement()) {
        s.execute(
            "CREATE TEMP TABLE ids AS SELECT gen_random_uuid() booking,gen_random_uuid()"
                + " reservation,gen_random_uuid() facility,gen_random_uuid()"
                + " court,gen_random_uuid() payer,gen_random_uuid() owner,gen_random_uuid()"
                + " payment,gen_random_uuid() obligation,gen_random_uuid()"
                + " operation,gen_random_uuid() destination,gen_random_uuid() entitlement");
        s.execute(
            "INSERT INTO"
                + " slot_reservations(id,court_id,facility_id,holder_id,starts_at,ends_at,expires_at,state,quote)"
                + " SELECT reservation,court,facility,payer,now()+interval '1 day',now()+interval"
                + " '25 hours',now()+interval '10 minutes','HOLD','{}' FROM ids");
        s.execute(
            "INSERT INTO"
                + " bookings(id,reservation_id,facility_id,court_id,created_by,customer_id,current_holder_id,starts_at,ends_at,status,source,amount,currency,price_snapshot,hold_expires_at)"
                + " SELECT"
                + " booking,reservation,facility,court,payer,payer,payer,r.starts_at,r.ends_at,'PENDING','ONLINE',100000,'VND','{}',r.expires_at"
                + " FROM ids JOIN slot_reservations r ON r.id=reservation");
        var all =
            Flyway.configure()
                .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                .target("17")
                .load();
        all.migrate();
        all.validate();
        assertThat(all.info().current().getVersion().toString()).isEqualTo("17");
        try (var r = s.executeQuery("SELECT count(*) FROM booking_money_cohorts")) {
          r.next();
          assertThat(r.getInt(1)).isZero();
        }
        s.execute(
            "INSERT INTO booking_money_cohorts SELECT"
                + " booking,facility,owner,gen_random_uuid(),gen_random_uuid(),'REAL',clock_timestamp()"
                + " FROM ids");
        assertThatThrownBy(
                () ->
                    s.execute(
                        "UPDATE booking_money_cohorts SET receiver_owner_id=gen_random_uuid()"))
            .isInstanceOf(SQLException.class);
        s.execute(
            "INSERT INTO booking_real_bank_credits SELECT"
                + " gen_random_uuid(),payment,booking,payer,100000,now(),gen_random_uuid(),owner,'proof',gen_random_uuid(),now()"
                + " FROM ids");
        s.execute(
            "INSERT INTO"
                + " facility_disbursement_obligations(id,source_key,booking_id,facility_id,receiver_owner_id,payment_id,kind,payee_id,amount,destination_revision_id,destination_hash,state)"
                + " SELECT"
                + " obligation,'one',booking,facility,owner,payment,'TECHNICAL_REFUND',payer,80000,destination,'hash','PREPARED'"
                + " FROM ids");
        s.execute(
            "INSERT INTO"
                + " facility_refund_entitlements(id,source_key,booking_id,facility_id,reason,base_amount,rate_bps,target_amount,requested_by)"
                + " SELECT"
                + " entitlement,'entitlement',booking,facility,'LATE_COLLECTION',80000,10000,80000,payer"
                + " FROM ids");
        s.execute(
            "INSERT INTO"
                + " facility_refund_allocations(id,entitlement_id,payment_id,base_amount,amount,payer_id,guest_booking_id,obligation_id)"
                + " SELECT gen_random_uuid(),entitlement,payment,80000,80000,payer,NULL,obligation"
                + " FROM ids");
        assertThatThrownBy(
                () ->
                    s.execute(
                        "INSERT INTO"
                            + " facility_refund_allocations(id,entitlement_id,payment_id,base_amount,amount,payer_id,guest_booking_id,obligation_id)"
                            + " SELECT"
                            + " gen_random_uuid(),entitlement,payment,30000,30000,payer,NULL,obligation"
                            + " FROM ids"))
            .isInstanceOf(SQLException.class);
        s.execute(
            "INSERT INTO"
                + " facility_disbursement_operations(id,obligation_id,claimed_by,destination_revision_id,destination_hash,amount,description,state)"
                + " SELECT operation,obligation,owner,destination,'hash',80000,'SH HO 1','PREPARED'"
                + " FROM ids");
        assertThatThrownBy(
                () ->
                    s.execute(
                        "INSERT INTO"
                            + " facility_disbursement_operations(id,obligation_id,claimed_by,destination_revision_id,destination_hash,amount,description,state)"
                            + " SELECT"
                            + " gen_random_uuid(),obligation,payer,destination,'hash',80000,'SH HO"
                            + " 2','PREPARED' FROM ids"))
            .isInstanceOf(SQLException.class);
        assertThatThrownBy(
                () ->
                    s.execute(
                        "INSERT INTO facility_disbursement_receipts SELECT"
                            + " gen_random_uuid(),operation,obligation,owner,'REF','REF',79999,destination,'hash','MANUALLY_RECORDED',now(),owner"
                            + " FROM ids"))
            .isInstanceOf(SQLException.class);
        s.execute(
            "INSERT INTO facility_disbursement_receipts SELECT"
                + " gen_random_uuid(),operation,obligation,owner,'REF','REF',80000,destination,'hash','MANUALLY_RECORDED',now(),owner"
                + " FROM ids");
        assertThatThrownBy(() -> s.execute("DELETE FROM facility_disbursement_receipts"))
            .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> s.execute("UPDATE facility_disbursement_receipts SET amount=1"))
            .isInstanceOf(SQLException.class);
        assertThatThrownBy(
                () ->
                    s.execute(
                        "INSERT INTO"
                            + " facility_transfer_settlements(acquisition_id,payment_id,booking_id,facility_id,court_id,receiver_owner_id,seller_id,buyer_id,amount,fee_bps,policy_version,fee_amount,seller_net_amount,terms_hash,terms,handoff_at)"
                            + " SELECT"
                            + " gen_random_uuid(),payment,booking,facility,court,owner,payer,gen_random_uuid(),100000,700,1,7000,94000,'hash','{\"mode\":\"REAL\",\"protocol\":\"FACILITY_REAL_V1\"}',now()"
                            + " FROM ids"))
            .isInstanceOf(SQLException.class);
        s.execute(
            "CREATE TEMP TABLE preserved AS SELECT (SELECT to_jsonb(b) FROM bookings b LIMIT 1)"
                + " booking,(SELECT to_jsonb(o) FROM facility_disbursement_obligations o LIMIT 1)"
                + " obligation,(SELECT to_jsonb(r) FROM facility_disbursement_receipts r LIMIT 1)"
                + " receipt,(SELECT to_jsonb(a) FROM facility_refund_allocations a LIMIT 1)"
                + " allocation");
        var latest =
            Flyway.configure()
                .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
                .load();
        latest.migrate();
        latest.validate();
        assertThat(latest.info().current().getVersion().toString()).isEqualTo("21");
        try (var rows =
            s.executeQuery(
                "SELECT count(*) FROM information_schema.tables WHERE table_name IN"
                    + " ('facility_disbursement_operations','facility_finance_commands','facility_finance_audit')")) {
          rows.next();
          assertThat(rows.getInt(1)).isZero();
        }
        try (var rows =
            s.executeQuery(
                "SELECT (SELECT to_jsonb(b) FROM bookings b LIMIT 1)=p.booking AND (SELECT"
                    + " to_jsonb(o) FROM facility_disbursement_obligations o LIMIT 1)=p.obligation"
                    + " AND (SELECT to_jsonb(r) FROM facility_disbursement_receipts r LIMIT"
                    + " 1)=p.receipt AND (SELECT"
                    + " to_jsonb(a)-ARRAY['beneficiary_id','peer_acquisition_id'] FROM"
                    + " facility_refund_allocations a LIMIT 1)=p.allocation FROM preserved p")) {
          rows.next();
          assertThat(rows.getBoolean(1)).isTrue();
        }
      }
      var fresh =
          Flyway.configure()
              .dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())
              .schemas("fresh")
              .defaultSchema("fresh")
              .load();
      fresh.migrate();
      fresh.validate();
    }
  }
}
