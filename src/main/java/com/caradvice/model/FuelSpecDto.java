package com.caradvice.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record FuelSpecDto(
        @JsonProperty("consumptionLiterPerMil") double consumptionLiterPerMil,
        @JsonProperty("gearbox")                String gearbox,
        @JsonProperty("horsepower")             int    horsepower,
        @JsonProperty("engineVolumeLiters")     double engineVolumeLiters
) {}
