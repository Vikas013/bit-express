# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

BiteExpress (`bit-express`): a Spring Boot 4.2 / Java 21 REST API for restaurant lookup and search, backed by MongoDB and seeded from the Zomato dataset (`src/main/resources/zomato.csv`). Base package is `com.example.bit_express` (underscore, since `bit-express` is not a valid package name).

## Commands

```bash
./mvnw spring-boot:run                                  # run locally, needs Mongo on 127.0.0.1:27017
docker compose up mongodb -d                            # just Mongo for local dev
docker compose up --build                               # Mongo + app on :8080
./mvnw test                                             # all tests
./mvnw test -Dtest=RestaurantApiIntegrationTest         # one class
./mvnw test -Dtest=RestaurantApiIntegrationTest#createRestaurant_withInvalidLatitude_returns400   # one method
./mvnw package -DskipTests                              # build jar in target/
```

Test requirements:
- `RestaurantApiIntegrationTest` uses Testcontainers (`mongo:8.0`), so Docker must be running. Use the Boot-managed Testcontainers 2.x (artifacts `testcontainers-junit-jupiter`, `testcontainers-mongodb`, class `org.testcontainers.mongodb.MongoDBContainer`); do not pin `testcontainers.version`, since 1.x fails against Docker 29 with "client version 1.32 is too old". It disables seeding (`app.seed.enabled=false`) and wipes the `restaurants` collection before each test.
- `BitExpressApplicationTests` is a plain `@SpringBootTest` with no container: it connects to the default `mongodb://127.0.0.1:27017/biteexpress` and will run the CSV seeder against it.

## Configuration

`application.yml` reads env vars: `SPRING_MONGODB_URI` (sets both `spring.mongodb.uri` and `spring.data.mongodb.uri`; Boot 4 uses the former), `APP_SEED_ENABLED`, `APP_SEED_RESOURCE`. `auto-index-creation` is on, so indexes are declared on `model/Restaurant.java`.

## Architecture

Single collection `restaurants`, single entity `Restaurant`. Request flow: `RestaurantController` (`/v1/restaurants`) to `RestaurantService` to `MongoTemplate` aggregation.

Endpoints:
- `POST /v1/restaurants`: validated by `CreateRestaurantRequest` bean-validation annotations, returns 201 + `Location`.
- `GET /v1/restaurants/{id}`: looks up Mongo `_id` first, then falls back to `externalRestaurantId` (the Zomato numeric id) if the path is numeric.
- `GET /v1/restaurants`: structured filters (`name, city, country, cuisines, minRating, maxCostForTwo, lat, lon, sortBy, sortDir, page, size`).
- `GET /v1/restaurants/search?q=...`: natural-language search. Explicit query params override anything parsed from `q`. Response adds `query`, `interpretedFilters`, `warnings`.

Key cross-file behavior:
- **One query path.** Both list and search build a `RestaurantSearchCriteria` and go through `RestaurantService.executeCriteria`, which builds an aggregation: `$geoNear` (when lat+lon present, distance in `distanceMeters`, converted to `distanceKm`) or `$match`, then optional `$sort`, then a `$facet` for page items plus total count. `size` is clamped to 1..100. Results are mapped from raw `Document`s via `fromDocument`, not the entity, so new response fields must be added there as well as in `toResponse`.
- **Normalized shadow fields.** `Restaurant.setCity` / `setCuisines` also populate `cityNormalized` / `cuisinesNormalized` (lowercased). Filters match on the normalized field OR a case-insensitive regex on the original. Always go through these setters.
- **Location.** `location` is a `GeoJsonPoint` with a 2dsphere index, stored as `[longitude, latitude]`. Coordinates of (0,0) are treated as missing during seeding.
- **NL parsing.** `search/RuleBasedNlQueryParser` (behind the `NlQueryParser` interface) is regex and dictionary based, no LLM. It extracts budget, min rating, city, country, cuisine, dish-to-cuisine aliases (e.g. "vada pav" to "Street Food"), and sort intent (nearest/cheapest/best). City and cuisine vocabularies are cached from `findDistinct` on Mongo, sorted longest-first so multi-word names win. "Nearest" with no coordinates defaults to Gurugram center and adds a warning.
- **Seeding.** `seed/CsvRestaurantSeeder` is an `ApplicationRunner`: if enabled and the collection is empty, it streams the CSV (ISO-8859-1) in batches of 500, maps Zomato country codes to names, then calls `refreshVocabulary()` on the parser. If the collection is already populated it only refreshes the vocabulary.
- **Errors.** `config/GlobalExceptionHandler` returns RFC 7807 `ProblemDetail` for validation errors (400), `ResponseStatusException`, and `IllegalArgumentException` (400).
- **Tenancy placeholder.** `tenantId` defaults to `"bite-express"` and leads the compound indexes, but no query filters on it yet.
