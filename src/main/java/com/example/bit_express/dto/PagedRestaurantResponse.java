package com.example.bit_express.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PagedRestaurantResponse(
        String query,
        Map<String, Object> interpretedFilters,
        List<String> warnings,
        int page,
        int size,
        long totalElements,
        int totalPages,
        List<RestaurantResponse> results
) {}