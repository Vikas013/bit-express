package com.example.bit_express.controller;

import com.example.bit_express.dto.CreateRestaurantRequest;
import com.example.bit_express.dto.PagedRestaurantResponse;
import com.example.bit_express.dto.RestaurantQueryParams;
import com.example.bit_express.dto.RestaurantResponse;
import com.example.bit_express.service.RestaurantService;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/restaurants")
public class RestaurantController {

  public static final String TENANT_HEADER = "X-Tenant-Id";
  private static final String DEFAULT_TENANT = "${app.default-tenant}";

  private final RestaurantService restaurantService;

  public RestaurantController(RestaurantService restaurantService) {
    this.restaurantService = restaurantService;
  }

  @PostMapping
  public ResponseEntity<RestaurantResponse> create(
      @RequestHeader(name = TENANT_HEADER, defaultValue = DEFAULT_TENANT) String tenantId,
      @Valid @RequestBody CreateRestaurantRequest request) {
    RestaurantResponse created = restaurantService.create(tenantId, request);
    return ResponseEntity.created(URI.create("/v1/restaurants/" + created.id())).body(created);
  }

  @GetMapping("/{id}")
  public RestaurantResponse getById(
      @RequestHeader(name = TENANT_HEADER, defaultValue = DEFAULT_TENANT) String tenantId,
      @PathVariable String id) {
    return restaurantService.getById(tenantId, id);
  }

  @GetMapping
  public PagedRestaurantResponse list(
      @RequestHeader(name = TENANT_HEADER, defaultValue = DEFAULT_TENANT) String tenantId,
      @Valid @ModelAttribute RestaurantQueryParams params) {
    return restaurantService.list(tenantId, params);
  }

  @GetMapping("/search")
  public PagedRestaurantResponse search(
      @RequestHeader(name = TENANT_HEADER, defaultValue = DEFAULT_TENANT) String tenantId,
      @RequestParam(required = false) String q,
      @Valid @ModelAttribute RestaurantQueryParams params) {
    return restaurantService.search(tenantId, q, params);
  }
}
