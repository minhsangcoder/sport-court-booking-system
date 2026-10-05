package com.sporthub.schedule.service;

import static com.sporthub.schedule.web.ScheduleDtos.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sporthub.common.exception.*;
import com.sporthub.common.security.RemoteIdentity.Caller;
import com.sporthub.schedule.repository.ScheduleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

@Service
@Transactional(readOnly=true)
public class ScheduleService {
    private final ScheduleRepository repo;
    private final FacilityClient facilities;
    private final ObjectMapper json;
    @org.springframework.beans.factory.annotation.Value("${SERVICE_CALL_SECRET:}") private String serviceSecret;
    @org.springframework.beans.factory.annotation.Value("${IDENTITY_SERVICE_URL:http://localhost:8081}") private String identityUrl;
    public ScheduleService(ScheduleRepository repo,FacilityClient facilities,ObjectMapper json) {
        this.repo=repo;this.facilities=facilities;this.json=json;
    }
    public List<Hours> hours(UUID facility,Caller caller,String token) {reader(facility,caller,token);return repo.hours(facility);}
    public List<ExceptionView> exceptions(UUID facility,Caller caller,String token) {reader(facility,caller,token);return repo.exceptions(facility);}
    public List<PriceRule> rules(UUID facility,Caller caller,String token) {reader(facility,caller,token);return repo.rules(facility);}
    @Transactional public Map<String,Object> freezeApplication(UUID facility,UUID application,List<FacilityClient.Context> contexts){
        repo.lock(facility);var hours=repo.hours(facility);var prices=repo.rules(facility);
        if(hours.isEmpty()||prices.isEmpty())throw new ConflictException("Configure operating hours and prices before submitting");
        boolean playable=false;
        for(var context:contexts){if(!facility.equals(context.facilityId()))throw new ForbiddenException("Foreign court context");if(!context.enabled())continue;var today=LocalDate.now(context.timezone());
            for(int day=0;day<=7;day++)for(var slot:preview(context,today.plusDays(day)).slots()){
                if(Set.of("PRICE_NOT_CONFIGURED","AMBIGUOUS_PRICE").contains(Objects.toString(slot.reason(),"")))throw new ConflictException("Configured slots must have an unambiguous price");
                if(slot.state().equals("ELIGIBLE")&&slot.startsAt().isAfter(Instant.now()))playable=true;
            }
        }
        if(!playable)throw new ConflictException("Configure a future playable slot before submitting");
        int bound=repo.jdbc().update("INSERT INTO application_configuration_freeze(facility_id,application_id,frozen) VALUES(?,?,true) ON CONFLICT(facility_id) DO UPDATE SET frozen=true WHERE application_configuration_freeze.application_id=EXCLUDED.application_id",facility,application);
        if(bound!=1)throw new ConflictException("Configuration belongs to another Owner application");
        return Map.of("hours",hours,"prices",prices,"exceptions",repo.exceptions(facility));
    }
    @Transactional public Map<String,Object> releaseApplication(UUID facility,UUID application){repo.lock(facility);repo.jdbc().update("UPDATE application_configuration_freeze SET frozen=false WHERE facility_id=? AND application_id=?",facility,application);return Map.of("released",true);}

    @Transactional
    public List<Hours> replaceHours(UUID facility,HoursInput input,Caller caller,String token) {
        owner(facility,caller,token);courtScope(facility,input.courtId(),token);repo.lock(facility);
        var intervals=input.intervals();
        for(var interval:intervals) {
            if(!interval.opensAt().isBefore(interval.closesAt()))throw new IllegalArgumentException("Opening time must precede closing time");
            if(Duration.between(interval.opensAt(),interval.closesAt()).toMinutes()%interval.slotMinutes()!=0)
                throw new IllegalArgumentException("Opening interval must contain whole slots");
            for(var other:intervals)if(other!=interval && other.dayOfWeek()==interval.dayOfWeek()
                && overlap(interval.opensAt(),interval.closesAt(),other.opensAt(),other.closesAt()))
                    throw new ConflictException("Operating intervals overlap");
        }
        repo.jdbc().update("UPDATE operating_hours SET is_active=FALSE,updated_at=NOW(),updated_by=? WHERE facility_id=? AND court_id IS NOT DISTINCT FROM ? AND is_active",caller.id(),facility,input.courtId());
        for(var i:intervals)repo.jdbc().update("INSERT INTO operating_hours(id,facility_id,court_id,day_of_week,open_time,close_time,slot_duration_minutes,created_by) VALUES(?,?,?,?,?,?,?,?)",
            UUID.randomUUID(),facility,input.courtId(),i.dayOfWeek(),java.sql.Time.valueOf(i.opensAt()),java.sql.Time.valueOf(i.closesAt()),i.slotMinutes(),caller.id());
        audit(facility,caller,"HOURS_REPLACED",input);return repo.hours(facility);
    }
    @Transactional
    public ExceptionView addException(UUID facility,ExceptionInput input,Caller caller,String token) {
        owner(facility,caller,token);courtScope(facility,input.courtId(),token);repo.lock(facility);
        if(input.type().equals("SPECIAL_HOURS") && (input.opensAt()==null || input.closesAt()==null || !input.opensAt().isBefore(input.closesAt())))
            throw new IllegalArgumentException("Special opening hours must have a valid interval");
        for(var e:repo.exceptions(facility))if(Objects.equals(e.courtId(),input.courtId()) && e.date().equals(input.date())) {
            if(e.type().equals("CLOSED") || input.type().equals("CLOSED") || overlap(e.opensAt(),e.closesAt(),input.opensAt(),input.closesAt()))
                throw new ConflictException("Exceptions for the same scope overlap");
        }
        UUID id=UUID.randomUUID();
        repo.jdbc().update("INSERT INTO exception_calendar(id,facility_id,court_id,exception_date,exception_type,open_time,close_time,reason,created_by) VALUES(?,?,?,?,?,?,?,?,?)",
            id,facility,input.courtId(),java.sql.Date.valueOf(input.date()),input.type(),time(input.opensAt()),time(input.closesAt()),input.reason(),caller.id());
        audit(facility,caller,"EXCEPTION_CREATED",input);return repo.exceptions(facility).stream().filter(e->e.id().equals(id)).findFirst().orElseThrow();
    }
    @Transactional
    public void removeException(UUID facility,UUID id,Caller caller,String token) {
        owner(facility,caller,token);repo.lock(facility);
        var previous=repo.exceptions(facility).stream().filter(e->e.id().equals(id)).findFirst().orElseThrow(()->new ResourceNotFoundException("Exception not found"));
        if(previous.date().isBefore(LocalDate.now()))throw new ConflictException("Past exceptions cannot be changed");
        repo.jdbc().update("DELETE FROM exception_calendar WHERE id=? AND facility_id=?",id,facility);audit(facility,caller,"EXCEPTION_REMOVED",previous);
    }
    @Transactional
    public PriceRule addPrice(UUID facility,PriceInput input,Caller caller,String token) {
        owner(facility,caller,token);courtScope(facility,input.courtId(),token);repo.lock(facility);
        if(!input.startsAt().isBefore(input.endsAt()) || input.pricePerSlot().signum()<=0)
            throw new IllegalArgumentException("Price and time interval must be positive");
        if((input.dayOfWeek()==null)==(input.specificDate()==null))throw new IllegalArgumentException("Choose exactly one weekday or specific date");
        if(input.effectiveTo()!=null && input.effectiveTo().isBefore(input.effectiveFrom()))throw new IllegalArgumentException("Effective period is invalid");
        if(input.specificDate()!=null && (input.specificDate().isBefore(input.effectiveFrom()) || (input.effectiveTo()!=null && input.specificDate().isAfter(input.effectiveTo()))))
            throw new IllegalArgumentException("Specific date is outside the effective period");
        for(var rule:repo.rules(facility))if(rule.active() && Objects.equals(rule.courtId(),input.courtId())
            && Objects.equals(rule.sportCategoryId(),input.sportCategoryId()) && rule.priority()==input.priority()
            && dayOverlaps(rule,input) && periodOverlaps(rule.effectiveFrom(),rule.effectiveTo(),input.effectiveFrom(),input.effectiveTo())
            && overlap(rule.startsAt(),rule.endsAt(),input.startsAt(),input.endsAt()))throw new ConflictException("Price rules of the same scope and priority overlap");
        var hours=repo.hours(facility).stream().filter(h->input.courtId()==null?h.courtId()==null:h.courtId()==null||h.courtId().equals(input.courtId())).toList();
        int weekday=input.dayOfWeek()!=null?input.dayOfWeek():input.specificDate().getDayOfWeek().getValue();
        if(hours.stream().noneMatch(h->h.dayOfWeek()==weekday && !input.startsAt().isBefore(h.opensAt()) && !input.endsAt().isAfter(h.closesAt())))
            throw new IllegalArgumentException("Price interval must be contained in configured operating hours");
        UUID id=UUID.randomUUID();
        repo.jdbc().update("INSERT INTO pricing_rules(id,facility_id,court_id,sport_category_id,day_of_week,specific_date,start_time,end_time,price_per_slot,priority,label,effective_from,effective_to,currency,created_by) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            id,facility,input.courtId(),input.sportCategoryId(),input.dayOfWeek(),date(input.specificDate()),time(input.startsAt()),time(input.endsAt()),input.pricePerSlot(),input.priority(),input.label(),date(input.effectiveFrom()),date(input.effectiveTo()),input.currency(),caller.id());
        audit(facility,caller,"PRICE_CREATED",input);return repo.rules(facility).stream().filter(r->r.id().equals(id)).findFirst().orElseThrow();
    }
    @Transactional
    public void disablePrice(UUID facility,UUID id,Caller caller,String token) {
        owner(facility,caller,token);repo.lock(facility);
        var rule=repo.rules(facility).stream().filter(r->r.id().equals(id)).findFirst().orElseThrow(()->new ResourceNotFoundException("Price rule not found"));
        repo.jdbc().update("UPDATE pricing_rules SET is_active=FALSE,version=version+1,updated_by=?,updated_at=NOW() WHERE id=? AND facility_id=?",caller.id(),id,facility);
        audit(facility,caller,"PRICE_DISABLED",rule);
    }
    public Preview ownerPreview(UUID facility,UUID court,LocalDate date,Caller caller,String token) {
        reader(facility,caller,token);var context=facilities.context(court,staffReader(facility,caller)?null:token);checkFacility(facility,context);return preview(context,date);
    }
    public Preview publicPreview(UUID court,LocalDate date) {return preview(facilities.context(court,null),date);}
    private void reader(UUID facility,Caller caller,String token) {
        if(staffReader(facility,caller))return;
        if(caller.hasRole("OWNER")||caller.facilityBindings().getOrDefault(facility,Set.of()).contains("APPLICATION_READ")){facilities.requireReader(facility,token);return;}
        throw new ForbiddenException("SCHEDULE_READ requires an active Staff binding at this facility");
    }
    private boolean staffReader(UUID facility,Caller caller){return caller.hasRole("STAFF")&&caller.facilityBindings().getOrDefault(facility,Set.of()).contains("SCHEDULE_READ");}
    public Quote quote(QuoteInput input) {
        if(!input.startsAt().isBefore(input.endsAt()))throw new IllegalArgumentException("Booking interval is invalid");
        var context=facilities.context(input.courtId(),null);
        LocalDate day=input.startsAt().atZone(context.timezone()).toLocalDate();
        if(!input.endsAt().minusNanos(1).atZone(context.timezone()).toLocalDate().equals(day))throw new IllegalArgumentException("Choose slots within one local date");
        var segments=preview(context,day).slots().stream().filter(s->!s.startsAt().isBefore(input.startsAt()) && !s.endsAt().isAfter(input.endsAt())).toList();
        Instant cursor=input.startsAt();BigDecimal amount=BigDecimal.ZERO;
        for(var segment:segments) {
            if(!segment.startsAt().equals(cursor) || !segment.state().equals("ELIGIBLE"))throw new ConflictException("Slot is not eligible: "+segment.reason());
            cursor=segment.endsAt();amount=amount.add(segment.amount());
        }
        if(segments.isEmpty() || !cursor.equals(input.endsAt()))throw new ConflictException("Interval does not align with operating slots");
        return new Quote(context.facilityId(),context.courtId(),input.startsAt(),input.endsAt(),amount,"VND",context.timezone().getId(),segments);
    }
    private Preview preview(FacilityClient.Context context,LocalDate day) {
        var facility=context.facilityId();var court=context.courtId();
        var hours=repo.hours(facility);var overrides=hours.stream().filter(h->court.equals(h.courtId())).toList();
        var base=overrides.isEmpty()?hours.stream().filter(h->h.courtId()==null).toList():overrides;
        var daily=base.stream().filter(h->h.dayOfWeek()==day.getDayOfWeek().getValue()).toList();
        var exceptions=repo.exceptions(facility).stream().filter(e->e.date().equals(day) && (e.courtId()==null||e.courtId().equals(court))).toList();
        boolean closed=exceptions.stream().anyMatch(e->e.type().equals("CLOSED"));
        var specials=exceptions.stream().filter(e->e.type().equals("SPECIAL_HOURS")).toList();
        var courtSpecials=specials.stream().filter(e->court.equals(e.courtId())).toList();
        if(!courtSpecials.isEmpty())specials=courtSpecials;
        var intervals=new ArrayList<Interval>();
        if(!specials.isEmpty()&&!closed)for(var e:specials)intervals.add(new Interval(day.getDayOfWeek().getValue(),e.opensAt(),e.closesAt(),daily.isEmpty()?60:daily.get(0).slotMinutes()));
        else for(var h:daily)intervals.add(new Interval(h.dayOfWeek(),h.opensAt(),h.closesAt(),h.slotMinutes()));
        var rules=repo.rules(facility);var slots=new ArrayList<Slot>();
        for(var interval:intervals)for(LocalTime start=interval.opensAt();start.plusMinutes(interval.slotMinutes()).isAfter(start) && !start.plusMinutes(interval.slotMinutes()).isAfter(interval.closesAt());start=start.plusMinutes(interval.slotMinutes())) {
            LocalTime end=start.plusMinutes(interval.slotMinutes());
            Instant s=day.atTime(start).atZone(context.timezone()).toInstant(),e=day.atTime(end).atZone(context.timezone()).toInstant();
            String reason=null;
            if(context.maintenance().stream().anyMatch(m->s.isBefore(m.endsAt())&&e.isAfter(m.startsAt())))reason="MAINTENANCE";
            else if(closed)reason="CLOSED_EXCEPTION";
            else if(!context.enabled())reason="COURT_DISABLED";
            final LocalTime slotStart=start,slotEnd=end;
            var matching=rules.stream().filter(r->matches(r,context,day,slotStart,slotEnd)).sorted(Comparator.comparing(this::rank).reversed()).toList();
            PriceRule selected=matching.isEmpty()?null:matching.get(0);
            if(reason==null && selected==null)reason="PRICE_NOT_CONFIGURED";
            if(reason==null && matching.size()>1 && rank(selected).equals(rank(matching.get(1))))reason="AMBIGUOUS_PRICE";
            slots.add(new Slot(s,e,reason==null?"ELIGIBLE":"BLOCKED",reason,selected==null?null:selected.pricePerSlot(),selected==null?"VND":selected.currency(),selected==null?null:selected.id(),selected==null?0:selected.version(),selected==null?null:selected.label()));
        }
        slots.sort(Comparator.comparing(Slot::startsAt));return new Preview(facility,court,day,context.timezone().getId(),List.copyOf(slots));
    }
    private boolean matches(PriceRule r,FacilityClient.Context c,LocalDate day,LocalTime start,LocalTime end) {
        return r.active() && (r.courtId()==null||r.courtId().equals(c.courtId())) && (r.sportCategoryId()==null||r.sportCategoryId().equals(c.sportCategoryId()))
            && (r.specificDate()!=null?r.specificDate().equals(day):Objects.equals(r.dayOfWeek(),day.getDayOfWeek().getValue()))
            && !day.isBefore(r.effectiveFrom()) && (r.effectiveTo()==null||!day.isAfter(r.effectiveTo()))
            && !start.isBefore(r.startsAt()) && !end.isAfter(r.endsAt());
    }
    // Specific court/date outrank general category/weekday. Equal rank is an error, never an arbitrary winner.
    private Rank rank(PriceRule r) {return new Rank(r.courtId()!=null||r.specificDate()!=null,(r.courtId()!=null?1:0)+(r.specificDate()!=null?1:0)+(r.sportCategoryId()!=null?1:0),r.priority());}
    private record Rank(boolean specific,int qualifiers,int priority) implements Comparable<Rank> {
        public int compareTo(Rank other) {int value=Boolean.compare(specific,other.specific);if(value==0)value=Integer.compare(qualifiers,other.qualifiers);if(value==0)value=Integer.compare(priority,other.priority);return value;}
    }
    private void owner(UUID facility,Caller caller,String token) {if(!caller.hasRole("OWNER")&&!caller.facilityBindings().getOrDefault(facility,Set.of()).contains("APPLICATION_EDIT"))throw new ForbiddenException("Owner or editable application workspace required");repo.lock(facility);var pending=repo.jdbc().queryForList("SELECT application_id FROM application_configuration_freeze WHERE facility_id=? AND frozen",UUID.class,facility);if(!pending.isEmpty()&&!facilities.applicationCommitted(pending.getFirst(),serviceSecret,identityUrl))throw new ConflictException("Application schedule is frozen during review");facilities.requireOwner(facility,token);}
    private void courtScope(UUID facility,UUID court,String token) {if(court!=null)checkFacility(facility,facilities.context(court,token));}
    private void checkFacility(UUID facility,FacilityClient.Context context) {if(!facility.equals(context.facilityId()))throw new ForbiddenException("Court is outside the facility");}
    private boolean overlap(LocalTime a,LocalTime b,LocalTime c,LocalTime d) {return a.isBefore(d)&&b.isAfter(c);}
    private boolean periodOverlaps(LocalDate a,LocalDate b,LocalDate c,LocalDate d) {return (b==null||!b.isBefore(c))&&(d==null||!d.isBefore(a));}
    private boolean dayOverlaps(PriceRule r,PriceInput i) {if(r.specificDate()!=null&&i.specificDate()!=null)return r.specificDate().equals(i.specificDate());if(r.specificDate()!=null)return r.specificDate().getDayOfWeek().getValue()==i.dayOfWeek();if(i.specificDate()!=null)return i.specificDate().getDayOfWeek().getValue()==r.dayOfWeek();return Objects.equals(r.dayOfWeek(),i.dayOfWeek());}
    private static java.sql.Time time(LocalTime value) {return value==null?null:java.sql.Time.valueOf(value);}
    private static java.sql.Date date(LocalDate value) {return value==null?null:java.sql.Date.valueOf(value);}
    private void audit(UUID facility,Caller caller,String action,Object snapshot) {
        try {repo.jdbc().update("INSERT INTO schedule_audit(id,facility_id,actor_id,action,snapshot) VALUES(?,?,?,?,?::jsonb)",UUID.randomUUID(),facility,caller.id(),action,json.writeValueAsString(snapshot));}
        catch(com.fasterxml.jackson.core.JsonProcessingException ex) {throw new IllegalStateException("Could not record configuration audit",ex);}
    }
}
