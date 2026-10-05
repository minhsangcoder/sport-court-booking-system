package com.sporthub.facility.repository;
import com.sporthub.facility.domain.Maintenance;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface MaintenanceRepository extends JpaRepository<Maintenance,UUID>{List<Maintenance> findByCourtIdAndCancelledFalseOrderByStartsAt(UUID courtId);}
