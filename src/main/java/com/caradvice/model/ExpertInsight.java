package com.caradvice.model;

import jakarta.persistence.*;

@Entity
@Table(name = "expert_insight")
public class ExpertInsight {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "expert_name")
    private String expertName;

    @Column(name = "car_make")
    private String carMake;

    @Column(name = "car_model")
    private String carModel;

    @Column(name = "fuel_type")
    private String fuelType;

    private String category;

    @Column(columnDefinition = "TEXT")
    private String insight;

    private Integer rating;

    public ExpertInsight() {}

    public ExpertInsight(String expertName, String carMake, String carModel,
                         String fuelType, String category, String insight, Integer rating) {
        this.expertName = expertName;
        this.carMake = carMake;
        this.carModel = carModel;
        this.fuelType = fuelType;
        this.category = category;
        this.insight = insight;
        this.rating = rating;
    }

    public Long getId()             { return id; }
    public String getExpertName()   { return expertName; }
    public String getCarMake()      { return carMake; }
    public String getCarModel()     { return carModel; }
    public String getFuelType()     { return fuelType; }
    public String getCategory()     { return category; }
    public String getInsight()      { return insight; }
    public Integer getRating()      { return rating; }
}
