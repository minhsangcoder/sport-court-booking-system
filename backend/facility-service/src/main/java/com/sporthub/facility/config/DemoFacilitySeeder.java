package com.sporthub.facility.config;
import com.sporthub.facility.domain.*;
import com.sporthub.facility.repository.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import java.util.*;
@Component @Profile({"local","demo"}) @ConditionalOnProperty(name="sporthub.demo.seed-enabled",havingValue="true")
public class DemoFacilitySeeder implements ApplicationRunner {
 private final SportCategoryRepository categories;private final FacilityRepository facilities;private final CourtRepository courts;
 private final String identityUrl;private final String password;
 public DemoFacilitySeeder(SportCategoryRepository categories,FacilityRepository facilities,CourtRepository courts,
  @Value("${IDENTITY_SERVICE_URL:http://localhost:8081}") String identityUrl,@Value("${DEMO_PASSWORD}") String password){this.categories=categories;this.facilities=facilities;this.courts=courts;this.identityUrl=identityUrl;this.password=password;}
 @Override @Transactional public void run(ApplicationArguments args){
  if(categories.count()==0){for(String name:List.of("Pickleball","Cầu lông","Tennis","Bóng đá"))categories.save(new SportCategory(name));}
  var client=RestClient.create(identityUrl);
  var login=client.post().uri("/api/v1/auth/login").body(Map.of("identifier","owner@sporthub.local","password",password)).retrieve().body(JsonNode.class).path("data");
  UUID ownerId=UUID.fromString(login.path("user").path("id").asText());
  try{
   if(!facilities.findByOwnerIdOrderByCreatedAtDesc(ownerId).isEmpty())return;
   var f=new Facility();f.setOwnerId(ownerId);f.setName("SportHub Demo Center");f.setPhone("+84901234567");f.setAddressLine("Cơ sở dữ liệu mẫu cho demo local");f.setProvince("Hà Nội");f.setDistrict("Cầu Giấy");f.setWard("Dịch Vọng");f.setTimezone("Asia/Ho_Chi_Minh");f.setStatus("ACTIVE");f.setAmenities(new LinkedHashSet<>(List.of("Bãi đỗ xe","Phòng thay đồ")));facilities.save(f);
   var category=categories.findAllByOrderByNameAsc().stream().filter(c->c.getName().equals("Pickleball")).findFirst().orElseThrow();
   for(int n=1;n<=2;n++){var c=new Court();c.setFacility(f);c.setSportCategoryId(category.getId());c.setCode("DEMO-"+n);c.setName("Sân Pickleball "+n);courts.save(c);}
  }finally{client.post().uri("/api/v1/auth/logout").header("Authorization","Bearer "+login.path("accessToken").asText()).retrieve().toBodilessEntity();}
 }
}
