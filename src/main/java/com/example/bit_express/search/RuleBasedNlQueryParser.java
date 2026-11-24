package com.example.bit_express.search;

import com.example.bit_express.dto.RestaurantSearchCriteria;
import com.example.bit_express.model.Restaurant;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class RuleBasedNlQueryParser implements NlQueryParser {

    private static final Pattern BUDGET_PATTERN =
            Pattern.compile("(?:under|below|less than|within|max)\\s*(?:rs\\.?|inr|₹|\\$)?\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern RATING_PATTERN =
            Pattern.compile("(?:above|over|at least|min|rating)\\s*(\\d(?:\\.\\d)?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern NEAREST_PATTERN =
            Pattern.compile("\\b(nearest|closest|near me|nearby)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern BEST_PATTERN =
            Pattern.compile("\\b(best|top|highest rated|popular)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CHEAPEST_PATTERN =
            Pattern.compile("\\b(cheapest|budget|affordable|low cost)\\b", Pattern.CASE_INSENSITIVE);

    private static final Map<String, String> DISH_TO_CUISINE = Map.ofEntries(
            Map.entry("vada pav", "Street Food"),
            Map.entry("pav bhaji", "Street Food"),
            Map.entry("chaat", "Street Food"),
            Map.entry("golgappa", "Street Food"),
            Map.entry("dosa", "South Indian"),
            Map.entry("idli", "South Indian"),
            Map.entry("uttapam", "South Indian"),
            Map.entry("biryani", "Biryani"),
            Map.entry("butter chicken", "North Indian"),
            Map.entry("paneer", "North Indian"),
            Map.entry("dim sum", "Chinese"),
            Map.entry("momos", "Chinese"),
            Map.entry("sushi", "Japanese"),
            Map.entry("pasta", "Italian"),
            Map.entry("burger", "Burger")
    );

    private static final Map<String, String> COUNTRY_MAP = Map.ofEntries(
            Map.entry("india", "India"),
            Map.entry("australia", "Australia"),
            Map.entry("brazil", "Brazil"),
            Map.entry("canada", "Canada"),
            Map.entry("indonesia", "Indonesia"),
            Map.entry("new zealand", "New Zealand"),
            Map.entry("philippines", "Philippines"),
            Map.entry("qatar", "Qatar"),
            Map.entry("singapore", "Singapore"),
            Map.entry("south africa", "South Africa"),
            Map.entry("sri lanka", "Sri Lanka"),
            Map.entry("turkey", "Turkey"),
            Map.entry("uae", "UAE"),
            Map.entry("united arab emirates", "UAE"),
            Map.entry("united kingdom", "United Kingdom"),
            Map.entry("uk", "United Kingdom"),
            Map.entry("united states", "United States"),
            Map.entry("usa", "United States")
    );

    private final MongoTemplate mongoTemplate;
    private volatile List<String> cachedCities = List.of();
    private volatile List<String> cachedCuisines = List.of();

    public RuleBasedNlQueryParser(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public void refreshVocabulary() {
        List<String> cities = new ArrayList<>(mongoTemplate.findDistinct("city", Restaurant.class, String.class));
        cities.removeIf(s -> !StringUtils.hasText(s));
        cities.sort(Comparator.comparingInt(String::length).reversed());
        this.cachedCities = List.copyOf(cities);

        List<String> cuisines = new ArrayList<>(mongoTemplate.findDistinct("cuisines", Restaurant.class, String.class));
        cuisines.removeIf(s -> !StringUtils.hasText(s));
        cuisines.sort(Comparator.comparingInt(String::length).reversed());
        this.cachedCuisines = List.copyOf(cuisines);
    }

    @Override
    public ParseResult parse(
            String rawQuery,
            String cityOverride,
            String cuisineOverride,
            Integer maxCostOverride,
            Double minRatingOverride,
            Double latOverride,
            Double lonOverride,
            String sortByOverride,
            String sortDirOverride,
            int page,
            int size
    ) {
        if (cachedCities.isEmpty() || cachedCuisines.isEmpty()) {
            refreshVocabulary();
        }

        String city = cityOverride;
        String country = null;
        String cuisine = cuisineOverride;
        Integer maxCost = maxCostOverride;
        Double minRating = minRatingOverride;
        Double lat = latOverride;
        Double lon = lonOverride;
        String sortBy = sortByOverride;
        String sortDir = sortDirOverride;
        List<String> warnings = new ArrayList<>();
        boolean matchedAnything = false;

        if (StringUtils.hasText(rawQuery)) {
            String lower = rawQuery.toLowerCase(Locale.ROOT).trim();

            if (maxCost == null) {
                Matcher budgetMatcher = BUDGET_PATTERN.matcher(lower);
                if (budgetMatcher.find()) {
                    maxCost = Integer.parseInt(budgetMatcher.group(1));
                    matchedAnything = true;
                }
            }

            if (minRating == null) {
                Matcher ratingMatcher = RATING_PATTERN.matcher(lower);
                if (ratingMatcher.find()) {
                    minRating = Double.parseDouble(ratingMatcher.group(1));
                    matchedAnything = true;
                }
            }

            if (!StringUtils.hasText(city)) {
                for (String knownCity : cachedCities) {
                    if (containsWord(lower, knownCity.toLowerCase(Locale.ROOT))) {
                        city = knownCity;
                        matchedAnything = true;
                        break;
                    }
                }
            }

            if (!StringUtils.hasText(city)) {
                for (Map.Entry<String, String> entry : COUNTRY_MAP.entrySet()) {
                    if (containsWord(lower, entry.getKey())) {
                        country = entry.getValue();
                        matchedAnything = true;
                        break;
                    }
                }
            }

            if (!StringUtils.hasText(cuisine)) {
                for (String knownCuisine : cachedCuisines) {
                    if (containsWord(lower, knownCuisine.toLowerCase(Locale.ROOT))) {
                        cuisine = knownCuisine;
                        matchedAnything = true;
                        break;
                    }
                }
            }

            if (!StringUtils.hasText(cuisine)) {
                for (Map.Entry<String, String> dish : DISH_TO_CUISINE.entrySet()) {
                    if (containsWord(lower, dish.getKey())) {
                        cuisine = dish.getValue();
                        matchedAnything = true;
                        break;
                    }
                }
            }

            if (!StringUtils.hasText(sortBy)) {
                if (NEAREST_PATTERN.matcher(lower).find()) {
                    sortBy = "distance";
                    sortDir = "asc";
                    matchedAnything = true;
                    if (lat == null || lon == null) {
                        lat = 28.4595;
                        lon = 77.0266;
                        warnings.add("No coordinates supplied for proximity search; defaulted to Gurugram center (28.4595, 77.0266).");
                    }
                } else if (CHEAPEST_PATTERN.matcher(lower).find()) {
                    sortBy = "cost";
                    sortDir = "asc";
                    matchedAnything = true;
                } else if (BEST_PATTERN.matcher(lower).find()) {
                    sortBy = "rating";
                    sortDir = "desc";
                    matchedAnything = true;
                }
            }

            if (!matchedAnything) {
                warnings.add("No known city, country, cuisine, dish, or budget filter recognized in query; returning top-rated restaurants.");
            }
        }

        if (!StringUtils.hasText(sortBy)) {
            sortBy = (lat != null && lon != null) ? "distance" : "rating";
        }
        if (!StringUtils.hasText(sortDir)) {
            sortDir = "distance".equalsIgnoreCase(sortBy) || "cost".equalsIgnoreCase(sortBy) ? "asc" : "desc";
        }

        List<String> cuisines = StringUtils.hasText(cuisine) ? List.of(cuisine) : List.of();
        RestaurantSearchCriteria criteria = new RestaurantSearchCriteria(
                null, city, country, cuisines, minRating, maxCost, lat, lon, sortBy, sortDir, page, size
        );

        Map<String, Object> interpreted = new LinkedHashMap<>();
        if (city != null) interpreted.put("city", city);
        if (country != null) interpreted.put("country", country);
        if (cuisine != null) interpreted.put("cuisine", cuisine);
        if (maxCost != null) interpreted.put("maxCostForTwo", maxCost);
        if (minRating != null) interpreted.put("minRating", minRating);
        if (lat != null && lon != null) {
            interpreted.put("lat", lat);
            interpreted.put("lon", lon);
        }
        interpreted.put("sortBy", sortBy);
        interpreted.put("sortDir", sortDir);

        return new ParseResult(criteria, interpreted, warnings);
    }

    private boolean containsWord(String text, String term) {
        return Pattern.compile("(?<!\\w)" + Pattern.quote(term) + "(?!\\w)").matcher(text).find();
    }
}