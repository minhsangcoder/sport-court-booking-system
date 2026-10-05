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
 "sporthub.media.access-key=test-access","sporthub.media.secret-key=test-secret","sporthub.media.endpoint=http://localhost:19000"})
class FacilityIntegrationTest {
 static PostgreSQLContainer<?> postgres;
 @DynamicPropertySource static void database(DynamicPropertyRegistry props){
  String external=System.getenv("SPORTHUB_FACILITY_TEST_DB_URL");
  if(external!=null){props.add("spring.datasource.url",()->external);props.add("spring.datasource.username",()->System.getenv("SPORTHUB_TEST_DB_USER"));props.add("spring.datasource.password",()->System.getenv("SPORTHUB_TEST_DB_PASSWORD"));}
  else{postgres=new PostgreSQLContainer<>("postgres:16-alpine");postgres.start();props.add("spring.datasource.url",postgres::getJdbcUrl);props.add("spring.datasource.username",postgres::getUsername);props.add("spring.datasource.password",postgres::getPassword);}
 }
 @AfterAll static void stop(){if(postgres!=null)postgres.stop();}
 @Autowired FacilityService service;
 @Autowired MediaService media;
 @MockBean RemoteIdentity identity;
 @Autowired com.sporthub.facility.web.TransferPolicyController policies;
 private Caller owner(){return new Caller(UUID.randomUUID(),"Owner",Set.of("OWNER","CUSTOMER"),Map.of());}
 private FacilityInput input(String name){return new FacilityInput(name,"+84901234567","Address","Province","District","Ward","Description","Asia/Ho_Chi_Minh",null,null,Set.of("Parking"));}

 @Test void transferPolicyRequiresOwnerAndHasNoImplicitDefault(){
  var owner=owner();var facility=service.create(input("Policy "+UUID.randomUUID()),owner);var request=new org.springframework.mock.web.MockHttpServletRequest();org.mockito.Mockito.when(identity.current(request)).thenReturn(owner);
  assertThat(policies.ownPolicy(facility.id(),request).getData().configured()).isFalse();var result=policies.update(facility.id(),new com.sporthub.facility.web.TransferPolicyController.PolicyInput(true,3600),request).getData();assertThat(result.configured()).isTrue();assertThat(result.minLeadSeconds()).isEqualTo(3600);
  org.mockito.Mockito.when(identity.current(request)).thenReturn(owner());assertThatThrownBy(()->policies.update(facility.id(),new com.sporthub.facility.web.TransferPolicyController.PolicyInput(false,0),request)).isInstanceOf(ForbiddenException.class);
 }
 @Test void facilityCrudRemainsDraftAndOwnerScoped(){
  var owner=owner();var facility=service.create(input("Facility "+UUID.randomUUID()),owner);
  assertThat(facility.status()).isEqualTo("DRAFT");
  assertThat(service.owned(owner)).extracting(FacilityView::id).contains(facility.id());
  assertThatThrownBy(()->service.publicDetail(facility.id())).isInstanceOf(ResourceNotFoundException.class);
  assertThatThrownBy(()->service.ownedDetail(facility.id(),owner())).isInstanceOf(ForbiddenException.class);
  assertThat(service.update(facility.id(),input("Updated Facility"),owner).name()).isEqualTo("Updated Facility");
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
  var bad=new FacilityInput("Bad","phone","address","p","d","w",null,"invalid/timezone",null,null,Set.of());
  assertThatThrownBy(()->service.create(bad,owner())).isInstanceOf(IllegalArgumentException.class);
  assertThatThrownBy(()->service.create(input("Denied"),new Caller(UUID.randomUUID(),"Customer",Set.of("CUSTOMER"),Map.of())))
    .isInstanceOf(ForbiddenException.class);
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
}
