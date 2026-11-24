# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

BiteExpress (`bit-express`): a Spring Boot 4.2 / Java 21 REST API for restaurant discovery, backed by MongoDB and seeded from the Zomato dataset (`src/main/resources/zomato.csv`). It is a take-home for a MongoDB Forward Deployed Engineer role, graded on implementation depth, containerization, API test coverage and solid MongoDB usage. `README.md` is the submission document; keep it in sync with behavior. Base package is `com.example.bit_express`.

## Commands

```bash
docker compose up --build                               # Mongo + app on :8080, seeds on first start
MONGO_HOST_PORT=27018 docker compose up --build         # when a local mongod already owns 27017
./mvnw spring-boot:run                                  # needs Mongo on 127.0.0.1:27017
./mvnw test                                             # all tests, needs Docker
./mvnw test -Dtest=RestaurantApiIntegrationTest#listPaginatesAndCapsPageSize   # one method
./mvnw verify                                           # tests + Spotless check
./mvnw spotless:apply                                   # format (google-java-format)
```

Tests share one Testcontainers Mongo (`TestMongo`), using a separate database per test class. Use the Boot-managed Testcontainers 2.x (`testcontainers-junit-jupiter`, `testcontainers-mongodb`, `org.testcontainers.mongodb.MongoDBContainer`); do not pin `testcontainers.version`, since 1.x fails against Docker 29 with "client version 1.32 is too old". Spotless uses its default google-java-format; pinning 1.37.0 crashes on JDK 25.

## Architecture

Request flow: `RestaurantController` (`/v1/restaurants`) to `RestaurantService` to one `MongoTemplate` aggregation.

- **Tenancy.** Every endpoint reads `X-Tenant-Id` (default `app.default-tenant`, `bite-express`). Every read filters on `tenantId` and every write sets it. The seeder writes into the default tenant.
- **One query path.** `GET /v1/restaurants` and `/search` both bind `dto/RestaurantQueryParams` (validated record). Search runs it through `NlQueryParser.parse(q, explicit)` first, where explicit params win, and echoes the result as `interpretedFilters`. `RestaurantService.execute` builds `$geoNear` (when lat/lon, `.inKilometers()`, distance field `distanceKm`) or `$match`, then `$sort` with `_id` tie-breaker, then `$facet` for page + count. Raw documents are converted back with `mongoTemplate.getConverter()` and mapped by the single `toResponse`.
- **Indexes are part of the contract.** Filters use exact matches on `cityNormalized` / `cuisinesNormalized` (lowercased by the entity setters and `Restaurant.normalize`) so the compound indexes on `model/Restaurant.java` apply. Sort index directions match `Sort.by(direction, field, "_id")`. `SeededDatasetIntegrationTest` asserts the Gurgaon listing plan via the Mongo profiler; changing filters, sorts or indexes must keep it green. Only one 2dsphere index may exist or `$geoNear` fails.
- **NL parsing.** `search/RuleBasedNlQueryParser` is regex and dictionary based, no LLM. City, cuisine and country vocabularies come from `findDistinct`, longest first. Only knowledge the data lacks is static: `PLACE_ALIASES` and `DISH_TO_CUISINE` (checked against the data, see README). Do not add a dish that is already a dataset cuisine. The service resolves the default sort before echoing `interpretedFilters`. "Nearest" without coordinates falls back to Gurugram centre with a warning.
- **Seeding.** `seed/CsvRestaurantSeeder` (`ApplicationRunner`) loads the ISO-8859-1 CSV in batches of 500 only when the collection is empty, then refreshes the parser vocabulary. Cleanup rules: currency is the ISO 4217 code derived from the country (CSV labels are wrong), cuisines are de-duplicated, and "Not rated" rows get no `aggregateRating` instead of 0. Readiness turns UP after it finishes, which the Docker HEALTHCHECK relies on.
- **Errors.** `config/GlobalExceptionHandler` returns RFC 7807 `ProblemDetail`. `IllegalArgumentException` from the service maps to 400.
