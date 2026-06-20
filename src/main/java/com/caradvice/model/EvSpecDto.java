package com.caradvice.model;

public record EvSpecDto(
        int wltpKm,
        int summerKm,
        int winterKm,
        int daysPerCharge,
        String daysLabel,
        double batteryKwh,
        int maxDcKw,
        int maxAcKw,
        int priceKr,
        String valueLabel,
        String carType
) {}
