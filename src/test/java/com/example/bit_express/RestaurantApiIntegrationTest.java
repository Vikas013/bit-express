package com.example.bit_express;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.bit_express.controller.RestaurantController;
import com.example.bit_express.model.Restaurant;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
class RestaurantApiIntegrationTest {

  private static final String CYBER_HUB = "lat=28.4950&lon=77.0895";

  @DynamicPropertySource
  static void mongo(DynamicPropertyRegistry registry) {
    TestMongo.register(registry, "api", false);
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private MongoTemplate mongoTemplate;

  @BeforeEach
  void cleanCollection() {
    mongoTemplate.remove(new Query(), Restaurant.class);
  }

  @Test
  void createReturns201WithLocationAndFullDetails_getReturnsSame_unknownIdReturns404()
      throws Exception {
    String body =
        mockMvc
            .perform(
                postJson(
                    restaurant(
                        "South Express Kitchen",
                        "Gurgaon",
                        "South Indian",
                        650,
                        4.6,
                        28.4855,
                        77.0878)))
            .andExpect(status().isCreated())
            .andExpect(header().string("Location", containsString("/v1/restaurants/")))
            .andExpect(jsonPath("$.id", notNullValue()))
            .andExpect(jsonPath("$.currency", is("INR")))
            .andExpect(jsonPath("$.hasTableBooking", is(false)))
            .andExpect(jsonPath("$.latitude", is(28.4855)))
            .andExpect(jsonPath("$.longitude", is(77.0878)))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String id = JsonPath.read(body, "$.id");

    mockMvc
        .perform(get("/v1/restaurants/{id}", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id", is(id)))
        .andExpect(jsonPath("$.name", is("South Express Kitchen")))
        .andExpect(jsonPath("$.address", is("Sample Street, Gurgaon")))
        .andExpect(jsonPath("$.country", is("India")))
        .andExpect(jsonPath("$.cuisines", contains("South Indian")))
        .andExpect(jsonPath("$.aggregateRating", is(4.6)))
        .andExpect(jsonPath("$.distanceKm").doesNotExist());

    mockMvc
        .perform(get("/v1/restaurants/{id}", "non-existent-id-999"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.detail", is("Restaurant not found: non-existent-id-999")));
  }

  @Test
  void createRejectsInvalidPayloadsWith400ProblemDetail() throws Exception {
    mockMvc
        .perform(postJson(restaurant("Bad Geo", "Gurgaon", "North Indian", 500, 4.0, 145.0, 77.0)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.title", is("Validation Failed")))
        .andExpect(jsonPath("$.detail", containsString("latitude")));
    mockMvc
        .perform(postJson(restaurant(" ", "Gurgaon", "North Indian", 500, 4.0, 28.4, 77.0)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail", containsString("name")));
    mockMvc
        .perform(postJson(restaurant("Too Good", "Gurgaon", "North Indian", 500, 6.0, 28.4, 77.0)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail", containsString("aggregateRating")));
    mockMvc
        .perform(
            postJson(
                """
                {"name": "No Cuisine", "city": "Gurgaon", "address": "Sample Street",
                 "cuisines": []}
                """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail", containsString("cuisines")));
  }

  @Test
  void createWithoutRatingStoresTheRestaurantAsUnrated() throws Exception {
    mockMvc
        .perform(
            postJson(
                """
                {"name": "Fresh Opening", "city": "Gurgaon", "address": "Sample Street",
                 "cuisines": ["Cafe"]}
                """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.aggregateRating").doesNotExist());
    mockMvc
        .perform(get("/v1/restaurants?minRating=0"))
        .andExpect(jsonPath("$.totalElements", is(0)));
  }

  @Test
  void getByIdAcceptsTheZomatoRestaurantId() throws Exception {
    Restaurant seeded = new Restaurant();
    seeded.setTenantId("bite-express");
    seeded.setExternalRestaurantId(18237L);
    seeded.setName("Seeded Place");
    seeded.setCity("Gurgaon");
    seeded.setCuisines(List.of("Cafe"));
    seeded.setVotes(120);
    seeded.setRatingText("Very Good");
    mongoTemplate.save(seeded);

    mockMvc
        .perform(get("/v1/restaurants/18237"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name", is("Seeded Place")))
        .andExpect(jsonPath("$.externalRestaurantId", is(18237)))
        .andExpect(jsonPath("$.votes", is(120)))
        .andExpect(jsonPath("$.ratingText", is("Very Good")));
  }

  @Test
  void tenantsAreIsolatedByHeader() throws Exception {
    insertSampleRestaurants();
    String body =
        mockMvc
            .perform(
                postJson(restaurant("Quick Eats Only", "Gurgaon", "Cafe", 300, 4.0, 28.4, 77.0))
                    .header(RestaurantController.TENANT_HEADER, "quick-eats"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String id = JsonPath.read(body, "$.id");

    mockMvc.perform(get("/v1/restaurants/{id}", id)).andExpect(status().isNotFound());
    mockMvc
        .perform(
            get("/v1/restaurants/{id}", id)
                .header(RestaurantController.TENANT_HEADER, "quick-eats"))
        .andExpect(status().isOk());
    mockMvc
        .perform(get("/v1/restaurants").header(RestaurantController.TENANT_HEADER, "quick-eats"))
        .andExpect(jsonPath("$.totalElements", is(1)))
        .andExpect(jsonPath("$.results[0].name", is("Quick Eats Only")));
    mockMvc.perform(get("/v1/restaurants")).andExpect(jsonPath("$.totalElements", is(4)));
  }

  @Test
  void listDefaultsToRatingDescending() throws Exception {
    insertSampleRestaurants();
    mockMvc
        .perform(get("/v1/restaurants"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.interpretedFilters").doesNotExist())
        .andExpect(
            jsonPath(
                "$.results[*].name",
                contains(
                    "Connaught Biryani House",
                    "Cyber Dosa Hub",
                    "Sector 29 Street Bites",
                    "Galleria Momos")));
  }

  @Test
  void listFiltersByCityCaseInsensitively() throws Exception {
    insertSampleRestaurants();
    mockMvc
        .perform(get("/v1/restaurants?city=gURgaon"))
        .andExpect(jsonPath("$.totalElements", is(3)))
        .andExpect(jsonPath("$.results[*].city", contains("Gurgaon", "Gurgaon", "Gurgaon")));
  }

  @Test
  void listFiltersByCuisinesRequiringAll() throws Exception {
    insertSampleRestaurants();
    mockMvc
        .perform(get("/v1/restaurants?cuisines=street food"))
        .andExpect(
            jsonPath("$.results[*].name", contains("Sector 29 Street Bites", "Galleria Momos")));
    mockMvc
        .perform(get("/v1/restaurants?cuisines=Chinese&cuisines=Street Food"))
        .andExpect(jsonPath("$.results[*].name", contains("Galleria Momos")));
    mockMvc
        .perform(get("/v1/restaurants?cuisines=Chinese,Street Food"))
        .andExpect(jsonPath("$.results[*].name", contains("Galleria Momos")));
  }

  @Test
  void listFiltersByNameRatingAndCost() throws Exception {
    insertSampleRestaurants();
    mockMvc
        .perform(get("/v1/restaurants?name=DOSA"))
        .andExpect(jsonPath("$.results[*].name", contains("Cyber Dosa Hub")));
    mockMvc
        .perform(get("/v1/restaurants?minRating=4.4"))
        .andExpect(
            jsonPath("$.results[*].name", contains("Connaught Biryani House", "Cyber Dosa Hub")));
    mockMvc
        .perform(get("/v1/restaurants?maxCostForTwo=500"))
        .andExpect(
            jsonPath("$.results[*].name", contains("Sector 29 Street Bites", "Galleria Momos")));
  }

  @Test
  void listSortsByCostAndName() throws Exception {
    insertSampleRestaurants();
    mockMvc
        .perform(get("/v1/restaurants?sortBy=cost"))
        .andExpect(jsonPath("$.results[*].averageCostForTwo", contains(300, 400, 700, 1200)));
    mockMvc
        .perform(get("/v1/restaurants?sortBy=cost&sortDir=desc"))
        .andExpect(jsonPath("$.results[*].averageCostForTwo", contains(1200, 700, 400, 300)));
    mockMvc
        .perform(get("/v1/restaurants?sortBy=name"))
        .andExpect(
            jsonPath(
                "$.results[*].name",
                contains(
                    "Connaught Biryani House",
                    "Cyber Dosa Hub",
                    "Galleria Momos",
                    "Sector 29 Street Bites")));
  }

  @Test
  void listPaginatesAndCapsPageSize() throws Exception {
    insertSampleRestaurants();
    mockMvc
        .perform(get("/v1/restaurants?size=2&page=1"))
        .andExpect(jsonPath("$.page", is(1)))
        .andExpect(jsonPath("$.size", is(2)))
        .andExpect(jsonPath("$.totalElements", is(4)))
        .andExpect(jsonPath("$.totalPages", is(2)))
        .andExpect(
            jsonPath("$.results[*].name", contains("Sector 29 Street Bites", "Galleria Momos")));
    mockMvc.perform(get("/v1/restaurants?size=500")).andExpect(jsonPath("$.size", is(100)));
  }

  @Test
  void listSortsByDistanceAndLimitsByMaxDistance() throws Exception {
    insertSampleRestaurants();
    mockMvc
        .perform(get("/v1/restaurants?city=Gurgaon&sortBy=distance&" + CYBER_HUB))
        .andExpect(
            jsonPath(
                "$.results[*].name",
                contains("Cyber Dosa Hub", "Galleria Momos", "Sector 29 Street Bites")))
        .andExpect(jsonPath("$.results[0].distanceKm", is(0.09)));
    mockMvc
        .perform(get("/v1/restaurants?maxDistanceKm=3&" + CYBER_HUB))
        .andExpect(jsonPath("$.results[*].name", contains("Cyber Dosa Hub")));
  }

  @Test
  void listRejectsInvalidParametersWith400() throws Exception {
    for (String query :
        List.of(
            "sortBy=distance",
            "lat=28.4",
            "maxDistanceKm=5",
            "sortBy=popularity",
            "sortDir=sideways",
            "lat=95&lon=77",
            "size=0",
            "minRating=abc")) {
      mockMvc.perform(get("/v1/restaurants?" + query)).andExpect(status().isBadRequest());
    }
  }

  @Test
  void searchParsesTheBriefExamples() throws Exception {
    insertSampleRestaurants();
    mockMvc
        .perform(
            get("/v1/restaurants/search")
                .param("q", "Best restaurants in Gurgaon with South Indian Food"))
        .andExpect(jsonPath("$.query", is("Best restaurants in Gurgaon with South Indian Food")))
        .andExpect(jsonPath("$.interpretedFilters.city", is("Gurgaon")))
        .andExpect(jsonPath("$.interpretedFilters.cuisines", contains("South Indian")))
        .andExpect(jsonPath("$.results[*].name", contains("Cyber Dosa Hub")));
    mockMvc
        .perform(
            get("/v1/restaurants/search")
                .param("q", "Restaurants in Gurgaon under Rs. 1000 for two"))
        .andExpect(jsonPath("$.interpretedFilters.maxCostForTwo", is(1000)))
        .andExpect(jsonPath("$.interpretedFilters.sortBy", is("rating")))
        .andExpect(jsonPath("$.interpretedFilters.sortDir", is("desc")))
        .andExpect(jsonPath("$.totalElements", is(3)));
    mockMvc
        .perform(get("/v1/restaurants/search").param("q", "Nearest place that serves Vada Pav"))
        .andExpect(jsonPath("$.interpretedFilters.cuisines", contains("Street Food")))
        .andExpect(jsonPath("$.interpretedFilters.sortBy", is("distance")))
        .andExpect(jsonPath("$.warnings", hasItem(containsString("No coordinates"))))
        .andExpect(
            jsonPath("$.results[*].name", contains("Sector 29 Street Bites", "Galleria Momos")));
  }

  @Test
  void searchAcceptsEveryListParameterAndExplicitParamsWin() throws Exception {
    insertSampleRestaurants();
    mockMvc
        .perform(
            get("/v1/restaurants/search?name=momos&country=india&maxDistanceKm=5&" + CYBER_HUB))
        .andExpect(jsonPath("$.results[*].name", contains("Galleria Momos")));
    mockMvc
        .perform(
            get("/v1/restaurants/search")
                .param("q", "restaurants in Gurugram under 1000")
                .param("city", "New Delhi"))
        .andExpect(jsonPath("$.interpretedFilters.city", is("New Delhi")))
        .andExpect(jsonPath("$.totalElements", is(0)));
  }

  private MockHttpServletRequestBuilder postJson(String json) {
    return post("/v1/restaurants").contentType(MediaType.APPLICATION_JSON).content(json);
  }

  private static String restaurant(
      String name, String city, String cuisine, int cost, double rating, double lat, double lon) {
    return """
    {"name": "%s", "city": "%s", "address": "Sample Street, %s", "country": "India",
     "cuisines": ["%s"], "averageCostForTwo": %d, "aggregateRating": %s,
     "latitude": %s, "longitude": %s}
    """
        .formatted(name, city, city, cuisine, cost, rating, lat, lon);
  }

  private void insertSampleRestaurants() throws Exception {
    for (String json :
        List.of(
            restaurant("Cyber Dosa Hub", "Gurgaon", "South Indian", 700, 4.5, 28.4946, 77.0887),
            restaurant(
                "Sector 29 Street Bites", "Gurgaon", "Street Food", 400, 4.2, 28.4685, 77.0645),
            restaurant(
                "Connaught Biryani House", "New Delhi", "Biryani", 1200, 4.8, 28.6315, 77.2167),
            restaurant(
                "Galleria Momos",
                "Gurgaon",
                "Chinese\", \"Street Food",
                300,
                3.9,
                28.4672,
                77.0817))) {
      mockMvc.perform(postJson(json)).andExpect(status().isCreated());
    }
  }
}
