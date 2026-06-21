package com.caradvice.model;

public record CarPreferences(
        int budget,
        String carCategory,
        boolean hasCharger,
        int kmPerYear,
        String usage,
        int passengers,
        boolean newCar,
        String fuelType,
        String transmission
) {}
