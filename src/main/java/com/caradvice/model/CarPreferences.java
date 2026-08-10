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
        String transmission,
        String budgetType,
        Integer maxAgeYears,
        /**
         * Minsta bagagevolym i liter, eller null när kravet inte är satt.
         *
         * <p>Eget filter vid sidan av {@code passengers}: antalet säten säger hur många som får
         * plats, volymen säger vad som får plats i bagaget — en femsitsig småbil och en kombi
         * har samma passagerarantal och helt olika lastförmåga. Mäts mot {@code cargo_spec}:s
         * normalvolym (baksätet uppfällt), inte maxvolymen med nedfällt säte.
         */
        Integer minCargoLiters
) {}
