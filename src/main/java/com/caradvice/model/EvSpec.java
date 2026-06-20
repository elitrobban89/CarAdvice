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

    @Column(name = "car_type")
    private String carType;

    public EvSpec() {}

    public EvSpec(String carName, Double maxAcKw, Double maxDcKw, Double batteryKwh, Integer rangeKm, Integer priceKr) {
        this(carName, maxAcKw, maxDcKw, batteryKwh, rangeKm, priceKr, "EV");
    }

    public EvSpec(String carName, Double maxAcKw, Double maxDcKw, Double batteryKwh, Integer rangeKm, Integer priceKr, String carType) {
        this.carName = carName;
        this.maxAcKw = maxAcKw;
        this.maxDcKw = maxDcKw;
        this.batteryKwh = batteryKwh;
        this.rangeKm = rangeKm;
        this.priceKr = priceKr;
        this.carType = carType;
    }

    public Long getId() { return id; }
    public String getCarName() { return carName; }
    public Double getMaxAcKw() { return maxAcKw; }
    public Double getMaxDcKw() { return maxDcKw; }
    public Double getBatteryKwh() { return batteryKwh; }
    public Integer getRangeKm() { return rangeKm; }
    public Integer getPriceKr() { return priceKr; }
    public String getCarType() { return carType; }

    public void setMaxAcKw(Double maxAcKw) { this.maxAcKw = maxAcKw; }
    public void setMaxDcKw(Double maxDcKw) { this.maxDcKw = maxDcKw; }
    public void setBatteryKwh(Double batteryKwh) { this.batteryKwh = batteryKwh; }
    public void setRangeKm(Integer rangeKm) { this.rangeKm = rangeKm; }
    public void setPriceKr(Integer priceKr) { this.priceKr = priceKr; }
}
