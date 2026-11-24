package com.example.bit_express.controller;

import com.example.bit_express.dto.CreateRestaurantRequest;
import com.example.bit_express.dto.PagedRestaurantResponse;
import com.example.bit_express.dto.RestaurantResponse;
import com.example.bit_express.dto.RestaurantSearchCriteria;
import com.example.bit_express.service.RestaurantService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/v1/restaurants")
public class RestaurantController {

    private final RestaurantService restaurantService;

    public RestaurantController(RestaurantService restaurantService) {
        this.restaurantService = restaurantService;
    }

    @PostMapping
    public ResponseEntity<RestaurantResponse> create(@Valid @RequestBody CreateRestaurantRequest request) {
        RestaurantResponse created = restaurantService.create(request);
        URI location = URI.create("/v1/restaurants/" + created.id());
        return ResponseEntity.created(location).body(created);
    }

    @GetMapping("/{id}")
    public RestaurantResponse getById(@PathVariable String id) {
        return restaurantService.getById(id);
    }

    @GetMapping
    public PagedRestaurantResponse list(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String country,
            @RequestParam(required = false) List<String> cuisines,
            @RequestParam(required = false) Double minRating,
            @RequestParam(required = false) Integer maxCostForTwo,
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lon,
            @RequestParam(defaultValue = "rating") String sortBy,
            @RequestParam(defaultValue = "desc") String sortDir,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        RestaurantSearchCriteria criteria = new RestaurantSearchCriteria(
                name, city, country, cuisines, minRating, maxCostForTwo, lat, lon, sortBy, sortDir, page, size
        );
        return restaurantService.list(criteria);
    }

    @GetMapping("/search")
    public PagedRestaurantResponse search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String cuisine,
            @RequestParam(required = false) Integer maxCostForTwo,
            @RequestParam(required = false) Double minRating,
            @RequestParam(required = false) Double lat,
            @RequestParam(required = false) Double lon,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size
    ) {
        return restaurantService.searchNaturalLanguage(
                q, city, cuisine, maxCostForTwo, minRating, lat, lon, sortBy, sortDir, page, size
        );
    }
}