package com.sporthub.booking.repository;
import static com.sporthub.booking.web.BookingDtos.*;
import com.fasterxml.jackson.databind.*;
import com.sporthub.common.exception.ResourceNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.sql.*;
import java.time.*;
import java.util.*;

@Repository
public class BookingRepository {
    private final JdbcTemplate jdbc;private final ObjectMapper json;
    public BookingRepository(JdbcTemplate jdbc,ObjectMapper json) {this.jdbc=jdbc;this.json=json;}
    public JdbcTemplate jdbc(){return jdbc;}
    public void lock(UUID id){jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,id.toString());}
    public Hold hold(UUID id) {return jdbc.query("SELECT * FROM slot_reservations WHERE id=?",(r,n)->new Hold(id,uuid(r,"court_id"),uuid(r,"facility_id"),uuid(r,"holder_id"),instant(r,"starts_at"),instant(r,"ends_at"),instant(r,"expires_at"),r.getString("state"),read(r.getString("quote"))),id).stream().findFirst().orElseThrow(()->new ResourceNotFoundException("Hold not found"));}
    public Booking find(UUID id) {return list("SELECT * FROM bookings WHERE id=?",id).stream().findFirst().orElseThrow(()->new ResourceNotFoundException("Booking not found"));}
    public List<Booking> mine(UUID id){return list("SELECT * FROM bookings WHERE current_holder_id=? OR customer_id=? OR created_by=? ORDER BY starts_at DESC LIMIT 200",id,id,id);}
    public List<Booking> facility(UUID id){return list("SELECT * FROM bookings WHERE facility_id=? ORDER BY starts_at DESC LIMIT 500",id);}
    public List<Booking> list(String sql,Object... args) {return jdbc.query(sql,mapper(),args);}
    public List<History> history(UUID id) {return jdbc.query("SELECT * FROM booking_history WHERE booking_id=? ORDER BY created_at,id",(r,n)->new History(uuid(r,"id"),r.getString("action"),uuid(r,"actor_id"),read(r.getString("details")),instant(r,"created_at")),id);}
    public void history(UUID id,UUID actor,String action,Object details) {jdbc.update("INSERT INTO booking_history(id,booking_id,actor_id,action,details) VALUES(?,?,?,?,?::jsonb)",UUID.randomUUID(),id,actor,action,write(details));}
    public String write(Object value){try{return json.writeValueAsString(value);}catch(Exception ex){throw new IllegalStateException(ex);}}
    public JsonNode read(String value){try{return json.readTree(value);}catch(Exception ex){throw new IllegalStateException(ex);}}
    private RowMapper<Booking> mapper() {return (r,n)->new Booking(uuid(r,"id"),uuid(r,"facility_id"),uuid(r,"court_id"),uuid(r,"customer_id"),uuid(r,"current_holder_id"),uuid(r,"created_by"),instant(r,"starts_at"),instant(r,"ends_at"),r.getString("status"),r.getString("source"),r.getString("guest_name"),r.getString("guest_phone"),r.getBigDecimal("amount"),r.getString("currency"),read(r.getString("price_snapshot")),instant(r,"hold_expires_at"),uuid(r,"payment_id"),instant(r,"paid_at"),instant(r,"checked_in_at"),instant(r,"completed_at"),r.getLong("version"),r.getInt("checkin_token_version"));}
    public static UUID uuid(ResultSet r,String key)throws SQLException{return r.getObject(key,UUID.class);}
    public static Instant instant(ResultSet r,String key)throws SQLException {var time=r.getTimestamp(key);return time==null?null:time.toInstant();}
}
