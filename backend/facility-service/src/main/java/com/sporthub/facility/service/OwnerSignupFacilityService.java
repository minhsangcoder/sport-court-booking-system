package com.sporthub.facility.service;

import com.fasterxml.jackson.databind.*;
import com.sporthub.common.dto.OwnerSignupSetup;
import com.sporthub.common.upload.UploadValidation;
import com.sporthub.common.security.RemoteIdentity.Caller;
import com.sporthub.facility.web.FacilityDtos.FacilityInput;
import org.springframework.stereotype.Service;
import java.time.ZoneId;
import java.util.*;

/** Only reached through the HMAC-protected first-facility controller. */
@Service
public class OwnerSignupFacilityService {
 private final FacilityService facilities;private final FacilityDocumentService documents;private final MediaService media;private final FacilityReviewDependencies dependencies;private final ObjectMapper json;private final jakarta.validation.Validator validator;
 public OwnerSignupFacilityService(FacilityService facilities,FacilityDocumentService documents,MediaService media,FacilityReviewDependencies dependencies,ObjectMapper json,jakarta.validation.Validator validator){this.facilities=facilities;this.documents=documents;this.media=media;this.dependencies=dependencies;this.json=json;this.validator=validator;}
 public void validate(JsonNode input)throws Exception {
  var facility=json.treeToValue(input.path("facility"),FacilityInput.class);
  if(facility==null||!validator.validate(facility).isEmpty()||facility.latitude()==null||facility.longitude()==null)throw new IllegalArgumentException("Invalid first facility or location");
  ZoneId.of(facility.timezone());var setup=json.treeToValue(input.path("setup"),OwnerSignupSetup.class);setup.validate();facilities.validateSignupCategory(setup.sportCategoryId());
 }
 public void prepare(UUID application,UUID facility,UUID user,JsonNode input)throws Exception {
  var setup=json.treeToValue(input.path("setup"),OwnerSignupSetup.class);setup.validate();
  var actor=new Caller(user,"Applicant",Set.of("CUSTOMER"),Map.of(facility,Set.of("APPLICATION_READ","APPLICATION_EDIT")));
  // Validate every file before the first write; never accept caller-supplied paths/resource IDs.
  var identity=file(input,"identityDocument");var location=file(input,"locationDocument");var image=file(input,"facilityImage");
  UploadValidation.document(identity);UploadValidation.document(location);UploadValidation.image(image);
  facilities.createSignupCourt(application,facility,user,setup);
  dependencies.prepareSignup(application,facility,user,facilities.find(facility).getTimezone(),setup);
  documents.uploadOnce(facility,OwnerSignupSetup.resourceId(application,"identityDocument"),identity,actor);
  documents.uploadOnce(facility,OwnerSignupSetup.resourceId(application,"locationDocument"),location,actor);
  media.uploadOnce(facility,null,OwnerSignupSetup.resourceId(application,"facilityImage"),image,actor);
 }
 private UploadValidation.FileData file(JsonNode input,String kind)throws Exception{return json.treeToValue(input.path(kind),UploadValidation.FileData.class);}
}
