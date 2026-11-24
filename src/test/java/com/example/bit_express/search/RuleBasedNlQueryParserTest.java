package com.example.bit_express.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.bit_express.dto.RestaurantQueryParams;
import com.example.bit_express.model.Restaurant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoOperations;

class RuleBasedNlQueryParserTest {

  private static final RestaurantQueryParams NONE =
      new RestaurantQueryParams(
          null, null, null, null, null, null, null, null, null, null, null, null, null);

  private RuleBasedNlQueryParser parser;

  @BeforeEach
  void setUp() {
    MongoOperations mongo = mock(MongoOperations.class);
    when(mongo.findDistinct("city", Restaurant.class, String.class))
        .thenReturn(List.of("Gurgaon", "New Delhi", "Noida", "Bangalore"));
    when(mongo.findDistinct("cuisines", Restaurant.class, String.class))
        .thenReturn(List.of("South Indian", "North Indian", "Street Food", "Chinese", "Indian"));
    parser = new RuleBasedNlQueryParser(mongo);
  }

  private NlQueryParser.ParseResult parse(String query) {
    return parser.parse(query, NONE);
  }

  @Test
  void bestRestaurantsInGurgaonWithSouthIndianFood() {
    RestaurantQueryParams p = parse("Best restaurants in Gurgaon with South Indian Food").params();
    assertThat(p.city()).isEqualTo("Gurgaon");
    assertThat(p.cuisines()).containsExactly("South Indian");
    assertThat(p.sortBy()).isEqualTo("rating");
    assertThat(p.sortDir()).isEqualTo("desc");
  }

  @Test
  void restaurantsInGurgaonUnderRs1000ForTwo() {
    RestaurantQueryParams p = parse("Restaurants in Gurgaon under Rs. 1000 for two").params();
    assertThat(p.city()).isEqualTo("Gurgaon");
    assertThat(p.maxCostForTwo()).isEqualTo(1000);
    assertThat(p.minRating()).isNull();
  }

  @Test
  void nearestPlaceThatServesVadaPavUsesDishAliasAndDefaultLocation() {
    NlQueryParser.ParseResult result = parse("Nearest place that serves Vada Pav");
    assertThat(result.params().cuisines()).containsExactly("Street Food");
    assertThat(result.params().sortBy()).isEqualTo("distance");
    assertThat(result.params().lat()).isEqualTo(RuleBasedNlQueryParser.DEFAULT_LAT);
    assertThat(result.params().lon()).isEqualTo(RuleBasedNlQueryParser.DEFAULT_LON);
    assertThat(result.warnings()).singleElement().asString().contains("No coordinates");
  }

  @Test
  void nearestWithCoordinatesKeepsThemAndDoesNotWarn() {
    RestaurantQueryParams explicit =
        new RestaurantQueryParams(
            null, null, null, null, null, null, 19.07, 72.87, null, null, null, null, null);
    NlQueryParser.ParseResult result = parser.parse("nearest dosa", explicit);
    assertThat(result.params().lat()).isEqualTo(19.07);
    assertThat(result.warnings()).isEmpty();
  }

  @Test
  void gurugramAndBengaluruResolveToDatasetCityNames() {
    assertThat(parse("dinner in Gurugram").params().city()).isEqualTo("Gurgaon");
    assertThat(parse("cafes in bengaluru").params().city()).isEqualTo("Bangalore");
  }

  @Test
  void longestCityAndCuisineWin() {
    RestaurantQueryParams p = parse("north indian in new delhi").params();
    assertThat(p.city()).isEqualTo("New Delhi");
    assertThat(p.cuisines()).containsExactly("North Indian");
  }

  @Test
  void rupeeSymbolBudget() {
    assertThat(parse("lunch under ₹500").params().maxCostForTwo()).isEqualTo(500);
  }

  @Test
  void minimumRating() {
    assertThat(parse("places rated above 4.2 in Noida").params().minRating()).isEqualTo(4.2);
  }

  @Test
  void cheapestSortsByCostAscending() {
    RestaurantQueryParams p = parse("cheapest food in Noida").params();
    assertThat(p.sortBy()).isEqualTo("cost");
    assertThat(p.sortDir()).isEqualTo("asc");
  }

  @Test
  void countryWhenNoCityMatches() {
    RestaurantQueryParams p = parse("restaurants in the UK").params();
    assertThat(p.city()).isNull();
    assertThat(p.country()).isEqualTo("United Kingdom");
  }

  @Test
  void unrecognisedQueryWarnsAndLeavesFiltersEmpty() {
    NlQueryParser.ParseResult result = parse("something tasty");
    assertThat(result.params().city()).isNull();
    assertThat(result.params().cuisines()).isNull();
    assertThat(result.warnings()).singleElement().asString().contains("No city");
  }

  @Test
  void explicitParamsOverrideParsedValues() {
    RestaurantQueryParams explicit =
        new RestaurantQueryParams(
            "dhaba",
            "Noida",
            null,
            List.of("Chinese"),
            3.5,
            800,
            null,
            null,
            null,
            "name",
            "asc",
            2,
            5);
    RestaurantQueryParams p =
        parser.parse("best south indian in gurgaon under 1000 above 4", explicit).params();
    assertThat(p)
        .isEqualTo(
            new RestaurantQueryParams(
                "dhaba",
                "Noida",
                null,
                List.of("Chinese"),
                3.5,
                800,
                null,
                null,
                null,
                "name",
                "asc",
                2,
                5));
  }

  @Test
  void blankQueryPassesExplicitParamsThrough() {
    NlQueryParser.ParseResult result = parser.parse("  ", NONE);
    assertThat(result.params()).isEqualTo(NONE);
    assertThat(result.warnings()).isEmpty();
  }
}
