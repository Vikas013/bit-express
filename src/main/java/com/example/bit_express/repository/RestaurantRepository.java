package com.example.bit_express.repository;

import com.example.bit_express.model.Restaurant;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface RestaurantRepository extends MongoRepository<Restaurant, String> {
    Optional<Restaurant> findByExternalRestaurantId(Long externalRestaurantId);
}