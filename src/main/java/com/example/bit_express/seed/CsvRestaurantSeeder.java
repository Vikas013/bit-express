package com.example.bit_express.seed;

import com.example.bit_express.model.Restaurant;
import com.example.bit_express.repository.RestaurantRepository;
import com.example.bit_express.search.RuleBasedNlQueryParser;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class CsvRestaurantSeeder implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(CsvRestaurantSeeder.class);

  private static final String NOT_RATED = "Not rated";

  private record Country(String name, String isoCode) {}

  private static final Map<Integer, Country> ZOMATO_COUNTRIES =
      Map.ofEntries(
          Map.entry(1, new Country("India", "IN")),
          Map.entry(14, new Country("Australia", "AU")),
          Map.entry(30, new Country("Brazil", "BR")),
          Map.entry(37, new Country("Canada", "CA")),
          Map.entry(94, new Country("Indonesia", "ID")),
          Map.entry(148, new Country("New Zealand", "NZ")),
          Map.entry(162, new Country("Philippines", "PH")),
          Map.entry(166, new Country("Qatar", "QA")),
          Map.entry(184, new Country("Singapore", "SG")),
          Map.entry(189, new Country("South Africa", "ZA")),
          Map.entry(191, new Country("Sri Lanka", "LK")),
          Map.entry(208, new Country("Turkey", "TR")),
          Map.entry(214, new Country("UAE", "AE")),
          Map.entry(215, new Country("United Kingdom", "GB")),
          Map.entry(216, new Country("United States", "US")));

  private final RestaurantRepository restaurantRepository;
  private final RuleBasedNlQueryParser nlQueryParser;

  @Value("${app.seed.enabled:true}")
  private boolean seedEnabled;

  @Value("${app.seed.resource:zomato.csv}")
  private String seedResource;

  @Value("${app.default-tenant}")
  private String tenantId;

  public CsvRestaurantSeeder(
      RestaurantRepository restaurantRepository, RuleBasedNlQueryParser nlQueryParser) {
    this.restaurantRepository = restaurantRepository;
    this.nlQueryParser = nlQueryParser;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!seedEnabled) {
      log.info("CSV seeding disabled via app.seed.enabled=false");
      return;
    }

    if (restaurantRepository.count() > 0) {
      log.info(
          "Restaurants collection already populated; skipping CSV seed and refreshing NLP vocabulary");
      nlQueryParser.refreshVocabulary();
      return;
    }

    ClassPathResource resource = new ClassPathResource(seedResource);
    if (!resource.exists()) {
      log.warn("Seed resource '{}' not found on classpath; skipping CSV seed", seedResource);
      return;
    }

    List<Restaurant> batch = new ArrayList<>(500);
    int inserted = 0;

    CSVFormat format =
        CSVFormat.DEFAULT
            .builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setIgnoreEmptyLines(true)
            .build();

    try (Reader reader =
            new InputStreamReader(resource.getInputStream(), StandardCharsets.ISO_8859_1);
        CSVParser parser = new CSVParser(reader, format)) {
      for (CSVRecord record : parser) {
        Restaurant restaurant = mapRecord(record);
        if (restaurant != null) {
          batch.add(restaurant);
        }
        if (batch.size() >= 500) {
          restaurantRepository.saveAll(batch);
          inserted += batch.size();
          batch.clear();
        }
      }
      if (!batch.isEmpty()) {
        restaurantRepository.saveAll(batch);
        inserted += batch.size();
      }
      log.info("Seeded {} restaurants from '{}'", inserted, seedResource);
      nlQueryParser.refreshVocabulary();
    } catch (Exception ex) {
      log.error("Failed to seed restaurants from '{}'", seedResource, ex);
    }
  }

  private Restaurant mapRecord(CSVRecord record) {
    String name = getOptional(record, "Restaurant Name");
    String city = getOptional(record, "City");
    if (!StringUtils.hasText(name) || !StringUtils.hasText(city)) {
      return null;
    }

    Restaurant r = new Restaurant();
    r.setTenantId(tenantId);
    r.setExternalRestaurantId(parseLong(getOptional(record, "Restaurant ID")));
    r.setName(name.trim());
    r.setCity(city.trim());
    r.setAddress(getOptional(record, "Address"));
    r.setLocality(getOptional(record, "Locality"));

    Integer zomatoCountryCode = parseInt(getOptional(record, "Country Code"));
    Country country = zomatoCountryCode == null ? null : ZOMATO_COUNTRIES.get(zomatoCountryCode);
    if (country != null) {
      r.setCountry(country.name());
      r.setCountryCode(country.isoCode());
      r.setCurrency(Currency.getInstance(Locale.of("", country.isoCode())).getCurrencyCode());
    }

    String rawCuisines = getOptional(record, "Cuisines");
    r.setCuisines(
        rawCuisines == null
            ? List.of()
            : Arrays.stream(rawCuisines.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .distinct()
                .toList());

    r.setAverageCostForTwo(parseInt(getOptional(record, "Average Cost for two")));
    r.setPriceRange(parseInt(getOptional(record, "Price range")));
    String ratingText = getOptional(record, "Rating text");
    r.setRatingText(ratingText);
    if (!NOT_RATED.equalsIgnoreCase(ratingText)) {
      r.setAggregateRating(parseDouble(getOptional(record, "Aggregate rating")));
    }
    r.setVotes(parseInt(getOptional(record, "Votes")));
    r.setHasOnlineDelivery(parseYesNo(getOptional(record, "Has Online delivery")));
    r.setHasTableBooking(parseYesNo(getOptional(record, "Has Table booking")));

    Double lat = parseDouble(getOptional(record, "Latitude"));
    Double lon = parseDouble(getOptional(record, "Longitude"));
    if (isValidCoordinate(lat, lon)) {
      r.setLocation(new GeoJsonPoint(lon, lat));
    }

    return r;
  }

  private boolean isValidCoordinate(Double lat, Double lon) {
    if (lat == null || lon == null) {
      return false;
    }
    if (Math.abs(lat) < 1e-6 && Math.abs(lon) < 1e-6) {
      return false;
    }
    return lat >= -90.0 && lat <= 90.0 && lon >= -180.0 && lon <= 180.0;
  }

  private String getOptional(CSVRecord record, String column) {
    try {
      return record.isMapped(column) ? record.get(column) : null;
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  private Long parseLong(String val) {
    if (!StringUtils.hasText(val)) return null;
    try {
      return Long.parseLong(val.trim());
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private Integer parseInt(String val) {
    if (!StringUtils.hasText(val)) return null;
    try {
      return Integer.parseInt(val.trim().split("\\.")[0]);
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private Double parseDouble(String val) {
    if (!StringUtils.hasText(val)) return null;
    try {
      return Double.parseDouble(val.trim());
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private Boolean parseYesNo(String val) {
    if (!StringUtils.hasText(val)) return null;
    String v = val.trim().toLowerCase();
    if ("yes".equals(v) || "true".equals(v) || "1".equals(v)) return true;
    if ("no".equals(v) || "false".equals(v) || "0".equals(v)) return false;
    return null;
  }
}
