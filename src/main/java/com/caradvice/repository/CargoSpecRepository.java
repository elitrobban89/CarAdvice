package com.caradvice.repository;

import com.caradvice.model.CargoSpec;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface CargoSpecRepository extends JpaRepository<CargoSpec, Long> {

    @Query("SELECT c.carName FROM CargoSpec c WHERE c.carName IS NOT NULL")
    List<String> findAllCarNames();

    /**
     * Rader med uppmätt bagagevolym. Resten är namn utan siffra — CargoSpecSyncService hämtar
     * bara NAMN från Bilweb och skriver null i literkolumnen, så täckningen är inte självklar
     * ur radantalet.
     */
    @Query("SELECT COUNT(c) FROM CargoSpec c WHERE c.cargoLiters IS NOT NULL AND c.cargoLiters > 0")
    long countWithVolume();

    /**
     * Bilnamnen som saknar volym — arbetslistan för auto-data-ifyllningen.
     *
     * <p>Volym 0 räknas som saknad av samma skäl som {@code countWithVolume} kräver > 0: en
     * nolla är ett omätt fält, inte en bil utan bagage, och bagagevakten skulle fälla den.
     */
    @Query("SELECT c.carName FROM CargoSpec c WHERE c.carName IS NOT NULL "
            + "AND (c.cargoLiters IS NULL OR c.cargoLiters <= 0) ORDER BY c.carName")
    List<String> findNamesWithoutVolume();
}
