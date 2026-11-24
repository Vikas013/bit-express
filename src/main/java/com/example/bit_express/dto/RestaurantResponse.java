package com.example.bit_express.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RestaurantResponse(
    String id,
    Long externalRestaurantId,
    String name,
    String address,
    String locality,
    String city,
    String country,
    String countryCode,
    List<String> cuisines,
    Double aggregateRating,
    String ratingText,
    Integer votes,
    Integer averageCostForTwo,
    String currency,
    Integer priceRange,
    Boolean hasOnlineDelivery,
    Boolean hasTableBooking,
    Double latitude,
    Double longitude,
    Double distanceKm) {}
