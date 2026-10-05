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
 private final SportCategoryRepository categories; private final MaintenanceRepository maintenance; private final JdbcTemplate jdbc;
 public FacilityService(FacilityRepository f,CourtRepository c,SportCategoryRepository s,MaintenanceRepository m,JdbcTemplate jdbc){facilities=f;courts=c;categories=s;maintenance=m;this.jdbc=jdbc;}
 public List<FacilityView> owned(Caller caller){caller.requireRole("OWNER");return facilities.findByOwnerIdOrderByCreatedAtDesc(caller.id()).stream().map(this::view).toList();}
 public FacilityView ownedDetail(UUID id,Caller caller){return view(ownedEntity(id,caller));}
 public Facility ownedEntity(UUID id,Caller caller){var facility=find(id);if(!caller.hasRole("OWNER") || !facility.getOwnerId().equals(caller.id()))throw new ForbiddenException("Facility is outside your ownership");return facility;}
 @Transactional public Facility mutableOwnedEntity(UUID id,Caller caller){jdbc.queryForList("SELECT id FROM facilities WHERE id=? FOR UPDATE",UUID.class,id);var f=ownedEntity(id,caller);if(Set.of("PENDING_APPROVAL","REJECTED").contains(f.getStatus()))throw new ConflictException("Submitted/rejected facility profile cannot be edited; await a supplement decision or create a new draft");return f;}
 public Facility find(UUID id){return facilities.findById(id).orElseThrow(()->new ResourceNotFoundException("Facility not found"));}
 public List<FacilityView> search(String query){String q=query==null?"":query.toLowerCase(Locale.ROOT);return facilities.findByStatusOrderByNameAsc("ACTIVE").stream().filter(f->(f.getName()+" "+f.getAddressLine()+" "+f.getProvince()).toLowerCase(Locale.ROOT).contains(q)).map(this::view).toList();}
 public FacilityView publicDetail(UUID id){var f=find(id);if(!f.getStatus().equals("ACTIVE"))throw new ResourceNotFoundException("Facility is not publicly available");return view(f);}
 @Transactional public FacilityView create(FacilityInput input,Caller caller){caller.requireRole("OWNER");var f=new Facility();f.setOwnerId(caller.id());assign(f,input);facilities.saveAndFlush(f);audit(f,caller,"FACILITY_CREATED");return view(f);}
 @Transactional public FacilityView update(UUID id,FacilityInput input,Caller caller){var f=mutableOwnedEntity(id,caller);assign(f,input);facilities.saveAndFlush(f);audit(f,caller,"FACILITY_UPDATED");return view(f);}
 public List<CourtView> ownedCourts(UUID id,Caller caller){ownedEntity(id,caller);return courts.findByFacilityIdOrderByNameAsc(id).stream().map(this::courtView).toList();}
 public List<CourtView> publicCourts(UUID id){publicDetail(id);return courts.findByFacilityIdOrderByNameAsc(id).stream().filter(Court::isEnabled).map(this::courtView).toList();}
 public Court courtEntity(UUID id){return courts.findById(id).orElseThrow(()->new ResourceNotFoundException("Court not found"));}
 public CourtView ownedCourt(UUID id,Caller caller){var court=courtEntity(id);ownedEntity(court.getFacility().getId(),caller);return courtView(court);}
 public CourtContext context(UUID courtId,Caller caller){var court=courtEntity(courtId);var f=court.getFacility();if(caller==null)publicDetail(f.getId());else ownedEntity(f.getId(),caller);return new CourtContext(view(f),courtView(court),maintenance.findByCourtIdAndCancelledFalseOrderByStartsAt(courtId).stream().map(this::maintenanceView).toList());}
 @Transactional public CourtView createCourt(UUID facilityId,CourtInput input,Caller caller){var c=new Court();c.setFacility(mutableOwnedEntity(facilityId,caller));assignCourt(c,input);courts.saveAndFlush(c);audit(c.getFacility(),caller,"COURT_CREATED");return courtView(c);}
 @Transactional public CourtView updateCourt(UUID id,CourtInput input,Caller caller){var c=courtEntity(id);mutableOwnedEntity(c.getFacility().getId(),caller);if(!c.getSportCategoryId().equals(input.sportCategoryId()) || !c.isEnabled())validateCategory(input.sportCategoryId());assignCourt(c,input);courts.saveAndFlush(c);audit(c.getFacility(),caller,"COURT_UPDATED");return courtView(c);}
 public List<MaintenanceView> windows(UUID courtId,Caller caller){ownedCourt(courtId,caller);return maintenance.findByCourtIdAndCancelledFalseOrderByStartsAt(courtId).stream().map(this::maintenanceView).toList();}
 @Transactional public MaintenanceView addWindow(UUID courtId,MaintenanceInput input,Caller caller){var court=courtEntity(courtId);ownedEntity(court.getFacility().getId(),caller);if(!input.startsAt().isBefore(input.endsAt()))throw new IllegalArgumentException("Maintenance start must precede end");var m=new Maintenance();m.setCourtId(courtId);m.setStartsAt(input.startsAt());m.setEndsAt(input.endsAt());m.setReason(input.reason());m.setCreatedBy(caller.id());maintenance.saveAndFlush(m);audit(court.getFacility(),caller,"MAINTENANCE_CREATED");return maintenanceView(m);}
 @Transactional public void cancelWindow(UUID id,Caller caller){var m=maintenance.findById(id).orElseThrow(()->new ResourceNotFoundException("Maintenance not found"));var court=courtEntity(m.getCourtId());ownedEntity(court.getFacility().getId(),caller);if(m.getStartsAt().isBefore(java.time.Instant.now()))throw new ConflictException("Past maintenance cannot be changed");m.setCancelled(true);audit(court.getFacility(),caller,"MAINTENANCE_CANCELLED");}
 public List<CategoryView> categories(){return categories.findAllByOrderByNameAsc().stream().map(c->new CategoryView(c.getId(),c.getName(),c.isActive())).toList();}
 @Transactional public CategoryView createCategory(CategoryInput input,Caller caller){caller.requireRole("ADMIN");var c=new SportCategory(input.name().trim());c.setActive(input.active());categories.saveAndFlush(c);return new CategoryView(c.getId(),c.getName(),c.isActive());}
 private void assign(Facility f,FacilityInput i){try{java.time.ZoneId.of(i.timezone());}catch(Exception ex){throw new IllegalArgumentException("Invalid timezone");}f.setName(i.name().trim());f.setPhone(i.phone());f.setAddressLine(i.addressLine());f.setProvince(i.province());f.setDistrict(i.district());f.setWard(i.ward());f.setDescription(i.description());f.setTimezone(i.timezone());f.setLatitude(i.latitude());f.setLongitude(i.longitude());f.setAmenities(i.amenities()==null?new LinkedHashSet<>():new LinkedHashSet<>(i.amenities()));}
 private void validateCategory(UUID id){if(!categories.findById(id).map(SportCategory::isActive).orElse(false))throw new IllegalArgumentException("Sport category must be active");}
 private void assignCourt(Court c,CourtInput i){if(c.getSportCategoryId()==null)validateCategory(i.sportCategoryId());c.setCode(i.code().trim());c.setName(i.name().trim());c.setSportCategoryId(i.sportCategoryId());c.setDescription(i.description());c.setEnabled(i.enabled());}
 private void audit(Facility f,Caller c,String action){jdbc.update("INSERT INTO facility_audit(id,actor_id,facility_id,action) VALUES(?,?,?,?)",UUID.randomUUID(),c.id(),f.getId(),action);}
 public FacilityView view(Facility f){return new FacilityView(f.getId(),f.getOwnerId(),f.getName(),f.getPhone(),f.getAddressLine(),f.getProvince(),f.getDistrict(),f.getWard(),f.getDescription(),f.getTimezone(),f.getLatitude(),f.getLongitude(),f.getStatus(),Set.copyOf(f.getAmenities()),f.getVersion());}
 public CourtView courtView(Court c){return new CourtView(c.getId(),c.getFacility().getId(),c.getSportCategoryId(),c.getCode(),c.getName(),c.getDescription(),c.isEnabled(),c.getVersion());}
 private MaintenanceView maintenanceView(Maintenance m){return new MaintenanceView(m.getId(),m.getCourtId(),m.getStartsAt(),m.getEndsAt(),m.getReason(),m.isCancelled());}
}
