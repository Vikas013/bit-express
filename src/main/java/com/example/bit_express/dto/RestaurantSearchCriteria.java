package com.example.bit_express.dto;

import java.util.List;

public record RestaurantSearchCriteria(
        String name,
        String city,
        String country,
        List<String> cuisines,
        Double minRating,
        Integer maxCostForTwo,
        Double latitude,
        Double longitude,
        String sortBy,
        String sortDir,
        int page,
        int size
) {
    public boolean hasGeo() {
        return latitude != null && longitude != null;
    }
}