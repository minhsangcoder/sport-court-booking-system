package com.sporthub.payment.repository;
import static com.sporthub.payment.web.PaymentDtos.*;
import com.sporthub.common.exception.ResourceNotFoundException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.Instant;
import java.util.*;
@Repository
public class PaymentRepository {
 private final JdbcTemplate jdbc;public PaymentRepository(JdbcTemplate jdbc){this.jdbc=jdbc;}
 public JdbcTemplate jdbc(){return jdbc;}
 public void lock(UUID id){jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,id.toString());}
 public Payment find(UUID id){return list("SELECT * FROM payment_orders WHERE id=?",id).stream().findFirst().orElseThrow(()->new ResourceNotFoundException("Payment not found"));}
 public List<Payment> list(String sql,Object...args){return jdbc.query(sql,(r,n)->new Payment(r.getObject("id",UUID.class),r.getObject("booking_id",UUID.class),r.getObject("payer_id",UUID.class),r.getObject("member_id",UUID.class),r.getObject("acquisition_id",UUID.class),r.getObject("seller_id",UUID.class),r.getString("purpose"),r.getBigDecimal("amount"),r.getString("currency"),r.getString("provider"),r.getString("provider_reference"),r.getString("status"),r.getTimestamp("expires_at").toInstant(),r.getTimestamp("paid_at")==null?null:r.getTimestamp("paid_at").toInstant()),args);}
}
