package com.caradvice.repository;

import com.caradvice.model.ExpertInsight;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExpertInsightRepository extends JpaRepository<ExpertInsight, Long> {
    List<ExpertInsight> findByCategoryIgnoreCaseOrFuelTypeIgnoreCase(String category, String fuelType);
}
