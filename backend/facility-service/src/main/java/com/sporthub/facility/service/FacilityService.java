package com.sporthub.facility.service;
import com.sporthub.common.exception.*;
import com.sporthub.common.security.RemoteIdentity.Caller;
import com.sporthub.facility.domain.*;
import com.sporthub.facility.repository.*;
import static com.sporthub.facility.web.FacilityDtos.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
@Service @Transactional(readOnly=true)
public class FacilityService {
 private final com.sporthub.common.schedule.ConfigurationChanges configuration;
 private final SportCategoryManagement categoryManagement;
 @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entities;
 private final FacilityRepository facilities; private final CourtRepository courts;
 private final SportCategoryRepository categories; private final MaintenanceRepository maintenance; private final JdbcTemplate jdbc;private final OwnerApplicationGate gate;private final jakarta.validation.Validator validator;
 public FacilityService(FacilityRepository f,CourtRepository c,SportCategoryRepository s,MaintenanceRepository m,JdbcTemplate jdbc,OwnerApplicationGate gate,jakarta.validation.Validator validator,com.sporthub.common.schedule.ConfigurationChanges configuration,SportCategoryManagement categoryManagement){this.categoryManagement=categoryManagement;this.configuration=configuration;this.validator=validator;facilities=f;courts=c;categories=s;maintenance=m;this.jdbc=jdbc;this.gate=gate;}
 public List<FacilityProfileView> owned(Caller caller){caller.requireRole("OWNER");return facilities.findByOwnerIdOrderByCreatedAtDesc(caller.id()).stream().map(this::profileView).toList();}
 public FacilityProfileView ownedDetail(UUID id,Caller caller){return profileView(ownedEntity(id,caller));}
 @Transactional public FacilityProfileView writeAccess(UUID id,Caller caller){var f=ownedEntity(id,caller);if(!caller.hasRole("OWNER")&&!caller.facilityBindings().getOrDefault(id,Set.of()).contains("APPLICATION_EDIT"))throw new ForbiddenException("Application workspace is read-only");if(Set.of("PENDING_APPROVAL","REJECTED").contains(f.getStatus()))throw new ConflictException("Facility configuration is frozen during review");var app=applicationId(id);if(app!=null&&f.getStatus().equals("ACTIVE")&&!gate.approved(List.of(app)).contains(app))throw new ConflictException("Activation is pending");return profileView(f);}
 public Facility ownedEntity(UUID id,Caller caller){var facility=find(id);boolean applicant=caller.hasRole("CUSTOMER")&&caller.facilityBindings().getOrDefault(id,Set.of()).contains("APPLICATION_READ")&&applicationId(id)!=null;if(!(caller.hasRole("OWNER")||applicant) || !facility.getOwnerId().equals(caller.id()))throw new ForbiddenException("Facility is outside your ownership");return facility;}
 @Transactional public Facility mutableOwnedEntity(UUID id,Caller caller){jdbc.queryForList("SELECT id FROM facilities WHERE id=? FOR UPDATE",UUID.class,id);var f=ownedEntity(id,caller);if(!caller.hasRole("OWNER")&&!caller.facilityBindings().getOrDefault(id,Set.of()).contains("APPLICATION_EDIT"))throw new ForbiddenException("Application workspace is read-only");if(Set.of("PENDING_APPROVAL","REJECTED").contains(f.getStatus()))throw new ConflictException("Submitted/rejected facility profile cannot be edited; await a supplement decision or create a new draft");if(applicationId(id)!=null&&f.getStatus().equals("ACTIVE")&&!gate.approved(List.of(applicationId(id))).contains(applicationId(id)))throw new ConflictException("First facility activation is awaiting application commit");return f;}
 public UUID applicationId(UUID id){return jdbc.queryForList("SELECT application_id FROM first_facility_applications WHERE facility_id=?",UUID.class,id).stream().findFirst().orElse(null);}
 public Facility find(UUID id){return facilities.findById(id).orElseThrow(()->new ResourceNotFoundException("Facility not found"));}
 public List<FacilityView> search(String query){return search(query,null,null);}
 public List<FacilityView> search(String query,String provinceCode,UUID category){return search(query,provinceCode,category,null,null);}
 public List<FacilityView> search(String query,String provinceCode,UUID category,String district,String ward){
  var args=new ArrayList<Object>();String text=FacilityAreas.plainSql("concat_ws(' ',f.name,f.address_line,f.province,f.ward)");
  // Free text also understands former districts; the raw text still matches names such as "Ha Dong Sports".
  String textFilter=text+" LIKE ?";args.add(pattern(FacilityAreas.plain(query)));
  var named=query==null?Optional.<FacilityAreas.Match>empty():FacilityAreas.inText(provinceCode,query);
  if(named.isPresent()){textFilter="("+textFilter+" OR ("+areaSql(named.get().district(),args)+" AND "+text+" LIKE ?))";args.add(pattern(named.get().remainder()));}
  String areaFilter="";
  if(district!=null&&!district.isBlank()){var area=FacilityAreas.find(provinceCode,district).orElseThrow(()->new IllegalArgumentException("Unknown former district"));areaFilter+=" AND "+areaSql(area,args);}
  if(ward!=null&&!ward.isBlank()){areaFilter+=" AND "+FacilityAreas.wardSql()+"=?";args.add(FacilityAreas.plain(ward));}
  args.addAll(Arrays.asList(provinceCode,provinceCode,category,category));
  var candidates=jdbc.queryForList("SELECT f.id FROM facilities f WHERE f.status='ACTIVE' AND "+textFilter+areaFilter+"""

    AND (?::varchar IS NULL OR f.province_code=?::varchar)
    AND EXISTS(SELECT 1 FROM courts c JOIN sport_categories s ON s.id=c.sport_category_id
      WHERE c.facility_id=f.id AND c.enabled AND (?::uuid IS NULL OR c.sport_category_id=?::uuid))
   ORDER BY f.name,f.id LIMIT 200
   """,UUID.class,args.toArray());var bindings=new HashMap<UUID,UUID>();for(var id:candidates){var app=applicationId(id);if(app!=null)bindings.put(id,app);}var committed=gate.approved(bindings.values());return candidates.stream().filter(id->!bindings.containsKey(id)||committed.contains(bindings.get(id))).map(id->view(find(id))).toList();
 }
 private String areaSql(FacilityAreas.District area,List<Object> args){var codes=area.wards().stream().map(FacilityAreas.Ward::code).toList();var names=area.wards().stream().map(w->FacilityAreas.plain(w.name())).toList();args.add(area.provinceCode());args.add("{"+String.join(",",codes)+"}");args.add("{"+String.join(",",names.stream().map(n->'"'+n+'"').toList())+"}");return "(f.province_code=? AND (f.ward_code=ANY(?::varchar[]) OR "+FacilityAreas.wardSql()+"=ANY(?::text[])))";}
 private String pattern(String value){if(value!=null && value.length()>180)throw new IllegalArgumentException("Search text must not exceed 180 characters");return "%"+(value==null?"":value.trim().toLowerCase(Locale.ROOT).replace("\\","\\\\").replace("%","\\%").replace("_","\\_"))+"%";}
 public Optional<FacilityView> publicIfAvailable(UUID id){var candidate=facilities.findById(id);if(candidate.isEmpty()||!candidate.get().getStatus().equals("ACTIVE"))return Optional.empty();var app=applicationId(id);if(app!=null&&!gate.approved(List.of(app)).contains(app))return Optional.empty();return Optional.of(view(candidate.get()));}
 public FacilityView publicDetail(UUID id){return publicIfAvailable(id).orElseThrow(()->new ResourceNotFoundException("Facility is not publicly available"));}
 @Transactional public FacilityProfileView createFirst(UUID applicationId,UUID facilityId,UUID userId,FacilityInput input){jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?::text,0))",Object.class,applicationId.toString());var existing=applicationId(facilityId);if(existing!=null){var f=find(facilityId);if(!existing.equals(applicationId)||!f.getOwnerId().equals(userId))throw new ConflictException("Application facility binding differs");return profileView(f);}if(facilities.existsById(facilityId))throw new ConflictException("Facility id already exists");var f=new Facility();f.setId(facilityId);f.setOwnerId(userId);assign(f,input);facilities.saveAndFlush(f);jdbc.update("INSERT INTO first_facility_applications(application_id,facility_id,owner_id) VALUES(?,?,?)",applicationId,facilityId,userId);audit(f,new Caller(userId,"Applicant",Set.of("CUSTOMER"),Map.of()),"FIRST_FACILITY_DRAFT_CREATED");return profileView(f);}
 @Transactional public FacilityProfileView create(FacilityInput input,Caller caller){caller.requireRole("OWNER");var f=new Facility();f.setOwnerId(caller.id());assign(f,input);facilities.saveAndFlush(f);audit(f,caller,"FACILITY_CREATED");return profileView(f);}
 @Transactional public FacilityProfileView update(UUID id,FacilityInput input,Caller caller){var f=mutableOwnedEntity(id,caller);var before=f.getContactEmail();var latitude=f.getLatitude();var longitude=f.getLongitude();if(!f.getTimezone().equals(input.timezone())){var affected=configuration.begin(id);if(!affected.isEmpty())throw new ConfigurationImpactException(affected);}assign(f,input);facilities.saveAndFlush(f);jdbc.update("INSERT INTO facility_audit(id,actor_id,facility_id,action,details) VALUES(?,?,?,'FACILITY_UPDATED',?)",UUID.randomUUID(),caller.id(),id,editDetails(before,latitude,longitude,f));return profileView(f);}
 public List<CourtView> ownedCourts(UUID id,Caller caller){operationalRead(id,caller);return courts.findByFacilityIdOrderByNameAsc(id).stream().map(this::courtView).toList();}
 public List<CourtView> publicCourts(UUID id){return publicCourts(id,false);}
 public List<CourtView> publicCourts(UUID id,boolean includeDisabled){publicDetail(id);return courts.findByFacilityIdOrderByNameAsc(id).stream().filter(c->includeDisabled||c.isEnabled()).map(this::courtView).toList();}
 public Court courtEntity(UUID id){return courts.findById(id).orElseThrow(()->new ResourceNotFoundException("Court not found"));}
 public CourtView ownedCourt(UUID id,Caller caller){var court=courtEntity(id);operationalRead(court.getFacility().getId(),caller);return courtView(court);}
 public CourtContext context(UUID courtId,Caller caller){var court=courtEntity(courtId);var f=court.getFacility();if(caller==null)publicDetail(f.getId());else operationalRead(f.getId(),caller);return new CourtContext(view(f),courtView(court),maintenance.findByCourtIdAndCancelledFalseOrderByStartsAt(courtId).stream().map(this::maintenanceView).toList());}
 private Facility operationalRead(UUID id,Caller caller){
  if(caller.hasRole("STAFF")&&caller.facilityBindings().getOrDefault(id,Set.of()).stream().anyMatch(Set.of("SCHEDULE_READ","SCHEDULE_MANAGE","COURT_STATUS_MANAGE","COURT_MAINTENANCE_MANAGE")::contains)){publicDetail(id);return find(id);}return ownedEntity(id,caller);
 }
 @Transactional public FacilityView operationAccess(UUID id,String permission,Caller caller){return view(mutableOperation(id,permission,caller));}
 private Facility mutableOperation(UUID id,String permission,Caller caller){
  if(!Set.of("SCHEDULE_MANAGE","COURT_STATUS_MANAGE","COURT_MAINTENANCE_MANAGE").contains(permission))throw new IllegalArgumentException("Unsupported operational permission");
  if(caller.hasRole("STAFF")&&caller.facilityBindings().getOrDefault(id,Set.of()).contains(permission)){jdbc.queryForList("SELECT id FROM facilities WHERE id=? FOR UPDATE",UUID.class,id);publicDetail(id);return find(id);}return mutableOwnedEntity(id,caller);
 }
 @Transactional public CourtView courtStatus(UUID id,boolean enabled,Caller caller){var court=courtEntity(id);var facility=mutableOperation(court.getFacility().getId(),"COURT_STATUS_MANAGE",caller);entities.refresh(court);if(court.isEnabled()==enabled)return courtView(court);if(enabled)validateCategory(court.getSportCategoryId());var affected=configuration.begin(facility.getId()).stream().filter(r->r.courtId().equals(id)).toList();if(!enabled&&!affected.isEmpty())throw new ConfigurationImpactException(affected);court.setEnabled(enabled);courts.saveAndFlush(court);audit(facility,caller,enabled?"COURT_ACTIVATED":"COURT_SUSPENDED");return courtView(court);}
 public void validateSignupCategory(UUID category){validateCategory(category);}
 @Transactional public CourtView createSignupCourt(UUID application,UUID facility,UUID user,com.sporthub.common.dto.OwnerSignupSetup input){
  var actor=new Caller(user,"Applicant",Set.of("CUSTOMER"),Map.of(facility,Set.of("APPLICATION_READ","APPLICATION_EDIT")));
  var f=ownedEntity(facility,actor);if(!application.equals(applicationId(facility)))throw new ForbiddenException("Application facility binding differs");
  jdbc.queryForList("SELECT id FROM facilities WHERE id=? FOR UPDATE",UUID.class,facility);
  UUID id=com.sporthub.common.dto.OwnerSignupSetup.resourceId(application,"court");
  if(courts.existsById(id))return courtView(courtEntity(id));
  mutableOwnedEntity(facility,actor);var court=new Court();court.setId(id);court.setFacility(f);
  assignCourt(court,new CourtInput(input.courtCode(),input.courtName(),input.sportCategoryId(),null,true));courts.saveAndFlush(court);audit(f,actor,"COURT_CREATED");return courtView(court);
 }
 @Transactional public CourtView createCourt(UUID facilityId,CourtInput input,Caller caller){var c=new Court();c.setFacility(mutableOwnedEntity(facilityId,caller));assignCourt(c,input);courts.saveAndFlush(c);audit(c.getFacility(),caller,"COURT_CREATED");return courtView(c);}
 @Transactional public CourtView updateCourt(UUID id,CourtInput input,Caller caller){var c=courtEntity(id);mutableOwnedEntity(c.getFacility().getId(),caller);entities.refresh(c);if(!c.getSportCategoryId().equals(input.sportCategoryId()) || !c.isEnabled())validateCategory(input.sportCategoryId());if(c.isEnabled()!=input.enabled()||!c.getSportCategoryId().equals(input.sportCategoryId())||(input.courtTypeProvided()&&!Objects.equals(c.getCourtTypeId(),input.courtTypeId()))){var affected=configuration.begin(c.getFacility().getId()).stream().filter(r->r.courtId().equals(id)).toList();if(c.isEnabled()&&!input.enabled()&&!affected.isEmpty())throw new ConfigurationImpactException(affected);}assignCourt(c,input);courts.saveAndFlush(c);audit(c.getFacility(),caller,"COURT_UPDATED");return courtView(c);}
 public List<MaintenanceView> windows(UUID courtId,Caller caller){ownedCourt(courtId,caller);return maintenance.findByCourtIdAndCancelledFalseOrderByStartsAt(courtId).stream().map(this::maintenanceView).toList();}
 @Transactional public MaintenanceView addWindow(UUID courtId,MaintenanceInput input,Caller caller){
  var court=courtEntity(courtId);mutableOperation(court.getFacility().getId(),"COURT_MAINTENANCE_MANAGE",caller);
  if(!input.startsAt().isBefore(input.endsAt()))throw new IllegalArgumentException("Maintenance start must precede end");
  if(input.startsAt().isBefore(java.time.Instant.now()))throw new IllegalArgumentException("Maintenance cannot start in the past");
  for(var existing:maintenance.findByCourtIdAndCancelledFalseOrderByStartsAt(courtId))
   if(input.startsAt().isBefore(existing.getEndsAt())&&input.endsAt().isAfter(existing.getStartsAt()))
    throw new ConflictException("Maintenance intervals overlap");
  var affected=configuration.begin(court.getFacility().getId()).stream()
   .filter(r->r.courtId().equals(courtId)&&r.startsAt().isBefore(input.endsAt())&&r.endsAt().isAfter(input.startsAt())).toList();
  if(!affected.isEmpty())throw new ConfigurationImpactException(affected);
  var m=new Maintenance();m.setCourtId(courtId);m.setStartsAt(input.startsAt());m.setEndsAt(input.endsAt());m.setReason(input.reason());m.setCreatedBy(caller.id());
  maintenance.saveAndFlush(m);audit(court.getFacility(),caller,"MAINTENANCE_CREATED");return maintenanceView(m);
 }
 @Transactional public void cancelWindow(UUID id,Caller caller){var m=maintenance.findById(id).orElseThrow(()->new ResourceNotFoundException("Maintenance not found"));var court=courtEntity(m.getCourtId());mutableOperation(court.getFacility().getId(),"COURT_MAINTENANCE_MANAGE",caller);if(m.getStartsAt().isBefore(java.time.Instant.now()))throw new ConflictException("Past maintenance cannot be changed");configuration.begin(court.getFacility().getId());m.setCancelled(true);audit(court.getFacility(),caller,"MAINTENANCE_CANCELLED");}
 public List<CourtTypeView> courtTypes(UUID facility,Caller caller){if(caller==null)publicDetail(facility);else ownedEntity(facility,caller);return jdbc.query("SELECT * FROM court_types WHERE facility_id=? ORDER BY name,id",(r,n)->new CourtTypeView(r.getObject("id",UUID.class),facility,r.getString("name"),r.getBoolean("active"),r.getLong("version")),facility);}
 @Transactional public CourtTypeView createCourtType(UUID facility,CourtTypeInput input,Caller caller){
  var f=mutableOwnedEntity(facility,caller);if(!validator.validate(input).isEmpty())throw new IllegalArgumentException("Invalid court type");UUID id=UUID.randomUUID();jdbc.update("INSERT INTO court_types(id,facility_id,name,active,created_by) VALUES(?,?,?,?,?)",id,facility,input.name().trim(),input.active(),caller.id());audit(f,caller,"COURT_TYPE_CREATED");return courtTypes(facility,caller).stream().filter(t->t.id().equals(id)).findFirst().orElseThrow();
 }
 @Transactional public CourtTypeView updateCourtType(UUID facility,UUID id,CourtTypeInput input,Caller caller){
  var f=mutableOwnedEntity(facility,caller);if(!validator.validate(input).isEmpty())throw new IllegalArgumentException("Invalid court type");if(courtTypes(facility,caller).stream().noneMatch(t->t.id().equals(id)))throw new ResourceNotFoundException("Court type not found");configuration.begin(facility);jdbc.update("UPDATE court_types SET name=?,active=?,version=version+1,updated_at=NOW() WHERE id=? AND facility_id=?",input.name().trim(),input.active(),id,facility);audit(f,caller,"COURT_TYPE_UPDATED");return courtTypes(facility,caller).stream().filter(t->t.id().equals(id)).findFirst().orElseThrow();
 }
 private void validateCourtType(UUID facility,UUID id){if(id!=null&&!jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM court_types WHERE id=? AND facility_id=? AND active)",Boolean.class,id,facility))throw new IllegalArgumentException("Court type must be active and belong to this facility");}
 public List<CategoryView> categories(){return categoryManagement.list();}
 @Transactional public CategoryView createCategory(CategoryInput input,Caller caller){return categoryManagement.create(input,caller);}
 private void assign(Facility f,FacilityInput i){if(!validator.validate(i).isEmpty())throw new IllegalArgumentException("Invalid facility input; supply valid paired coordinates and contact information");var address=com.sporthub.common.dto.VietnamAdministrativeDivisions.resolve(i.provinceCode(),i.wardCode());if(i.contactEmailProvided())f.setContactEmail(i.contactEmail());try{java.time.ZoneId.of(i.timezone());}catch(Exception ex){throw new IllegalArgumentException("Invalid timezone");}f.setName(i.name().trim());f.setPhone(i.phone());f.setAddressLine(i.addressLine());f.setProvinceCode(address.provinceCode());f.setProvince(address.province());f.setWardCode(address.wardCode());f.setWard(address.ward());f.setDescription(i.description());f.setTimezone(i.timezone());if(i.latitudeProvided()){f.setLatitude(coordinate(i.latitude()));f.setLongitude(coordinate(i.longitude()));}f.setAmenities(i.amenities()==null?new LinkedHashSet<>():new LinkedHashSet<>(i.amenities()));}
 private java.math.BigDecimal coordinate(java.math.BigDecimal value){return value==null?null:value.setScale(7,java.math.RoundingMode.HALF_UP);}
 private String editDetails(String email,java.math.BigDecimal latitude,java.math.BigDecimal longitude,Facility f){
  var fields=new ArrayList<String>();if(!Objects.equals(email,f.getContactEmail()))fields.add("\"contactEmail\"");
  if(!Objects.equals(latitude,f.getLatitude())||!Objects.equals(longitude,f.getLongitude()))fields.add("\"location\"");
  return fields.isEmpty()?"{}":"{\"changedFields\":["+String.join(",",fields)+"]}";
 }
 private void validateCategory(UUID id){if(jdbc.queryForList("SELECT active FROM sport_categories WHERE id=?"+(org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly()?"":" FOR SHARE"),Boolean.class,id).stream().noneMatch(Boolean.TRUE::equals))throw new IllegalArgumentException("Sport category must be active");}
 private void assignCourt(Court c,CourtInput i){if(c.getSportCategoryId()==null)validateCategory(i.sportCategoryId());if(i.courtTypeProvided()){validateCourtType(c.getFacility().getId(),i.courtTypeId());c.setCourtTypeId(i.courtTypeId());}c.setCode(i.code().trim());c.setName(i.name().trim());c.setSportCategoryId(i.sportCategoryId());c.setDescription(i.description());c.setEnabled(i.enabled());}
 private void audit(Facility f,Caller c,String action){jdbc.update("INSERT INTO facility_audit(id,actor_id,facility_id,action) VALUES(?,?,?,?)",UUID.randomUUID(),c.id(),f.getId(),action);}
 public FacilityView view(Facility f){return new FacilityView(f.getId(),f.getOwnerId(),f.getName(),f.getPhone(),f.getAddressLine(),f.getProvinceCode(),f.getProvince(),f.getWardCode(),f.getWard(),f.getDescription(),f.getTimezone(),f.getLatitude(),f.getLongitude(),f.getStatus(),Set.copyOf(f.getAmenities()),f.getVersion());}
 public FacilityProfileView profileView(Facility f){return new FacilityProfileView(f.getId(),f.getOwnerId(),f.getName(),f.getPhone(),f.getAddressLine(),f.getProvinceCode(),f.getProvince(),f.getWardCode(),f.getWard(),f.getDescription(),f.getTimezone(),f.getLatitude(),f.getLongitude(),f.getStatus(),Set.copyOf(f.getAmenities()),f.getVersion(),f.getContactEmail());}
 public CourtView courtView(Court c){return new CourtView(c.getId(),c.getFacility().getId(),c.getSportCategoryId(),c.getCode(),c.getName(),c.getDescription(),c.isEnabled(),c.getVersion(),c.getCourtTypeId());}
 private MaintenanceView maintenanceView(Maintenance m){return new MaintenanceView(m.getId(),m.getCourtId(),m.getStartsAt(),m.getEndsAt(),m.getReason(),m.isCancelled());}
}
