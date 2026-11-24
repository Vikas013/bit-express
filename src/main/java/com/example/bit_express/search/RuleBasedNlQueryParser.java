package com.example.bit_express.search;

import com.example.bit_express.dto.RestaurantQueryParams;
import com.example.bit_express.model.Restaurant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class RuleBasedNlQueryParser implements NlQueryParser {

  static final double DEFAULT_LAT = 28.4595;
  static final double DEFAULT_LON = 77.0266;

  private static final Pattern BUDGET_PATTERN =
      Pattern.compile("(?:under|below|less than|within|max)\\s*(?:rs\\.?|inr|₹|\\$)?\\s*(\\d+)");
  private static final Pattern RATING_PATTERN =
      Pattern.compile("(?:above|over|at least|min|rating)\\s*(\\d(?:\\.\\d)?)\\b");
  private static final Pattern NEAREST_PATTERN =
      Pattern.compile("\\b(nearest|closest|near me|nearby)\\b");
  private static final Pattern BEST_PATTERN =
      Pattern.compile("\\b(best|top|highest rated|popular)\\b");
  private static final Pattern CHEAPEST_PATTERN =
      Pattern.compile("\\b(cheapest|budget|affordable|low cost)\\b");

  private static final Map<String, String> PLACE_ALIASES =
      Map.of(
          "gurugram", "gurgaon",
          "bengaluru", "bangalore",
          "bombay", "mumbai",
          "madras", "chennai",
          "calcutta", "kolkata",
          "uk", "united kingdom",
          "usa", "united states",
          "united arab emirates", "uae");

  private static final Map<String, String> DISH_TO_CUISINE =
      Map.ofEntries(
          Map.entry("vada pav", "Street Food"),
          Map.entry("pav bhaji", "Street Food"),
          Map.entry("chole bhature", "Street Food"),
          Map.entry("chaat", "Street Food"),
          Map.entry("golgappa", "Street Food"),
          Map.entry("dosa", "South Indian"),
          Map.entry("idli", "South Indian"),
          Map.entry("uttapam", "South Indian"),
          Map.entry("butter chicken", "North Indian"),
          Map.entry("paneer", "North Indian"),
          Map.entry("momos", "Chinese"),
          Map.entry("pasta", "Italian"));

  private final MongoOperations mongoOperations;
  private volatile List<String> cachedCities = List.of();
  private volatile List<String> cachedCuisines = List.of();
  private volatile List<String> cachedCountries = List.of();

  public RuleBasedNlQueryParser(MongoOperations mongoOperations) {
    this.mongoOperations = mongoOperations;
  }

  public void refreshVocabulary() {
    cachedCities = distinctLongestFirst("city");
    cachedCuisines = distinctLongestFirst("cuisines");
    cachedCountries = distinctLongestFirst("country");
  }

  private List<String> distinctLongestFirst(String field) {
    List<String> values =
        new ArrayList<>(mongoOperations.findDistinct(field, Restaurant.class, String.class));
    values.removeIf(v -> !StringUtils.hasText(v));
    values.sort(Comparator.comparingInt(String::length).reversed());
    return List.copyOf(values);
  }

  @Override
  public ParseResult parse(String query, RestaurantQueryParams explicit) {
    if (cachedCities.isEmpty() || cachedCuisines.isEmpty()) {
      refreshVocabulary();
    }

    String city = explicit.city();
    String country = explicit.country();
    List<String> cuisines = explicit.cuisines();
    Integer maxCost = explicit.maxCostForTwo();
    Double minRating = explicit.minRating();
    Double lat = explicit.lat();
    Double lon = explicit.lon();
    String sortBy = explicit.sortBy();
    String sortDir = explicit.sortDir();
    List<String> warnings = new ArrayList<>();

    if (StringUtils.hasText(query)) {
      String text = withPlaceAliases(query.toLowerCase(Locale.ROOT).trim());
      boolean matched = false;

      if (maxCost == null) {
        maxCost = firstNumber(BUDGET_PATTERN, text).map(Integer::parseInt).orElse(null);
        matched |= maxCost != null;
      }
      if (minRating == null) {
        minRating = firstNumber(RATING_PATTERN, text).map(Double::parseDouble).orElse(null);
        matched |= minRating != null;
      }
      if (!StringUtils.hasText(city)) {
        city = findTerm(text, cachedCities).orElse(null);
        matched |= city != null;
      }
      if (!StringUtils.hasText(city) && !StringUtils.hasText(country)) {
        country = findTerm(text, cachedCountries).orElse(null);
        matched |= country != null;
      }
      if (cuisines == null || cuisines.isEmpty()) {
        cuisines =
            findTerm(text, cachedCuisines)
                .or(() -> findTerm(text, DISH_TO_CUISINE))
                .map(List::of)
                .orElse(null);
        matched |= cuisines != null;
      }
      if (!StringUtils.hasText(sortBy)) {
        if (NEAREST_PATTERN.matcher(text).find()) {
          sortBy = "distance";
          if (lat == null || lon == null) {
            lat = DEFAULT_LAT;
            lon = DEFAULT_LON;
            warnings.add(
                "No coordinates supplied for a proximity search; using Gurugram city centre ("
                    + DEFAULT_LAT
                    + ", "
                    + DEFAULT_LON
                    + ").");
          }
        } else if (CHEAPEST_PATTERN.matcher(text).find()) {
          sortBy = "cost";
          sortDir = "asc";
        } else if (BEST_PATTERN.matcher(text).find()) {
          sortBy = "rating";
          sortDir = "desc";
        }
        matched |= sortBy != null;
      }
      if (!matched) {
        warnings.add(
            "No city, country, cuisine, dish, budget or rating recognised in the query; returning"
                + " top-rated restaurants.");
      }
    }

    RestaurantQueryParams params =
        new RestaurantQueryParams(
            explicit.name(),
            city,
            country,
            cuisines,
            minRating,
            maxCost,
            lat,
            lon,
            explicit.maxDistanceKm(),
            sortBy,
            sortDir,
            explicit.page(),
            explicit.size());
    return new ParseResult(params, warnings);
  }

  private static String withPlaceAliases(String text) {
    for (Map.Entry<String, String> alias : PLACE_ALIASES.entrySet()) {
      text = wordPattern(alias.getKey()).matcher(text).replaceAll(alias.getValue());
    }
    return text;
  }

  private static Optional<String> firstNumber(Pattern pattern, String text) {
    Matcher m = pattern.matcher(text);
    return m.find() ? Optional.of(m.group(1)) : Optional.empty();
  }

  private static Optional<String> findTerm(String text, List<String> vocabulary) {
    return vocabulary.stream()
        .filter(term -> wordPattern(term.toLowerCase(Locale.ROOT)).matcher(text).find())
        .findFirst();
  }

  private static Optional<String> findTerm(String text, Map<String, String> synonyms) {
    return synonyms.entrySet().stream()
        .filter(e -> wordPattern(e.getKey()).matcher(text).find())
        .map(Map.Entry::getValue)
        .findFirst();
  }

  private static Pattern wordPattern(String term) {
    return Pattern.compile("(?<!\\w)" + Pattern.quote(term) + "(?!\\w)");
  }
}
