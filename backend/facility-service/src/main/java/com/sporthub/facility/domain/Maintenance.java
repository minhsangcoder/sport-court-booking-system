package com.sporthub.facility.domain;
import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;
import java.util.UUID;
@Entity @Table(name="maintenance_windows") @Getter @Setter @NoArgsConstructor
public class Maintenance {
 @Id private UUID id=UUID.randomUUID();
 @Column(name="court_id") private UUID courtId;
 @Column(name="starts_at") private Instant startsAt;
 @Column(name="ends_at") private Instant endsAt;
 private String reason;
 @Column(name="created_by") private UUID createdBy;
 private boolean cancelled;
}
