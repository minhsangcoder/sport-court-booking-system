package com.sporthub.transfer.service;
import com.sporthub.transfer.repository.TransferRepository;
import com.sporthub.transfer.repository.TransferRepository.Row;
import static com.sporthub.transfer.web.TransferDtos.*;
import com.sporthub.common.security.RemoteIdentity.Caller;
import com.sporthub.common.security.Signatures;
import com.sporthub.common.exception.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Service @Transactional(readOnly=true)
public class TransferService {
 private final TransferRepository repo;private final TransferDependencies deps;private final Clock clock;private final int lockSeconds;
 public TransferService(TransferRepository repo,TransferDependencies deps,Clock clock,@Value("${TRANSFER_ACQUISITION_SECONDS:600}") int lockSeconds){this.repo=repo;this.deps=deps;this.clock=clock;this.lockSeconds=lockSeconds;}
 @Transactional public UUID create(Create input,String key,Caller caller){
  caller.requireRole("CUSTOMER");key(key);repo.lock(caller.id());String fingerprint=Signatures.sha256(repo.write(input));var previous=repo.previous(caller.id(),"CREATE",key,fingerprint);if(previous!=null)return previous;
  repo.lock(input.bookingId());var b=deps.booking(input.bookingId());if(!b.path("currentHolderId").asText().equals(caller.id().toString()))throw new ForbiddenException("Only your current booking can be transferred");
  validateBooking(b);UUID facility=UUID.fromString(b.path("facilityId").asText()),court=UUID.fromString(b.path("courtId").asText());policy(facility,Instant.parse(b.path("startsAt").asText()));
  var original=b.path("amount").decimalValue();price(input.price(),original,b.path("currency").asText());deadline(input.deadline(),Instant.parse(b.path("startsAt").asText()));
  if(repo.jdbc().queryForObject("SELECT count(*) FROM transfer_listings WHERE booking_id=? AND state IN ('ACTIVE','LOCKED','PENDING_AUDIT')",Integer.class,input.bookingId())>0)throw new ConflictException("Booking already has an active transfer listing");
  var context=deps.context(court);var venue=Map.of("facilityName",context.path("facility").path("name").asText(),"courtName",context.path("court").path("name").asText(),"sportCategoryId",context.path("court").path("sportCategoryId").asText(),"address",context.path("facility").path("addressLine").asText(),"province",context.path("facility").path("province").asText(),"district",context.path("facility").path("district").asText(),"timezone",context.path("facility").path("timezone").asText(),"amenities",context.path("facility").path("amenities"));
  UUID id=UUID.randomUUID();repo.jdbc().update("INSERT INTO transfer_listings(id,booking_id,seller_id,facility_id,court_id,snapshot,original_amount,price,currency,starts_at,ends_at,deadline,state,workflow) VALUES(?,?,?,?,?,?::jsonb,?,?,?,?,?,?,'ACTIVE','REGISTER')",id,input.bookingId(),caller.id(),facility,court,repo.write(venue),original,input.price(),b.path("currency").asText(),Timestamp.from(Instant.parse(b.path("startsAt").asText())),Timestamp.from(Instant.parse(b.path("endsAt").asText())),Timestamp.from(input.deadline().truncatedTo(ChronoUnit.MILLIS)));
  repo.remember(caller.id(),"CREATE",key,fingerprint,id);repo.audit(repo.find(id),caller.id(),"LISTING_CREATED",input);return id;
 }
 public List<Listing> market(String q,UUID category,Instant after,Instant before,BigDecimal maxPrice,String sort,Caller caller){
  caller.requireRole("CUSTOMER");var rows=repo.rows("SELECT * FROM transfer_listings WHERE state='ACTIVE' AND workflow='READY' AND deadline>? ORDER BY starts_at LIMIT 100",Timestamp.from(clock.instant()));var result=new ArrayList<Listing>();
  for(var r:rows){if(q!=null&&!q.isBlank()&&!repo.write(r.snapshot()).toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT)))continue;if(category!=null&&!r.snapshot().path("sportCategoryId").asText().equals(category.toString()))continue;if(after!=null&&r.startsAt().isBefore(after)||before!=null&&r.startsAt().isAfter(before)||maxPrice!=null&&r.price().compareTo(maxPrice)>0)continue;if(valid(r))result.add(repo.view(r,caller.id(),true));}
  if("price".equals(sort))result.sort(Comparator.comparing(Listing::price));else if("discount".equals(sort))result.sort(Comparator.comparing((Listing l)->l.originalAmount().subtract(l.price()).divide(l.originalAmount(),6,java.math.RoundingMode.HALF_UP)).reversed());return result;
 }
 public List<Listing> mine(Caller caller){return repo.rows("SELECT * FROM transfer_listings WHERE seller_id=? ORDER BY created_at DESC LIMIT 100",caller.id()).stream().map(r->repo.view(r,caller.id(),false)).toList();}
 public List<Acquisition> purchases(Caller caller){return repo.jdbc().queryForList("SELECT id FROM transfer_acquisitions WHERE buyer_id=? ORDER BY created_at DESC LIMIT 100",UUID.class,caller.id()).stream().map(repo::acquisition).toList();}
 public Listing detail(UUID id,Caller caller){caller.requireRole("CUSTOMER");var r=repo.find(id);return repo.view(r,caller.id(),r.state().equals("ACTIVE")&&r.workflow().equals("READY")&&valid(r));}
 @Transactional public UUID acquire(UUID id,String key,Caller caller){
  caller.requireRole("CUSTOMER");key(key);repo.lock(caller.id());String fp=Signatures.sha256(id.toString());var previous=repo.previous(caller.id(),"ACQUIRE",key,fp);if(previous!=null)return previous;
  repo.lock(id);var r=repo.find(id);if(r.sellerId().equals(caller.id()))throw new ForbiddenException("You cannot acquire your own listing");if(!r.state().equals("ACTIVE")||!r.workflow().equals("READY")||!valid(r))throw new ConflictException("Listing is locked, expired or no longer eligible");policy(r.facilityId(),r.startsAt());
  Instant expiry=clock.instant().plusSeconds(lockSeconds).truncatedTo(ChronoUnit.MILLIS);if(expiry.isAfter(r.deadline()))expiry=r.deadline();UUID acquisition=UUID.randomUUID();
  repo.jdbc().update("INSERT INTO transfer_acquisitions(id,listing_id,buyer_id,amount,currency,expires_at,state) VALUES(?,?,?,?,?,?,'PENDING')",acquisition,id,caller.id(),r.price(),r.currency(),Timestamp.from(expiry));
  repo.jdbc().update("UPDATE transfer_listings SET state='LOCKED',workflow='LOCK',active_acquisition_id=?,version=version+1,updated_at=NOW() WHERE id=?",acquisition,id);
  repo.remember(caller.id(),"ACQUIRE",key,fp,acquisition);repo.audit(r,caller.id(),"ACQUISITION_CREATED",Map.of("acquisitionId",acquisition));return acquisition;
 }
 public Acquisition acquisition(UUID id,Caller caller){var a=repo.acquisition(id);if(!a.buyerId().equals(caller.id()))throw new ForbiddenException("Acquisition belongs to another buyer");return a;}
 public Payable payable(UUID id,Caller caller){var a=acquisition(id,caller);var r=repo.find(a.listingId());if(!a.state().equals("PENDING")||!a.expiresAt().isAfter(clock.instant())||!r.workflow().equals("READY")||!r.state().equals("LOCKED")||!id.equals(r.activeAcquisitionId()))throw new ConflictException("Transfer acquisition is no longer payable");return new Payable(r.bookingId(),caller.id(),r.sellerId(),id,a.amount(),a.currency(),a.expiresAt(),"TRANSFER");}
 @Transactional public void cancel(UUID id,Caller caller){var a=acquisition(id,caller);repo.lock(a.listingId());a=repo.acquisition(id);if(a.state().equals("CANCELLED"))return;if(!a.state().equals("PENDING"))throw new ConflictException("Only an unpaid pending acquisition can be cancelled");repo.jdbc().update("UPDATE transfer_acquisitions SET state='CANCELLED',updated_at=NOW() WHERE id=?",id);repo.jdbc().update("UPDATE transfer_listings SET workflow='UNLOCK',next_attempt_at=NOW(),updated_at=NOW() WHERE id=? AND active_acquisition_id=?",a.listingId(),id);repo.audit(repo.find(a.listingId()),caller.id(),"ACQUISITION_CANCELLED",Map.of("acquisitionId",id));}
 @Transactional public void edit(UUID id,Edit input,Caller caller){repo.lock(id);var r=owner(id,caller);if(!r.state().equals("ACTIVE")||!r.workflow().equals("READY"))throw new ConflictException("Only an unlocked active listing can be edited");price(input.price(),r.originalAmount(),r.currency());deadline(input.deadline(),r.startsAt());policy(r.facilityId(),r.startsAt());repo.jdbc().update("UPDATE transfer_listings SET price=?,deadline=?,workflow='EDIT',next_attempt_at=NOW(),version=version+1,updated_at=NOW() WHERE id=?",input.price(),Timestamp.from(input.deadline().truncatedTo(ChronoUnit.MILLIS)),id);repo.audit(r,caller.id(),"LISTING_EDITED",input);}
 @Transactional public void withdraw(UUID id,Caller caller){repo.lock(id);var r=owner(id,caller);if(r.state().equals("WITHDRAWN"))return;if(!r.state().equals("ACTIVE")||!r.workflow().equals("READY"))throw new ConflictException("Only an unlocked active listing can be withdrawn");repo.jdbc().update("UPDATE transfer_listings SET state='WITHDRAWN',workflow='WITHDRAW',next_attempt_at=NOW(),version=version+1,updated_at=NOW() WHERE id=?",id);repo.audit(r,caller.id(),"LISTING_WITHDRAWN",Map.of());}
 private Row owner(UUID id,Caller caller){var r=repo.find(id);if(!r.sellerId().equals(caller.id()))throw new ForbiddenException("Listing belongs to another seller");return r;}
 private boolean valid(Row r){if(!r.deadline().isAfter(clock.instant()))return false;var b=deps.booking(r.bookingId());return b.path("status").asText().equals("CONFIRMED")&&b.path("currentHolderId").asText().equals(r.sellerId().toString())&&r.startsAt().isAfter(clock.instant());}
 private void validateBooking(JsonNode b){if(!b.path("transferBlockedReason").asText().isBlank())throw new ConflictException(b.path("transferBlockedReason").asText());if(!b.path("status").asText().equals("CONFIRMED")||b.path("paidAt").isNull()||!Instant.parse(b.path("startsAt").asText()).isAfter(clock.instant()))throw new ConflictException("A paid confirmed future booking is required");}
 private void policy(UUID id,Instant starts){var p=deps.policy(id);if(!p.path("configured").asBoolean())throw new ConflictException("BLOCKED_RULE: facility Owner must configure its transfer eligibility policy");if(!p.path("enabled").asBoolean()||!starts.minusSeconds(p.path("minLeadSeconds").asLong()).isAfter(clock.instant()))throw new ConflictException("Facility transfer policy does not permit this booking");}
 private void price(BigDecimal amount,BigDecimal original,String currency){if(amount.signum()<=0||amount.compareTo(original)>0||amount.scale()>2||currency.equals("VND")&&amount.stripTrailingZeros().scale()>0)throw new ConflictException("Transfer price must be positive and no greater than the original paid amount");}
 private void deadline(Instant value,Instant starts){if(!value.isAfter(clock.instant())||value.isAfter(starts))throw new ConflictException("Transfer deadline must be in the future and no later than play starts");}
 private void key(String key){if(key==null||!key.matches("[A-Za-z0-9_.:-]{1,100}"))throw new IllegalArgumentException("A valid Idempotency-Key is required");}
}
