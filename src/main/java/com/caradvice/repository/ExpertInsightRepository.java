package com.caradvice.repository;

import com.caradvice.model.ExpertInsight;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ExpertInsightRepository extends JpaRepository<ExpertInsight, Long> {
    List<ExpertInsight> findByCategoryIgnoreCaseOrFuelTypeIgnoreCase(String category, String fuelType);
    void deleteByExpertName(String expertName);
    long countByExpertName(String expertName);
    List<ExpertInsight> findAllByOrderByIdDesc(Pageable pageable);
    List<ExpertInsight> findTop15ByCarMakeIgnoreCaseAndCarModelIgnoreCaseOrderByIdDesc(String carMake, String carModel);
    List<ExpertInsight> findByExpertNameIgnoreCaseOrderByIdDesc(String expertName, Pageable pageable);

    @Modifying
    @Query("UPDATE ExpertInsight i SET i.category = :to WHERE lower(i.category) = lower(:from)")
    int renameCategory(@Param("from") String from, @Param("to") String to);
}
