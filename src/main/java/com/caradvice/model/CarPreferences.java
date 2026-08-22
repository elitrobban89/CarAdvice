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
) {
    /**
     * Kanoniserar kategorin — i dag bara {@code ekonomibil → smaabil}.
     *
     * <p><b>Aliaset fanns bara i frontenden.</b> {@code CA_CAT_ALIAS} i {@code car-advice-main.js}
     * översätter värdet vid varje läsning, men WordPress-sidan är en MANUELL kopia: sitter den
     * gamla snippeten kvar bär dess {@code <select>} fortfarande alternativet {@code ekonomibil},
     * och då är det värdet som postas. Backenden hade ingen motsvarande översättning.
     *
     * <p><b>Vad det kostade.</b> Systemprompten har regler och exempelmodeller för familjebil,
     * SUV och småbil — men inte för en kategori som inte finns. Mätt skarpt 2026-08-22: samma
     * sökning (bensin, manuell, 150 000 kr) gav med {@code smaabil} tre riktiga bensinmanuella
     * småbilar, och med {@code ekonomibil} gav den <b>Toyota Corolla Hybrid, Honda Jazz Hybrid
     * och Kia Niro Hybrid</b> — tre hybrider, alla dessutom påstådda som manuella fast ingen av
     * dem finns med manuell låda. En okänd kategori lämnar modellen utan exempel, och då
     * betyder "ekonomi" plötsligt hybrid.
     */
    public String canonicalCategory() {
        if (carCategory == null) return null;
        String c = carCategory.trim().toLowerCase(java.util.Locale.ROOT);
        return "ekonomibil".equals(c) ? "smaabil" : carCategory;
    }

    /** Samma preferenser, med kategorin kanoniserad. */
    public CarPreferences canonical() {
        String c = canonicalCategory();
        return java.util.Objects.equals(c, carCategory) ? this
                : new CarPreferences(budget, c, hasCharger, kmPerYear, usage, passengers, newCar,
                        fuelType, transmission, budgetType, maxAgeYears, minCargoLiters);
    }
}
