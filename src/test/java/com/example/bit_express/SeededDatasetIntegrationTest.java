package com.example.bit_express;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.Comparator;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class SeededDatasetIntegrationTest {

  private static final int CSV_ROWS = 9551;
  private static final int GURGAON = 1118;
  private static final int GURGAON_SOUTH_INDIAN = 70;
  private static final int GURGAON_UNDER_1000 = 924;
  private static final int STREET_FOOD = 562;

  @DynamicPropertySource
  static void mongo(DynamicPropertyRegistry registry) {
    TestMongo.register(registry, "dataset", true);
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private MongoTemplate mongoTemplate;

  private String getJson(String url) throws Exception {
    return mockMvc
        .perform(get(url))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private static int total(String json) {
    return JsonPath.read(json, "$.totalElements");
  }

  @Test
  void seedsEveryCsvRowWithCleanedFields() throws Exception {
    assertThat(total(getJson("/v1/restaurants?size=1"))).isEqualTo(CSV_ROWS);
    String json = getJson("/v1/restaurants/6317637");
    assertThat(JsonPath.<String>read(json, "$.country")).isEqualTo("Philippines");
    assertThat(JsonPath.<List<String>>read(json, "$.cuisines"))
        .containsExactly("French", "Japanese", "Desserts");
    assertThat(JsonPath.<Integer>read(json, "$.votes")).isEqualTo(314);
    assertThat(JsonPath.<String>read(json, "$.ratingText")).isEqualTo("Excellent");
    assertThat(JsonPath.<Integer>read(json, "$.priceRange")).isEqualTo(3);
  }

  @Test
  void bestRestaurantsInGurgaonWithSouthIndianFood() throws Exception {
    String json =
        getJson(
            "/v1/restaurants/search?size=100&q=Best restaurants in Gurgaon with South Indian Food");
    assertThat(total(json)).isEqualTo(GURGAON_SOUTH_INDIAN);
    assertThat(JsonPath.<List<String>>read(json, "$.results[*].city")).containsOnly("Gurgaon");
    assertThat(JsonPath.<List<List<String>>>read(json, "$.results[*].cuisines"))
        .allSatisfy(cuisines -> assertThat(cuisines).contains("South Indian"));
    assertThat(JsonPath.<List<Double>>read(json, "$.results[*].aggregateRating"))
        .isSortedAccordingTo(Comparator.reverseOrder());
  }

  @Test
  void restaurantsInGurgaonUnderRs1000ForTwo() throws Exception {
    String json =
        getJson("/v1/restaurants/search?size=100&q=Restaurants in Gurgaon under Rs. 1000 for two");
    assertThat(total(json)).isEqualTo(GURGAON_UNDER_1000);
    assertThat(JsonPath.<List<Integer>>read(json, "$.results[*].averageCostForTwo"))
        .allSatisfy(cost -> assertThat(cost).isLessThanOrEqualTo(1000));
  }

  @Test
  void nearestPlaceThatServesVadaPav() throws Exception {
    String json = getJson("/v1/restaurants/search?size=100&q=Nearest place that serves Vada Pav");
    assertThat(JsonPath.<List<List<String>>>read(json, "$.results[*].cuisines"))
        .allSatisfy(cuisines -> assertThat(cuisines).contains("Street Food"));
    assertThat(JsonPath.<List<Double>>read(json, "$.results[*].distanceKm"))
        .hasSize(100)
        .isSorted();
    assertThat(total(json)).isLessThanOrEqualTo(STREET_FOOD);
  }

  @Test
  void gurugramFindsTheGurgaonRestaurants() throws Exception {
    assertThat(total(getJson("/v1/restaurants/search?q=restaurants in Gurugram")))
        .isEqualTo(GURGAON);
  }

  @Test
  void cityListingUsesTheCompoundIndexWithoutAnInMemorySort() throws Exception {
    mongoTemplate.executeCommand(new Document("profile", 2));
    try {
      assertThat(total(getJson("/v1/restaurants?city=Gurgaon"))).isEqualTo(GURGAON);
    } finally {
      mongoTemplate.executeCommand(new Document("profile", 0));
    }

    Document profile =
        mongoTemplate
            .getCollection("system.profile")
            .find(new Document("command.aggregate", "restaurants"))
            .sort(new Document("ts", -1))
            .first();

    assertThat(profile).isNotNull();
    assertThat(profile.getString("planSummary")).isEqualTo("IXSCAN { tenantId: 1, cityNormalized: 1, aggregateRating: -1, _id: -1 }");
    assertThat(profile.get("docsExamined", Number.class).intValue()).isEqualTo(GURGAON);
    assertThat(profile.getBoolean("hasSortStage", false)).isFalse();
  }
}
