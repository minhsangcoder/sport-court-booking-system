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
 private final FacilityRepository facilities; private final CourtRepository courts;
 private final SportCategoryRepository categories; private final MaintenanceRepository maintenance; private final JdbcTemplate jdbc;private final OwnerApplicationGate gate;private final jakarta.validation.Validator validator;
 public FacilityService(FacilityRepository f,CourtRepository c,SportCategoryRepository s,MaintenanceRepository m,JdbcTemplate jdbc,OwnerApplicationGate gate,jakarta.validation.Validator validator){this.validator=validator;facilities=f;courts=c;categories=s;maintenance=m;this.jdbc=jdbc;this.gate=gate;}
 public List<FacilityProfileView> owned(Caller caller){caller.requireRole("OWNER");return facilities.findByOwnerIdOrderByCreatedAtDesc(caller.id()).stream().map(this::profileView).toList();}
 public FacilityProfileView ownedDetail(UUID id,Caller caller){return profileView(ownedEntity(id,caller));}
 @Transactional public FacilityProfileView writeAccess(UUID id,Caller caller){var f=ownedEntity(id,caller);if(!caller.hasRole("OWNER")&&!caller.facilityBindings().getOrDefault(id,Set.of()).contains("APPLICATION_EDIT"))throw new ForbiddenException("Application workspace is read-only");if(Set.of("PENDING_APPROVAL","REJECTED").contains(f.getStatus()))throw new ConflictException("Facility configuration is frozen during review");var app=applicationId(id);if(app!=null&&f.getStatus().equals("ACTIVE")&&!gate.approved(List.of(app)).contains(app))throw new ConflictException("Activation is pending");return profileView(f);}
 public Facility ownedEntity(UUID id,Caller caller){var facility=find(id);boolean applicant=caller.hasRole("CUSTOMER")&&caller.facilityBindings().getOrDefault(id,Set.of()).contains("APPLICATION_READ")&&applicationId(id)!=null;if(!(caller.hasRole("OWNER")||applicant) || !facility.getOwnerId().equals(caller.id()))throw new ForbiddenException("Facility is outside your ownership");return facility;}
 @Transactional public Facility mutableOwnedEntity(UUID id,Caller caller){jdbc.queryForList("SELECT id FROM facilities WHERE id=? FOR UPDATE",UUID.class,id);var f=ownedEntity(id,caller);if(!caller.hasRole("OWNER")&&!caller.facilityBindings().getOrDefault(id,Set.of()).contains("APPLICATION_EDIT"))throw new ForbiddenException("Application workspace is read-only");if(Set.of("PENDING_APPROVAL","REJECTED").contains(f.getStatus()))throw new ConflictException("Submitted/rejected facility profile cannot be edited; await a supplement decision or create a new draft");if(applicationId(id)!=null&&f.getStatus().equals("ACTIVE")&&!gate.approved(List.of(applicationId(id))).contains(applicationId(id)))throw new ConflictException("First facility activation is awaiting application commit");return f;}
 public UUID applicationId(UUID id){return jdbc.queryForList("SELECT application_id FROM first_facility_applications WHERE facility_id=?",UUID.class,id).stream().findFirst().orElse(null);}
 public Facility find(UUID id){return facilities.findById(id).orElseThrow(()->new ResourceNotFoundException("Facility not found"));}
 public List<FacilityView> search(String query){return search(query,null,null,null);}
 public List<FacilityView> search(String query,String province,String district,UUID category){
  var candidates=jdbc.queryForList("""
   SELECT f.id FROM facilities f WHERE f.status='ACTIVE'
    AND lower(concat_ws(' ',f.name,f.address_line,f.province,f.district,f.ward)) LIKE ?
    AND lower(f.province) LIKE ? AND lower(f.district) LIKE ?
    AND EXISTS(SELECT 1 FROM courts c JOIN sport_categories s ON s.id=c.sport_category_id
      WHERE c.facility_id=f.id AND c.enabled AND s.active AND (?::uuid IS NULL OR c.sport_category_id=?::uuid))
   ORDER BY f.name,f.id LIMIT 200
   """,UUID.class,pattern(query),pattern(province),pattern(district),category,category);var bindings=new HashMap<UUID,UUID>();for(var id:candidates){var app=applicationId(id);if(app!=null)bindings.put(id,app);}var committed=gate.approved(bindings.values());return candidates.stream().filter(id->!bindings.containsKey(id)||committed.contains(bindings.get(id))).map(id->view(find(id))).toList();
 }
 private String pattern(String value){if(value!=null && value.length()>180)throw new IllegalArgumentException("Search text must not exceed 180 characters");return "%"+(value==null?"":value.trim().toLowerCase(Locale.ROOT).replace("\\","\\\\").replace("%","\\%").replace("_","\\_"))+"%";}
 public FacilityView publicDetail(UUID id){var f=find(id);var app=applicationId(id);if(!f.getStatus().equals("ACTIVE")||(app!=null&&!gate.approved(List.of(app)).contains(app)))throw new ResourceNotFoundException("Facility is not publicly available");return view(f);}
 @Transactional public FacilityProfileView createFirst(UUID applicationId,UUID facilityId,UUID userId,FacilityInput input){jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?::text,0))",Object.class,applicationId.toString());var existing=applicationId(facilityId);if(existing!=null){var f=find(facilityId);if(!existing.equals(applicationId)||!f.getOwnerId().equals(userId))throw new ConflictException("Application facility binding differs");return profileView(f);}if(facilities.existsById(facilityId))throw new ConflictException("Facility id already exists");var f=new Facility();f.setId(facilityId);f.setOwnerId(userId);assign(f,input);facilities.saveAndFlush(f);jdbc.update("INSERT INTO first_facility_applications(application_id,facility_id,owner_id) VALUES(?,?,?)",applicationId,facilityId,userId);audit(f,new Caller(userId,"Applicant",Set.of("CUSTOMER"),Map.of()),"FIRST_FACILITY_DRAFT_CREATED");return profileView(f);}
 @Transactional public FacilityProfileView create(FacilityInput input,Caller caller){caller.requireRole("OWNER");var f=new Facility();f.setOwnerId(caller.id());assign(f,input);facilities.saveAndFlush(f);audit(f,caller,"FACILITY_CREATED");return profileView(f);}
 @Transactional public FacilityProfileView update(UUID id,FacilityInput input,Caller caller){var f=mutableOwnedEntity(id,caller);var before=f.getContactEmail();var latitude=f.getLatitude();var longitude=f.getLongitude();assign(f,input);facilities.saveAndFlush(f);jdbc.update("INSERT INTO facility_audit(id,actor_id,facility_id,action,details) VALUES(?,?,?,'FACILITY_UPDATED',?)",UUID.randomUUID(),caller.id(),id,editDetails(before,latitude,longitude,f));return profileView(f);}
 public List<CourtView> ownedCourts(UUID id,Caller caller){ownedEntity(id,caller);return courts.findByFacilityIdOrderByNameAsc(id).stream().map(this::courtView).toList();}
 public List<CourtView> publicCourts(UUID id){publicDetail(id);return courts.findByFacilityIdOrderByNameAsc(id).stream().filter(Court::isEnabled).map(this::courtView).toList();}
 public Court courtEntity(UUID id){return courts.findById(id).orElseThrow(()->new ResourceNotFoundException("Court not found"));}
 public CourtView ownedCourt(UUID id,Caller caller){var court=courtEntity(id);ownedEntity(court.getFacility().getId(),caller);return courtView(court);}
 public CourtContext context(UUID courtId,Caller caller){var court=courtEntity(courtId);var f=court.getFacility();if(caller==null)publicDetail(f.getId());else ownedEntity(f.getId(),caller);return new CourtContext(view(f),courtView(court),maintenance.findByCourtIdAndCancelledFalseOrderByStartsAt(courtId).stream().map(this::maintenanceView).toList());}
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
 @Transactional public CourtView updateCourt(UUID id,CourtInput input,Caller caller){var c=courtEntity(id);mutableOwnedEntity(c.getFacility().getId(),caller);if(!c.getSportCategoryId().equals(input.sportCategoryId()) || !c.isEnabled())validateCategory(input.sportCategoryId());assignCourt(c,input);courts.saveAndFlush(c);audit(c.getFacility(),caller,"COURT_UPDATED");return courtView(c);}
 public List<MaintenanceView> windows(UUID courtId,Caller caller){ownedCourt(courtId,caller);return maintenance.findByCourtIdAndCancelledFalseOrderByStartsAt(courtId).stream().map(this::maintenanceView).toList();}
 @Transactional public MaintenanceView addWindow(UUID courtId,MaintenanceInput input,Caller caller){var court=courtEntity(courtId);mutableOwnedEntity(court.getFacility().getId(),caller);if(!input.startsAt().isBefore(input.endsAt()))throw new IllegalArgumentException("Maintenance start must precede end");var m=new Maintenance();m.setCourtId(courtId);m.setStartsAt(input.startsAt());m.setEndsAt(input.endsAt());m.setReason(input.reason());m.setCreatedBy(caller.id());maintenance.saveAndFlush(m);audit(court.getFacility(),caller,"MAINTENANCE_CREATED");return maintenanceView(m);}
 @Transactional public void cancelWindow(UUID id,Caller caller){var m=maintenance.findById(id).orElseThrow(()->new ResourceNotFoundException("Maintenance not found"));var court=courtEntity(m.getCourtId());mutableOwnedEntity(court.getFacility().getId(),caller);if(m.getStartsAt().isBefore(java.time.Instant.now()))throw new ConflictException("Past maintenance cannot be changed");m.setCancelled(true);audit(court.getFacility(),caller,"MAINTENANCE_CANCELLED");}
 public List<CategoryView> categories(){return categories.findAllByOrderByNameAsc().stream().map(c->new CategoryView(c.getId(),c.getName(),c.isActive())).toList();}
 @Transactional public CategoryView createCategory(CategoryInput input,Caller caller){caller.requireRole("ADMIN");var c=new SportCategory(input.name().trim());c.setActive(input.active());categories.saveAndFlush(c);return new CategoryView(c.getId(),c.getName(),c.isActive());}
 private void assign(Facility f,FacilityInput i){if(!validator.validate(i).isEmpty())throw new IllegalArgumentException("Invalid facility input; supply valid paired coordinates and contact information");if(i.contactEmailProvided())f.setContactEmail(i.contactEmail());try{java.time.ZoneId.of(i.timezone());}catch(Exception ex){throw new IllegalArgumentException("Invalid timezone");}f.setName(i.name().trim());f.setPhone(i.phone());f.setAddressLine(i.addressLine());f.setProvince(i.province());f.setDistrict(i.district());f.setWard(i.ward());f.setDescription(i.description());f.setTimezone(i.timezone());if(i.latitudeProvided()){f.setLatitude(coordinate(i.latitude()));f.setLongitude(coordinate(i.longitude()));}f.setAmenities(i.amenities()==null?new LinkedHashSet<>():new LinkedHashSet<>(i.amenities()));}
 private java.math.BigDecimal coordinate(java.math.BigDecimal value){return value==null?null:value.setScale(7,java.math.RoundingMode.HALF_UP);}
 private String editDetails(String email,java.math.BigDecimal latitude,java.math.BigDecimal longitude,Facility f){
  var fields=new ArrayList<String>();if(!Objects.equals(email,f.getContactEmail()))fields.add("\"contactEmail\"");
  if(!Objects.equals(latitude,f.getLatitude())||!Objects.equals(longitude,f.getLongitude()))fields.add("\"location\"");
  return fields.isEmpty()?"{}":"{\"changedFields\":["+String.join(",",fields)+"]}";
 }
 private void validateCategory(UUID id){if(!categories.findById(id).map(SportCategory::isActive).orElse(false))throw new IllegalArgumentException("Sport category must be active");}
 private void assignCourt(Court c,CourtInput i){if(c.getSportCategoryId()==null)validateCategory(i.sportCategoryId());c.setCode(i.code().trim());c.setName(i.name().trim());c.setSportCategoryId(i.sportCategoryId());c.setDescription(i.description());c.setEnabled(i.enabled());}
 private void audit(Facility f,Caller c,String action){jdbc.update("INSERT INTO facility_audit(id,actor_id,facility_id,action) VALUES(?,?,?,?)",UUID.randomUUID(),c.id(),f.getId(),action);}
 public FacilityView view(Facility f){return new FacilityView(f.getId(),f.getOwnerId(),f.getName(),f.getPhone(),f.getAddressLine(),f.getProvince(),f.getDistrict(),f.getWard(),f.getDescription(),f.getTimezone(),f.getLatitude(),f.getLongitude(),f.getStatus(),Set.copyOf(f.getAmenities()),f.getVersion());}
 public FacilityProfileView profileView(Facility f){return new FacilityProfileView(f.getId(),f.getOwnerId(),f.getName(),f.getPhone(),f.getAddressLine(),f.getProvince(),f.getDistrict(),f.getWard(),f.getDescription(),f.getTimezone(),f.getLatitude(),f.getLongitude(),f.getStatus(),Set.copyOf(f.getAmenities()),f.getVersion(),f.getContactEmail());}
 public CourtView courtView(Court c){return new CourtView(c.getId(),c.getFacility().getId(),c.getSportCategoryId(),c.getCode(),c.getName(),c.getDescription(),c.isEnabled(),c.getVersion());}
 private MaintenanceView maintenanceView(Maintenance m){return new MaintenanceView(m.getId(),m.getCourtId(),m.getStartsAt(),m.getEndsAt(),m.getReason(),m.isCancelled());}
}
