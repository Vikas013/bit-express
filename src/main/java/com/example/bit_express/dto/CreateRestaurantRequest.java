package com.example.bit_express.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record CreateRestaurantRequest(
        @NotBlank(message = "Restaurant name is required")
        String name,

        @NotBlank(message = "City is required")
        String city,

        @NotBlank(message = "Address is required")
        String address,

        String country,

        @NotEmpty(message = "At least one cuisine is required")
        List<String> cuisines,

        @Min(value = 0, message = "Average cost for two cannot be negative")
        Integer averageCostForTwo,

        String currency,

        @DecimalMin(value = "0.0", message = "Rating must be >= 0.0")
        @DecimalMax(value = "5.0", message = "Rating must be <= 5.0")
        Double aggregateRating,

        @DecimalMin(value = "-90.0", message = "Latitude must be >= -90.0")
        @DecimalMax(value = "90.0", message = "Latitude must be <= 90.0")
        Double latitude,

        @DecimalMin(value = "-180.0", message = "Longitude must be >= -180.0")
        @DecimalMax(value = "180.0", message = "Longitude must be <= 180.0")
        Double longitude,

        Boolean hasOnlineDelivery,
        Boolean hasTableBooking
) {}