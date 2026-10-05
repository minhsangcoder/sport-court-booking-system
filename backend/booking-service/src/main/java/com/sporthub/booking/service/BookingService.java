package com.sporthub.booking.service;

import static com.sporthub.booking.web.BookingDtos.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sporthub.booking.repository.BookingRepository;
import com.sporthub.common.exception.*;
import com.sporthub.common.security.RemoteIdentity.Caller;
import com.sporthub.common.security.Signatures;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@Service @Transactional(readOnly=true)
public class BookingService {
    private final BookingRepository repo;private final BookingDependencies dependencies;
    private final int holdSeconds,maxPending,advanceDays;private final String secret;private final Clock clock;
    private final CheckinQr qr;
    private final GroupExpiration groups;
    public BookingService(BookingRepository repo,BookingDependencies dependencies,@Value("${booking.slot-hold.ttl-seconds:600}") int holdSeconds,
        @Value("${booking.slot-hold.max-pending:5}") int maxPending,@Value("${booking.advance-days:90}") int advanceDays,
        @Value("${JWT_SECRET}") String secret,Clock clock,CheckinQr qr,GroupExpiration groups) {
        this.repo=repo;this.dependencies=dependencies;this.holdSeconds=holdSeconds;this.maxPending=maxPending;this.advanceDays=advanceDays;this.secret=secret;this.clock=clock;this.qr=qr;this.groups=groups;
    }
    @Transactional
    public Hold hold(HoldInput input,String key,Caller caller,String token,boolean counter) {
        if(!counter)caller.requireRole("CUSTOMER");checkKey(key);repo.lock(caller.id());
        String fingerprint=Signatures.sha256(repo.write(input)+"|"+counter);
        UUID previous=idempotent(caller.id(),"HOLD",key,fingerprint);if(previous!=null)return repo.hold(previous);
        var now=clock.instant();if(!input.startsAt().isAfter(now)||input.startsAt().isAfter(now.plusSeconds(advanceDays*86400L)))throw new IllegalArgumentException("Slot is outside the booking window");
        if(!input.startsAt().isBefore(input.endsAt()))throw new IllegalArgumentException("Booking interval is invalid");
        repo.lock(input.courtId());expireCourt(input.courtId());
        var quote=dependencies.quote(input);UUID facility=UUID.fromString(quote.path("facilityId").asText());
        if(counter)dependencies.facilityPermission(facility,caller,token,"BOOKING_CREATE_COUNTER");
        if(quote.path("amount").decimalValue().compareTo(input.expectedAmount())!=0)throw new ConflictException("Price changed. Review the updated quote before holding again");
        Long count=repo.jdbc().queryForObject("SELECT count(*) FROM slot_reservations WHERE holder_id=? AND state='HOLD' AND expires_at>?",Long.class,caller.id(),Timestamp.from(now));
        if(count>=maxPending)throw new ConflictException("Pending hold limit reached");
        UUID id=UUID.randomUUID();Instant expires=now.plusSeconds(holdSeconds);if(expires.isAfter(input.startsAt()))expires=input.startsAt();
        try {repo.jdbc().update("INSERT INTO slot_reservations(id,court_id,facility_id,holder_id,starts_at,ends_at,expires_at,state,quote) VALUES(?,?,?,?,?,?,?,'HOLD',?::jsonb)",id,input.courtId(),facility,caller.id(),Timestamp.from(input.startsAt()),Timestamp.from(input.endsAt()),Timestamp.from(expires),repo.write(quote));}
        catch(org.springframework.dao.DataIntegrityViolationException ex) {throw new ConflictException("Slot was just held or booked by another user");}
        remember(caller.id(),"HOLD",key,fingerprint,id);return repo.hold(id);
    }
    public Hold ownHold(UUID id,Caller caller) {var h=repo.hold(id);if(!h.holderId().equals(caller.id()))throw new ForbiddenException("Hold belongs to another user");return h;}
    @Transactional
    public void releaseHold(UUID id,Caller caller) {var h=ownHold(id,caller);repo.lock(h.courtId());if(repo.jdbc().queryForObject("SELECT count(*) FROM bookings WHERE reservation_id=?",Integer.class,id)>0)throw new ConflictException("Cancel the booking instead");repo.jdbc().update("UPDATE slot_reservations SET state='RELEASED' WHERE id=? AND state='HOLD'",id);}
    @Transactional
    public Booking create(CreateInput input,String key,Caller caller,String token,boolean counter) {
        checkKey(key);repo.lock(caller.id());String fingerprint=Signatures.sha256(repo.write(input)+"|"+counter);
        UUID previous=idempotent(caller.id(),"BOOKING",key,fingerprint);if(previous!=null)return repo.find(previous);
        var h=ownHold(input.holdId(),caller);repo.lock(h.courtId());expireCourt(h.courtId());h=repo.hold(h.id());
        if(!h.state().equals("HOLD") || !h.expiresAt().isAfter(clock.instant()))throw new ConflictException("Hold expired or was released");
        if(counter) {dependencies.facilityPermission(h.facilityId(),caller,token,"BOOKING_CREATE_COUNTER");if(input.guestName()==null||input.guestName().isBlank()||input.guestPhone()==null||!input.guestPhone().matches("\\+?[0-9]{8,15}"))throw new IllegalArgumentException("Guest name and phone are required");}
        else caller.requireRole("CUSTOMER");
        var live=dependencies.quote(new HoldInput(h.courtId(),h.startsAt(),h.endsAt(),h.quote().path("amount").decimalValue()));
        if(live.path("amount").decimalValue().compareTo(h.quote().path("amount").decimalValue())!=0)throw new ConflictException("Price changed. Review price and create a new hold");
        UUID id=UUID.randomUUID();
        repo.jdbc().update("INSERT INTO bookings(id,reservation_id,facility_id,court_id,created_by,customer_id,current_holder_id,starts_at,ends_at,status,source,guest_name,guest_phone,amount,currency,price_snapshot,hold_expires_at) VALUES(?,?,?,?,?,?,?,?,?,'PENDING',?,?,?,?,?,?::jsonb,?)",
            id,h.id(),h.facilityId(),h.courtId(),caller.id(),caller.id(),caller.id(),Timestamp.from(h.startsAt()),Timestamp.from(h.endsAt()),counter?"OFFLINE_COUNTER":"ONLINE",input.guestName(),input.guestPhone(),live.path("amount").decimalValue(),live.path("currency").asText(),repo.write(live),Timestamp.from(h.expiresAt()));
        repo.history(id,caller.id(),"BOOKING_CREATED",Map.of("source",counter?"OFFLINE_COUNTER":"ONLINE"));remember(caller.id(),"BOOKING",key,fingerprint,id);return repo.find(id);
    }
    public List<Booking> mine(Caller caller){return repo.mine(caller.id());}
    public List<Booking> facility(UUID id,Caller caller,String token){dependencies.facilityPermission(id,caller,token,"BOOKING_READ");return repo.facility(id);}
    public Detail detail(UUID id,Caller caller,String token) {
        var b=repo.find(id);if(!b.currentHolderId().equals(caller.id())&&!b.customerId().equals(caller.id()))dependencies.facilityPermission(b.facilityId(),caller,token,"BOOKING_READ");
        String checkin=b.status().equals("CONFIRMED")&&b.currentHolderId().equals(caller.id())?checkinToken(b):null;
        return new Detail(b,repo.history(id),checkin,checkin==null?null:qr.svg(checkin));
    }
    public Payable payable(UUID id,Caller caller) {
        if(isGroup(id))throw new ConflictException("Pay group contributions through the group screen");
        var b=repo.find(id);if(!b.currentHolderId().equals(caller.id()))throw new ForbiddenException("Only the booking payer can pay");
        if(!b.status().equals("PENDING")||!b.holdExpiresAt().isAfter(clock.instant()))throw new ConflictException("Booking is no longer payable");
        return new Payable(id,caller.id(),b.amount(),b.currency(),b.holdExpiresAt(),"BOOKING",null);
    }
    @Transactional
    public Booking cancel(UUID id,CancelInput input,Caller caller) {
        if(isGroup(id))throw new ConflictException("Cancel group bookings through the group screen");
        var b=repo.find(id);repo.lock(b.courtId());b=repo.find(id);
        if(!b.currentHolderId().equals(caller.id()))throw new ForbiddenException("Only the usage holder may cancel this booking");
        if(b.status().equals("CANCELLED"))return b;
        if(!b.status().equals("PENDING"))throw new ConflictException("BLOCKED_RULE: paid cancellation requires an approved refund policy");
        repo.jdbc().update("UPDATE bookings SET status='CANCELLED',version=version+1,updated_at=NOW() WHERE id=?",id);
        repo.jdbc().update("UPDATE slot_reservations SET state='RELEASED' WHERE id=(SELECT reservation_id FROM bookings WHERE id=?)",id);
        repo.history(id,caller.id(),"CANCELLED",Map.of("reason",input.reason()));return repo.find(id);
    }
    @Transactional
    public Booking checkin(UUID id,CheckinInput input,Caller caller,String token) {
        var b=repo.find(id);repo.lock(b.courtId());b=repo.find(id);dependencies.facilityPermission(b.facilityId(),caller,token,"BOOKING_CHECK_IN");
        if(!b.status().equals("CONFIRMED"))throw new ConflictException("Only a confirmed booking may check in once");
        Instant now=clock.instant();if(now.isBefore(b.startsAt().minusSeconds(1800))||!now.isBefore(b.endsAt()))throw new ConflictException("Check-in is permitted from 30 minutes before play until the booking ends");
        if(!validCheckin(b,input.token(),now))throw new ForbiddenException("Check-in code expired, is invalid or belongs to a previous usage holder");
        repo.jdbc().update("UPDATE bookings SET status='CHECKED_IN',checked_in_at=?,version=version+1,updated_at=NOW() WHERE id=?",Timestamp.from(now),id);
        repo.history(id,caller.id(),"CHECKED_IN",Map.of());return repo.find(id);
    }
    @Transactional
    public Booking complete(UUID id,Caller caller,String token) {
        var b=repo.find(id);repo.lock(b.courtId());b=repo.find(id);dependencies.facilityPermission(b.facilityId(),caller,token,"BOOKING_COMPLETE");
        if(!b.status().equals("CHECKED_IN"))throw new ConflictException("Booking must be checked in before completing");
        if(clock.instant().isBefore(b.endsAt()))throw new ConflictException("Booking may only complete after its scheduled end");
        repo.jdbc().update("UPDATE bookings SET status='COMPLETED',completed_at=?,version=version+1,updated_at=NOW() WHERE id=?",Timestamp.from(clock.instant()),id);
        repo.history(id,caller.id(),"COMPLETED",Map.of());return repo.find(id);
    }
    public JsonNode availability(UUID court,String date) {
        var preview=(ObjectNode)dependencies.preview(court,date).deepCopy();
        var rows=repo.jdbc().queryForList("SELECT starts_at,ends_at,state,expires_at FROM slot_reservations WHERE court_id=? AND (state='BOOKED' OR (state='HOLD' AND expires_at>?))",court,Timestamp.from(clock.instant()));
        for(var slot:preview.withArray("slots"))if(slot.path("state").asText().equals("ELIGIBLE")) {
            Instant start=Instant.parse(slot.path("startsAt").asText()),end=Instant.parse(slot.path("endsAt").asText());String reason=null;
            if(!start.isAfter(clock.instant()))reason="PAST_SLOT";
            else for(var row:rows)if(start.isBefore(((Timestamp)row.get("ends_at")).toInstant())&&end.isAfter(((Timestamp)row.get("starts_at")).toInstant())){reason=row.get("state").equals("HOLD")?"HELD":"BOOKED";break;}
            ((ObjectNode)slot).put("state",reason==null?"AVAILABLE":"BLOCKED");if(reason!=null)((ObjectNode)slot).put("reason",reason);
        }
        return preview;
    }
    @Transactional
    public void expireCourt(UUID court) {
        repo.lock(court);
        var bookings=repo.list("SELECT * FROM bookings WHERE court_id=? AND status='PENDING' AND hold_expires_at<=?",court,Timestamp.from(clock.instant()));
        for(var b:bookings){groups.expire(b);repo.jdbc().update("UPDATE bookings SET status='EXPIRED',version=version+1,updated_at=NOW() WHERE id=?",b.id());repo.history(b.id(),null,"EXPIRED",Map.of());}
        repo.jdbc().update("UPDATE slot_reservations SET state='RELEASED' WHERE court_id=? AND state='HOLD' AND expires_at<=?",court,Timestamp.from(clock.instant()));
    }
    private String checkinToken(Booking b){Instant expires=clock.instant().plusSeconds(120);if(expires.isAfter(b.endsAt()))expires=b.endsAt();String value=b.id()+"|"+b.currentHolderId()+"|"+b.checkinTokenVersion()+"|"+expires.getEpochSecond();return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))+"."+Signatures.hmac(secret,"CHECKIN|"+value);}
    private boolean isGroup(UUID id){return repo.jdbc().queryForObject("SELECT count(*) FROM booking_groups WHERE id=?",Integer.class,id)>0;}
    private boolean validCheckin(Booking b,String token,Instant now) {
        try {String[] parts=token.split("\\.");if(parts.length!=2)return false;String value=new String(Base64.getUrlDecoder().decode(parts[0]),java.nio.charset.StandardCharsets.UTF_8);String[] fields=value.split("\\|");
            return fields.length==4&&fields[0].equals(b.id().toString())&&fields[1].equals(b.currentHolderId().toString())&&Integer.parseInt(fields[2])==b.checkinTokenVersion()
                && Instant.ofEpochSecond(Long.parseLong(fields[3])).isAfter(now)&&Signatures.matches(Signatures.hmac(secret,"CHECKIN|"+value),parts[1]);
        } catch(Exception ex){return false;}
    }
    private UUID idempotent(UUID actor,String operation,String key,String fingerprint) {
        var rows=repo.jdbc().queryForList("SELECT fingerprint,resource_id FROM request_idempotency WHERE actor_id=? AND operation=? AND key=?",actor,operation,key);
        if(rows.isEmpty())return null;if(!rows.get(0).get("fingerprint").equals(fingerprint))throw new ConflictException("Idempotency key was used with different input");return (UUID)rows.get(0).get("resource_id");
    }
    private void remember(UUID actor,String operation,String key,String fingerprint,UUID resource){repo.jdbc().update("INSERT INTO request_idempotency(actor_id,operation,key,fingerprint,resource_id) VALUES(?,?,?,?,?)",actor,operation,key,fingerprint,resource);}
    private void checkKey(String key){if(key==null||!key.matches("[A-Za-z0-9_.:-]{1,100}"))throw new IllegalArgumentException("A valid Idempotency-Key is required");}
}
