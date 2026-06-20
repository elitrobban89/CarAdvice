package com.caradvice.model;

import jakarta.persistence.*;

@Entity
@Table(name = "safety_rating")
public class SafetyRating {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "car_make")
    private String carMake;

    @Column(name = "car_model")
    private String carModel;

    @Column(name = "test_year")
    private Integer testYear;

    private Integer stars;

    @Column(name = "adult_pct")
    private Integer adultPct;

    @Column(name = "child_pct")
    private Integer childPct;

    @Column(name = "pedestrian_pct")
    private Integer pedestrianPct;

    @Column(name = "safety_assist_pct")
    private Integer safetyAssistPct;

    public SafetyRating() {}

    public SafetyRating(String carMake, String carModel, Integer testYear, Integer stars,
                        Integer adultPct, Integer childPct, Integer pedestrianPct, Integer safetyAssistPct) {
        this.carMake = carMake;
        this.carModel = carModel;
        this.testYear = testYear;
        this.stars = stars;
        this.adultPct = adultPct;
        this.childPct = childPct;
        this.pedestrianPct = pedestrianPct;
        this.safetyAssistPct = safetyAssistPct;
    }

    public Long getId() { return id; }
    public String getCarMake() { return carMake; }
    public String getCarModel() { return carModel; }
    public Integer getTestYear() { return testYear; }
    public Integer getStars() { return stars; }
    public Integer getAdultPct() { return adultPct; }
    public Integer getChildPct() { return childPct; }
    public Integer getPedestrianPct() { return pedestrianPct; }
    public Integer getSafetyAssistPct() { return safetyAssistPct; }
}
