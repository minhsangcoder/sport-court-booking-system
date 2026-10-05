package com.sporthub.transfer.repository;
import com.fasterxml.jackson.databind.*;
import com.sporthub.common.exception.*;
import com.sporthub.transfer.web.TransferDtos.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.*;
@Repository
public class TransferRepository {
 public record Row(UUID id,UUID bookingId,UUID sellerId,UUID facilityId,UUID courtId,JsonNode snapshot,BigDecimal originalAmount,
  BigDecimal price,String currency,Instant startsAt,Instant endsAt,Instant deadline,String state,String workflow,UUID activeAcquisitionId,long version){}
 private final JdbcTemplate jdbc;private final ObjectMapper json;
 public TransferRepository(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}
 public JdbcTemplate jdbc(){return jdbc;}
 public void lock(UUID id){jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,id.toString());}
 public Row find(UUID id){return rows("SELECT * FROM transfer_listings WHERE id=?",id).stream().findFirst().orElseThrow(()->new ResourceNotFoundException("Transfer listing not found"));}
 public List<Row> rows(String sql,Object...args){return jdbc.query(sql,(r,n)->new Row(uuid(r,"id"),uuid(r,"booking_id"),uuid(r,"seller_id"),uuid(r,"facility_id"),uuid(r,"court_id"),read(r.getString("snapshot")),r.getBigDecimal("original_amount"),r.getBigDecimal("price"),r.getString("currency"),instant(r,"starts_at"),instant(r,"ends_at"),instant(r,"deadline"),r.getString("state"),r.getString("workflow"),uuid(r,"active_acquisition_id"),r.getLong("version")),args);}
 public Acquisition acquisition(UUID id){return jdbc.query("SELECT a.*,l.booking_id FROM transfer_acquisitions a JOIN transfer_listings l ON l.id=a.listing_id WHERE a.id=?",(r,n)->new Acquisition(uuid(r,"id"),uuid(r,"listing_id"),uuid(r,"booking_id"),uuid(r,"buyer_id"),r.getBigDecimal("amount"),r.getString("currency"),instant(r,"expires_at"),r.getString("state"),uuid(r,"payment_id")),id).stream().findFirst().orElseThrow(()->new ResourceNotFoundException("Acquisition not found"));}
 public Listing view(Row r,UUID caller,boolean valid){return new Listing(r.id(),r.facilityId(),r.courtId(),r.snapshot(),r.originalAmount(),r.price(),r.currency(),r.startsAt(),r.endsAt(),r.deadline(),r.state(),r.sellerId().equals(caller),valid&&r.state().equals("ACTIVE")&&r.workflow().equals("READY"),r.version());}
 public UUID previous(UUID actor,String operation,String key,String fingerprint){var rows=jdbc.queryForList("SELECT * FROM request_idempotency WHERE actor_id=? AND operation=? AND key=?",actor,operation,key);if(rows.isEmpty())return null;if(!rows.get(0).get("fingerprint").equals(fingerprint))throw new ConflictException("Idempotency key input changed");return (UUID)rows.get(0).get("resource_id");}
 public void remember(UUID actor,String operation,String key,String fingerprint,UUID id){jdbc.update("INSERT INTO request_idempotency(actor_id,operation,key,fingerprint,resource_id) VALUES(?,?,?,?,?)",actor,operation,key,fingerprint,id);}
 public void audit(Row r,UUID actor,String action,Object details){jdbc.update("INSERT INTO transfer_audit(id,listing_id,actor_id,action,details) VALUES(?,?,?,?,?::jsonb)",UUID.randomUUID(),r.id(),actor,action,write(details));}
 public String write(Object value){try{return json.writeValueAsString(value);}catch(Exception ex){throw new IllegalStateException(ex);}}
 public JsonNode read(String value){try{return json.readTree(value);}catch(Exception ex){throw new IllegalStateException(ex);}}
 private static UUID uuid(ResultSet r,String key)throws SQLException{return r.getObject(key,UUID.class);}
 private static Instant instant(ResultSet r,String key)throws SQLException{var t=r.getTimestamp(key);return t==null?null:t.toInstant();}
}
