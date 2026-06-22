package com.caradvice.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record CarRecommendation(
        @JsonProperty("title")           String title,
        @JsonProperty("price")           String price,
        @JsonProperty("whyRecommended")  String whyRecommended,
        @JsonProperty("pros")            List<String> pros,
        @JsonProperty("con")             String con,
        @JsonProperty("fitSummary")      String fitSummary,
        @JsonProperty("expertOpinion")   String expertOpinion,
        @JsonProperty("safetyRating")    String safetyRating,
        @JsonProperty("evSpec")          EvSpecDto evSpec,
        @JsonProperty("cargoSpec")       CargoSpecDto cargoSpec,
        @JsonProperty("fuelSpec")        FuelSpecDto fuelSpec,
        @JsonProperty("blocketPrice")    String blocketPrice,
        @JsonProperty("horsepower")      Integer horsepower,
        @JsonProperty("engineOptions")   String engineOptions
) {}
