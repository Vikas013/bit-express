package com.example.bit_express.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RestaurantResponse(
        String id,
        Long externalRestaurantId,
        String name,
        String address,
        String city,
        String country,
        List<String> cuisines,
        Double aggregateRating,
        Integer averageCostForTwo,
        String currency,
        Boolean hasOnlineDelivery,
        Double distanceKm
) {}