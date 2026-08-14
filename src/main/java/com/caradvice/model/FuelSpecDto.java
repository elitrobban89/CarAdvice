package com.caradvice.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * @param fuel drivmedlet enligt {@code ice_consumption} ("bensin", "diesel", "hybrid",
 *             "laddhybrid"), eller {@code null} när ingen rad matchade. Fältet är
 *             <b>alltid verifierat</b> — AI:ns egen gissning kastas, av samma skäl som för
 *             förbrukning och hästkrafter.
 *             <p>Tillkom 2026-08-14 för att frontenden räknade ägandekostnad med
 *             <i>förbrukningen som drivmedelsproxy</i>: {@code consumptionLiterPerMil > 7}
 *             gav dieselpris. En Kia Sportage 1.6 T-GDI drar 8,0 l/100 km och prissattes
 *             därför som diesel, medan en snål diesel under 7 fick bensinpris — fel åt båda
 *             hållen. Värdet fanns hela tiden i raden förbrukningssiffran hämtas ur, det
 *             skickades bara inte med. Är fältet {@code null} faller frontenden tillbaka på
 *             den gamla tröskeln.
 */
public record FuelSpecDto(
        @JsonProperty("consumptionLiterPerMil") Double  consumptionLiterPerMil,
        @JsonProperty("gearbox")                String  gearbox,
        @JsonProperty("horsepower")             Integer horsepower,
        @JsonProperty("engineVolumeLiters")     Double  engineVolumeLiters,
        @JsonProperty("fuel")                   String  fuel
) {}
