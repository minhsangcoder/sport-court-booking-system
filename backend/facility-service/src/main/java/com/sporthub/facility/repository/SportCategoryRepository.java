package com.sporthub.facility.repository;
import com.sporthub.facility.domain.SportCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface SportCategoryRepository extends JpaRepository<SportCategory,UUID>{List<SportCategory> findAllByOrderByNameAsc();}
