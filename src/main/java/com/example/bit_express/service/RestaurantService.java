package com.example.bit_express.service;

import com.example.bit_express.dto.CreateRestaurantRequest;
import com.example.bit_express.dto.PagedRestaurantResponse;
import com.example.bit_express.dto.RestaurantResponse;
import com.example.bit_express.dto.RestaurantSearchCriteria;
import com.example.bit_express.model.Restaurant;
import com.example.bit_express.repository.RestaurantRepository;
import com.example.bit_express.search.NlQueryParser;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.aggregation.FacetOperation;
import org.springframework.data.mongodb.core.aggregation.GeoNearOperation;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.NearQuery;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class RestaurantService {

    private final RestaurantRepository restaurantRepository;
    private final MongoTemplate mongoTemplate;
    private final NlQueryParser nlQueryParser;

    public RestaurantService(
            RestaurantRepository restaurantRepository,
            MongoTemplate mongoTemplate,
            NlQueryParser nlQueryParser
    ) {
        this.restaurantRepository = restaurantRepository;
        this.mongoTemplate = mongoTemplate;
        this.nlQueryParser = nlQueryParser;
    }

    public RestaurantResponse create(CreateRestaurantRequest req) {
        Restaurant r = new Restaurant();
        r.setName(req.name().trim());
        r.setCity(req.city().trim());
        r.setAddress(req.address().trim());
        r.setCountry(StringUtils.hasText(req.country()) ? req.country().trim() : null);

        List<String> cleanedCuisines = req.cuisines().stream()
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
        r.setCuisines(cleanedCuisines);

        r.setAverageCostForTwo(req.averageCostForTwo());
        r.setCurrency(StringUtils.hasText(req.currency()) ? req.currency().trim() : "INR");
        r.setAggregateRating(req.aggregateRating() != null ? req.aggregateRating() : 0.0);
        r.setHasOnlineDelivery(req.hasOnlineDelivery() != null ? req.hasOnlineDelivery() : false);
        r.setHasTableBooking(req.hasTableBooking() != null ? req.hasTableBooking() : false);

        if (req.latitude() != null && req.longitude() != null) {
            r.setLocation(new GeoJsonPoint(req.longitude(), req.latitude()));
        }

        Restaurant saved = restaurantRepository.save(r);
        return toResponse(saved, null);
    }

    public RestaurantResponse getById(String id) {
        return restaurantRepository.findById(id)
                .map(r -> toResponse(r, null))
                .orElseGet(() -> {
                    try {
                        long numericId = Long.parseLong(id);
                        return restaurantRepository.findByExternalRestaurantId(numericId)
                                .map(r -> toResponse(r, null))
                                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Restaurant not found: " + id));
                    } catch (NumberFormatException ex) {
                        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Restaurant not found: " + id);
                    }
                });
    }

    public PagedRestaurantResponse list(RestaurantSearchCriteria criteria) {
        return executeCriteria(null, criteria, Map.of(), List.of());
    }

    public PagedRestaurantResponse searchNaturalLanguage(
            String q,
            String city,
            String cuisine,
            Integer maxCostForTwo,
            Double minRating,
            Double lat,
            Double lon,
            String sortBy,
            String sortDir,
            int page,
            int size
    ) {
        NlQueryParser.ParseResult parsed = nlQueryParser.parse(
                q, city, cuisine, maxCostForTwo, minRating, lat, lon, sortBy, sortDir, page, size
        );
        return executeCriteria(q, parsed.criteria(), parsed.interpretedFilters(), parsed.warnings());
    }

    private PagedRestaurantResponse executeCriteria(
            String rawQuery,
            RestaurantSearchCriteria criteria,
            Map<String, Object> interpretedFilters,
            List<String> warnings
    ) {
        int safePage = Math.max(0, criteria.page());
        int safeSize = Math.min(100, Math.max(1, criteria.size()));
        long skip = (long) safePage * safeSize;

        Criteria filterCriteria = buildFilterCriteria(criteria);
        Sort sortSpec = buildSortSpec(criteria);

        List<AggregationOperation> pipeline = new ArrayList<>();

        if (criteria.hasGeo()) {
            NearQuery nearQuery = NearQuery.near(new GeoJsonPoint(criteria.longitude(), criteria.latitude()))
                    .spherical(true);
            if (!filterCriteria.getCriteriaObject().isEmpty()) {
                nearQuery.query(org.springframework.data.mongodb.core.query.Query.query(filterCriteria));
            }
            GeoNearOperation geoNear = Aggregation.geoNear(nearQuery, "distanceMeters");
            pipeline.add(geoNear);
        } else if (!filterCriteria.getCriteriaObject().isEmpty()) {
            pipeline.add(Aggregation.match(filterCriteria));
        }

        if (!criteria.hasGeo() && !sortSpec.isUnsorted()) {
            pipeline.add(Aggregation.sort(sortSpec));
        } else if (criteria.hasGeo() && !"distance".equalsIgnoreCase(criteria.sortBy()) && !sortSpec.isUnsorted()) {
            pipeline.add(Aggregation.sort(sortSpec));
        }

        FacetOperation facet = Aggregation.facet(
                Aggregation.skip(skip),
                Aggregation.limit(safeSize)
        ).as("items").and(
                Aggregation.count().as("total")
        ).as("meta");

        pipeline.add(facet);

        AggregationResults<Document> results = mongoTemplate.aggregate(
                Aggregation.newAggregation(pipeline),
                Restaurant.class,
                Document.class
        );

        Document root = results.getUniqueMappedResult();
        List<RestaurantResponse> items = new ArrayList<>();
        long totalElements = 0;

        if (root != null) {
            List<Document> metaDocs = root.getList("meta", Document.class, List.of());
            if (!metaDocs.isEmpty() && metaDocs.get(0).get("total") instanceof Number totalNum) {
                totalElements = totalNum.longValue();
            }

            List<Document> rawItems = root.getList("items", Document.class, List.of());
            for (Document doc : rawItems) {
                Double distMeters = doc.get("distanceMeters") instanceof Number num ? num.doubleValue() : null;
                Double distKm = distMeters != null ? Math.round((distMeters / 1000.0) * 100.0) / 100.0 : null;
                items.add(fromDocument(doc, distKm));
            }
        }

        int totalPages = totalElements == 0 ? 0 : (int) Math.ceil((double) totalElements / safeSize);

        return new PagedRestaurantResponse(
                rawQuery,
                interpretedFilters.isEmpty() ? null : interpretedFilters,
                warnings.isEmpty() ? null : warnings,
                safePage,
                safeSize,
                totalElements,
                totalPages,
                items
        );
    }

    private Criteria buildFilterCriteria(RestaurantSearchCriteria criteria) {
        List<Criteria> clauses = new ArrayList<>();

        if (StringUtils.hasText(criteria.name())) {
            clauses.add(Criteria.where("name").regex(Pattern.quote(criteria.name().trim()), "i"));
        }
        if (StringUtils.hasText(criteria.city())) {
            String normCity = criteria.city().trim().toLowerCase(Locale.ROOT);
            clauses.add(new Criteria().orOperator(
                    Criteria.where("cityNormalized").is(normCity),
                    Criteria.where("city").regex("^" + Pattern.quote(criteria.city().trim()) + "$", "i")
            ));
        }
        if (StringUtils.hasText(criteria.country())) {
            clauses.add(Criteria.where("country").regex("^" + Pattern.quote(criteria.country().trim()) + "$", "i"));
        }
        if (criteria.cuisines() != null && !criteria.cuisines().isEmpty()) {
            List<Criteria> cuisineClauses = new ArrayList<>();
            for (String c : criteria.cuisines()) {
                if (StringUtils.hasText(c)) {
                    String norm = c.trim().toLowerCase(Locale.ROOT);
                    cuisineClauses.add(new Criteria().orOperator(
                            Criteria.where("cuisinesNormalized").in(norm),
                            Criteria.where("cuisines").regex(Pattern.quote(c.trim()), "i")
                    ));
                }
            }
            if (!cuisineClauses.isEmpty()) {
                clauses.add(new Criteria().andOperator(cuisineClauses));
            }
        }
        if (criteria.minRating() != null) {
            clauses.add(Criteria.where("aggregateRating").gte(criteria.minRating()));
        }
        if (criteria.maxCostForTwo() != null) {
            clauses.add(Criteria.where("averageCostForTwo").lte(criteria.maxCostForTwo()));
        }

        if (clauses.isEmpty()) {
            return new Criteria();
        }
        return new Criteria().andOperator(clauses);
    }

    private Sort buildSortSpec(RestaurantSearchCriteria criteria) {
        Sort.Direction direction = "asc".equalsIgnoreCase(criteria.sortDir()) ? Sort.Direction.ASC : Sort.Direction.DESC;
        String sortBy = StringUtils.hasText(criteria.sortBy()) ? criteria.sortBy().toLowerCase(Locale.ROOT) : "rating";

        return switch (sortBy) {
            case "cost" -> Sort.by(direction, "averageCostForTwo");
            case "name" -> Sort.by(direction, "name");
            case "distance" -> Sort.unsorted(); // $geoNear orders by distance ascending by default
            default -> Sort.by(direction, "aggregateRating");
        };
    }

    private RestaurantResponse toResponse(Restaurant r, Double distanceKm) {
        return new RestaurantResponse(
                r.getId(),
                r.getExternalRestaurantId(),
                r.getName(),
                r.getAddress(),
                r.getCity(),
                r.getCountry(),
                r.getCuisines(),
                r.getAggregateRating(),
                r.getAverageCostForTwo(),
                r.getCurrency(),
                r.getHasOnlineDelivery(),
                distanceKm
        );
    }

    @SuppressWarnings("unchecked")
    private RestaurantResponse fromDocument(Document doc, Double distanceKm) {
        Object idObj = doc.get("_id");
        String id = idObj != null ? idObj.toString() : null;
        Long extId = doc.get("externalRestaurantId") instanceof Number n ? n.longValue() : null;
        List<String> cuisines = doc.get("cuisines") instanceof List<?> list
                ? list.stream().map(Object::toString).toList()
                : List.of();
        Double rating = doc.get("aggregateRating") instanceof Number n ? n.doubleValue() : null;
        Integer cost = doc.get("averageCostForTwo") instanceof Number n ? n.intValue() : null;

        return new RestaurantResponse(
                id,
                extId,
                doc.getString("name"),
                doc.getString("address"),
                doc.getString("city"),
                doc.getString("country"),
                cuisines,
                rating,
                cost,
                doc.getString("currency"),
                doc.getBoolean("hasOnlineDelivery"),
                distanceKm
        );
    }
}