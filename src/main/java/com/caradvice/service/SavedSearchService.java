package com.caradvice.service;

import com.caradvice.model.SavedSearch;
import com.caradvice.model.User;
import com.caradvice.repository.SavedSearchRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SavedSearchService {

    private static final int MAX_SAVED = 20;
    private final SavedSearchRepository repo;

    public SavedSearchService(SavedSearchRepository repo) {
        this.repo = repo;
    }

    public SavedSearch save(User user, String prefsJson, String recommendationsJson, String label) {
        List<SavedSearch> existing = repo.findByUserOrderByCreatedAtDesc(user);
        if (existing.size() >= MAX_SAVED) {
            repo.delete(existing.get(existing.size() - 1));
        }
        return repo.save(new SavedSearch(user, prefsJson, recommendationsJson, label));
    }

    public List<SavedSearch> findByUser(User user) {
        return repo.findByUserOrderByCreatedAtDesc(user);
    }

    public boolean deleteById(Long id, User user) {
        return repo.findById(id).map(s -> {
            if (!s.getUser().getId().equals(user.getId())) return false;
            repo.delete(s);
            return true;
        }).orElse(false);
    }
}
