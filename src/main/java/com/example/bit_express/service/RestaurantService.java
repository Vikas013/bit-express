package com.example.bit_express.service;

import com.example.bit_express.dto.CreateRestaurantRequest;
import com.example.bit_express.dto.PagedRestaurantResponse;
import com.example.bit_express.dto.RestaurantQueryParams;
import com.example.bit_express.dto.RestaurantResponse;
import com.example.bit_express.model.Restaurant;
import com.example.bit_express.repository.RestaurantRepository;
import com.example.bit_express.search.NlQueryParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.Metrics;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.NearQuery;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RestaurantService {

  static final int DEFAULT_PAGE_SIZE = 10;
  static final int MAX_PAGE_SIZE = 100;
  private static final String DISTANCE_FIELD = "distanceKm";

  private final RestaurantRepository restaurantRepository;
  private final MongoTemplate mongoTemplate;
  private final NlQueryParser nlQueryParser;

  public RestaurantService(
      RestaurantRepository restaurantRepository,
      MongoTemplate mongoTemplate,
      NlQueryParser nlQueryParser) {
    this.restaurantRepository = restaurantRepository;
    this.mongoTemplate = mongoTemplate;
    this.nlQueryParser = nlQueryParser;
  }

  public RestaurantResponse create(String tenantId, CreateRestaurantRequest req) {
    Restaurant r = new Restaurant();
    r.setTenantId(tenantId);
    r.setName(req.name().trim());
    r.setCity(req.city().trim());
    r.setAddress(req.address().trim());
    r.setCountry(StringUtils.hasText(req.country()) ? req.country().trim() : null);
    r.setCuisines(req.cuisines().stream().map(String::trim).filter(StringUtils::hasText).toList());
    r.setAverageCostForTwo(req.averageCostForTwo());
    r.setCurrency(StringUtils.hasText(req.currency()) ? req.currency().trim() : "INR");
    r.setAggregateRating(req.aggregateRating() != null ? req.aggregateRating() : 0.0);
    r.setHasOnlineDelivery(Boolean.TRUE.equals(req.hasOnlineDelivery()));
    r.setHasTableBooking(Boolean.TRUE.equals(req.hasTableBooking()));
    if (req.latitude() != null && req.longitude() != null) {
      r.setLocation(new GeoJsonPoint(req.longitude(), req.latitude()));
    }
    return toResponse(restaurantRepository.save(r), null);
  }

  public RestaurantResponse getById(String tenantId, String id) {
    return restaurantRepository
        .findByIdAndTenantId(id, tenantId)
        .or(() -> findByExternalId(tenantId, id))
        .map(r -> toResponse(r, null))
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Restaurant not found: " + id));
  }

  public PagedRestaurantResponse list(String tenantId, RestaurantQueryParams params) {
    return execute(tenantId, params, null, null, List.of());
  }

  public PagedRestaurantResponse search(
      String tenantId, String query, RestaurantQueryParams explicit) {
    NlQueryParser.ParseResult parsed = nlQueryParser.parse(query, explicit);
    return execute(tenantId, parsed.params(), query, parsed.params(), parsed.warnings());
  }

  private Optional<Restaurant> findByExternalId(String tenantId, String id) {
    try {
      return restaurantRepository.findByExternalRestaurantIdAndTenantId(
          Long.parseLong(id), tenantId);
    } catch (NumberFormatException ex) {
      return Optional.empty();
    }
  }

  private PagedRestaurantResponse execute(
      String tenantId,
      RestaurantQueryParams params,
      String query,
      RestaurantQueryParams interpretedFilters,
      List<String> warnings) {
    validate(params);
    int page = params.page() == null ? 0 : params.page();
    int size = Math.min(MAX_PAGE_SIZE, params.size() == null ? DEFAULT_PAGE_SIZE : params.size());
    String sortBy = sortBy(params);

    Criteria filter = buildFilter(tenantId, params);
    List<AggregationOperation> pipeline = new ArrayList<>();
    if (params.hasGeo()) {
      NearQuery near =
          NearQuery.near(new GeoJsonPoint(params.lon(), params.lat()))
              .spherical(true)
              .inKilometers()
              .query(Query.query(filter));
      if (params.maxDistanceKm() != null) {
        near.maxDistance(new Distance(params.maxDistanceKm(), Metrics.KILOMETERS));
      }
      pipeline.add(Aggregation.geoNear(near, DISTANCE_FIELD));
    } else {
      pipeline.add(Aggregation.match(filter));
    }
    if (!"distance".equals(sortBy)) {
      pipeline.add(Aggregation.sort(buildSort(sortBy, params.sortDir())));
    }
    pipeline.add(
        Aggregation.facet(Aggregation.skip((long) page * size), Aggregation.limit(size))
            .as("items")
            .and(Aggregation.count().as("total"))
            .as("meta"));

    Document root =
        mongoTemplate
            .aggregate(Aggregation.newAggregation(pipeline), Restaurant.class, Document.class)
            .getUniqueMappedResult();

    List<Document> meta = root.getList("meta", Document.class, List.of());
    long total = meta.isEmpty() ? 0 : ((Number) meta.get(0).get("total")).longValue();
    List<RestaurantResponse> items =
        root.getList("items", Document.class, List.of()).stream()
            .map(doc ->
                toResponse(mongoTemplate.getConverter().read(Restaurant.class, doc), km(doc)))
            .toList();

    return new PagedRestaurantResponse(
        query,
        interpretedFilters,
        warnings.isEmpty() ? null : warnings,
        page,
        size,
        total,
        (int) Math.ceil((double) total / size),
        items);
  }

  private void validate(RestaurantQueryParams params) {
    if ((params.lat() == null) != (params.lon() == null)) {
      throw new IllegalArgumentException("lat and lon must be supplied together");
    }
    if (!params.hasGeo() && params.maxDistanceKm() != null) {
      throw new IllegalArgumentException("maxDistanceKm requires lat and lon");
    }
    if (!params.hasGeo() && "distance".equals(sortBy(params))) {
      throw new IllegalArgumentException("sortBy=distance requires lat and lon");
    }
  }

  private static String sortBy(RestaurantQueryParams params) {
    if (StringUtils.hasText(params.sortBy())) {
      return params.sortBy().toLowerCase(Locale.ROOT);
    }
    return params.hasGeo() ? "distance" : "rating";
  }

  private static Sort buildSort(String sortBy, String sortDir) {
    String field =
        switch (sortBy) {
          case "cost" -> "averageCostForTwo";
          case "name" -> "name";
          case "rating" -> "aggregateRating";
          default ->
              throw new IllegalArgumentException(
                  "sortBy must be one of rating, cost, name, distance");
        };
    Sort.Direction defaultDirection =
        "rating".equals(sortBy) ? Sort.Direction.DESC : Sort.Direction.ASC;
    Sort.Direction direction =
        StringUtils.hasText(sortDir) ? Sort.Direction.fromString(sortDir) : defaultDirection;
    return Sort.by(direction, field, "_id");
  }

  private static Criteria buildFilter(String tenantId, RestaurantQueryParams params) {
    List<Criteria> clauses = new ArrayList<>();
    clauses.add(Criteria.where("tenantId").is(tenantId));
    if (StringUtils.hasText(params.city())) {
      clauses.add(Criteria.where("cityNormalized").is(Restaurant.normalize(params.city())));
    }
    if (params.cuisines() != null && !params.cuisines().isEmpty()) {
      List<String> wanted =
          params.cuisines().stream()
              .filter(StringUtils::hasText)
              .map(Restaurant::normalize)
              .toList();
      if (!wanted.isEmpty()) {
        clauses.add(Criteria.where("cuisinesNormalized").all(wanted));
      }
    }
    if (StringUtils.hasText(params.name())) {
      clauses.add(Criteria.where("name").regex(Pattern.quote(params.name().trim()), "i"));
    }
    if (StringUtils.hasText(params.country())) {
      clauses.add(
          Criteria.where("country").regex("^" + Pattern.quote(params.country().trim()) + "$", "i"));
    }
    if (params.minRating() != null) {
      clauses.add(Criteria.where("aggregateRating").gte(params.minRating()));
    }
    if (params.maxCostForTwo() != null) {
      clauses.add(Criteria.where("averageCostForTwo").lte(params.maxCostForTwo()));
    }
    return new Criteria().andOperator(clauses);
  }

  private static Double km(Document doc) {
    return doc.get(DISTANCE_FIELD) instanceof Number km
        ? Math.round(km.doubleValue() * 100.0) / 100.0
        : null;
  }

  private static RestaurantResponse toResponse(Restaurant r, Double distanceKm) {
    GeoJsonPoint location = r.getLocation();
    return new RestaurantResponse(
        r.getId(),
        r.getExternalRestaurantId(),
        r.getName(),
        r.getAddress(),
        r.getLocality(),
        r.getCity(),
        r.getCountry(),
        r.getCuisines(),
        r.getAggregateRating(),
        r.getRatingText(),
        r.getVotes(),
        r.getAverageCostForTwo(),
        r.getCurrency(),
        r.getPriceRange(),
        r.getHasOnlineDelivery(),
        r.getHasTableBooking(),
        location == null ? null : location.getY(),
        location == null ? null : location.getX(),
        distanceKm);
  }
}
