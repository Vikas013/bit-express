package com.example.bit_express.repository;

import com.example.bit_express.model.Restaurant;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface RestaurantRepository extends MongoRepository<Restaurant, String> {
  Optional<Restaurant> findByIdAndTenantId(String id, String tenantId);

  Optional<Restaurant> findByExternalRestaurantIdAndTenantId(
      Long externalRestaurantId, String tenantId);
}
