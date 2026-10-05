package com.sporthub.facility.repository;
import com.sporthub.facility.domain.Facility;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface FacilityRepository extends JpaRepository<Facility,UUID>{
 List<Facility> findByOwnerIdOrderByCreatedAtDesc(UUID ownerId);
 List<Facility> findByStatusOrderByNameAsc(String status);
}
