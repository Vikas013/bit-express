# BiteExpress Restaurant Discovery API

An MVP of the customer-facing restaurant discovery service for BiteExpress, built on MongoDB. It serves listing, filtering, geo search and natural-language search over the Zomato restaurants dataset, and is designed so the same service can host several similar businesses (tenants).

Stack: Java 21, Spring Boot 4.2, Spring Data MongoDB, MongoDB 8.0, Testcontainers, Docker Compose.

## Run it

Requires Docker.

```bash
docker compose up --build
```

The API is on http://localhost:8080. On first start the app loads all 9,551 restaurants from `src/main/resources/zomato.csv` (a few seconds), and the container reports healthy once seeding is done. Data lives in the `mongo_data` volume, so later starts skip seeding. `docker compose down -v` resets it.

If something on your machine already uses port 27017, set `MONGO_HOST_PORT=27018 docker compose up --build`. The app reaches Mongo over the compose network either way.

Run locally without the app container:

```bash
docker compose up mongodb -d
./mvnw spring-boot:run
```

## Endpoints

Every endpoint accepts an optional `X-Tenant-Id` header (default `bite-express`). Each tenant only sees and writes its own restaurants.

| Method and path | Purpose |
|---|---|
| `POST /v1/restaurants` | Create a restaurant. Returns `201` with `Location` and the created entity. |
| `GET /v1/restaurants/{id}` | Full details by MongoDB id or by the original Zomato restaurant id. |
| `GET /v1/restaurants` | Filtered, sorted, paginated listing. |
| `GET /v1/restaurants/search` | Everything the listing does, plus a natural-language `q`. |
| `GET /actuator/health` | Liveness and readiness. |

Listing and search parameters:

| Parameter | Meaning |
|---|---|
| `name` | Case-insensitive "contains" match |
| `city`, `country` | Case-insensitive exact match |
| `cuisines` | Repeat or comma-separate. A restaurant must serve all of them. |
| `minRating`, `maxCostForTwo` | Rating floor (0 to 5), budget ceiling |
| `lat`, `lon` | Enables distance: results get `distanceKm` and default to nearest first |
| `maxDistanceKm` | Radius limit, needs `lat` and `lon` |
| `sortBy` | `rating` (default), `distance` (default when `lat`/`lon` given), `cost`, `name` |
| `sortDir` | `asc` or `desc` |
| `page`, `size` | Zero-based page, size 1 to 100 (default 10, larger values are capped) |

Invalid input returns `400` as an RFC 7807 problem document, for example `sortBy=distance` without coordinates, `lat` without `lon`, or a rating above 5.

### Examples

```bash
curl -X POST localhost:8080/v1/restaurants -H 'Content-Type: application/json' -d '{
  "name": "Sample Dosa Corner", "city": "Gurgaon", "address": "12 Sample Street, Gurgaon",
  "country": "India", "cuisines": ["South Indian"], "averageCostForTwo": 600,
  "aggregateRating": 4.4, "latitude": 28.4946, "longitude": 77.0887}'

curl localhost:8080/v1/restaurants/6317637

curl 'localhost:8080/v1/restaurants?city=Gurgaon&cuisines=South%20Indian&minRating=4'

curl 'localhost:8080/v1/restaurants?lat=28.4950&lon=77.0895&maxDistanceKm=2&cuisines=Cafe'

curl -G localhost:8080/v1/restaurants/search --data-urlencode 'q=Best restaurants in Gurgaon with South Indian Food'
curl -G localhost:8080/v1/restaurants/search --data-urlencode 'q=Restaurants in Gurgaon under Rs. 1000 for two'
curl -G localhost:8080/v1/restaurants/search --data-urlencode 'q=Nearest place that serves Vada Pav' -d lat=28.4950 -d lon=77.0895

curl -H 'X-Tenant-Id: quick-eats' localhost:8080/v1/restaurants
```

Search responses also echo `interpretedFilters` (what the query was understood as) and `warnings` (for example when "nearest" had no coordinates and fell back to the Gurugram city centre).

## How natural-language search works

`RuleBasedNlQueryParser` turns the query into the same `RestaurantQueryParams` the listing endpoint uses, so both endpoints share one query path.

- **Cities and cuisines** come from the data itself (`distinct` on `city` and `cuisines`, cached and refreshed after seeding), matched longest first so "North Indian" beats "Indian" and "New Delhi" beats "Delhi".
- **Aliases** cover renamed cities (Gurugram to Gurgaon, Bengaluru to Bangalore, Bombay to Mumbai) and dishes (Vada Pav and Chaat to Street Food, Dosa to South Indian, Momos to Chinese).
- **Budget, rating and intent** come from patterns: "under Rs. 1000", "above 4", "nearest", "cheapest", "best".
- **Explicit parameters win.** `q=...in Gurgaon&city=Noida` searches Noida.

It is deterministic and has no external dependency, which suits a POC and makes it unit-testable. It sits behind the `NlQueryParser` interface, so an LLM-backed parser, or Atlas Search, can replace it without touching the API.

## Data model and MongoDB design

One `restaurants` collection, one document per restaurant, with cuisines embedded as an array and location as a GeoJSON point.

```json
{
  "tenantId": "bite-express", "externalRestaurantId": 6317637,
  "name": "...", "city": "Makati City", "cityNormalized": "makati city",
  "cuisines": ["French", "Japanese"], "cuisinesNormalized": ["french", "japanese"],
  "aggregateRating": 4.8, "averageCostForTwo": 1100, "priceRange": 3, "votes": 314,
  "location": { "type": "Point", "coordinates": [121.027535, 14.565443] }
}
```

- **Normalized copies** (`cityNormalized`, `cuisinesNormalized`) are written on every save, so case-insensitive filters become exact matches that an index can serve.
- **Indexes match the query shapes** and lead with `tenantId`:
  - `{tenantId, aggregateRating, _id}`: the default home-screen listing
  - `{tenantId, cityNormalized, aggregateRating, _id}` and `{tenantId, cityNormalized, averageCostForTwo, _id}`: city listings by rating or price
  - `{tenantId, cuisinesNormalized, aggregateRating, _id}`: cuisine listings
  - `location` 2dsphere: `$geoNear` distance search and radius limits
  - `{tenantId, externalRestaurantId}` unique (partial): idempotent imports
- **`_id` as tie-breaker** keeps paging stable when ratings tie, and is part of each index so the sort is still served by the index.
- **One aggregation per request**: `$geoNear` or `$match`, then `$sort`, then `$facet` for the page and the total count together.

Measured on the full dataset, `GET /v1/restaurants?city=Gurgaon`:

| | Plan | Documents examined | In-memory sort |
|---|---|---|---|
| Before (no tenant filter, regex fallback) | `COLLSCAN` | 9,551 | yes |
| After | `IXSCAN { tenantId, cityNormalized, aggregateRating, _id }` | 1,118 (exactly the matches) | no |

`SeededDatasetIntegrationTest` asserts the "after" row using the MongoDB profiler, so a change that breaks index use fails the build.

### Data cleanup during seeding

- The CSV is ISO-8859-1, so it is read with that charset.
- Country codes are mapped to country names.
- Cuisines are split on commas and trimmed into an array.
- 497 rows have coordinates of (0, 0). These are stored without a location, so they appear in listings but never in distance results.
- "Yes"/"No" flags become booleans. Unparseable numbers become `null` rather than failing the import.

## Tests

```bash
./mvnw test                                           # all tests, needs Docker
./mvnw test -Dtest=RuleBasedNlQueryParserTest         # parser only, no Docker
./mvnw verify                                         # tests plus formatting check
./mvnw spotless:apply                                 # format the code
```

- `RuleBasedNlQueryParserTest` (13): parser rules in isolation, including the three queries from the brief.
- `RestaurantApiIntegrationTest` (14): every endpoint through HTTP against a real MongoDB in Testcontainers. Covers create and validation, lookup by both ids, tenant isolation, each filter and sort, paging, distance and radius, invalid parameters, and search parity with the listing.
- `SeededDatasetIntegrationTest` (6): loads the real CSV and checks counts measured from it (9,551 restaurants, 1,118 in Gurgaon, 70 South Indian in Gurgaon), the brief's queries, and index use via the profiler.

## Trade-offs and next steps

- **Name search** is a "contains" regex. It cannot use an index on its own, but it runs after the tenant and city filters have narrowed the set. The production answer is Atlas Search (autocomplete, fuzzy matching, relevance), which can be developed locally with the `mongodb/mongodb-atlas-local` image.
- **Tenancy** is a shared collection keyed by `tenantId`. Tenant identity should come from an authenticated token rather than a plain header once auth exists. Large tenants or data-residency rules can move to their own database or cluster without changing the model.
- **Peak traffic**: the main read paths are index-only and the service is stateless, so it scales horizontally behind a load balancer. Next steps would be a read-preference of `secondaryPreferred` for discovery reads, connection pool sizing from load tests, and caching for the unfiltered home listing.
- **International expansion and compliance**: Atlas global clusters with zone sharding on country would keep each country's data in-region. Currency is stored per restaurant; prices should become a numeric amount plus an ISO currency code.

## Use of AI tools

This project was built with help from Claude Code (Anthropic), used for code generation, refactoring, writing tests and drafting this README. `CLAUDE.md` in the repository is the guidance file it used. All design decisions, measurements and test results were reviewed and verified by me.

Other references: Spring Data MongoDB and MongoDB documentation, and the Zomato Restaurants dataset on Kaggle.
