package com.caradvice.repository;

import com.caradvice.model.ExpertInsight;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExpertInsightRepository extends JpaRepository<ExpertInsight, Long> {
    List<ExpertInsight> findByCategoryIgnoreCaseOrFuelTypeIgnoreCase(String category, String fuelType);
    void deleteByExpertName(String expertName);
    long countByExpertName(String expertName);
    List<ExpertInsight> findAllByOrderByIdDesc(Pageable pageable);
    List<ExpertInsight> findByExpertNameIgnoreCaseOrderByIdDesc(String expertName, Pageable pageable);
}
