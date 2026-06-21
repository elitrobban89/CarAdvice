package com.caradvice.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "saved_search")
public class SavedSearch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "prefs_json", columnDefinition = "text", nullable = false)
    private String prefsJson;

    @Column(name = "recommendations_json", columnDefinition = "text")
    private String recommendationsJson;

    @Column(length = 200)
    private String label;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public SavedSearch() {}

    public SavedSearch(User user, String prefsJson, String recommendationsJson, String label) {
        this.user = user;
        this.prefsJson = prefsJson;
        this.recommendationsJson = recommendationsJson;
        this.label = label;
    }

    public Long getId() { return id; }
    public User getUser() { return user; }
    public String getPrefsJson() { return prefsJson; }
    public String getRecommendationsJson() { return recommendationsJson; }
    public String getLabel() { return label; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
