package com.caradvice.repository;

import com.caradvice.model.SafetyRating;
import org.springframework.data.jpa.repository.JpaRepository;

/** @author Robert Andersson Kopler */
public interface SafetyRatingRepository extends JpaRepository<SafetyRating, Long> {}
