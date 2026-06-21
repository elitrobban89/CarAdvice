package com.caradvice.repository;

import com.caradvice.model.SavedSearch;
import com.caradvice.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SavedSearchRepository extends JpaRepository<SavedSearch, Long> {
    List<SavedSearch> findByUserOrderByCreatedAtDesc(User user);
    long countByUser(User user);
}
