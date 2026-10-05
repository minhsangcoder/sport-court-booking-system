package com.sporthub.schedule.repository;

import static com.sporthub.schedule.web.ScheduleDtos.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.*;
import java.util.*;
import java.sql.*;

@Repository
public class ScheduleRepository {
    private final JdbcTemplate jdbc;
    public ScheduleRepository(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    public JdbcTemplate jdbc() {return jdbc;}
    // Serializes configuration writes across all scopes of one facility.
    public void lock(UUID id) {jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,id.toString());}
    public List<Hours> hours(UUID facility) {
        return jdbc.query("SELECT * FROM operating_hours WHERE facility_id=? AND is_active ORDER BY day_of_week,open_time",(r,n)->
            new Hours(uuid(r,"id"),facility,uuid(r,"court_id"),r.getInt("day_of_week"),r.getObject("open_time",LocalTime.class),
                r.getObject("close_time",LocalTime.class),r.getInt("slot_duration_minutes"),r.getLong("version")),facility);
    }
    public List<ExceptionView> exceptions(UUID facility) {
        return jdbc.query("SELECT * FROM exception_calendar WHERE facility_id=? ORDER BY exception_date,open_time",(r,n)->
            new ExceptionView(uuid(r,"id"),facility,uuid(r,"court_id"),r.getObject("exception_date",LocalDate.class),
                r.getString("exception_type"),r.getObject("open_time",LocalTime.class),r.getObject("close_time",LocalTime.class),r.getString("reason")),facility);
    }
    public List<PriceRule> rules(UUID facility) {
        return jdbc.query("SELECT * FROM pricing_rules WHERE facility_id=? ORDER BY created_at DESC",(r,n)->
            new PriceRule(uuid(r,"id"),facility,uuid(r,"court_id"),uuid(r,"sport_category_id"),r.getObject("day_of_week")==null?null:r.getInt("day_of_week"),
                r.getObject("specific_date",LocalDate.class),r.getObject("start_time",LocalTime.class),r.getObject("end_time",LocalTime.class),
                r.getBigDecimal("price_per_slot"),r.getInt("priority"),r.getString("label"),r.getObject("effective_from",LocalDate.class),
                r.getObject("effective_to",LocalDate.class),r.getString("currency"),r.getBoolean("is_active"),r.getLong("version")),facility);
    }
    private static UUID uuid(ResultSet r,String name) throws SQLException {return r.getObject(name,UUID.class);}
}
