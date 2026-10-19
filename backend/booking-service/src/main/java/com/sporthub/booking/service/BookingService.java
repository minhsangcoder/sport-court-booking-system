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
    private final int holdSeconds,maxPending,advanceDays,maxSlots;private final String secret;private final Clock clock;
    private final CheckinQr qr;
    private final GroupExpiration groups;
    private final ConfigurationGuardService configuration;
    private final BookingLifecycle lifecycle;
    public BookingService(BookingRepository repo,BookingDependencies dependencies,@Value("${booking.slot-hold.ttl-seconds:600}") int holdSeconds,
        @Value("${booking.slot-hold.max-pending:5}") int maxPending,@Value("${booking.advance-days:90}") int advanceDays,
        @Value("${JWT_SECRET}") String secret,Clock clock,CheckinQr qr,GroupExpiration groups,ConfigurationGuardService configuration,BookingLifecycle lifecycle,
        @Value("${booking.slot-hold.max-slots-per-hold:10}") int maxSlots) {
        if(holdSeconds<=0||maxPending<=0||advanceDays<=0||maxSlots<=0)throw new IllegalArgumentException("Booking limits must be positive");
        this.repo=repo;this.dependencies=dependencies;this.holdSeconds=holdSeconds;this.maxPending=maxPending;this.advanceDays=advanceDays;this.secret=secret;this.clock=clock;this.qr=qr;this.groups=groups;this.configuration=configuration;this.lifecycle=lifecycle;this.maxSlots=maxSlots;
    }
    @Transactional
    public Hold hold(HoldInput input,String key,Caller caller,String token,boolean counter) {
        if(!counter)caller.requireRole("CUSTOMER");checkKey(key);repo.lock(caller.id());
        String fingerprint=Signatures.sha256(repo.write(new HoldInput(input.courtId(),input.startsAt(),input.endsAt(),input.expectedAmount().stripTrailingZeros()))+"|"+counter);
        UUID previous=idempotent(caller.id(),"HOLD",key,fingerprint);if(previous!=null){var old=repo.hold(previous);if(counter)dependencies.facilityPermission(old.facilityId(),caller,token,"BOOKING_CREATE_COUNTER");return old;}
        var now=clock.instant();if(!input.startsAt().isAfter(now)||input.startsAt().isAfter(now.plusSeconds(advanceDays*86400L)))throw new IllegalArgumentException("Slot is outside the booking window");
        if(!input.startsAt().isBefore(input.endsAt()))throw new IllegalArgumentException("Booking interval is invalid");
        repo.lock(input.courtId());expireCourt(input.courtId());
        var initial=dependencies.quote(input);UUID facility=UUID.fromString(initial.path("facilityId").asText());
        if(counter)dependencies.facilityPermission(facility,caller,token,"BOOKING_CREATE_COUNTER");
        configuration.requireAccepting(facility);
        // The initial quote may predate a committed configuration change. Quote again under the fence.
        var quote=dependencies.quote(input);
        validateQuote(quote,input);
        if(quote.path("segments").size()>maxSlots)throw new IllegalArgumentException("Too many slots in one hold");
        if(!facility.toString().equals(quote.path("facilityId").asText()))throw new ConflictException("Court facility changed");
        now=clock.instant();
        if(!input.startsAt().isAfter(now)||input.startsAt().isAfter(now.plusSeconds(advanceDays*86400L)))throw new ConflictException("Slot is outside the booking window after schedule validation");
        if(counter)dependencies.facilityPermission(facility,caller,token,"BOOKING_CREATE_COUNTER");
        if(quote.path("amount").decimalValue().compareTo(input.expectedAmount())!=0)throw new ConflictException("Price changed. Review the updated quote before holding again");
        Long count=repo.jdbc().queryForObject("SELECT count(*) FROM slot_reservations r WHERE (holder_id=? OR EXISTS(SELECT 1 FROM bookings b WHERE b.reservation_id=r.id AND b.created_by=? AND b.source='OFFLINE_COUNTER')) AND r.state='HOLD' AND r.expires_at>?",Long.class,caller.id(),caller.id(),Timestamp.from(now));
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
        UUID previous=idempotent(caller.id(),"BOOKING",key,fingerprint);if(previous!=null){var old=repo.find(previous);if(counter)dependencies.facilityPermission(old.facilityId(),caller,token,"BOOKING_CREATE_COUNTER");else caller.requireRole("CUSTOMER");return replayView(old,caller);}
        var h=ownHold(input.holdId(),caller);repo.lock(h.courtId());expireCourt(h.courtId());h=repo.hold(h.id());
        if(!h.state().equals("HOLD") || !h.expiresAt().isAfter(clock.instant()))throw new ConflictException("Hold expired or was released");
        if(repo.jdbc().queryForObject("SELECT count(*) FROM bookings WHERE reservation_id=?",Integer.class,h.id())>0)throw new ConflictException("A booking already uses this hold; retry with the original Idempotency-Key");
        if(counter) {dependencies.facilityPermission(h.facilityId(),caller,token,"BOOKING_CREATE_COUNTER");if(input.guestName()==null||input.guestName().isBlank()||input.guestPhone()==null||!input.guestPhone().matches("\\+?[0-9]{8,15}"))throw new IllegalArgumentException("Guest name and phone are required");}
        else caller.requireRole("CUSTOMER");
        configuration.requireAccepting(h.facilityId());
        var live=dependencies.quote(new HoldInput(h.courtId(),h.startsAt(),h.endsAt(),h.quote().path("amount").decimalValue()));
        validateQuote(live,new HoldInput(h.courtId(),h.startsAt(),h.endsAt(),h.quote().path("amount").decimalValue()));
        if(!h.expiresAt().isAfter(clock.instant()))throw new ConflictException("Hold expired during price validation");
        if(!h.facilityId().toString().equals(live.path("facilityId").asText())||!h.courtId().toString().equals(live.path("courtId").asText()))throw new ConflictException("Quote scope changed");
        if(!h.quote().path("currency").asText().equals(live.path("currency").asText())||live.path("amount").decimalValue().compareTo(h.quote().path("amount").decimalValue())!=0)throw new ConflictException("Price changed. Review price and create a new hold");
        UUID linkedCustomer=counter?dependencies.counterCustomer(h.facilityId(),input.guestPhone(),token):null;
        if(counter)dependencies.facilityPermission(h.facilityId(),caller,token,"BOOKING_CREATE_COUNTER");
        if(!h.expiresAt().isAfter(clock.instant()))throw new ConflictException("Hold expired during customer validation");
        UUID holder=linkedCustomer==null?caller.id():linkedCustomer;
        UUID id=UUID.randomUUID();repo.auditContext(caller.id(),counter?"COUNTER_API":"BOOKING_API");
        repo.jdbc().update("INSERT INTO bookings(id,reservation_id,facility_id,court_id,created_by,customer_id,current_holder_id,starts_at,ends_at,status,source,guest_name,guest_phone,amount,currency,price_snapshot,hold_expires_at) VALUES(?,?,?,?,?,?,?,?,?,'PENDING',?,?,?,?,?,?::jsonb,?)",
            id,h.id(),h.facilityId(),h.courtId(),caller.id(),holder,holder,Timestamp.from(h.startsAt()),Timestamp.from(h.endsAt()),counter?"OFFLINE_COUNTER":"ONLINE",counter?input.guestName().trim():input.guestName(),input.guestPhone(),live.path("amount").decimalValue(),live.path("currency").asText(),repo.write(live),Timestamp.from(h.expiresAt()));
        if(counter){repo.jdbc().update("INSERT INTO counter_booking_guests(booking_id,operator_id,linked_customer_id,guest_name,guest_phone) VALUES(?,?,?,?,?)",id,caller.id(),linkedCustomer,input.guestName().trim(),input.guestPhone());repo.jdbc().update("UPDATE slot_reservations SET holder_id=? WHERE id=?",holder,h.id());}
        repo.history(id,caller.id(),"BOOKING_CREATED",Map.of("source",counter?"OFFLINE_COUNTER":"ONLINE"));remember(caller.id(),"BOOKING",key,fingerprint,id);return repo.find(id);
    }
    /** Single local transaction: failures leave neither a new hold nor a partial booking. */
    @Transactional public Booking counter(CounterInput input,String key,Caller caller,String token){
        if(input.guestName()==null||input.guestName().isBlank()||input.guestName().length()>180||input.guestPhone()==null||!input.guestPhone().matches("\\+?[0-9]{8,15}"))throw new IllegalArgumentException("Guest name and phone are required");
        if(input.paymentMethod()!=null&&!input.paymentMethod().equals("DEMO"))throw new ConflictException("BLOCKED_RULE: cash/POS collection or a real provider is not configured");
        checkKey(key);repo.lock(caller.id());String fingerprint=Signatures.sha256(repo.write(new CounterInput(input.courtId(),input.startsAt(),input.endsAt(),input.expectedAmount().stripTrailingZeros(),input.guestName().trim(),input.guestPhone(),input.paymentMethod())));
        UUID previous=idempotent(caller.id(),"COUNTER_BOOKING",key,fingerprint);if(previous!=null){var b=repo.find(previous);dependencies.facilityPermission(b.facilityId(),caller,token,"BOOKING_CREATE_COUNTER");return replayView(b,caller);}
        var hold=hold(new HoldInput(input.courtId(),input.startsAt(),input.endsAt(),input.expectedAmount()),"counter-hold-"+Signatures.sha256(key),caller,token,true);
        var booking=create(new CreateInput(hold.id(),input.guestName().trim(),input.guestPhone()),"counter-create-"+Signatures.sha256(key),caller,token,true);remember(caller.id(),"COUNTER_BOOKING",key,fingerprint,booking.id());return booking;
    }
    public List<Booking> mine(Caller caller,String token){return repo.mine(caller.id()).stream().filter(b->{
        if(!counterOperator(b,caller))return true;
        try{dependencies.facilityPermission(b.facilityId(),caller,token,"BOOKING_READ");return true;}
        catch(ForbiddenException denied){return false;}
    }).map(b->personalView(b,caller)).toList();}
    public List<Booking> facility(UUID id,Caller caller,String token){dependencies.facilityPermission(id,caller,token,"BOOKING_READ");return repo.facility(id);}
    public Detail detail(UUID id,Caller caller,String token) {
        var b=repo.find(id);if(counterOperator(b,caller)||!b.currentHolderId().equals(caller.id())&&!b.customerId().equals(caller.id())&&!b.createdBy().equals(caller.id())&&!historicalHolder(b,caller))dependencies.facilityPermission(b.facilityId(),caller,token,"BOOKING_READ");
        boolean usage=b.currentHolderId().equals(caller.id());
        if(usage&&counterOperator(b,caller)) {
            try{dependencies.facilityPermission(b.facilityId(),caller,token,"BOOKING_CHECK_IN");}
            catch(ForbiddenException denied){usage=false;}
        }
        String checkin=b.status().equals("CONFIRMED")&&usage?checkinToken(b):null;
        return new Detail(personalView(b,caller),repo.history(id),checkin,checkin==null?null:qr.svg(checkin));
    }
    private boolean counterOperator(Booking b,Caller caller) {
        return b.source().equals("OFFLINE_COUNTER")&&b.createdBy().equals(caller.id())
            &&repo.jdbc().queryForObject("SELECT (SELECT count(*) FROM counter_booking_guests WHERE booking_id=? AND linked_customer_id=?)+(SELECT count(*) FROM transfer_leases WHERE booking_id=? AND buyer_id=? AND state='COMPLETED')",Integer.class,b.id(),caller.id(),b.id(),caller.id())==0;
    }
    private Booking personalView(Booking b,Caller caller) {
        boolean historical=historicalHolder(b,caller);
        boolean hideGuest=b.source().equals("OFFLINE_COUNTER")&&!b.createdBy().equals(caller.id())&&(b.currentHolderId().equals(caller.id())||b.customerId().equals(caller.id())||historical);
        boolean oldCreator=!b.currentHolderId().equals(caller.id())&&(historical||b.createdBy().equals(caller.id())&&!counterOperator(b,caller));
        if(hideGuest||oldCreator)
            return new Booking(b.id(),b.facilityId(),b.courtId(),b.customerId(),b.currentHolderId(),b.createdBy(),
                b.startsAt(),b.endsAt(),b.status(),b.source(),hideGuest?null:b.guestName(),hideGuest?null:b.guestPhone(),b.amount(),b.currency(),b.priceSnapshot(),
                b.holdExpiresAt(),b.paymentId(),b.paidAt(),b.checkedInAt(),b.completedAt(),b.version(),b.checkinTokenVersion(),oldCreator?null:b.bookingCode());
        return b;
    }
    private boolean historicalHolder(Booking b,Caller caller){return caller.hasRole("CUSTOMER")&&repo.jdbc().queryForObject("SELECT count(*) FROM transfer_leases WHERE booking_id=? AND seller_id=? AND state='COMPLETED'",Integer.class,b.id(),caller.id())>0;}
    private Booking replayView(Booking b,Caller caller){var view=personalView(b,caller);if(caller.id().equals(b.currentHolderId()))return view;return new Booking(view.id(),view.facilityId(),view.courtId(),view.customerId(),view.currentHolderId(),view.createdBy(),view.startsAt(),view.endsAt(),view.status(),view.source(),view.guestName(),view.guestPhone(),view.amount(),view.currency(),view.priceSnapshot(),view.holdExpiresAt(),view.paymentId(),view.paidAt(),view.checkedInAt(),view.completedAt(),view.version(),view.checkinTokenVersion(),null);}
    public Payable payable(UUID id,Caller caller,String token) {
        if(isGroup(id))throw new ConflictException("Pay group contributions through the group screen");
        var b=repo.find(id);boolean operator=counterOperator(b,caller);if(operator)dependencies.facilityPermission(b.facilityId(),caller,token,"BOOKING_CREATE_COUNTER");if(!operator&&!b.currentHolderId().equals(caller.id()))throw new ForbiddenException("Only the booking payer or original counter operator can pay");
        if(!b.status().equals("PENDING")||!b.holdExpiresAt().isAfter(clock.instant())||!lifecycle.hasLiveHold(b,clock.instant()))throw new ConflictException("Booking is no longer payable");
        return new Payable(id,caller.id(),b.amount(),b.currency(),b.holdExpiresAt(),"BOOKING",null);
    }
    @Transactional
    public Booking cancel(UUID id,CancelInput input,Caller caller,String token) {
        if(isGroup(id))throw new ConflictException("Cancel group bookings through the group screen");
        var b=repo.find(id);repo.lock(b.courtId());b=repo.find(id);
        if(input==null||input.reason()==null||input.reason().isBlank()||input.reason().length()>500)throw new IllegalArgumentException("A cancellation reason is required");
        boolean operator=counterOperator(b,caller),self=b.currentHolderId().equals(caller.id())&&!operator;
        if(operator)dependencies.facilityPermission(b.facilityId(),caller,token,"BOOKING_CREATE_COUNTER");
        else if(!self)dependencies.facilityPermission(b.facilityId(),caller,token,"BOOKING_CANCEL_PENDING");
        else caller.requireRole("CUSTOMER");
        if(b.status().equals("CANCELLED"))return b;
        if(!Set.of("PENDING","CONFIRMED").contains(b.status()))throw new ConflictException("Only pending or confirmed bookings may be cancelled before play");
        Instant now=clock.instant();if(!now.isBefore(b.startsAt()))throw new ConflictException("Booking has already started");
        if(repo.jdbc().queryForObject("SELECT count(*) FROM transfer_leases WHERE booking_id=? AND state IN ('ACTIVE','LOCKED')",Integer.class,id)>0)throw new ConflictException("Withdraw/reconcile the transfer listing before cancelling");
        if(b.status().equals("CONFIRMED")&&!self)throw new ConflictException("BLOCKED_RULE: facility paid cancellation requires the 100% refund execution/reconciliation protocol");
        if(b.status().equals("CONFIRMED")&&(b.paymentId()==null||b.paidAt()==null))throw new ConflictException("Confirmed payment evidence is incomplete");
        if(b.status().equals("PENDING")&&(!b.holdExpiresAt().isAfter(now)||!lifecycle.hasLiveHold(b,now)))throw new ConflictException("Booking payment hold has expired or changed");
        String policy=b.status().equals("CONFIRMED")?"CUSTOMER_SELF_NO_REFUND":"UNPAID_NO_REFUND";
        return lifecycle.transition(b,"CANCELLED",caller.id(),"CANCELLED",Map.of("reason",input.reason().trim(),"cancellationPolicy",policy,"refundAmount",BigDecimal.ZERO,"cancellationActor",self?"CUSTOMER":operator?"COUNTER_OPERATOR":"FACILITY"),now,null);
    }
    @Transactional
    public Booking checkin(UUID id,CheckinInput input,Caller caller,String token) {
        return checkin(id,input.token(),caller,token,false);
    }
    @Transactional public Booking checkinByCode(UUID facility,CodeInput input,Caller caller,String token){
        dependencies.facilityPermission(facility,caller,token,"BOOKING_CHECK_IN");if(input.bookingCode()==null||!input.bookingCode().matches("[0-9A-Fa-f]{10}"))throw new IllegalArgumentException("Supply a ten-character booking code");
        var ids=repo.jdbc().queryForList("SELECT id FROM bookings WHERE facility_id=? AND booking_code=?",UUID.class,facility,input.bookingCode().toUpperCase(Locale.ROOT));if(ids.isEmpty())throw new ResourceNotFoundException("Booking code not found at this facility");return checkin(ids.getFirst(),null,caller,token,true);
    }
    private Booking checkin(UUID id,String checkinCode,Caller caller,String token,boolean manual){
        var b=repo.find(id);repo.lock(b.courtId());b=repo.find(id);dependencies.facilityPermission(b.facilityId(),caller,token,"BOOKING_CHECK_IN");
        if(!b.status().equals("CONFIRMED"))throw new ConflictException("Only a confirmed booking may check in once");
        if(!isGroup(id)&&(b.paymentId()==null||b.paidAt()==null))throw new ConflictException("Verified payment evidence is required before check-in");
        if(isGroup(id)&&repo.jdbc().queryForObject("SELECT coalesce(sum(amount_paid),0) FROM group_members WHERE group_id=? AND active",BigDecimal.class,id).compareTo(b.amount())!=0)throw new ConflictException("Group contributions are not fully paid");
        if(repo.jdbc().queryForObject("SELECT count(*) FROM transfer_leases WHERE booking_id=? AND state='LOCKED'",Integer.class,id)>0)throw new ConflictException("Booking is locked by an unresolved transfer acquisition");
        Instant now=clock.instant();if(now.isBefore(b.startsAt().minusSeconds(1800))||!now.isBefore(b.endsAt()))throw new ConflictException("Check-in is permitted from 30 minutes before play until the booking ends");
        if(!manual&&!validCheckin(b,checkinCode,now))throw new ForbiddenException("Check-in code expired, is invalid or belongs to a previous usage holder");
        var result=lifecycle.transition(b,"CHECKED_IN",caller.id(),"CHECKED_IN",Map.of("checkinMethod",manual?"BOOKING_CODE":"SIGNED_QR"),now,null);
        var listings=repo.jdbc().queryForList("SELECT listing_id FROM transfer_leases WHERE booking_id=? AND state='ACTIVE'",UUID.class,id);
        repo.jdbc().update("UPDATE transfer_leases SET state='RELEASED' WHERE booking_id=? AND state='ACTIVE'",id);
        for(var listing:listings)repo.jdbc().update("INSERT INTO transfer_lease_audit(id,listing_id,action,details) VALUES(?,?,?,?::jsonb)",UUID.randomUUID(),listing,"CHECKIN_RELEASE",repo.write(Map.of("actorId",caller.id(),"bookingId",id)));
        return result;
    }
    @Transactional
    public Booking complete(UUID id,Caller caller,String token) {
        var b=repo.find(id);repo.lock(b.courtId());b=repo.find(id);dependencies.facilityPermission(b.facilityId(),caller,token,"BOOKING_COMPLETE");
        if(!b.status().equals("CHECKED_IN"))throw new ConflictException("Booking must be checked in before completing");
        if(clock.instant().isBefore(b.endsAt()))throw new ConflictException("Booking may only complete after its scheduled end");
        return lifecycle.transition(b,"COMPLETED",caller.id(),"COMPLETED",Map.of(),clock.instant(),null);
    }
    public JsonNode availability(UUID court,String date) {
        LocalDate day;
        try{day=LocalDate.parse(date);}catch(DateTimeException ex){throw new IllegalArgumentException("Use a valid ISO play date");}
        var preview=(ObjectNode)dependencies.preview(court,day.toString()).deepCopy();
        UUID facility=preview.hasNonNull("facilityId")?UUID.fromString(preview.path("facilityId").asText()):null;
        if(facility!=null){
            configuration.lockReservationFacility(facility);
            preview=(ObjectNode)dependencies.preview(court,day.toString()).deepCopy();
        }
        Instant observed=clock.instant(),horizon=observed.plusSeconds(advanceDays*86400L);
        var zone=ZoneId.of(preview.path("timezone").asText("Asia/Ho_Chi_Minh"));
        LocalDate today=observed.atZone(zone).toLocalDate();
        if(day.isBefore(today)||day.isAfter(horizon.atZone(zone).toLocalDate()))throw new IllegalArgumentException("Choose a date inside the booking window");
        boolean updating=facility!=null&&repo.jdbc().queryForObject("SELECT count(*) FROM configuration_change_guards WHERE facility_id=? AND state='PREPARED'",Integer.class,facility)>0;
        var rows=repo.jdbc().queryForList("SELECT starts_at,ends_at,state FROM slot_reservations WHERE court_id=? AND (state='BOOKED' OR (state='HOLD' AND expires_at>?))",court,Timestamp.from(observed));
        return overlayAvailability(preview,rows,observed,horizon,updating);
    }
    public Map<UUID,JsonNode> availabilityBatch(List<UUID> ids,String date) {
        if(ids==null)throw new IllegalArgumentException("Supply court IDs");
        if(ids.isEmpty())return Map.of();
        LocalDate day;
        try{day=LocalDate.parse(date);}catch(DateTimeException ex){throw new IllegalArgumentException("Use a valid ISO play date");}
        var result=new LinkedHashMap<UUID,JsonNode>();
        var jdbc=new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(repo.jdbc());
        for(int offset=0;offset<ids.size();offset+=200) {
            var batch=ids.subList(offset,Math.min(offset+200,ids.size()));
            var previews=dependencies.previews(batch,day.toString());
            var facilities=new TreeSet<UUID>();
            for(var preview:previews)if(preview.hasNonNull("facilityId"))facilities.add(UUID.fromString(preview.path("facilityId").asText()));
            for(var facility:facilities)configuration.lockReservationFacility(facility);
            if(!facilities.isEmpty())previews=dependencies.previews(batch,day.toString());
            Instant observed=clock.instant(),horizon=observed.plusSeconds(advanceDays*86400L);
            Set<UUID> updating=facilities.isEmpty()?Set.of():new HashSet<>(jdbc.queryForList(
                "SELECT facility_id FROM configuration_change_guards WHERE facility_id IN (:ids) AND state='PREPARED'",Map.of("ids",facilities),UUID.class));
            var rows=jdbc.queryForList(
                "SELECT court_id,starts_at,ends_at,state FROM slot_reservations WHERE court_id IN (:ids) AND (state='BOOKED' OR (state='HOLD' AND expires_at>:now)) AND starts_at<:end AND ends_at>:start",
                Map.of("ids",batch,"now",Timestamp.from(observed),"start",Timestamp.from(day.minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()),"end",Timestamp.from(day.plusDays(2).atStartOfDay(ZoneOffset.UTC).toInstant())));
            var byCourt=rows.stream().collect(java.util.stream.Collectors.groupingBy(row->(UUID)row.get("court_id")));
            for(var value:previews) {
                var preview=(ObjectNode)value.deepCopy();
                var zone=ZoneId.of(preview.path("timezone").asText("Asia/Ho_Chi_Minh"));
                if(day.isBefore(observed.atZone(zone).toLocalDate())||day.isAfter(horizon.atZone(zone).toLocalDate()))throw new IllegalArgumentException("Choose a date inside the booking window");
                UUID court=UUID.fromString(preview.path("courtId").asText());
                UUID facility=preview.hasNonNull("facilityId")?UUID.fromString(preview.path("facilityId").asText()):null;
                result.put(court,overlayAvailability(preview,byCourt.getOrDefault(court,List.of()),observed,horizon,facility!=null&&updating.contains(facility)));
            }
        }
        return result;
    }
    private JsonNode overlayAvailability(ObjectNode preview,List<Map<String,Object>> rows,Instant observed,Instant horizon,boolean updating) {
        for(var slot:preview.withArray("slots"))if(slot.path("state").asText().equals("ELIGIBLE")) {
            Instant start=Instant.parse(slot.path("startsAt").asText()),end=Instant.parse(slot.path("endsAt").asText());String reason=null;
            if(!start.isAfter(observed))reason="PAST_SLOT";
            else if(start.isAfter(horizon))reason="BOOKING_WINDOW";
            else if(updating)reason="CONFIGURATION_UPDATING";
            else for(var row:rows)if(start.isBefore(((Timestamp)row.get("ends_at")).toInstant())&&end.isAfter(((Timestamp)row.get("starts_at")).toInstant())){reason=row.get("state").equals("HOLD")?"HELD":"BOOKED";break;}
            ((ObjectNode)slot).put("state",reason==null?"AVAILABLE":"BLOCKED");if(reason!=null)((ObjectNode)slot).put("reason",reason);
        }
        preview.put("observedAt",observed.toString()).put("configurationUpdating",updating).put("bookingWindowEndsAt",horizon.toString());
        return preview;
    }
    @Transactional
    public void expireCourt(UUID court) {
        repo.lock(court);
        var bookings=repo.list("SELECT * FROM bookings WHERE court_id=? AND status='PENDING' AND hold_expires_at<=?",court,Timestamp.from(clock.instant()));
        for(var b:bookings){groups.expire(b);lifecycle.transition(b,"EXPIRED",null,"EXPIRED",Map.of(),clock.instant(),null);}
        repo.jdbc().update("UPDATE slot_reservations SET state='RELEASED' WHERE court_id=? AND state='HOLD' AND expires_at<=?",court,Timestamp.from(clock.instant()));
    }
    private void validateQuote(JsonNode quote,HoldInput input){
        com.sporthub.common.money.Money.positive(quote.path("amount").decimalValue(),quote.path("currency").asText());
        if(!input.courtId().toString().equals(quote.path("courtId").asText())
            ||!input.startsAt().toString().equals(quote.path("startsAt").asText())
            ||!input.endsAt().toString().equals(quote.path("endsAt").asText()))throw new ConflictException("Quote does not match the requested interval");
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
