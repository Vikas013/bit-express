package com.example.bit_express;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.mongodb.MongoDBContainer;

final class TestMongo {

  private static final MongoDBContainer CONTAINER = new MongoDBContainer("mongo:8.0");

  static {
    CONTAINER.start();
  }

  private TestMongo() {}

  static void register(DynamicPropertyRegistry registry, String database, boolean seed) {
    String uri = CONTAINER.getReplicaSetUrl(database);
    registry.add("spring.mongodb.uri", () -> uri);
    registry.add("spring.data.mongodb.uri", () -> uri);
    registry.add("app.seed.enabled", () -> String.valueOf(seed));
  }
}
