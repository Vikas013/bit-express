package com.example.bit_express.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record RestaurantQueryParams(
    String name,
    String city,
    String country,
    List<String> cuisines,
    @DecimalMin("0.0") @DecimalMax("5.0") Double minRating,
    @Min(0) Integer maxCostForTwo,
    @DecimalMin("-90.0") @DecimalMax("90.0") Double lat,
    @DecimalMin("-180.0") @DecimalMax("180.0") Double lon,
    @Positive Double maxDistanceKm,
    String sortBy,
    String sortDir,
    @Min(0) Integer page,
    @Min(1) Integer size) {

  public boolean hasGeo() {
    return lat != null && lon != null;
  }
}
