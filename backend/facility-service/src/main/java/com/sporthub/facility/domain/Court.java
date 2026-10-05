package com.sporthub.facility.domain;
import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;
@Entity @Table(name="courts") @Getter @Setter @NoArgsConstructor
public class Court {
 @Id private UUID id=UUID.randomUUID();
 @ManyToOne(fetch=FetchType.LAZY,optional=false) @JoinColumn(name="facility_id") private Facility facility;
 @Column(name="sport_category_id") private UUID sportCategoryId;
 private String code; private String name;
 @Column(columnDefinition="text") private String description;
 private boolean enabled=true;
 @Version private long version;
}
