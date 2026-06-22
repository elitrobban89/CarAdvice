package com.caradvice.repository;

import com.caradvice.model.CargoSpec;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface CargoSpecRepository extends JpaRepository<CargoSpec, Long> {

    @Query("SELECT c.carName FROM CargoSpec c WHERE c.carName IS NOT NULL")
    List<String> findAllCarNames();
}
