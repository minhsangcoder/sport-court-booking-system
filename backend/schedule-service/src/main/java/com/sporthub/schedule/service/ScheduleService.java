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
@Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
public class ScheduleService {
    private final ScheduleRepository repo;
    private final FacilityClient facilities;
    private final ObjectMapper json;
    private final com.sporthub.common.schedule.ConfigurationChanges configuration;
    private final Clock clock;
    private final jakarta.validation.Validator validator;
    private final PricingEngine pricing=new PricingEngine();
    @org.springframework.beans.factory.annotation.Value("${SERVICE_CALL_SECRET:}") private String serviceSecret;
    @org.springframework.beans.factory.annotation.Value("${IDENTITY_SERVICE_URL:http://localhost:8081}") private String identityUrl;
    public ScheduleService(ScheduleRepository repo,FacilityClient facilities,ObjectMapper json,com.sporthub.common.schedule.ConfigurationChanges configuration,Clock clock,jakarta.validation.Validator validator) {
        this.repo=repo;this.facilities=facilities;this.json=json;this.configuration=configuration;this.clock=clock;this.validator=validator;
    }
    public List<Hours> hours(UUID facility,Caller caller,String token) {reader(facility,caller,token);return repo.hours(facility);}
    public List<ExceptionView> exceptions(UUID facility,Caller caller,String token) {reader(facility,caller,token);return repo.exceptions(facility);}
    public List<PriceRule> rules(UUID facility,Caller caller,String token) {reader(facility,caller,token);return repo.rules(facility);}
    @Transactional public Map<String,Object> prepareSignup(UUID facility,UUID application,UUID user,String timezone,com.sporthub.common.dto.OwnerSignupSetup setup){
        setup.validate();var zone=ZoneId.of(timezone);repo.lock(facility);
        var bindings=repo.jdbc().queryForList("SELECT application_id FROM application_configuration_freeze WHERE facility_id=?",UUID.class,facility);
        if(!bindings.isEmpty()){if(!application.equals(bindings.getFirst()))throw new ForbiddenException("Configuration belongs to another application");return Map.of("prepared",true);}
        if(!repo.hours(facility).isEmpty()||!repo.rules(facility).isEmpty())throw new ConflictException("Signup cannot replace existing configuration");
        for(var day:new TreeSet<>(setup.days())){
            repo.jdbc().update("INSERT INTO operating_hours(id,facility_id,day_of_week,open_time,close_time,slot_duration_minutes,created_by) VALUES(?,?,?,?,?,?,?)",UUID.randomUUID(),facility,day,time(setup.opensAt()),time(setup.closesAt()),setup.slotMinutes(),user);
            repo.jdbc().update("INSERT INTO pricing_rules(id,facility_id,day_of_week,start_time,end_time,price_per_slot,priority,label,effective_from,currency,created_by) VALUES(?,?,?,?,?,?,0,'Initial signup price',?,'VND',?)",UUID.randomUUID(),facility,day,time(setup.opensAt()),time(setup.closesAt()),setup.pricePerSlot(),date(LocalDate.now(zone)),user);
        }
        repo.jdbc().update("INSERT INTO application_configuration_freeze(facility_id,application_id,frozen) VALUES(?,?,false)",facility,application);
        for(var rule:repo.rules(facility))recordPriceVersion(rule,user);
        audit(facility,new Caller(user,"Applicant",Set.of("CUSTOMER"),Map.of()),"SIGNUP_CONFIGURATION_CREATED",Map.of("applicationId",application));return Map.of("prepared",true);
    }
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
        scheduleManager(facility,caller,token);courtScope(facility,input.courtId(),token);repo.lock(facility);
        var intervals=input.intervals();
        for(var interval:intervals) {
            validateTime(interval.opensAt());validateTime(interval.closesAt());
            if(!interval.opensAt().isBefore(interval.closesAt()))throw new IllegalArgumentException("Opening time must precede closing time");
            if(Duration.between(interval.opensAt(),interval.closesAt()).toMinutes()%interval.slotMinutes()!=0)
                throw new IllegalArgumentException("Opening interval must contain whole slots");
            for(var other:intervals)if(other!=interval && other.dayOfWeek()==interval.dayOfWeek()
                && overlap(interval.opensAt(),interval.closesAt(),other.opensAt(),other.closesAt()))
                    throw new ConflictException("Operating intervals overlap");
        }
        var reservations=configuration.begin(facility);
        repo.jdbc().update("UPDATE operating_hours SET is_active=FALSE,updated_at=NOW(),updated_by=? WHERE facility_id=? AND court_id IS NOT DISTINCT FROM ? AND is_active",caller.id(),facility,input.courtId());
        for(var i:intervals)repo.jdbc().update("INSERT INTO operating_hours(id,facility_id,court_id,day_of_week,open_time,close_time,slot_duration_minutes,created_by) VALUES(?,?,?,?,?,?,?,?)",
            UUID.randomUUID(),facility,input.courtId(),i.dayOfWeek(),java.sql.Time.valueOf(i.opensAt()),java.sql.Time.valueOf(i.closesAt()),i.slotMinutes(),caller.id());
        ensureNoImpact(facility,input.courtId(),reservations,token);
        audit(facility,caller,"HOURS_REPLACED",input);return repo.hours(facility);
    }
    @Transactional
    public ExceptionView addException(UUID facility,ExceptionInput input,Caller caller,String token) {
        return saveException(facility,null,input,caller,token);
    }
    @Transactional
    public ExceptionView updateException(UUID facility,UUID id,ExceptionInput input,Caller caller,String token) {
        return saveException(facility,id,input,caller,token);
    }
    private ExceptionView saveException(UUID facility,UUID existingId,ExceptionInput input,Caller caller,String token) {
        scheduleManager(facility,caller,token);courtScope(facility,input.courtId(),token);repo.lock(facility);
        var previous=existingId==null?null:repo.exceptions(facility).stream().filter(e->e.id().equals(existingId)).findFirst().orElseThrow(()->new ResourceNotFoundException("Exception not found"));
        var facilityConfig=facilities.configuration(facility,token);
        if(previous!=null)requireFutureException(previous,facilityConfig.timezone());
        if(input.date().isBefore(LocalDate.now(clock.withZone(facilityConfig.timezone()))))
            throw new IllegalArgumentException("Past exceptions cannot be created");
        if(input.type().equals("SPECIAL_HOURS") && facilityConfig.status().equals("SUSPENDED"))
            throw new ConflictException("Suspended facilities cannot be specially opened");
        if(input.type().equals("CLOSED") && (input.opensAt()!=null||input.closesAt()!=null))
            throw new IllegalArgumentException("CLOSED exceptions apply to the whole local date");
        if(input.type().equals("SPECIAL_HOURS") && (input.opensAt()==null || input.closesAt()==null || !input.opensAt().isBefore(input.closesAt())))
            throw new IllegalArgumentException("Special opening hours must have a valid interval");
        if(input.type().equals("SPECIAL_HOURS")) {
            validateTime(input.opensAt());validateTime(input.closesAt());
            var hours=repo.hours(facility);var overridden=hours.stream().filter(h->Objects.equals(h.courtId(),input.courtId())).toList();
            if(input.courtId()!=null&&overridden.isEmpty())overridden=hours.stream().filter(h->h.courtId()==null).toList();
            int minutes=overridden.stream().filter(h->h.dayOfWeek()==input.date().getDayOfWeek().getValue()).findFirst().map(Hours::slotMinutes).orElse(60);
            if(Duration.between(input.opensAt(),input.closesAt()).toMinutes()%minutes!=0)
                throw new IllegalArgumentException("Special opening interval must contain whole slots");
        }
        for(var e:repo.exceptions(facility))if(!e.id().equals(existingId)&&Objects.equals(e.courtId(),input.courtId()) && e.date().equals(input.date())) {
            if(e.type().equals("CLOSED") || input.type().equals("CLOSED") || overlap(e.opensAt(),e.closesAt(),input.opensAt(),input.closesAt()))
                throw new ConflictException("Exceptions for the same scope overlap");
        }
        var reservations=configuration.begin(facility);
        var freshConfig=facilities.configuration(facility,token);
        if(previous!=null)requireFutureException(previous,freshConfig.timezone());
        if(input.date().isBefore(LocalDate.now(clock.withZone(freshConfig.timezone()))))throw new IllegalArgumentException("Past exceptions cannot be created");
        if(input.type().equals("SPECIAL_HOURS")&&freshConfig.status().equals("SUSPENDED"))throw new ConflictException("Suspended facilities cannot be specially opened");
        if(input.type().equals("SPECIAL_HOURS")&&!input.date().atTime(input.closesAt()).atZone(freshConfig.timezone()).toInstant().isAfter(clock.instant()))throw new IllegalArgumentException("Special opening interval has already ended");
        UUID id=existingId==null?UUID.randomUUID():existingId;
        repo.jdbc().update("INSERT INTO exception_calendar(id,facility_id,court_id,exception_date,exception_type,open_time,close_time,reason,created_by) VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET court_id=EXCLUDED.court_id,exception_date=EXCLUDED.exception_date,exception_type=EXCLUDED.exception_type,open_time=EXCLUDED.open_time,close_time=EXCLUDED.close_time,reason=EXCLUDED.reason",
            id,facility,input.courtId(),java.sql.Date.valueOf(input.date()),input.type(),time(input.opensAt()),time(input.closesAt()),input.reason(),caller.id());
        if(previous==null)ensureNoImpact(facility,input.courtId(),reservations,token);
        else {
            var scoped=reservations.stream().filter(r->previous.courtId()==null||input.courtId()==null||previous.courtId().equals(r.courtId())||input.courtId().equals(r.courtId())).toList();
            ensureNoImpact(facility,null,scoped,token);
        }
        audit(facility,caller,previous==null?"EXCEPTION_CREATED":"EXCEPTION_UPDATED",previous==null?input:Map.of("before",previous,"after",input));return repo.exceptions(facility).stream().filter(e->e.id().equals(id)).findFirst().orElseThrow();
    }
    @Transactional
    public void removeException(UUID facility,UUID id,Caller caller,String token) {
        scheduleManager(facility,caller,token);repo.lock(facility);
        var previous=repo.exceptions(facility).stream().filter(e->e.id().equals(id)).findFirst().orElseThrow(()->new ResourceNotFoundException("Exception not found"));
        var zone=facilities.configuration(facility,token).timezone();
        var starts=previous.date().atTime(previous.opensAt()==null?LocalTime.MIDNIGHT:previous.opensAt()).atZone(zone).toInstant();
        if(!starts.isAfter(clock.instant()))throw new ConflictException("Started or past exceptions cannot be changed");
        var reservations=configuration.begin(facility);
        var freshZone=facilities.configuration(facility,token).timezone();
        if(!previous.date().atTime(previous.opensAt()==null?LocalTime.MIDNIGHT:previous.opensAt()).atZone(freshZone).toInstant().isAfter(clock.instant()))throw new ConflictException("Started or past exceptions cannot be changed");
        repo.jdbc().update("DELETE FROM exception_calendar WHERE id=? AND facility_id=?",id,facility);ensureNoImpact(facility,previous.courtId(),reservations,token);audit(facility,caller,"EXCEPTION_REMOVED",previous);
    }
    @Transactional
    public PriceRule addPrice(UUID facility,PriceInput input,Caller caller,String token) {
        owner(facility,caller,token);configuration.begin(facility);
        validatePrice(facility,input,token);UUID id=UUID.randomUUID();var candidate=price(facility,id,input,0);
        ensurePriceUnambiguous(facility,candidate,null,token);
        repo.jdbc().update("INSERT INTO pricing_rules(id,facility_id,court_id,sport_category_id,day_of_week,specific_date,start_time,end_time,price_per_slot,priority,label,effective_from,effective_to,currency,created_by,court_type_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            id,facility,input.courtId(),input.sportCategoryId(),input.dayOfWeek(),date(input.specificDate()),time(input.startsAt()),time(input.endsAt()),candidate.pricePerSlot(),input.priority(),input.label().trim(),date(input.effectiveFrom()),date(input.effectiveTo()),input.currency(),caller.id(),input.courtTypeId());
        recordPriceVersion(candidate,caller.id());audit(facility,caller,"PRICE_CREATED",candidate);return candidate;
    }
    @Transactional
    public PriceRule updatePrice(UUID facility,UUID id,PriceInput input,long expectedVersion,Caller caller,String token) {
        owner(facility,caller,token);var previous=findPrice(facility,id);
        if(previous.version()!=expectedVersion)throw new ConflictException("Price version changed; reload the rule before editing");
        if(!previous.active())throw new ConflictException("Create a new rule instead of editing a disabled price");
        if(pricing.legacy(previous)&&input.courtTypeId()==null)throw new IllegalArgumentException("Map the legacy court type explicitly before editing this rule");
        if(!input.courtTypeProvided())input=new PriceInput(input.courtId(),input.sportCategoryId(),input.dayOfWeek(),input.specificDate(),input.startsAt(),input.endsAt(),input.pricePerSlot(),input.priority(),input.label(),input.effectiveFrom(),input.effectiveTo(),input.currency(),previous.courtTypeId());
        configuration.begin(facility);validatePrice(facility,input,token);var candidate=price(facility,id,input,previous.version()+1);
        ensurePriceUnambiguous(facility,candidate,id,token);recordPriceVersion(previous,caller.id());
        int changed=repo.jdbc().update("UPDATE pricing_rules SET court_id=?,sport_category_id=?,court_type_id=?,court_type=NULL,day_of_week=?,specific_date=?,start_time=?,end_time=?,price_per_slot=?,priority=?,label=?,effective_from=?,effective_to=?,currency=?,version=version+1,updated_by=?,updated_at=NOW() WHERE id=? AND facility_id=? AND version=?",
            input.courtId(),input.sportCategoryId(),input.courtTypeId(),input.dayOfWeek(),date(input.specificDate()),time(input.startsAt()),time(input.endsAt()),candidate.pricePerSlot(),input.priority(),input.label().trim(),date(input.effectiveFrom()),date(input.effectiveTo()),input.currency(),caller.id(),id,facility,expectedVersion);
        if(changed!=1)throw new ConflictException("Price version changed during update");
        recordPriceVersion(candidate,caller.id());audit(facility,caller,"PRICE_UPDATED",Map.of("before",previous,"after",candidate));return candidate;
    }
    @Transactional
    public void disablePrice(UUID facility,UUID id,Caller caller,String token) {
        owner(facility,caller,token);var rule=findPrice(facility,id);if(!rule.active())return;
        configuration.begin(facility);recordPriceVersion(rule,caller.id());
        repo.jdbc().update("UPDATE pricing_rules SET is_active=FALSE,version=version+1,updated_by=?,updated_at=NOW() WHERE id=? AND facility_id=?",caller.id(),id,facility);
        recordPriceVersion(findPrice(facility,id),caller.id());audit(facility,caller,"PRICE_DISABLED",rule);
    }
    public List<PriceRevision> priceHistory(UUID facility,UUID id,Caller caller,String token) {
        reader(facility,caller,token);findPrice(facility,id);
        return repo.jdbc().query("SELECT * FROM pricing_rule_versions WHERE rule_id=? ORDER BY version",(r,n)->new PriceRevision(id,r.getLong("version"),readPriceSnapshot(r.getString("snapshot")),r.getObject("actor_id",UUID.class),r.getTimestamp("recorded_at").toInstant()),id);
    }
    public Preview pricingDraft(UUID facility,PricingDraft draft,Caller caller,String token) {
        reader(facility,caller,token);String readToken=staffReader(facility,caller)?null:token;
        var context=facilities.context(draft.courtId(),readToken);checkFacility(facility,context);
        PriceRule previous=draft.replacesRuleId()==null?null:findPrice(facility,draft.replacesRuleId());
        var input=draft.rule();if(previous!=null&&!input.courtTypeProvided())input=new PriceInput(input.courtId(),input.sportCategoryId(),input.dayOfWeek(),input.specificDate(),input.startsAt(),input.endsAt(),input.pricePerSlot(),input.priority(),input.label(),input.effectiveFrom(),input.effectiveTo(),input.currency(),previous.courtTypeId());
        validatePrice(facility,input,readToken);var candidate=price(facility,previous==null?new UUID(0,0):previous.id(),input,previous==null?0:previous.version()+1);
        var rules=new ArrayList<>(repo.rules(facility));if(previous!=null)rules.removeIf(r->r.id().equals(previous.id()));rules.add(candidate);
        return preview(context,draft.date(),rules);
    }
    private PriceRule findPrice(UUID facility,UUID id){return repo.rules(facility).stream().filter(r->r.id().equals(id)).findFirst().orElseThrow(()->new ResourceNotFoundException("Price rule not found"));}
    private PriceRule price(UUID facility,UUID id,PriceInput i,long version){return new PriceRule(id,facility,i.courtId(),i.sportCategoryId(),i.dayOfWeek(),i.specificDate(),i.startsAt(),i.endsAt(),com.sporthub.common.money.Money.positive(i.pricePerSlot(),i.currency()),i.priority(),i.label().trim(),i.effectiveFrom(),i.effectiveTo(),i.currency(),true,version,i.courtTypeId(),null);}
    private com.fasterxml.jackson.databind.JsonNode readPriceSnapshot(String value){try{return json.readTree(value);}catch(com.fasterxml.jackson.core.JsonProcessingException ex){throw new IllegalStateException("Invalid price revision",ex);}}
    private void recordPriceVersion(PriceRule rule,UUID actor){
        try{repo.jdbc().update("INSERT INTO pricing_rule_versions(rule_id,version,snapshot,actor_id) VALUES(?,?,?::jsonb,?) ON CONFLICT(rule_id,version) DO NOTHING",rule.id(),rule.version(),json.writeValueAsString(rule),actor);}catch(com.fasterxml.jackson.core.JsonProcessingException ex){throw new IllegalStateException("Could not record price revision",ex);}
    }
    private void ensurePriceUnambiguous(UUID facility,PriceRule candidate,UUID ignored,String token){
        var contexts=new HashMap<UUID,FacilityClient.Context>();
        for(var rule:repo.rules(facility))if(!rule.id().equals(ignored)&&pricing.conflict(rule,candidate,id->contexts.computeIfAbsent(id,key->facilities.context(key,token))))throw new ConflictException("Price rules conflict at the same scope or precedence; change the scope or priority");
    }
    private void validatePrice(UUID facility,PriceInput i,String token){
        if(!validator.validate(i).isEmpty())throw new IllegalArgumentException("Invalid pricing fields");
        com.sporthub.common.money.Money.positive(i.pricePerSlot(),i.currency());validateTime(i.startsAt());validateTime(i.endsAt());
        if(!i.startsAt().isBefore(i.endsAt()))throw new IllegalArgumentException("Price interval must end after it starts");
        if((i.dayOfWeek()==null)==(i.specificDate()==null))throw new IllegalArgumentException("Choose exactly one weekday or specific date");
        if(i.effectiveTo()!=null&&i.effectiveTo().isBefore(i.effectiveFrom()))throw new IllegalArgumentException("Effective period is invalid");
        LocalDate day=i.specificDate()!=null?i.specificDate():i.effectiveFrom().plusDays(Math.floorMod(i.dayOfWeek()-i.effectiveFrom().getDayOfWeek().getValue(),7));
        if(day.isBefore(i.effectiveFrom())||i.effectiveTo()!=null&&day.isAfter(i.effectiveTo()))throw new IllegalArgumentException("Rule has no applicable date inside its effective period");
        if(i.sportCategoryId()!=null)facilities.requireCategory(i.sportCategoryId());
        if(i.courtTypeId()!=null)facilities.requireType(facility,i.courtTypeId(),token);
        var hours=repo.hours(facility);UUID scope=i.courtId();
        if(scope!=null){var context=facilities.context(scope,token);checkFacility(facility,context);if(!context.enabled())throw new IllegalArgumentException("Court must be enabled");if(i.sportCategoryId()!=null&&!i.sportCategoryId().equals(context.sportCategoryId())||i.courtTypeId()!=null&&!i.courtTypeId().equals(context.courtTypeId()))throw new IllegalArgumentException("Price scope does not match the court classification");}
        var overrides=hours.stream().filter(h->scope!=null&&scope.equals(h.courtId())).toList();
        var base=overrides.isEmpty()?hours.stream().filter(h->h.courtId()==null).toList():overrides;
        List<Interval> intervals=base.stream().filter(h->h.dayOfWeek()==day.getDayOfWeek().getValue()).map(h->new Interval(h.dayOfWeek(),h.opensAt(),h.closesAt(),h.slotMinutes())).toList();
        if(i.specificDate()!=null){var calendar=calendar(facility,scope,day);intervals=calendar.closed()?List.of():calendar.intervals();}
        if(scope==null&&(i.courtTypeId()!=null||i.sportCategoryId()!=null)){
            var targets=facilities.courts(facility,token).stream().filter(c->c.enabled()&&(i.sportCategoryId()==null||i.sportCategoryId().equals(c.sportCategoryId()))&&(i.courtTypeId()==null||i.courtTypeId().equals(c.courtTypeId()))).toList();
            if(!targets.isEmpty()){
                for(var context:targets){checkFacility(facility,context);var own=hours.stream().filter(h->context.courtId().equals(h.courtId())).toList();var effective=own.isEmpty()?hours.stream().filter(h->h.courtId()==null).toList():own;
                    var selected=effective.stream().filter(h->h.dayOfWeek()==day.getDayOfWeek().getValue()).map(h->new Interval(h.dayOfWeek(),h.opensAt(),h.closesAt(),h.slotMinutes())).toList();
                    if(i.specificDate()!=null){var calendar=calendar(facility,context.courtId(),day);selected=calendar.closed()?List.of():calendar.intervals();}
                    if(priceRangeFits(i,selected))return;
                }
                throw new IllegalArgumentException("Price interval is outside every matching court operating calendar");
            }
        }
        if(!priceRangeFits(i,intervals)){
            // Broad prices can serve separately configured courts, including a court specially opened on a facility holiday.
            if(scope==null){for(var context:facilities.courts(facility,token))if(context.enabled()&&(i.sportCategoryId()==null||i.sportCategoryId().equals(context.sportCategoryId()))&&(i.courtTypeId()==null||i.courtTypeId().equals(context.courtTypeId()))){
                checkFacility(facility,context);var own=hours.stream().filter(h->context.courtId().equals(h.courtId())).toList();var effective=own.isEmpty()?hours.stream().filter(h->h.courtId()==null).toList():own;
                var selected=effective.stream().filter(h->h.dayOfWeek()==day.getDayOfWeek().getValue()).map(h->new Interval(h.dayOfWeek(),h.opensAt(),h.closesAt(),h.slotMinutes())).toList();
                if(i.specificDate()!=null){var calendar=calendar(facility,context.courtId(),day);selected=calendar.closed()?List.of():calendar.intervals();}
                if(priceRangeFits(i,selected))return;
            }}throw new IllegalArgumentException("Price interval must fit effective operating hours and slot boundaries");
        }
    }
    private boolean priceRangeFits(PriceInput input,List<Interval> intervals){
        LocalTime cursor=input.startsAt();for(var interval:intervals.stream().sorted(Comparator.comparing(Interval::opensAt)).toList()){
            if(!interval.closesAt().isAfter(cursor))continue;if(interval.opensAt().isAfter(cursor))return false;
            LocalTime end=interval.closesAt().isBefore(input.endsAt())?interval.closesAt():input.endsAt();
            if(Duration.between(interval.opensAt(),cursor).toMinutes()%interval.slotMinutes()!=0||Duration.between(interval.opensAt(),end).toMinutes()%interval.slotMinutes()!=0)return false;
            cursor=end;if(cursor.equals(input.endsAt()))return true;
        }return false;
    }
    public Preview ownerPreview(UUID facility,UUID court,LocalDate date,Caller caller,String token) {
        reader(facility,caller,token);var context=facilities.context(court,staffReader(facility,caller)?null:token);checkFacility(facility,context);return preview(context,date);
    }
    public Preview publicPreview(UUID court,LocalDate date) {return preview(facilities.context(court,null),date);}
    public List<Preview> publicPreviews(List<UUID> ids,LocalDate day) {
        if(ids==null||ids.isEmpty()||ids.size()>200)throw new IllegalArgumentException("Supply 1–200 court IDs");
        var contexts=facilities.contexts(ids);
        var facilities=contexts.stream().map(FacilityClient.Context::facilityId).distinct().toList();
        var hours=repo.hours(facilities).stream().collect(java.util.stream.Collectors.groupingBy(Hours::facilityId));
        var exceptions=repo.exceptions(facilities).stream().collect(java.util.stream.Collectors.groupingBy(ExceptionView::facilityId));
        var rules=repo.rules(facilities).stream().collect(java.util.stream.Collectors.groupingBy(PriceRule::facilityId));
        return contexts.stream().map(context->preview(context,day,
            calendar(context.facilityId(),context.courtId(),day,hours.getOrDefault(context.facilityId(),List.of()),exceptions.getOrDefault(context.facilityId(),List.of())),
            rules.getOrDefault(context.facilityId(),List.of()))).toList();
    }
    private void reader(UUID facility,Caller caller,String token) {
        if(staffReader(facility,caller))return;
        if(caller.hasRole("OWNER")||caller.facilityBindings().getOrDefault(facility,Set.of()).contains("APPLICATION_READ")){facilities.requireReader(facility,token);return;}
        throw new ForbiddenException("SCHEDULE_READ requires an active Staff binding at this facility");
    }
    private boolean staffReader(UUID facility,Caller caller){return caller.hasRole("STAFF")&&caller.facilityBindings().getOrDefault(facility,Set.of()).stream().anyMatch(Set.of("SCHEDULE_READ","SCHEDULE_MANAGE")::contains);}
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
        return new Quote(context.facilityId(),context.courtId(),input.startsAt(),input.endsAt(),com.sporthub.common.money.Money.positive(amount,"VND"),"VND",context.timezone().getId(),segments);
    }
    private record CalendarDay(boolean closed,List<Interval> intervals){}
    private CalendarDay calendar(UUID facility,UUID court,LocalDate day){
        return calendar(facility,court,day,repo.hours(facility),repo.exceptions(facility));
    }
    private CalendarDay calendar(UUID facility,UUID court,LocalDate day,List<Hours> hours,List<ExceptionView> exceptionsForFacility){
        var overrides=hours.stream().filter(h->court!=null&&court.equals(h.courtId())).toList();
        var base=overrides.isEmpty()?hours.stream().filter(h->h.courtId()==null).toList():overrides;
        var daily=base.stream().filter(h->h.dayOfWeek()==day.getDayOfWeek().getValue()).toList();
        var allExceptions=exceptionsForFacility.stream().filter(e->e.date().equals(day) && (e.courtId()==null||e.courtId().equals(court))).toList();
        var courtExceptions=allExceptions.stream().filter(e->court!=null&&court.equals(e.courtId())).toList();
        // A court-specific calendar overrides the general date, including special opening on a facility holiday.
        var exceptions=courtExceptions.isEmpty()?allExceptions.stream().filter(e->e.courtId()==null).toList():courtExceptions;
        boolean closed=exceptions.stream().anyMatch(e->e.type().equals("CLOSED"));
        var specials=exceptions.stream().filter(e->e.type().equals("SPECIAL_HOURS")).toList();
        var intervals=new ArrayList<Interval>();
        if(!specials.isEmpty()&&!closed)for(var e:specials)intervals.add(new Interval(day.getDayOfWeek().getValue(),e.opensAt(),e.closesAt(),daily.isEmpty()?60:daily.get(0).slotMinutes()));
        else for(var h:daily)intervals.add(new Interval(h.dayOfWeek(),h.opensAt(),h.closesAt(),h.slotMinutes()));
        return new CalendarDay(closed,List.copyOf(intervals));
    }
    private Preview preview(FacilityClient.Context context,LocalDate day){return preview(context,day,repo.rules(context.facilityId()));}
    private Preview preview(FacilityClient.Context context,LocalDate day,List<PriceRule> rules) {
        return preview(context,day,calendar(context.facilityId(),context.courtId(),day),rules);
    }
    private Preview preview(FacilityClient.Context context,LocalDate day,CalendarDay calendar,List<PriceRule> rules) {
        var facility=context.facilityId();var court=context.courtId();var intervals=calendar.intervals();boolean closed=calendar.closed();
        var slots=new ArrayList<Slot>();
        for(var interval:intervals)for(LocalTime start=interval.opensAt();start.plusMinutes(interval.slotMinutes()).isAfter(start) && !start.plusMinutes(interval.slotMinutes()).isAfter(interval.closesAt());start=start.plusMinutes(interval.slotMinutes())) {
            LocalTime end=start.plusMinutes(interval.slotMinutes());
            Instant s=day.atTime(start).atZone(context.timezone()).toInstant(),e=day.atTime(end).atZone(context.timezone()).toInstant();
            if(!s.isBefore(e))continue; // A skipped local hour must never become a zero/negative reservation.
            String reason=null;
            if(context.timezone().getRules().getValidOffsets(day.atTime(start)).size()!=1
                    ||context.timezone().getRules().getValidOffsets(day.atTime(end)).size()!=1)reason="TIMEZONE_TRANSITION";
            if(context.maintenance().stream().anyMatch(m->s.isBefore(m.endsAt())&&e.isAfter(m.startsAt())))reason="MAINTENANCE";
            else if(closed)reason="CLOSED_EXCEPTION";
            else if(!context.enabled())reason="COURT_DISABLED";
            else if(!context.status().equals("ACTIVE")&&!context.status().equals("DRAFT"))reason="FACILITY_UNAVAILABLE";
            final LocalTime slotStart=start,slotEnd=end;
            var matching=rules.stream().filter(r->pricing.matches(r,context,day,slotStart,slotEnd)).sorted(Comparator.comparing(pricing::rank).reversed()).toList();
            PriceRule selected=matching.isEmpty()?null:matching.get(0);
            if(reason==null && selected==null)reason="PRICE_NOT_CONFIGURED";
            var conflicts=new ArrayList<UUID>();
            if(reason==null&&selected!=null){try{com.sporthub.common.money.Money.positive(selected.pricePerSlot(),selected.currency());}catch(IllegalArgumentException invalid){reason="INVALID_PRICE_RULE";conflicts.add(selected.id());}}
            if(reason==null&&matching.stream().anyMatch(pricing::legacy)){reason="LEGACY_COURT_TYPE_UNRESOLVED";matching.stream().filter(pricing::legacy).forEach(r->conflicts.add(r.id()));}
            if(reason==null&&matching.size()>1&&pricing.rank(selected).equals(pricing.rank(matching.get(1)))){reason="AMBIGUOUS_PRICE";var top=pricing.rank(selected);matching.stream().filter(r->pricing.rank(r).equals(top)).forEach(r->conflicts.add(r.id()));}
            if(!conflicts.isEmpty())selected=null;
            slots.add(new Slot(s,e,reason==null?"ELIGIBLE":"BLOCKED",reason,selected==null?null:selected.pricePerSlot(),selected==null?"VND":selected.currency(),selected==null?null:selected.id(),selected==null?0:selected.version(),selected==null?null:selected.label(),List.copyOf(conflicts)));
        }
        slots.sort(Comparator.comparing(Slot::startsAt));return new Preview(facility,court,day,context.timezone().getId(),List.copyOf(slots));
    }
    private void ensureNoImpact(UUID facility,UUID scope,List<com.sporthub.common.schedule.ConfigurationGuardProtocol.Reservation> reservations,String token) {
        var contexts=new HashMap<UUID,FacilityClient.Context>();
        var affected=new ArrayList<com.sporthub.common.schedule.ConfigurationGuardProtocol.Reservation>();
        for(var reservation:reservations) {
            if(scope!=null&&!scope.equals(reservation.courtId()))continue;
            var context=contexts.computeIfAbsent(reservation.courtId(),id->facilities.context(id,token));
            checkFacility(facility,context);
            var day=reservation.startsAt().atZone(context.timezone()).toLocalDate();
            Instant cursor=reservation.startsAt();
            boolean maintenance=context.maintenance().stream().anyMatch(m->reservation.startsAt().isBefore(m.endsAt())&&reservation.endsAt().isAfter(m.startsAt()));
            if(!maintenance)for(var slot:preview(context,day).slots()) {
                if(!slot.endsAt().isAfter(cursor)||!slot.startsAt().isBefore(reservation.endsAt()))continue;
                String reason=Objects.toString(slot.reason(),"");
                if(Set.of("CLOSED_EXCEPTION","COURT_DISABLED","FACILITY_UNAVAILABLE","TIMEZONE_TRANSITION").contains(reason))break;
                if(reservation.state().equals("BOOKED")) {
                    // A confirmed booking keeps its original grid and price. Only temporal coverage matters.
                    if(slot.startsAt().isAfter(cursor))break;
                    cursor=slot.endsAt().isAfter(reservation.endsAt())?reservation.endsAt():slot.endsAt();
                } else {
                    if(!slot.startsAt().equals(cursor)||slot.endsAt().isAfter(reservation.endsAt()))break;
                    cursor=slot.endsAt();
                }
                if(cursor.equals(reservation.endsAt()))break;
            }
            // Price eligibility is intentionally excluded: existing price snapshots remain immutable.
            if(!cursor.equals(reservation.endsAt()))affected.add(reservation);
        }
        if(!affected.isEmpty())throw new ConfigurationImpactException(affected);
    }
    private void requireFutureException(ExceptionView previous,ZoneId zone) {
        if(!previous.date().atTime(previous.opensAt()==null?LocalTime.MIDNIGHT:previous.opensAt()).atZone(zone).toInstant().isAfter(clock.instant()))
            throw new ConflictException("Started or past exceptions cannot be changed");
    }
    private void validateTime(LocalTime time) {
        if(time==null||time.getSecond()!=0||time.getNano()!=0)throw new IllegalArgumentException("Schedule times must use whole minutes");
    }
    private void owner(UUID facility,Caller caller,String token) {if(!caller.hasRole("OWNER")&&!caller.facilityBindings().getOrDefault(facility,Set.of()).contains("APPLICATION_EDIT"))throw new ForbiddenException("Owner or editable application workspace required");repo.lock(facility);var pending=repo.jdbc().queryForList("SELECT application_id FROM application_configuration_freeze WHERE facility_id=? AND frozen",UUID.class,facility);if(!pending.isEmpty()&&!facilities.applicationCommitted(pending.getFirst(),serviceSecret,identityUrl))throw new ConflictException("Application schedule is frozen during review");facilities.requireOwner(facility,token);}
    private void scheduleManager(UUID facility,Caller caller,String token){
        if(caller.hasRole("STAFF")&&caller.facilityBindings().getOrDefault(facility,Set.of()).contains("SCHEDULE_MANAGE")){repo.lock(facility);var pending=repo.jdbc().queryForList("SELECT application_id FROM application_configuration_freeze WHERE facility_id=? AND frozen",UUID.class,facility);if(!pending.isEmpty()&&!facilities.applicationCommitted(pending.getFirst(),serviceSecret,identityUrl))throw new ConflictException("Application schedule is frozen during review");facilities.requireScheduleManager(facility,token);return;}owner(facility,caller,token);
    }
    private void courtScope(UUID facility,UUID court,String token) {if(court!=null)checkFacility(facility,facilities.context(court,token));}
    private void checkFacility(UUID facility,FacilityClient.Context context) {if(!facility.equals(context.facilityId()))throw new ForbiddenException("Court is outside the facility");}
    private boolean overlap(LocalTime a,LocalTime b,LocalTime c,LocalTime d) {return a.isBefore(d)&&b.isAfter(c);}
    private static java.sql.Time time(LocalTime value) {return value==null?null:java.sql.Time.valueOf(value);}
    private static java.sql.Date date(LocalDate value) {return value==null?null:java.sql.Date.valueOf(value);}
    private void audit(UUID facility,Caller caller,String action,Object snapshot) {
        try {repo.jdbc().update("INSERT INTO schedule_audit(id,facility_id,actor_id,action,snapshot) VALUES(?,?,?,?,?::jsonb)",UUID.randomUUID(),facility,caller.id(),action,json.writeValueAsString(snapshot));}
        catch(com.fasterxml.jackson.core.JsonProcessingException ex) {throw new IllegalStateException("Could not record configuration audit",ex);}
    }
}
