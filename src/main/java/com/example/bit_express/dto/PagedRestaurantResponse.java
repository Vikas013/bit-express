package com.example.bit_express.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PagedRestaurantResponse(
    String query,
    RestaurantQueryParams interpretedFilters,
    List<String> warnings,
    int page,
    int size,
    long totalElements,
    int totalPages,
    List<RestaurantResponse> results) {}
