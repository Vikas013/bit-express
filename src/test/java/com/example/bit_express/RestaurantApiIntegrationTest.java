package com.example.bit_express;


import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class RestaurantApiIntegrationTest {

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8.0");

    @DynamicPropertySource
    static void configureMongo(DynamicPropertyRegistry registry) {
        registry.add("spring.mongodb.uri", MONGO::getReplicaSetUrl);
        registry.add("spring.data.mongodb.uri", MONGO::getReplicaSetUrl);
        registry.add("spring.data.mongodb.auto-index-creation", () -> "true");
        registry.add("app.seed.enabled", () -> "false");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void cleanCollection() {
        mongoTemplate.getDb().getCollection("restaurants").deleteMany(new Document());
    }

    @Test
    void createAndFetchRestaurant_returns201And200_and404WhenMissing() throws Exception {
        String payload = """
            {
              "name": "South Express Kitchen",
              "city": "Gurgaon",
              "address": "DLF Phase 2, Gurugram",
              "country": "India",
              "cuisines": ["South Indian"],
              "averageCostForTwo": 650,
              "currency": "INR",
              "aggregateRating": 4.6,
              "latitude": 28.4855,
              "longitude": 77.0878,
              "hasOnlineDelivery": true,
              "hasTableBooking": false
            }
            """;

        String responseBody = mockMvc.perform(post("/v1/restaurants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.name", is("South Express Kitchen")))
                .andExpect(jsonPath("$.city", is("Gurgaon")))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String createdId = com.jayway.jsonpath.JsonPath.read(responseBody, "$.id");

        mockMvc.perform(get("/v1/restaurants/{id}", createdId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(createdId)))
                .andExpect(jsonPath("$.cuisines", hasItem("South Indian")));

        mockMvc.perform(get("/v1/restaurants/{id}", "non-existent-id-999"))
                .andExpect(status().isNotFound());
    }

    @Test
    void createRestaurant_withInvalidLatitude_returns400() throws Exception {
        String invalidPayload = """
            {
              "name": "Invalid Geo Spot",
              "city": "Gurgaon",
              "address": "MG Road",
              "cuisines": ["North Indian"],
              "latitude": 145.0,
              "longitude": 77.0266
            }
            """;

        mockMvc.perform(post("/v1/restaurants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidPayload))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listWithGeoProximity_sortsByNearestAndComputesDistanceKm() throws Exception {
        insertSampleRestaurants();

        // Query near Cyber Hub / DLF Phase 2 (~28.4950, 77.0895)
        mockMvc.perform(get("/v1/restaurants")
                        .param("city", "Gurgaon")
                        .param("lat", "28.4950")
                        .param("lon", "77.0895")
                        .param("sortBy", "distance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].name", is("Cyber Dosa Hub")))
                .andExpect(jsonPath("$.results[0].distanceKm", notNullValue()))
                .andExpect(jsonPath("$.results[1].name", is("Sector 29 Street Bites")));
    }

    @Test
    void naturalLanguageSearch_parsesCityCuisineBudgetAndDishAlias() throws Exception {
        insertSampleRestaurants();

        mockMvc.perform(get("/v1/restaurants/search")
                        .param("q", "Best restaurants in Gurgaon under Rs. 1000 for two"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.interpretedFilters.city", is("Gurgaon")))
                .andExpect(jsonPath("$.interpretedFilters.maxCostForTwo", is(1000)))
                .andExpect(jsonPath("$.totalElements", greaterThanOrEqualTo(1)));

        mockMvc.perform(get("/v1/restaurants/search")
                        .param("q", "Nearest place that serves Vada Pav"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.interpretedFilters.cuisine", is("Street Food")))
                .andExpect(jsonPath("$.interpretedFilters.sortBy", is("distance")));
    }

    private void insertSampleRestaurants() throws Exception {
        String r1 = """
            {
              "name": "Cyber Dosa Hub",
              "city": "Gurgaon",
              "address": "Cyber Hub, DLF Phase 2",
              "country": "India",
              "cuisines": ["South Indian"],
              "averageCostForTwo": 700,
              "currency": "INR",
              "aggregateRating": 4.5,
              "latitude": 28.4946,
              "longitude": 77.0887
            }
            """;

        String r2 = """
            {
              "name": "Sector 29 Street Bites",
              "city": "Gurgaon",
              "address": "Sector 29 Market",
              "country": "India",
              "cuisines": ["Street Food"],
              "averageCostForTwo": 400,
              "currency": "INR",
              "aggregateRating": 4.2,
              "latitude": 28.4685,
              "longitude": 77.0645
            }
            """;

        mockMvc.perform(post("/v1/restaurants").contentType(MediaType.APPLICATION_JSON).content(r1))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/v1/restaurants").contentType(MediaType.APPLICATION_JSON).content(r2))
                .andExpect(status().isCreated());
    }
}
