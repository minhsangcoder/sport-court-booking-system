package com.sporthub.facility.repository;
import com.sporthub.facility.domain.Court;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface CourtRepository extends JpaRepository<Court,UUID>{List<Court> findByFacilityIdOrderByNameAsc(UUID facilityId);}
