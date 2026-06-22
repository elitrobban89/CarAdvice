package com.caradvice.repository;

import com.caradvice.model.EvSpec;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface EvSpecRepository extends JpaRepository<EvSpec, Long> {

    @Query("SELECT e.carName FROM EvSpec e WHERE e.carName IS NOT NULL")
    List<String> findAllCarNames();
}
