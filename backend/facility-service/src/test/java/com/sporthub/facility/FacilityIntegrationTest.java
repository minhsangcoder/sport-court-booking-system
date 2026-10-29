package com.sporthub.facility;

import com.sporthub.common.security.RemoteIdentity;
import com.sporthub.common.security.RemoteIdentity.Caller;
import com.sporthub.common.exception.*;
import com.sporthub.facility.service.*;
import static com.sporthub.facility.web.FacilityDtos.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.mock.web.MockMultipartFile;
import org.testcontainers.containers.PostgreSQLContainer;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties={"spring.rabbitmq.username=test","spring.rabbitmq.password=test",
 "sporthub.media.access-key=test-access","sporthub.media.secret-key=test-secret","sporthub.media.endpoint=http://localhost:19000","sporthub.events.outbox.delay-ms=3600000"})
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class FacilityIntegrationTest {
 static PostgreSQLContainer<?> postgres;
 @DynamicPropertySource static void database(DynamicPropertyRegistry props){
  String external=System.getenv("SPORTHUB_FACILITY_TEST_DB_URL");
  if(external!=null){props.add("spring.datasource.url",()->external);props.add("spring.datasource.username",()->System.getenv("SPORTHUB_TEST_DB_USER"));props.add("spring.datasource.password",()->System.getenv("SPORTHUB_TEST_DB_PASSWORD"));}
  else{postgres=new PostgreSQLContainer<>("postgres:16-alpine");postgres.start();props.add("spring.datasource.url",postgres::getJdbcUrl);props.add("spring.datasource.username",postgres::getUsername);props.add("spring.datasource.password",postgres::getPassword);}
 }
 @AfterAll static void stop(){if(postgres!=null)postgres.stop();}
 @Autowired org.springframework.test.web.servlet.MockMvc http;
 @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
 @Autowired FacilityService service;
 @Autowired MediaService media;
 @Autowired FacilityReviewService reviews;
 @Autowired FacilityDocumentService documents;
 @MockBean FacilityReviewDependencies reviewDependencies;
 @MockBean OwnerApplicationGate applicationGate;
 @MockBean RemoteIdentity identity;
 @MockBean com.sporthub.common.schedule.ConfigurationGuardTransport guard;
 @Autowired com.sporthub.common.schedule.ConfigurationChanges changes;
 UUID fixtureCategory;
 @BeforeEach void emptyBookingImpact(){fixtureCategory=service.createCategory(new CategoryInput("Fixture sport "+UUID.randomUUID(),true),new Caller(UUID.randomUUID(),"Test admin",Set.of("ADMIN"),Map.of())).id();org.mockito.Mockito.doAnswer(a->new com.sporthub.common.schedule.ConfigurationGuardProtocol.Prepared(a.<com.sporthub.common.schedule.ConfigurationGuardProtocol.Command>getArgument(0).operationId(),List.of())).when(guard).prepare(org.mockito.ArgumentMatchers.any());}
 @Autowired com.sporthub.facility.web.TransferPolicyController policies;
 @Autowired com.sporthub.facility.service.FacilityTransferFeePolicyService transferFees;
 private Caller owner(){return new Caller(UUID.randomUUID(),"Owner",Set.of("OWNER","CUSTOMER"),Map.of());}
 @Autowired OwnerSignupFacilityService signupFacilities;
 @Test void removedRefundPolicyRoutesAndConfigurationTablesAreAbsent() throws Exception {
  var actor=owner();var f=service.create(input("No refund policy "+UUID.randomUUID()),actor);
  org.mockito.Mockito.when(identity.current(org.mockito.ArgumentMatchers.any())).thenReturn(actor);
  http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/owner/facilities/"+f.id()+"/refund-policy"))
    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
  http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/owner/facilities/"+f.id()+"/refund-policy").contentType("application/json").content("{\"rateBps\":9500,\"version\":0}"))
    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
  assertThat(jdbc.queryForObject("SELECT to_regclass('facility_refund_policy_heads') IS NULL AND to_regclass('facility_refund_policy_versions') IS NULL",Boolean.class)).isTrue();
 }
 @Test void ownerSignupFirstCourtIsIdempotentPrivateAndCannotChangeBinding(){
  UUID application=UUID.randomUUID(),facility=UUID.randomUUID(),user=UUID.randomUUID();service.createFirst(application,facility,user,locationInput("Signup facility","21","105"));UUID category=fixtureCategory;
  var setup=new com.sporthub.common.dto.OwnerSignupSetup("SIGNUP","Signup court",category,Set.of(1,2,3,4,5,6,7),java.time.LocalTime.of(6,0),java.time.LocalTime.of(22,0),60,new java.math.BigDecimal("100000"));
  var first=service.createSignupCourt(application,facility,user,setup);assertThat(service.createSignupCourt(application,facility,user,setup).id()).isEqualTo(first.id());assertThat(jdbc.queryForObject("SELECT count(*) FROM courts WHERE facility_id=?",Integer.class,facility)).isEqualTo(1);assertThatThrownBy(()->service.publicDetail(facility)).isInstanceOf(ResourceNotFoundException.class);assertThatThrownBy(()->service.createSignupCourt(UUID.randomUUID(),facility,user,setup)).isInstanceOf(ForbiddenException.class);
 }
 @Test void ownerSignupPreflightChecksExistingFacilityCategoryAndCoordinatesWithoutWriting()throws Exception{
  var input=json.createObjectNode();input.set("facility",json.valueToTree(locationInput("Preflight","21","105")));input.set("setup",json.valueToTree(Map.of("courtCode","C1","courtName","Court","sportCategoryId",fixtureCategory,"days",List.of(1),"opensAt","06:00","closesAt","22:00","slotMinutes",60,"pricePerSlot",100000)));
  assertThatCode(()->signupFacilities.validate(input)).doesNotThrowAnyException();((com.fasterxml.jackson.databind.node.ObjectNode)input.path("setup")).put("sportCategoryId",UUID.randomUUID().toString());assertThatThrownBy(()->signupFacilities.validate(input)).isInstanceOf(IllegalArgumentException.class);
  ((com.fasterxml.jackson.databind.node.ObjectNode)input.path("facility")).put("latitude",91);assertThatThrownBy(()->signupFacilities.validate(input)).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void firstFacilityIsScopedFrozenAndHiddenUntilIdentityCommitsEvenAfterLocalPreparation() throws Exception {
  UUID application=UUID.randomUUID(),facility=UUID.randomUUID(),user=UUID.randomUUID();var customer=new Caller(user,"Applicant",Set.of("CUSTOMER"),Map.of(facility,Set.of("APPLICATION_READ","APPLICATION_EDIT")));var admin=new Caller(UUID.randomUUID(),"Admin",Set.of("ADMIN"),Map.of());
  service.createFirst(application,facility,user,locationInput("First facility","21","105"));service.createFirst(application,facility,user,input("Ignored repeat"));
  assertThat(jdbc.queryForObject("SELECT count(*) FROM first_facility_applications WHERE application_id=?",Integer.class,application)).isEqualTo(1);
  assertThatThrownBy(()->service.create(input("No Owner grant"),customer)).isInstanceOf(ForbiddenException.class);
  assertThatThrownBy(()->service.ownedDetail(facility,new Caller(user,"No binding",Set.of("CUSTOMER"),Map.of()))).isInstanceOf(ForbiddenException.class);
  assertThatThrownBy(()->service.ownedDetail(facility,new Caller(UUID.randomUUID(),"Foreign applicant",Set.of("CUSTOMER"),customer.facilityBindings()))).isInstanceOf(ForbiddenException.class);
  var category=service.createCategory(new CategoryInput("First sport "+application,true),admin);service.createCourt(facility,new CourtInput("FIRST","First court",category.id(),null,true),customer);
  for(int n=0;n<2;n++)jdbc.update("INSERT INTO facility_documents(id,facility_id,name,object_key,content_type,size_bytes) VALUES(?,?,?,?,'application/pdf',100)",UUID.randomUUID(),facility,"Synthetic legal "+n,"private/first/"+UUID.randomUUID());
  jdbc.update("INSERT INTO facility_images(id,facility_id,object_key,content_type,size_bytes) VALUES(?,?,?,'image/png',100)",UUID.randomUUID(),facility,"first/"+UUID.randomUUID());
  var json=new com.fasterxml.jackson.databind.ObjectMapper();org.mockito.Mockito.when(reviewDependencies.applicationSnapshot(org.mockito.ArgumentMatchers.eq(application),org.mockito.ArgumentMatchers.eq(facility),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.eq(false))).thenReturn(json.readTree("{\"hours\":[{}],\"prices\":[{}],\"exceptions\":[]}"));
  reviews.submitFirst(facility,customer,null);reviews.submitFirst(facility,customer,null);
  assertThat(reviews.reviews(facility)).hasSize(1);
  assertThatThrownBy(()->service.writeAccess(facility,customer)).isInstanceOf(ConflictException.class);
  assertThatThrownBy(()->service.update(facility,input("Frozen"),customer)).isInstanceOf(ConflictException.class);
  assertThatThrownBy(()->reviews.decide(facility,"APPROVE","Wrong review channel",admin)).isInstanceOf(ConflictException.class);
  reviews.decideFirst(facility,"APPROVE","Reviewed first facility",admin);reviews.decideFirst(facility,"APPROVE","Repeated",admin);
  assertThatThrownBy(()->service.publicDetail(facility)).isInstanceOf(ResourceNotFoundException.class);
  assertThat(service.search("First facility")).noneMatch(f->f.id().equals(facility));
  org.mockito.Mockito.when(applicationGate.approved(org.mockito.ArgumentMatchers.anyCollection())).thenReturn(Set.of(application));
  assertThat(service.publicDetail(facility).status()).isEqualTo("ACTIVE");
  var approvedOwner=new Caller(user,"Owner",Set.of("CUSTOMER","OWNER"),Map.of());assertThat(service.writeAccess(facility,approvedOwner).id()).isEqualTo(facility);
  assertThat(service.search("First facility")).anyMatch(f->f.id().equals(facility));
  service.update(facility,locationInput("First facility","22","106"),approvedOwner);
  assertThat(service.publicDetail(facility).latitude()).isEqualByComparingTo("22");
  assertThat(reviews.reviews(facility).getFirst().snapshot().path("facility").path("latitude").decimalValue()).isEqualByComparingTo("21");
  assertThat(jdbc.queryForObject("SELECT count(*) FROM facility_audit WHERE facility_id=? AND action='ADMIN_FACILITY_APPROVE'",Integer.class,facility)).isEqualTo(1);
 }
 private FacilityInput input(String name){return new FacilityInput(name,"+84901234567","Address","01","Hà Nội","00004","Ba Đình","Description","Asia/Ho_Chi_Minh",null,null,Set.of("Parking"));}

 @Test void administrativeAddressValidatesHierarchyAndPersistsCanonicalNames()throws Exception{
  var actor=owner();var original=input("Canonical address");
  var untrusted=new FacilityInput(original.name(),original.phone(),"123 Example street","01","Arbitrary province","00004","Arbitrary ward",original.description(),original.timezone(),null,null,original.amenities());
  var saved=service.create(untrusted,actor);assertThat(saved.provinceCode()).isEqualTo("01");assertThat(saved.province()).isEqualTo("Hà Nội");assertThat(saved.wardCode()).isEqualTo("00004");assertThat(saved.ward()).isEqualTo("Ba Đình");assertThat(saved.addressLine()).isEqualTo("123 Example street");
  org.mockito.Mockito.when(identity.current(org.mockito.ArgumentMatchers.any())).thenReturn(actor);
  for(var pair:List.of(new String[]{"99","00004"},new String[]{"79","00004"},new String[]{"01","99999"},new String[]{"01","00001"})){
   var body=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(original);body.put("provinceCode",pair[0]);body.put("wardCode",pair[1]);
   http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/owner/facilities/"+saved.id()).contentType("application/json").content(body.toString())).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
   assertThatThrownBy(()->service.create(json.treeToValue(body,FacilityInput.class),actor)).isInstanceOf(IllegalArgumentException.class);
  }
  assertThat(service.ownedDetail(saved.id(),actor).addressLine()).isEqualTo("123 Example street");assertThat(service.ownedDetail(saved.id(),actor).provinceCode()).isEqualTo("01");assertThat(service.owned(actor)).hasSize(1);
 }

 @Test void facilityReviewFreezesSubmissionSupportsSupplementAndPublishesExactlyOneDecision(){
  var owner=owner();var admin=new Caller(UUID.randomUUID(),"Admin",Set.of("ADMIN"),Map.of());var f=service.create(emailInput("Review "+UUID.randomUUID(),"old@example.test"),owner);
  assertThatThrownBy(()->reviews.submit(f.id(),owner,"Bearer test")).isInstanceOf(ConflictException.class);
  var category=service.createCategory(new CategoryInput("Review sport "+UUID.randomUUID(),true),admin);service.createCourt(f.id(),new CourtInput("R1","Review court",category.id(),null,true),owner);
  var json=new com.fasterxml.jackson.databind.ObjectMapper();org.mockito.Mockito.when(reviewDependencies.hours(f.id(),"Bearer test")).thenReturn(json.createArrayNode());
  assertThatThrownBy(()->reviews.submit(f.id(),owner,"Bearer test")).isInstanceOf(ConflictException.class);
  var hours=json.createArrayNode();hours.addObject().put("dayOfWeek",1).put("opensAt","06:00").put("closesAt","22:00");org.mockito.Mockito.when(reviewDependencies.hours(f.id(),"Bearer test")).thenReturn(hours);
  UUID attachment=UUID.randomUUID();jdbc.update("INSERT INTO facility_documents(id,facility_id,name,object_key,content_type,size_bytes) VALUES(?,?,?,?,'application/pdf',100)",attachment,f.id(),"Submitted lease.pdf","private/review-lease/"+attachment);
  var first=reviews.submit(f.id(),owner,"Bearer test");assertThat(service.ownedDetail(f.id(),owner).status()).isEqualTo("PENDING_APPROVAL");
  assertThatThrownBy(()->reviews.submit(f.id(),owner,"Bearer test")).isInstanceOf(ConflictException.class);
  assertThatThrownBy(()->service.update(f.id(),input("Should not change"),owner)).isInstanceOf(ConflictException.class);
  assertThatThrownBy(()->reviews.decide(f.id(),"APPROVE","Review accepted",owner)).isInstanceOf(ForbiddenException.class);
  assertThatThrownBy(()->reviews.decide(f.id(),"REJECT","short",admin)).isInstanceOf(IllegalArgumentException.class);
  reviews.decide(f.id(),"SUPPLEMENT_REQUIRED","Please provide a clearer location document",admin);
  documents.delete(f.id(),attachment,owner);assertThat(documents.list(f.id(),owner)).isEmpty();assertThat(documents.list(f.id(),admin).getFirst().archived()).isTrue();assertThat(jdbc.queryForObject("SELECT count(*) FROM media_cleanup WHERE object_key=?",Integer.class,"private/review-lease/"+attachment)).isZero();
  assertThat(service.ownedDetail(f.id(),owner).status()).isEqualTo("DRAFT");service.update(f.id(),emailInput("Supplemented Facility","new@example.test"),owner);
  var second=reviews.submit(f.id(),owner,"Bearer test");reviews.decide(f.id(),"APPROVE","The additional facility meets the review criteria",admin);
  assertThat(service.publicDetail(f.id()).name()).isEqualTo("Supplemented Facility");assertThat(reviews.reviews(f.id())).hasSize(2);
  assertThat(reviews.reviews(f.id()).stream().filter(r->r.id().equals(first.id())).findFirst().orElseThrow().snapshot().path("facility").path("name").asText()).startsWith("Review ");
  assertThat(first.snapshot().path("facility").path("contactEmail").asText()).isEqualTo("old@example.test");assertThat(second.snapshot().path("facility").path("contactEmail").asText()).isEqualTo("new@example.test");
  service.update(f.id(),emailInput("Supplemented Facility","operational@example.test"),owner);assertThat(reviews.reviews(f.id()).stream().filter(r->r.id().equals(second.id())).findFirst().orElseThrow().snapshot().path("facility").path("contactEmail").asText()).isEqualTo("new@example.test");
  assertThatThrownBy(()->reviews.decide(f.id(),"APPROVE","Repeated approval must not publish twice",admin)).isInstanceOf(ConflictException.class);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM event_outbox WHERE body->>'aggregateId'=?",Integer.class,f.id().toString())).isEqualTo(2);
  assertThatThrownBy(()->jdbc.update("UPDATE facility_reviews SET snapshot='{}'::jsonb WHERE id=?",second.id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
  assertThatThrownBy(()->jdbc.update("DELETE FROM facility_audit WHERE facility_id=?",f.id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
 }
 @Test void rejectedFacilityPersistsDecisionKeepsSnapshotAndPublishesOnce() throws Exception {
  var owner=owner();var admin=new Caller(UUID.randomUUID(),"Admin",Set.of("ADMIN"),Map.of());
  var facility=service.create(input("Rejected facility "+UUID.randomUUID()),owner);
  service.createCourt(facility.id(),new CourtInput("REJECT","Review court",fixtureCategory,null,true),owner);
  org.mockito.Mockito.when(reviewDependencies.hours(facility.id(),"Bearer test")).thenReturn(json.readTree("[{\"dayOfWeek\":1,\"opensAt\":\"06:00\",\"closesAt\":\"22:00\"}]"));
  var submitted=reviews.submit(facility.id(),owner,"Bearer test");
  String reason="The submitted location documents require correction";
  assertThat(reviews.decide(facility.id(),"REJECT",reason,admin).status()).isEqualTo("REJECTED");
  var rejected=reviews.reviews(facility.id()).getFirst();
  assertThat(rejected.state()).isEqualTo("REJECTED");assertThat(rejected.reviewedBy()).isEqualTo(admin.id());
  assertThat(rejected.reviewedAt()).isNotNull();assertThat(rejected.reason()).isEqualTo(reason);
  assertThat(rejected.snapshot()).isEqualTo(submitted.snapshot());
  assertThatThrownBy(()->service.publicDetail(facility.id())).isInstanceOf(ResourceNotFoundException.class);
  assertThatThrownBy(()->service.update(facility.id(),input("Changed rejected facility"),owner)).isInstanceOf(ConflictException.class);
  assertThatThrownBy(()->reviews.decide(facility.id(),"REJECT",reason,admin)).isInstanceOf(ConflictException.class);
  var events=jdbc.queryForList("SELECT body::text FROM event_outbox WHERE body->>'aggregateId'=?",String.class,facility.id().toString());
  assertThat(events).hasSize(1);assertThat(json.readTree(events.getFirst()).path("payload").path("state").asText()).isEqualTo("REJECTED");
 }
 @Test void privateReviewDocumentsRejectPublicAndForeignAccess(){
  var owner=owner();var f=service.create(input("Private documents "+UUID.randomUUID()),owner);UUID doc=UUID.randomUUID();jdbc.update("INSERT INTO facility_documents(id,facility_id,name,object_key,content_type,size_bytes) VALUES(?,?,? ,?,'application/pdf',100)",doc,f.id(),"Lease.pdf","private/test/"+doc);
  assertThatThrownBy(()->documents.list(f.id(),owner())).isInstanceOf(ForbiddenException.class);
  var customer=new Caller(UUID.randomUUID(),"Customer",Set.of("CUSTOMER"),Map.of());assertThatThrownBy(()->documents.list(f.id(),customer)).isInstanceOf(ForbiddenException.class);
  assertThat(documents.list(f.id(),owner)).hasSize(1);var admin=new Caller(UUID.randomUUID(),"Admin",Set.of("ADMIN"),Map.of());assertThat(documents.list(f.id(),admin).getFirst().url()).contains("X-Amz-Expires=300");
  assertThat(jdbc.queryForObject("SELECT count(*) FROM facility_audit WHERE facility_id=? AND action='ADMIN_DOCUMENTS_VIEWED'",Integer.class,f.id())).isEqualTo(1);
  assertThatThrownBy(()->documents.upload(f.id(),new MockMultipartFile("file","bad.pdf","application/pdf","not pdf".getBytes()),owner)).isInstanceOf(IllegalArgumentException.class);
  documents.delete(f.id(),doc,owner);assertThat(documents.list(f.id(),owner)).isEmpty();assertThat(jdbc.queryForObject("SELECT count(*) FROM media_cleanup WHERE object_key=?",Integer.class,"private/test/"+doc)).isEqualTo(1);
 }

 @Test void transferPolicyRequiresOwnerAndHasNoImplicitDefault(){
  var owner=owner();var facility=service.create(input("Policy "+UUID.randomUUID()),owner);var request=new org.springframework.mock.web.MockHttpServletRequest();org.mockito.Mockito.when(identity.current(request)).thenReturn(owner);
  assertThat(policies.ownPolicy(facility.id(),request).getData().configured()).isFalse();var result=policies.update(facility.id(),new com.sporthub.facility.web.TransferPolicyController.PolicyInput(true,3600),request).getData();assertThat(result.configured()).isTrue();assertThat(result.minLeadSeconds()).isEqualTo(3600);
  org.mockito.Mockito.when(identity.current(request)).thenReturn(owner());assertThatThrownBy(()->policies.update(facility.id(),new com.sporthub.facility.web.TransferPolicyController.PolicyInput(false,0),request)).isInstanceOf(ForbiddenException.class);
 }
 @Test void transferFeePolicyIsVersionedHasNoDefaultAndRejectsNonOwners(){
  var actor=owner();var f=service.create(input("Refund policy "+UUID.randomUUID()),actor);
  assertThat(transferFees.own(f.id(),actor).configured()).isFalse();
  assertThat(transferFees.own(f.id(),actor).feeBps()).isNull();
  long version=0;
  for(int rate:new int[]{0,5000,8000,10000}){
   var next=transferFees.update(f.id(),new com.sporthub.facility.service.FacilityTransferFeePolicyService.Input(java.math.BigDecimal.valueOf(rate),version),actor);
   assertThat(next.version()).isEqualTo(++version);assertThat(next.feeBps()).isEqualTo(rate);assertThat(next.termsHash()).hasSize(64);
  }
  assertThat(transferFees.version(f.id(),1).feeBps()).isZero();
  assertThat(jdbc.queryForObject("SELECT count(*) FROM facility_audit WHERE facility_id=? AND action='TRANSFER_FEE_POLICY_UPDATED'",Integer.class,f.id())).isEqualTo(4);
  assertThatThrownBy(()->transferFees.update(f.id(),new com.sporthub.facility.service.FacilityTransferFeePolicyService.Input(java.math.BigDecimal.ZERO,0L),actor)).isInstanceOf(ConflictException.class);
  assertThatThrownBy(()->transferFees.update(f.id(),new com.sporthub.facility.service.FacilityTransferFeePolicyService.Input(java.math.BigDecimal.ZERO,4L),owner())).isInstanceOf(ForbiddenException.class);
  var staff=new Caller(UUID.randomUUID(),"Staff",Set.of("STAFF"),Map.of(f.id(),Set.of("BOOKING_REFUND")));
  assertThatThrownBy(()->transferFees.own(f.id(),staff)).isInstanceOf(ForbiddenException.class);
  assertThatThrownBy(()->transferFees.update(f.id(),new com.sporthub.facility.service.FacilityTransferFeePolicyService.Input(java.math.BigDecimal.ZERO,4L),staff)).isInstanceOf(ForbiddenException.class);
  assertThatThrownBy(()->jdbc.update("UPDATE facility_transfer_fee_policy_versions SET transfer_fee_bps=5000 WHERE facility_id=? AND version=1",f.id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  assertThatThrownBy(()->jdbc.update("DELETE FROM facility_transfer_fee_policy_heads WHERE facility_id=?",f.id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
 }
 @Test void transferFeePolicyApiRejectsFractionalBasisPointsAndOutOfRangeInputs()throws Exception{
  var actor=owner();var f=service.create(input("Refund validation "+UUID.randomUUID()),actor);
  org.mockito.Mockito.when(identity.current(org.mockito.ArgumentMatchers.any())).thenReturn(actor);
  for(String rate:List.of("-1","10001","8000.5","null")){
   http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/owner/facilities/"+f.id()+"/transfer-fee-policy").contentType("application/json").content("{\"feeBps\":"+rate+",\"version\":0}"))
    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
  }
  assertThat(transferFees.own(f.id(),actor).configured()).isFalse();
  http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/owner/facilities/"+f.id()+"/transfer-fee-policy").contentType("application/json").content("{\"feeBps\":8000,\"version\":0}"))
   .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
   .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.feeBps").value(8000));
 }
 @Test void transferFeePolicyIsIndependentPerFacilityAndSeparateFromRefundPolicy(){
  var actor=owner();var first=service.create(input("Fee facility A "+UUID.randomUUID()),actor);var second=service.create(input("Fee facility B "+UUID.randomUUID()),actor);
  var a=transferFees.update(first.id(),new com.sporthub.facility.service.FacilityTransferFeePolicyService.Input(java.math.BigDecimal.valueOf(500),0L),actor);
  var b=transferFees.update(second.id(),new com.sporthub.facility.service.FacilityTransferFeePolicyService.Input(java.math.BigDecimal.valueOf(1000),0L),actor);
  assertThat(a.feeBps()).isEqualTo(500);assertThat(b.feeBps()).isEqualTo(1000);
  transferFees.update(first.id(),new com.sporthub.facility.service.FacilityTransferFeePolicyService.Input(java.math.BigDecimal.ZERO,1L),actor);
  assertThat(transferFees.own(second.id(),actor).feeBps()).isEqualTo(1000);assertThat(transferFees.version(first.id(),1).feeBps()).isEqualTo(500);
  assertThat(a.termsHash()).isNotEqualTo(b.termsHash());
 }
 @Test void facilityCrudRemainsDraftAndOwnerScoped(){
  var owner=owner();var facility=service.create(input("Facility "+UUID.randomUUID()),owner);
  assertThat(facility.status()).isEqualTo("DRAFT");
  assertThat(service.owned(owner)).extracting(FacilityProfileView::id).contains(facility.id());
  assertThatThrownBy(()->service.publicDetail(facility.id())).isInstanceOf(ResourceNotFoundException.class);
  assertThatThrownBy(()->service.ownedDetail(facility.id(),owner())).isInstanceOf(ForbiddenException.class);
  assertThat(service.update(facility.id(),input("Updated Facility"),owner).name()).isEqualTo("Updated Facility");
 }
 @Test void publicSearchCombinesFiltersAndExcludesDraftDisabledAndInactiveSports(){
  var owner=owner();var admin=new Caller(UUID.randomUUID(),"Admin",Set.of("ADMIN"),Map.of());var sport=service.createCategory(new CategoryInput("Search sport "+UUID.randomUUID(),true),admin);String name="Search "+UUID.randomUUID();var f=service.create(input(name),owner);
  var court=service.createCourt(f.id(),new CourtInput("SEARCH","Search court",sport.id(),null,true),owner);
  assertThat(service.search(name,null,sport.id())).isEmpty();jdbc.update("UPDATE facilities SET status='ACTIVE' WHERE id=?",f.id());
  assertThat(service.search(name,"01",sport.id())).extracting(FacilityView::id).containsExactly(f.id());
  assertThat(service.search(name,"99",sport.id())).isEmpty();assertThat(service.search("%",null,null)).isEmpty();
  service.updateCourt(court.id(),new CourtInput("SEARCH","Search court",sport.id(),null,false),owner);assertThat(service.search(name,null,sport.id())).isEmpty();
  service.updateCourt(court.id(),new CourtInput("SEARCH","Search court",sport.id(),null,true),owner);jdbc.update("UPDATE sport_categories SET active=false WHERE id=?",sport.id());assertThat(service.search(name,null,sport.id())).extracting(FacilityView::id).containsExactly(f.id());
 }
 @Test void courtUniquenessMaintenanceAndImageValidationArePersistent(){
  var owner=owner();var facility=service.create(input("Courts "+UUID.randomUUID()),owner);
  var admin=new Caller(UUID.randomUUID(),"Admin",Set.of("ADMIN"),Map.of());
  var category=service.createCategory(new CategoryInput("Sport "+UUID.randomUUID(),true),admin);
  var court=service.createCourt(facility.id(),new CourtInput("C1","Court 1",category.id(),"Indoor",true),owner);
  assertThatThrownBy(()->service.createCourt(facility.id(),new CourtInput("C1","Court 2",category.id(),null,true),owner))
    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  var updated=service.updateCourt(court.id(),new CourtInput("C1","Court 1",category.id(),null,false),owner);
  assertThat(updated.enabled()).isFalse();
  var maintenance=service.addWindow(court.id(),new MaintenanceInput(Instant.now().plusSeconds(3600),Instant.now().plusSeconds(7200),"Repair"),owner);
  assertThat(service.windows(court.id(),owner)).extracting(MaintenanceView::id).contains(maintenance.id());
  service.cancelWindow(maintenance.id(),owner);
  assertThat(service.windows(court.id(),owner)).isEmpty();
  assertThatThrownBy(()->media.upload(facility.id(),court.id(),new MockMultipartFile("file","fake.png","image/png","not an image".getBytes()),owner))
    .isInstanceOf(IllegalArgumentException.class);
 }
 @Test void invalidTimezoneAndRoleAreRejected(){
  var bad=new FacilityInput("Bad","phone","address","01","Hà Nội","00004","Ba Đình",null,"invalid/timezone",null,null,Set.of());
  assertThatThrownBy(()->service.create(bad,owner())).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->service.create(input("Denied"),new Caller(UUID.randomUUID(),"Customer",Set.of("CUSTOMER"),Map.of())))
    .isInstanceOf(ForbiddenException.class);
 }

 private FacilityInput emailInput(String name,String email){return new FacilityInput(name,"+84901234567","Address","01","Hà Nội","00004","Ba Đình","Description","Asia/Ho_Chi_Minh",null,null,Set.of("Parking"),email);}
 @Test void contactEmailCreateUpdateNormalizeClearAndLegacyPutPreserveContact() throws Exception {
  var owner=owner();var f=service.create(emailInput("Contact "+UUID.randomUUID(),"  Local+Contact@EXAMPLE.TEST  "),owner);
  assertThat(f.contactEmail()).isEqualTo("local+contact@example.test");assertThat(service.ownedDetail(f.id(),owner).contactEmail()).isEqualTo(f.contactEmail());
  assertThat(jdbc.queryForObject("SELECT contact_email FROM facilities WHERE id=?",String.class,f.id())).isEqualTo(f.contactEmail());
  assertThat(service.create(input("Old client"),owner).contactEmail()).isNull();
  var legacy=json.readValue(json.writeValueAsString(input("Old update")).replace(",\"contactEmail\":null",""),FacilityInput.class);
  assertThat(legacy.contactEmailProvided()).isFalse();assertThat(service.update(f.id(),legacy,owner).contactEmail()).isEqualTo(f.contactEmail());
  assertThat(service.update(f.id(),emailInput("Updated","second@example.test"),owner).contactEmail()).isEqualTo("second@example.test");
  var blank=json.readValue(json.writeValueAsString(emailInput("Clear","  ")),FacilityInput.class);assertThat(blank.contactEmailProvided()).isTrue();
  assertThat(service.update(f.id(),blank,owner).contactEmail()).isNull();
  assertThat(jdbc.queryForList("SELECT details FROM facility_audit WHERE facility_id=? AND action='FACILITY_UPDATED' ORDER BY occurred_at",String.class,f.id())).contains("{\"changedFields\":[\"contactEmail\"]}").allSatisfy(a->assertThat(a).doesNotContain("@"));
 }
 @Test void contactEmailValidationReturnsFieldErrorAndDoesNotPersistMalformedValue() throws Exception {
  var caller=owner();org.mockito.Mockito.when(identity.current(org.mockito.ArgumentMatchers.any())).thenReturn(caller);
  for(var value:List.of("bad-email","a@@example.test","a b@example.test","x".repeat(255)+"@example.test")){
   var body=json.writeValueAsString(emailInput("Invalid contact",value));
   http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/owner/facilities").contentType("application/json").content(body)).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.fieldErrors[0].field").value("contactEmail"));
  }
  for(var invalid:List.<com.fasterxml.jackson.databind.JsonNode>of(json.valueToTree(123),json.valueToTree(true),json.createObjectNode(),json.createArrayNode().add("valid@example.test"))){
   var body=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(emailInput("Invalid type",null));body.set("contactEmail",invalid);
   http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/owner/facilities").contentType("application/json").content(body.toString())).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.fieldErrors[0].field").value("contactEmail"));
  }
  assertThatThrownBy(()->service.create(emailInput("Invalid","bad-email"),caller)).isInstanceOf(IllegalArgumentException.class);
  assertThat(service.owned(caller)).isEmpty();
  // Do not silently strip local-part characters, Unicode or plus tags.
  var dto=emailInput("Unicode"," ĐỐI.TÁC+Sân@EXAMPLE.TEST ");assertThat(dto.contactEmail()).isEqualTo("đối.tác+sân@example.test");
 }
 @Test void contactEmailCannotBeChangedByForeignOwnerOrUnscopedApplicant(){
  var owner=owner();var f=service.create(emailInput("Scoped","private@example.test"),owner);
  assertThatThrownBy(()->service.update(f.id(),emailInput("Changed","forged@example.test"),owner())).isInstanceOf(ForbiddenException.class);
  assertThatThrownBy(()->service.update(f.id(),emailInput("Changed","forged@example.test"),new Caller(owner.id(),"Applicant",Set.of("CUSTOMER"),Map.of()))).isInstanceOf(ForbiddenException.class);
  assertThat(service.ownedDetail(f.id(),owner).contactEmail()).isEqualTo("private@example.test");
 }
 @Test void contactEmailIsAbsentFromPublicSearchDetailAndSharedCourtContext() throws Exception {
  var owner=owner();var f=service.create(emailInput("Privacy "+UUID.randomUUID(),"hidden@example.test"),owner);
  var category=service.createCategory(new CategoryInput("Privacy "+UUID.randomUUID(),true),new Caller(UUID.randomUUID(),"Admin",Set.of("ADMIN"),Map.of()));var court=service.createCourt(f.id(),new CourtInput("P1","Privacy court",category.id(),null,true),owner);
  jdbc.update("UPDATE facilities SET status='ACTIVE' WHERE id=?",f.id());
  for(var value:List.of(service.publicDetail(f.id()),service.search(f.name()),service.context(court.id(),null),service.context(court.id(),owner)))assertThat(json.writeValueAsString(value)).doesNotContain("contactEmail","hidden@example.test");
  assertThat(json.writeValueAsString(service.ownedDetail(f.id(),owner))).contains("contactEmail","hidden@example.test");
 }

 private FacilityInput locationInput(String name,String latitude,String longitude){return new FacilityInput(name,"+84901234567","Address","01","Hà Nội","00004","Ba Đình","Description","Asia/Ho_Chi_Minh",latitude==null?null:new java.math.BigDecimal(latitude),longitude==null?null:new java.math.BigDecimal(longitude),Set.of("Parking"),"private@example.test");}
 @Test void locationUpdateReloadPreservesOmittedPairAndUsesExistingStoragePrecision() throws Exception {
  var actor=owner();var f=service.create(locationInput("Location","21.028","105.78"),actor);
  var saved=service.update(f.id(),locationInput("Location edited","21.032123456","105.791234567"),actor);
  assertThat(saved.latitude()).isEqualByComparingTo("21.0321235");assertThat(service.ownedDetail(f.id(),actor).longitude()).isEqualByComparingTo("105.7912346");
  var legacy=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(input("Old client"));legacy.remove(List.of("latitude","longitude"));
  var dto=json.treeToValue(legacy,FacilityInput.class);assertThat(dto.latitudeProvided()).isFalse();
  service.update(f.id(),dto,actor);assertThat(service.ownedDetail(f.id(),actor).latitude()).isEqualByComparingTo(saved.latitude());
  assertThat(jdbc.queryForObject("SELECT details FROM facility_audit WHERE facility_id=? AND action='FACILITY_UPDATED' ORDER BY occurred_at LIMIT 1",String.class,f.id())).isEqualTo("{\"changedFields\":[\"location\"]}");
  assertThat(jdbc.queryForObject("SELECT count(*) FROM facility_audit WHERE facility_id=? AND actor_id=? AND occurred_at IS NOT NULL",Integer.class,f.id(),actor.id())).isEqualTo(3);
 }
 @Test void locationZeroBoundariesAndExplicitNullPairAreValid() throws Exception {
  var actor=owner();var f=service.create(locationInput("Zero","0","0"),actor);
  assertThat(service.ownedDetail(f.id(),actor).latitude()).isZero();assertThat(service.ownedDetail(f.id(),actor).longitude()).isZero();
  service.update(f.id(),locationInput("Boundary","-90","180"),actor);assertThat(service.ownedDetail(f.id(),actor).longitude()).isEqualByComparingTo("180");
  service.update(f.id(),locationInput("Boundary","90","-180"),actor);assertThat(service.ownedDetail(f.id(),actor).latitude()).isEqualByComparingTo("90");
  var clear=json.treeToValue(json.valueToTree(locationInput("Clear",null,null)),FacilityInput.class);service.update(f.id(),clear,actor);
  assertThat(service.ownedDetail(f.id(),actor).latitude()).isNull();assertThat(service.ownedDetail(f.id(),actor).longitude()).isNull();
 }
 @Test void locationInvalidRangesMalformedNumbersAndHalfPairsReturn400WithoutSaving() throws Exception {
  var actor=owner();org.mockito.Mockito.when(identity.current(org.mockito.ArgumentMatchers.any())).thenReturn(actor);
  var f=service.create(locationInput("Unchanged","21","105"),actor);
  var invalid=new ArrayList<com.fasterxml.jackson.databind.node.ObjectNode>();
  for(var pair:List.of(new String[]{"90.1","105"},new String[]{"-90.1","105"},new String[]{"21","180.1"},new String[]{"21","-180.1"},new String[]{null,"105"},new String[]{"21",null}))invalid.add(json.valueToTree(locationInput("Invalid",pair[0],pair[1])));
  for(var field:List.of("latitude","longitude")){
   var missing=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(locationInput("Invalid","21","105"));missing.remove(field);invalid.add(missing);
   for(var value:List.<com.fasterxml.jackson.databind.JsonNode>of(json.valueToTree("not-a-number"),json.valueToTree("NaN"),json.valueToTree(true),json.createObjectNode(),json.createArrayNode())){var body=(com.fasterxml.jackson.databind.node.ObjectNode)json.valueToTree(locationInput("Invalid","21","105"));body.set(field,value);invalid.add(body);}
  }
  for(var body:invalid)http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/owner/facilities/"+f.id()).contentType("application/json").content(body.toString())).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
  assertThatThrownBy(()->service.update(f.id(),locationInput("Invalid","91","105"),actor)).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->service.update(f.id(),locationInput("Invalid",null,"105"),actor)).isInstanceOf(IllegalArgumentException.class);
  assertThat(service.ownedDetail(f.id(),actor).name()).isEqualTo("Unchanged");assertThat(service.ownedDetail(f.id(),actor).latitude()).isEqualByComparingTo("21");
 }
 @Test void locationCannotBeChangedByForeignOwnerOrStaffWithoutFacilityEditPermission() throws Exception {
  var actor=owner();var f=service.create(locationInput("Scoped","21","105"),actor);
  var staff=new Caller(actor.id(),"Staff",Set.of("STAFF","CUSTOMER"),Map.of(f.id(),Set.of("BOOKING_READ","SCHEDULE_READ")));
  for(var denied:List.of(owner(),staff,new Caller(actor.id(),"Customer",Set.of("CUSTOMER"),Map.of()))){
   org.mockito.Mockito.when(identity.current(org.mockito.ArgumentMatchers.any())).thenReturn(denied);
   http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/owner/facilities/"+f.id()).contentType("application/json").content(json.writeValueAsString(locationInput("Forged","22","106")))).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
  }
  assertThat(service.ownedDetail(f.id(),actor).latitude()).isEqualByComparingTo("21");
 }
 @Test void locationPublicSearchReflectsOperationalUpdateWithoutPublishingPrivateFacilityOrContact() throws Exception {
  var actor=owner();var admin=new Caller(UUID.randomUUID(),"Admin",Set.of("ADMIN"),Map.of());var f=service.create(locationInput("Public location "+UUID.randomUUID(),"21","105"),actor);
  var category=service.createCategory(new CategoryInput("Location sport "+UUID.randomUUID(),true),admin);var court=service.createCourt(f.id(),new CourtInput("MAP","Location court",category.id(),null,true),actor);
  for(var status:List.of("DRAFT","PENDING_APPROVAL","REJECTED")){
   jdbc.update("UPDATE facilities SET status=? WHERE id=?",status,f.id());assertThat(service.search(f.name())).noneMatch(v->v.id().equals(f.id()));assertThatThrownBy(()->service.publicDetail(f.id())).isInstanceOf(ResourceNotFoundException.class);
  }
  jdbc.update("UPDATE facilities SET status='ACTIVE' WHERE id=?",f.id());service.update(f.id(),locationInput(f.name(),"22","106"),actor);
  assertThat(service.search(f.name())).singleElement().satisfies(v->assertThat(v.latitude()).isEqualByComparingTo("22"));assertThat(service.publicDetail(f.id()).longitude()).isEqualByComparingTo("106");
  for(var value:List.of(service.search(f.name()),service.publicDetail(f.id()),service.context(court.id(),null)))assertThat(json.writeValueAsString(value)).doesNotContain("contactEmail","private@example.test","identityNumber","bankAccountNumber");
 }

 @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
 @Test void rejectedReplacementPreservesExistingMetadataAndEnforcesOwner(){
  var owner=owner();var facility=service.create(input("Replacement "+UUID.randomUUID()),owner);UUID id=UUID.randomUUID();String key="test/"+id+".png";
  jdbc.update("INSERT INTO facility_images(id,facility_id,object_key,content_type,size_bytes) VALUES(?,?,?,'image/png',100)",id,facility.id(),key);
  var invalid=new MockMultipartFile("file","fake.png","image/png","invalid".getBytes());
  assertThatThrownBy(()->media.replace(facility.id(),id,invalid,owner())).isInstanceOf(ForbiddenException.class);
  assertThatThrownBy(()->media.replace(facility.id(),id,invalid,owner)).isInstanceOf(IllegalArgumentException.class);
  assertThat(jdbc.queryForObject("SELECT object_key FROM facility_images WHERE id=?",String.class,id)).isEqualTo(key);
 }

 @Test void roleAndCrossFacilityGuardsRejectCustomerStaffForeignOwnerAndNonAdmin() throws Exception {
  var owner=owner();var facility=service.create(input("Security facility"),owner);
  for(String role:List.of("CUSTOMER","STAFF","ADMIN")) {
   var actor=new Caller(owner.id(),role,Set.of(role),Map.of(facility.id(),Set.of("BOOKING_READ","SCHEDULE_READ")));
   assertThatThrownBy(()->service.ownedDetail(facility.id(),actor)).isInstanceOf(ForbiddenException.class);
   assertThatThrownBy(()->service.update(facility.id(),input("Forbidden change"),actor)).isInstanceOf(ForbiddenException.class);
  }
  assertThatThrownBy(()->service.ownedDetail(facility.id(),owner())).isInstanceOf(ForbiddenException.class);
  for(String role:List.of("CUSTOMER","OWNER","STAFF")) {
   var actor=new Caller(UUID.randomUUID(),role,Set.of(role),Map.of());
   assertThatThrownBy(()->service.createCategory(new CategoryInput("Forbidden "+UUID.randomUUID(),true),actor)).isInstanceOf(ForbiddenException.class);
  }
  org.mockito.Mockito.when(identity.current(org.mockito.ArgumentMatchers.any())).thenThrow(new UnauthorizedException("Authentication required"));
  http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/owner/facilities/"+facility.id()).header("X-User-Role","OWNER"))
   .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
  http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/actuator/env")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
 }


 private com.sporthub.common.schedule.ConfigurationGuardProtocol.Reservation impact(UUID court,Instant start,Instant end,String state){return new com.sporthub.common.schedule.ConfigurationGuardProtocol.Reservation(UUID.randomUUID(),UUID.randomUUID(),court,start.truncatedTo(java.time.temporal.ChronoUnit.MICROS),end.truncatedTo(java.time.temporal.ChronoUnit.MICROS),state);}
 private CourtView maintenanceCourt(Caller actor){var f=service.create(input("Maintenance "+UUID.randomUUID()),actor);return service.createCourt(f.id(),new CourtInput("C1","Court",fixtureCategory,null,true),actor);}
 @Test void maintenanceRejectsBookedAndHeldOverlapWithoutChangingHistoryOrAudit(){
  var actor=owner();var court=maintenanceCourt(actor);var start=Instant.now().plusSeconds(7200);var end=start.plusSeconds(3600);
  for(var state:List.of("BOOKED","HOLD")){
   org.mockito.Mockito.doAnswer(a->new com.sporthub.common.schedule.ConfigurationGuardProtocol.Prepared(a.<com.sporthub.common.schedule.ConfigurationGuardProtocol.Command>getArgument(0).operationId(),List.of(impact(court.id(),start,end,state)))).when(guard).prepare(org.mockito.ArgumentMatchers.any());
   assertThatThrownBy(()->service.addWindow(court.id(),new MaintenanceInput(start,end,"Repair"),actor)).isInstanceOf(ConfigurationImpactException.class);
  }
  assertThat(service.windows(court.id(),actor)).isEmpty();
  assertThat(jdbc.queryForObject("SELECT count(*) FROM facility_audit WHERE facility_id=? AND action='MAINTENANCE_CREATED'",Integer.class,court.facilityId())).isZero();
 }
 @Test void adjacentMaintenanceAndOtherCourtReservationsAreAllowedButOverlappingWindowsAreRejected(){
  var actor=owner();var court=maintenanceCourt(actor);var start=Instant.now().plusSeconds(7200);var end=start.plusSeconds(3600);
  org.mockito.Mockito.doAnswer(a->new com.sporthub.common.schedule.ConfigurationGuardProtocol.Prepared(a.<com.sporthub.common.schedule.ConfigurationGuardProtocol.Command>getArgument(0).operationId(),List.of(impact(court.id(),start.minusSeconds(3600),start,"BOOKED"),impact(UUID.randomUUID(),start,end,"HOLD")))).when(guard).prepare(org.mockito.ArgumentMatchers.any());
  service.addWindow(court.id(),new MaintenanceInput(start,end,"Repair"),actor);
  assertThatThrownBy(()->service.addWindow(court.id(),new MaintenanceInput(start.plusSeconds(10),end,"Overlap"),actor)).isInstanceOf(ConflictException.class);
  service.addWindow(court.id(),new MaintenanceInput(end,end.plusSeconds(3600),"Adjacent"),actor);
  assertThat(service.windows(court.id(),actor)).hasSize(2);
 }
 @Test void maintenanceFailureAndPermissionChecksLeaveConfigurationUntouched(){
  var actor=owner();var court=maintenanceCourt(actor);var start=Instant.now().plusSeconds(7200);var end=start.plusSeconds(3600);
  org.mockito.Mockito.doThrow(new IllegalStateException("Booking down")).when(guard).prepare(org.mockito.ArgumentMatchers.any());
  assertThatThrownBy(()->service.addWindow(court.id(),new MaintenanceInput(start,end,"Repair"),actor)).isInstanceOf(IllegalStateException.class);
  assertThat(service.windows(court.id(),actor)).isEmpty();
  var staff=new Caller(UUID.randomUUID(),"Staff",Set.of("STAFF"),Map.of(court.facilityId(),Set.of("SCHEDULE_READ","BOOKING_READ")));
  assertThatThrownBy(()->service.addWindow(court.id(),new MaintenanceInput(start,end,"Repair"),staff)).isInstanceOf(ForbiddenException.class);
  assertThatThrownBy(()->service.addWindow(court.id(),new MaintenanceInput(start,end,"Repair"),owner())).isInstanceOf(ForbiddenException.class);
  assertThatThrownBy(()->service.addWindow(court.id(),new MaintenanceInput(Instant.now().minusSeconds(3600),end,"Past"),actor)).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void ownerImpactWarningHasReservationTimesButNoPersonalOrPaymentData()throws Exception{
  var actor=owner();var court=maintenanceCourt(actor);var start=Instant.now().plusSeconds(7200);var end=start.plusSeconds(3600);
  org.mockito.Mockito.when(identity.current(org.mockito.ArgumentMatchers.any())).thenReturn(actor);
  org.mockito.Mockito.doAnswer(a->new com.sporthub.common.schedule.ConfigurationGuardProtocol.Prepared(a.<com.sporthub.common.schedule.ConfigurationGuardProtocol.Command>getArgument(0).operationId(),List.of(impact(court.id(),start,end,"BOOKED")))).when(guard).prepare(org.mockito.ArgumentMatchers.any());
  var response=http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/owner/courts/"+court.id()+"/maintenance").contentType("application/json").content(json.writeValueAsString(new MaintenanceInput(start,end,"Repair"))))
   .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict()).andReturn();
  var body=response.getResponse().getContentAsString();assertThat(body).contains("CONFIGURATION_IMPACT","affectedReservations","BOOKED").doesNotContain("holderId","guestPhone","paymentId","checkinToken");
 }

 @Test void concurrentMaintenanceWritersCannotCreateOverlappingActiveWindows()throws Exception{
  var actor=owner();var court=maintenanceCourt(actor);var start=Instant.now().plusSeconds(7200);var end=start.plusSeconds(3600);
  var ready=new java.util.concurrent.CountDownLatch(2);var go=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
  try{
   var futures=new ArrayList<java.util.concurrent.Future<Boolean>>();
   for(int n=0;n<2;n++)futures.add(pool.submit(()->{ready.countDown();go.await();try{service.addWindow(court.id(),new MaintenanceInput(start,end,"Concurrent repair"),actor);return true;}catch(ConflictException conflict){return false;}}));
   assertThat(ready.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();go.countDown();int winners=0;for(var future:futures)if(future.get(10,java.util.concurrent.TimeUnit.SECONDS))winners++;
   assertThat(winners).isEqualTo(1);assertThat(service.windows(court.id(),actor)).hasSize(1);
  }finally{pool.shutdownNow();}
 }

 @Test void independentCourtTypesAreFacilityScopedAndLegacyCourtPayloadPreservesAssignment()throws Exception{
  var actor=owner();var f=service.create(input("Typed venue"),actor);var type=service.createCourtType(f.id(),new CourtTypeInput("Trong nhà",true),actor);var category=service.categories().stream().filter(c->c.id().equals(fixtureCategory)).findFirst().orElseThrow();
  var c=service.createCourt(f.id(),new CourtInput("TYPE1","Court",category.id(),null,true,type.id()),actor);assertThat(c.courtTypeId()).isEqualTo(type.id());assertThat(c.sportCategoryId()).isEqualTo(category.id());
  var oldBody=json.valueToTree(new CourtInput("TYPE1","Renamed",category.id(),null,true));((com.fasterxml.jackson.databind.node.ObjectNode)oldBody).remove("courtTypeId");var oldInput=json.treeToValue(oldBody,CourtInput.class);assertThat(oldInput.courtTypeProvided()).isFalse();
  assertThat(service.updateCourt(c.id(),oldInput,actor).courtTypeId()).isEqualTo(type.id());
  ((com.fasterxml.jackson.databind.node.ObjectNode)oldBody).putNull("courtTypeId");assertThat(service.updateCourt(c.id(),json.treeToValue(oldBody,CourtInput.class),actor).courtTypeId()).isNull();
 }
 @Test void courtTypeCannotBeAssignedAcrossFacilitiesOrWhenInactiveAndDatabaseEnforcesBinding(){
  var actor=owner();var first=service.create(input("First types"),actor);var second=service.create(input("Second types"),actor);var type=service.createCourtType(first.id(),new CourtTypeInput("Outdoor",true),actor);var category=service.categories().stream().filter(c->c.id().equals(fixtureCategory)).findFirst().orElseThrow();
  assertThatThrownBy(()->service.createCourt(second.id(),new CourtInput("WRONG","Wrong",category.id(),null,true,type.id()),actor)).isInstanceOf(IllegalArgumentException.class);
  var court=service.createCourt(second.id(),new CourtInput("C1","Court",category.id(),null,true),actor);assertThatThrownBy(()->jdbc.update("UPDATE courts SET court_type_id=? WHERE id=?",type.id(),court.id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  service.updateCourtType(first.id(),type.id(),new CourtTypeInput("Outdoor",false),actor);assertThatThrownBy(()->service.createCourt(first.id(),new CourtInput("OFF","Off",category.id(),null,true,type.id()),actor)).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void courtTypeLifecycleKeepsExistingCourtAndRejectsForeignOwnerAndStaff(){
  var actor=owner();var f=service.create(input("Types lifecycle"),actor);var type=service.createCourtType(f.id(),new CourtTypeInput("Standard",true),actor);var category=service.categories().stream().filter(c->c.id().equals(fixtureCategory)).findFirst().orElseThrow();var court=service.createCourt(f.id(),new CourtInput("OLD","Old",category.id(),null,true,type.id()),actor);
  assertThat(service.updateCourtType(f.id(),type.id(),new CourtTypeInput("Archived standard",false),actor).version()).isEqualTo(1);assertThat(service.ownedCourt(court.id(),actor).courtTypeId()).isEqualTo(type.id());
  assertThatThrownBy(()->service.createCourtType(f.id(),new CourtTypeInput("Foreign",true),owner())).isInstanceOf(ForbiddenException.class);
  var staff=new Caller(UUID.randomUUID(),"Staff",Set.of("STAFF"),Map.of(f.id(),Set.of("SCHEDULE_READ")));assertThatThrownBy(()->service.createCourtType(f.id(),new CourtTypeInput("Staff type",true),staff)).isInstanceOf(ForbiddenException.class);
  assertThatThrownBy(()->service.updateCourtType(f.id(),type.id(),new CourtTypeInput("Staff edit",true),staff)).isInstanceOf(ForbiddenException.class);
 }
 @Test void staffOperationalPermissionsAreScopedAndNeverGrantOwnerConfiguration(){
  var owner=owner();var f=service.create(input("Staff operations"),owner);jdbc.update("UPDATE facilities SET status='ACTIVE' WHERE id=?",f.id());var c=service.createCourt(f.id(),new CourtInput("S1","Staff Court",fixtureCategory,null,true),owner);
  var staff=new Caller(UUID.randomUUID(),"Staff",Set.of("STAFF"),Map.of(f.id(),Set.of("COURT_STATUS_MANAGE","COURT_MAINTENANCE_MANAGE","SCHEDULE_MANAGE")));
  assertThat(service.courtStatus(c.id(),false,staff).enabled()).isFalse();assertThat(service.courtStatus(c.id(),true,staff).enabled()).isTrue();assertThat(service.context(c.id(),staff).court().id()).isEqualTo(c.id());
  var start=Instant.now().plusSeconds(3600);var window=service.addWindow(c.id(),new MaintenanceInput(start,start.plusSeconds(3600),"Routine maintenance"),staff);service.cancelWindow(window.id(),staff);assertThat(service.windows(c.id(),staff)).isEmpty();assertThat(service.operationAccess(f.id(),"SCHEDULE_MANAGE",staff).id()).isEqualTo(f.id());
  assertThatThrownBy(()->service.update(f.id(),input("Forbidden edit"),staff)).isInstanceOf(ForbiddenException.class);assertThatThrownBy(()->service.updateCourt(c.id(),new CourtInput("S1","Forbidden rename",fixtureCategory,null,true),staff)).isInstanceOf(ForbiddenException.class);assertThatThrownBy(()->service.ownedDetail(f.id(),staff)).isInstanceOf(ForbiddenException.class);
  var foreign=new Caller(staff.id(),"Staff",Set.of("STAFF"),Map.of(UUID.randomUUID(),Set.of("SCHEDULE_MANAGE","COURT_STATUS_MANAGE","COURT_MAINTENANCE_MANAGE")));assertThatThrownBy(()->service.courtStatus(c.id(),false,foreign)).isInstanceOf(ForbiddenException.class);assertThatThrownBy(()->service.addWindow(c.id(),new MaintenanceInput(start,start.plusSeconds(3600),"Wrong facility"),foreign)).isInstanceOf(ForbiddenException.class);
 }
 @Test void staffReadPermissionCannotWriteAndOperationBlockedAtInactiveFacility(){
  var owner=owner();var f=service.create(input("Read permissions"),owner);jdbc.update("UPDATE facilities SET status='ACTIVE' WHERE id=?",f.id());var c=service.createCourt(f.id(),new CourtInput("R1","Read Court",fixtureCategory,null,true),owner);var staff=new Caller(UUID.randomUUID(),"Staff",Set.of("STAFF"),Map.of(f.id(),Set.of("SCHEDULE_READ")));
  assertThat(service.ownedCourts(f.id(),staff)).hasSize(1);assertThatThrownBy(()->service.courtStatus(c.id(),false,staff)).isInstanceOf(ForbiddenException.class);var start=Instant.now().plusSeconds(3600);assertThatThrownBy(()->service.addWindow(c.id(),new MaintenanceInput(start,start.plusSeconds(3600),"Read only"),staff)).isInstanceOf(ForbiddenException.class);
  jdbc.update("UPDATE facilities SET status='SUSPENDED' WHERE id=?",f.id());var writer=new Caller(staff.id(),"Staff",Set.of("STAFF"),Map.of(f.id(),Set.of("COURT_STATUS_MANAGE")));assertThatThrownBy(()->service.courtStatus(c.id(),false,writer)).isInstanceOf(ResourceNotFoundException.class);
 }
 @Test void staffStatusAndMaintenancePreserveExistingBookingImpactGuard(){
  var owner=owner();var f=service.create(input("Protected bookings"),owner);jdbc.update("UPDATE facilities SET status='ACTIVE' WHERE id=?",f.id());var c=service.createCourt(f.id(),new CourtInput("G1","Guard Court",fixtureCategory,null,true),owner);var staff=new Caller(UUID.randomUUID(),"Staff",Set.of("STAFF"),Map.of(f.id(),Set.of("COURT_STATUS_MANAGE","COURT_MAINTENANCE_MANAGE")));
  var start=Instant.now().plusSeconds(3600);var affected=impact(c.id(),start,start.plusSeconds(3600),"BOOKED");
  org.mockito.Mockito.doAnswer(a->new com.sporthub.common.schedule.ConfigurationGuardProtocol.Prepared(a.<com.sporthub.common.schedule.ConfigurationGuardProtocol.Command>getArgument(0).operationId(),List.of(affected))).when(guard).prepare(org.mockito.ArgumentMatchers.any());
  assertThatThrownBy(()->service.courtStatus(c.id(),false,staff)).isInstanceOf(ConfigurationImpactException.class);assertThat(service.ownedCourt(c.id(),owner).enabled()).isTrue();assertThatThrownBy(()->service.addWindow(c.id(),new MaintenanceInput(start,start.plusSeconds(3600),"Blocked maintenance"),staff)).isInstanceOf(ConfigurationImpactException.class);assertThat(service.windows(c.id(),owner)).isEmpty();
 }


 @Autowired SportCategoryManagement categoryManagement;
 @Autowired FacilityStatusService statusService;
 private Caller administrator(){return new Caller(UUID.randomUUID(),"Admin",Set.of("ADMIN"),Map.of());}
 @Test void categoryManagementSupportsMetadataStatusHistoryAndExistingCourts(){
  var admin=administrator();var owner=owner();var f=service.create(input("Inactive legacy "+UUID.randomUUID()),owner);var category=categoryManagement.create(new CategoryInput("Category "+UUID.randomUUID(),true,"Description","tennis"),admin);
  var court=service.createCourt(f.id(),new CourtInput("C1","Court",category.id(),null,true),owner);jdbc.update("UPDATE facilities SET status='ACTIVE' WHERE id=?",f.id());
  var changed=categoryManagement.update(category.id(),new CategoryUpdate(category.name()+" updated",true,"New description","football",0L),admin);assertThat(changed.description()).isEqualTo("New description");assertThat(changed.version()).isEqualTo(1);
  categoryManagement.status(category.id(),new CategoryStatus(false,1L),admin);assertThat(service.publicCourts(f.id())).hasSize(1);assertThat(service.search(f.name(),null,category.id())).hasSize(1);assertThat(service.context(court.id(),null).court().enabled()).isTrue();
  assertThatThrownBy(()->service.createCourt(f.id(),new CourtInput("C2","New",category.id(),null,true),owner)).isInstanceOf(IllegalArgumentException.class);
  assertThatCode(()->service.updateCourt(court.id(),new CourtInput("C1","Renamed existing",category.id(),null,true),owner)).doesNotThrowAnyException();
  assertThat(categoryManagement.history(category.id(),admin)).hasSize(3);assertThat(categoryManagement.administration(admin).stream().filter(r->category.id().equals(r.get("id"))).findFirst().orElseThrow().get("court_count")).isEqualTo(1L);
  assertThatThrownBy(()->jdbc.update("DELETE FROM sport_categories WHERE id=?",category.id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  assertThatThrownBy(()->jdbc.update("DELETE FROM sport_category_audit WHERE category_id=?",category.id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
  assertThat(categoryManagement.status(category.id(),new CategoryStatus(true,2L),admin).active()).isTrue();
 }
 @Test void categoriesRejectCanonicalDuplicatesAndUnauthorizedOrStaleChanges(){
  var admin=administrator();String unique="Sport "+UUID.randomUUID();var category=categoryManagement.create(new CategoryInput(unique,true),admin);
  assertThatThrownBy(()->categoryManagement.create(new CategoryInput("  "+unique.toUpperCase(Locale.ROOT).replace(" ","   ")+"  ",true),admin)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  for(var actor:List.of(owner(),new Caller(UUID.randomUUID(),"Staff",Set.of("STAFF"),Map.of()),new Caller(UUID.randomUUID(),"Customer",Set.of("CUSTOMER"),Map.of())))assertThatThrownBy(()->categoryManagement.status(category.id(),new CategoryStatus(false,0L),actor)).isInstanceOf(ForbiddenException.class);
  assertThatThrownBy(()->categoryManagement.update(category.id(),new CategoryUpdate("Valid",true,null,null,99L),admin)).isInstanceOf(ConflictException.class);
  assertThatThrownBy(()->categoryManagement.update(category.id(),new CategoryUpdate("Valid",true,null,"<svg/>",0L),admin)).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void concurrentCategoryUpdatesHaveOneWinner()throws Exception{
  var admin=administrator();var c=categoryManagement.create(new CategoryInput("Concurrent "+UUID.randomUUID(),true),admin);var go=new java.util.concurrent.CountDownLatch(1);var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
  try{var futures=new ArrayList<java.util.concurrent.Future<Boolean>>();for(int i=0;i<2;i++){int suffix=i;futures.add(pool.submit(()->{go.await();try{categoryManagement.update(c.id(),new CategoryUpdate(c.name()+suffix,true,null,null,0L),admin);return true;}catch(ConflictException ex){return false;}}));}go.countDown();int winners=0;for(var future:futures)if(future.get(15,java.util.concurrent.TimeUnit.SECONDS))winners++;assertThat(winners).isEqualTo(1);assertThat(categoryManagement.find(c.id()).version()).isEqualTo(1);
  }finally{pool.shutdownNow();}
 }
 private FacilityProfileView activeFacility(Caller owner){var f=service.create(input("Status "+UUID.randomUUID()),owner);service.createCourt(f.id(),new CourtInput("STATUS","Court",fixtureCategory,null,true),owner);jdbc.update("UPDATE facilities SET status='ACTIVE' WHERE id=?",f.id());return service.ownedDetail(f.id(),owner);}
 private FacilityStatusService.Preview preview(UUID id,Caller admin,List<com.sporthub.common.schedule.ConfigurationGuardProtocol.Reservation> rows){org.mockito.Mockito.doReturn(rows).when(guard).impact(id);org.mockito.Mockito.doAnswer(a->new com.sporthub.common.schedule.ConfigurationGuardProtocol.Prepared(a.<com.sporthub.common.schedule.ConfigurationGuardProtocol.Command>getArgument(0).operationId(),rows)).when(guard).prepare(org.mockito.ArgumentMatchers.any());return statusService.preview(id,admin);}
 private FacilityStatusService.Decision decision(String action,FacilityStatusService.Preview p){return new FacilityStatusService.Decision(action,"Verified facility safety violation decision",p.version(),p.impactFingerprint());}
 @Test void suspensionPreservesImpactBlocksPublicationAndRestoresWithHistory(){
  var owner=owner();var admin=administrator();var f=activeFacility(owner);var court=service.ownedCourts(f.id(),owner).getFirst();var row=new com.sporthub.common.schedule.ConfigurationGuardProtocol.Reservation(UUID.randomUUID(),UUID.randomUUID(),court.id(),Instant.now().plusSeconds(3600),Instant.now().plusSeconds(7200),"BOOKED");
  var p=preview(f.id(),admin,List.of(row));assertThat(statusService.history(f.id(),admin)).isEmpty();assertThat(service.publicDetail(f.id()).status()).isEqualTo("ACTIVE");
  assertThat(statusService.decide(f.id(),decision("SUSPEND",p),admin).status()).isEqualTo("SUSPENDED");assertThatThrownBy(()->service.publicDetail(f.id())).isInstanceOf(ResourceNotFoundException.class);assertThatThrownBy(()->service.context(court.id(),null)).isInstanceOf(ResourceNotFoundException.class);assertThat(service.search(f.name())).isEmpty();
  assertThat(statusService.history(f.id(),owner)).singleElement().satisfies(r->assertThat(r.get("impact").toString()).contains(row.bookingId().toString()));assertThat(service.ownedCourts(f.id(),owner).getFirst().enabled()).isTrue();
  assertThat(statusService.decide(f.id(),decision("RESTORE",preview(f.id(),admin,List.of(row))),admin).status()).isEqualTo("ACTIVE");assertThat(statusService.history(f.id(),admin)).hasSize(2);
  assertThat(jdbc.queryForObject("SELECT count(*) FROM event_outbox WHERE body->>'eventType'='facility.status.changed' AND body->>'aggregateId'=?",Integer.class,f.id().toString())).isEqualTo(2);
  assertThatThrownBy(()->jdbc.update("DELETE FROM facility_status_decisions WHERE facility_id=?",f.id())).isInstanceOf(org.springframework.dao.DataAccessException.class);
 }
 @Test void statusDecisionsRejectChangedImpactVersionAndDependenciesWithoutPartialMutation(){
  var owner=owner();var admin=administrator();var f=activeFacility(owner);var p=preview(f.id(),admin,List.of());
  var row=new com.sporthub.common.schedule.ConfigurationGuardProtocol.Reservation(UUID.randomUUID(),null,UUID.randomUUID(),Instant.now().plusSeconds(3600),Instant.now().plusSeconds(7200),"HOLD");preview(f.id(),admin,List.of(row));
  assertThatThrownBy(()->statusService.decide(f.id(),decision("SUSPEND",p),admin)).isInstanceOf(ConflictException.class);assertThat(statusService.history(f.id(),admin)).isEmpty();assertThat(service.publicDetail(f.id()).status()).isEqualTo("ACTIVE");
  org.mockito.Mockito.doThrow(new IllegalStateException("Booking unavailable")).when(guard).prepare(org.mockito.ArgumentMatchers.any());assertThatThrownBy(()->statusService.decide(f.id(),decision("SUSPEND",p),admin)).isInstanceOf(IllegalStateException.class);assertThat(service.publicDetail(f.id()).status()).isEqualTo("ACTIVE");
  service.update(f.id(),input("Changed status profile"),owner);assertThatThrownBy(()->statusService.decide(f.id(),decision("SUSPEND",p),admin)).isInstanceOf(ConflictException.class);
 }
 @Test void suspensionIsAdminOnlyAndCannotPublishUncommittedFirstFacility(){
  var owner=owner();var admin=administrator();var f=activeFacility(owner);var p=preview(f.id(),admin,List.of());
  for(var actor:List.of(owner,new Caller(UUID.randomUUID(),"Staff",Set.of("STAFF"),Map.of(f.id(),Set.of("COURT_STATUS_MANAGE"))),new Caller(UUID.randomUUID(),"Customer",Set.of("CUSTOMER"),Map.of()))){assertThatThrownBy(()->statusService.preview(f.id(),actor)).isInstanceOf(ForbiddenException.class);assertThatThrownBy(()->statusService.decide(f.id(),decision("SUSPEND",p),actor)).isInstanceOf(ForbiddenException.class);}
  assertThatThrownBy(()->statusService.history(f.id(),owner())).isInstanceOf(ForbiddenException.class);
  UUID app=UUID.randomUUID(),first=UUID.randomUUID();service.createFirst(app,first,owner.id(),input("Uncommitted"));jdbc.update("UPDATE facilities SET status='ACTIVE' WHERE id=?",first);var pending=preview(first,admin,List.of());assertThatThrownBy(()->statusService.decide(first,decision("SUSPEND",pending),admin)).isInstanceOf(ConflictException.class);
 }
 @Test void concurrentSuspensionHasOneDecision()throws Exception{
  var owner=owner();var admin=administrator();var f=activeFacility(owner);var p=preview(f.id(),admin,List.of());var pool=java.util.concurrent.Executors.newFixedThreadPool(2);var go=new java.util.concurrent.CountDownLatch(1);
  try{var futures=new ArrayList<java.util.concurrent.Future<Boolean>>();for(int i=0;i<2;i++)futures.add(pool.submit(()->{go.await();try{statusService.decide(f.id(),decision("SUSPEND",p),admin);return true;}catch(ConflictException ex){return false;}}));go.countDown();int winners=0;for(var future:futures)if(future.get(15,java.util.concurrent.TimeUnit.SECONDS))winners++;assertThat(winners).isEqualTo(1);assertThat(statusService.history(f.id(),admin)).hasSize(1);
  }finally{pool.shutdownNow();}
 }
 @Test void legacyCourtPutCannotBypassReservationImpact(){var owner=owner();var f=activeFacility(owner);var court=service.ownedCourts(f.id(),owner).getFirst();preview(f.id(),administrator(),List.of(new com.sporthub.common.schedule.ConfigurationGuardProtocol.Reservation(UUID.randomUUID(),UUID.randomUUID(),court.id(),Instant.now().plusSeconds(3600),Instant.now().plusSeconds(7200),"BOOKED")));assertThatThrownBy(()->service.updateCourt(court.id(),new CourtInput(court.code(),court.name(),court.sportCategoryId(),null,false),owner)).isInstanceOf(ConfigurationImpactException.class);assertThat(service.ownedCourt(court.id(),owner).enabled()).isTrue();}


 @Test void reviewChecksAdminBeforeLinkedApplicationAndValidatesReason(){var owner=owner();var application=UUID.randomUUID();var first=UUID.randomUUID();service.createFirst(application,first,owner.id(),input("Review role guard"));assertThatThrownBy(()->reviews.decide(first,"APPROVE","The applicant cannot approve their facility",owner)).isInstanceOf(ForbiddenException.class);var f=service.create(input("Reason validation"),owner);assertThatThrownBy(()->reviews.decide(f.id(),"APPROVE",null,administrator())).isInstanceOf(IllegalArgumentException.class);}

 @Autowired FacilityFavorites favorites;
 @Test void favoritesArePrivateIdempotentAndUseOnlyPublicFacilityProjection(){
  var f=activeFacility(owner());var a=new Caller(UUID.randomUUID(),"Customer",Set.of("CUSTOMER"),Map.of());var b=new Caller(UUID.randomUUID(),"Other",Set.of("CUSTOMER"),Map.of());
  var saved=favorites.save(f.id(),a);assertThat(saved.favorite()).isTrue();assertThat(favorites.save(f.id(),a).addedAt()).isEqualTo(saved.addedAt());assertThat(favorites.list(0,10,a).total()).isEqualTo(1);assertThat(favorites.list(0,10,b).items()).isEmpty();favorites.remove(f.id(),b);assertThat(favorites.state(f.id(),a).favorite()).isTrue();assertThat(json.valueToTree(saved).toString()).doesNotContain("ownerId","contactEmail","documents");favorites.remove(f.id(),a);favorites.remove(f.id(),a);assertThat(favorites.list(0,10,a).total()).isZero();
 }
 @Test void unavailableFavoritesRemainSavedWithoutExposingPrivateFacility(){var owner=owner();var a=new Caller(UUID.randomUUID(),"Customer",Set.of("CUSTOMER"),Map.of());var draft=service.create(input("Private favorite"),owner);assertThatThrownBy(()->favorites.save(draft.id(),a)).isInstanceOf(ResourceNotFoundException.class);var f=activeFacility(owner);favorites.save(f.id(),a);jdbc.update("UPDATE facilities SET status='SUSPENDED' WHERE id=?",f.id());var saved=favorites.list(0,10,a).items().getFirst();assertThat(saved.favorite()).isTrue();assertThat(saved.available()).isFalse();assertThat(saved.facility()).isNull();favorites.remove(f.id(),a);assertThat(favorites.list(0,10,a).total()).isZero();}
 @Test void concurrentFavoriteSaveCreatesOneRow()throws Exception{var f=activeFacility(owner());var a=new Caller(UUID.randomUUID(),"Customer",Set.of("CUSTOMER"),Map.of());var go=new java.util.concurrent.CountDownLatch(1);try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){var x=pool.submit(()->{go.await();return favorites.save(f.id(),a);});var y=pool.submit(()->{go.await();return favorites.save(f.id(),a);});go.countDown();assertThat(x.get().favorite()).isTrue();assertThat(y.get().favorite()).isTrue();}assertThat(favorites.list(0,10,a).total()).isEqualTo(1);}
 @Test void favoriteHttpCannotSelectAnotherUsersIdentityAndRequiresAuthentication()throws Exception{var f=activeFacility(owner());var a=new Caller(UUID.randomUUID(),"Customer",Set.of("CUSTOMER"),Map.of());org.mockito.Mockito.when(identity.current(org.mockito.ArgumentMatchers.any())).thenThrow(new ForbiddenException("Authentication required"));http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/facilities/"+f.id()+"/favorite")).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());org.mockito.Mockito.doReturn(a).when(identity).current(org.mockito.ArgumentMatchers.any());http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/facilities/"+f.id()+"/favorite").param("userId",UUID.randomUUID().toString())).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());assertThat(favorites.list(0,10,a).total()).isEqualTo(1);assertThatThrownBy(()->favorites.list(-1,10,a)).isInstanceOf(IllegalArgumentException.class);}
 @Test void optionalDisabledCourtProjectionPreservesDefaultSearch(){var owner=owner();var f=activeFacility(owner);service.createCourt(f.id(),new CourtInput("OFF","Unavailable court",fixtureCategory,null,false),owner);assertThat(service.publicCourts(f.id())).hasSize(1);assertThat(service.publicCourts(f.id(),true)).hasSize(2);assertThat(service.publicCourts(f.id(),true).stream().filter(c->!c.enabled())).hasSize(1);}

}
