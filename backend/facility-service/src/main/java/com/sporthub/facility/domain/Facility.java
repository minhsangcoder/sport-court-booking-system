package com.sporthub.facility.domain;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.*;
@Entity @Table(name="facilities") @Getter @Setter @NoArgsConstructor
public class Facility {
 @Id private UUID id=UUID.randomUUID();
 @Column(name="owner_id",nullable=false) private UUID ownerId;
 private String name; private String phone;
 @Column(name="contact_email",length=254) private String contactEmail;
 @Column(name="address_line") private String addressLine;
 private String province; private String district; private String ward;
 @Column(columnDefinition="text") private String description;
 private String timezone; private java.math.BigDecimal latitude; private java.math.BigDecimal longitude;
 private String status="DRAFT";
 @ElementCollection @CollectionTable(name="facility_amenities",joinColumns=@JoinColumn(name="facility_id"))
 @Column(name="name") private Set<String> amenities=new LinkedHashSet<>();
 @Column(name="created_at") private Instant createdAt=Instant.now();
 @Column(name="updated_at") private Instant updatedAt=Instant.now();
 @Version private long version;
 @PreUpdate void updated(){updatedAt=Instant.now();}
}
