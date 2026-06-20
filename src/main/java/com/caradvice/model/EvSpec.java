package com.caradvice.model;

import jakarta.persistence.*;

@Entity
@Table(name = "ev_spec")
public class EvSpec {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "car_name")
    private String carName;

    @Column(name = "max_ac_kw")
    private Double maxAcKw;

    @Column(name = "max_dc_kw")
    private Double maxDcKw;

    @Column(name = "battery_kwh")
    private Double batteryKwh;

    @Column(name = "range_km")
    private Integer rangeKm;

    @Column(name = "price_kr")
    private Integer priceKr;

    public EvSpec() {}

    public EvSpec(String carName, Double maxAcKw, Double maxDcKw, Double batteryKwh, Integer rangeKm, Integer priceKr) {
        this.carName = carName;
        this.maxAcKw = maxAcKw;
        this.maxDcKw = maxDcKw;
        this.batteryKwh = batteryKwh;
        this.rangeKm = rangeKm;
        this.priceKr = priceKr;
    }

    public Long getId() { return id; }
    public String getCarName() { return carName; }
    public Double getMaxAcKw() { return maxAcKw; }
    public Double getMaxDcKw() { return maxDcKw; }
    public Double getBatteryKwh() { return batteryKwh; }
    public Integer getRangeKm() { return rangeKm; }
    public Integer getPriceKr() { return priceKr; }
}
