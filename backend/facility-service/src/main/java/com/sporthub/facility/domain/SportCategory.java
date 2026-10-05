package com.sporthub.facility.domain;
import jakarta.persistence.*;
import lombok.*;
import java.util.UUID;
@Entity @Table(name="sport_categories") @Getter @Setter @NoArgsConstructor
public class SportCategory {
 @Id private UUID id=UUID.randomUUID(); private String name; private boolean active=true;
 public SportCategory(String name){this.name=name;}
}
