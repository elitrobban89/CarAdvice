package com.caradvice.model;

import jakarta.persistence.*;

@Entity
@Table(name = "cargo_spec")
public class CargoSpec {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "car_name")
    private String carName;

    @Column(name = "cargo_liters")
    private Integer cargoLiters;

    @Column(name = "cargo_max_liters")
    private Integer cargoMaxLiters;

    public CargoSpec() {}

    public CargoSpec(String carName, Integer cargoLiters, Integer cargoMaxLiters) {
        this.carName = carName;
        this.cargoLiters = cargoLiters;
        this.cargoMaxLiters = cargoMaxLiters;
    }

    public Long getId() { return id; }
    public String getCarName() { return carName; }
    public Integer getCargoLiters() { return cargoLiters; }
    public Integer getCargoMaxLiters() { return cargoMaxLiters; }

    public void setCargoLiters(Integer cargoLiters) { this.cargoLiters = cargoLiters; }
    public void setCargoMaxLiters(Integer cargoMaxLiters) { this.cargoMaxLiters = cargoMaxLiters; }
}
