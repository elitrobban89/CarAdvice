package com.caradvice.model;

import java.util.List;

public record CarRecommendation(
        String title,
        String price,
        String whyRecommended,
        List<String> pros,
        String con,
        String fitSummary,
        String expertOpinion
) {}
