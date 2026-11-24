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

- **Cities, cuisines and countries come from the data**, not from code: `distinct` on `city` (141 values), `cuisines` (145) and `country` (15), cached and refreshed after seeding. They are matched longest first, so "North Indian" beats "Indian" and "New Delhi" beats "Delhi". A cuisine added through `POST` becomes searchable on the next refresh.
- **Only knowledge the dataset does not contain is hand-written**: place aliases (Gurugram, Bengaluru, UK, USA) and 12 dish-to-cuisine mappings. There are no menus in the data, so nothing in it says that vada pav is street food.
- **Budget, rating and intent** come from patterns: "under Rs. 1000", "above 4", "nearest", "cheapest", "best".
- **Explicit parameters win.** `q=...in Gurgaon&city=Noida` searches Noida.
- **`interpretedFilters`** echoes the exact filters and sort the query ran with, including defaults.

It is deterministic and has no external dependency, which suits a POC and makes it unit-testable. It sits behind the `NlQueryParser` interface, so another parser can replace it without touching the API.

### How the dish map was checked against the data

Restaurant names often contain a dish, so the dish map was validated by counting the cuisines of restaurants whose name contains each dish (the same thing as a `$match` on `name`, `$unwind` on `cuisines`, `$group`):

| Dish in name | Restaurants | Cuisines in the data | Mapping |
|---|---|---|---|
| vada pav | 13 | Street Food 100% | Street Food |
| chaat | 30 | Street Food 83% | Street Food |
| chole bhature | 9 | Street Food 100% | Street Food (added from this check) |
| dosa | 29 | South Indian 100% | South Indian |
| momos | 27 | Chinese 81% | Chinese |
| biryani, burger, sushi, pizza, kebab, dim sum | | each is also a cuisine in the data | not in the map: matched directly as cuisines |
| pasta | 12 | Fast Food 66%, Italian 50% | Italian (kept, ambiguous) |

Dishes with no restaurant names to check (golgappa, uttapam) are kept as domain knowledge.

### Next step: Atlas Search (designed, not built)

Atlas Search would replace the hand-written dictionaries and the `name` regex with a native MongoDB feature. It runs locally in the `mongodb/mongodb-atlas-local` image (and in Testcontainers as `MongoDBAtlasLocalContainer`). It was left as a design to keep the submission's scope and live demo simple.

Search index on `restaurants`:

```json
{
  "mappings": {
    "dynamic": false,
    "fields": {
      "tenantId": { "type": "token" },
      "cityNormalized": { "type": "token" },
      "name": [{ "type": "string" }, { "type": "autocomplete" }],
      "cuisines": { "type": "string" },
      "aggregateRating": { "type": "number" },
      "location": { "type": "geo" }
    }
  },
  "synonyms": [{ "name": "food_terms", "analyzer": "lucene.standard",
                 "source": { "collection": "search_synonyms" } }]
}
```

The dish and alias maps become documents in `search_synonyms` that ops can edit without a deploy, for example `{ "mappingType": "explicit", "input": ["vada pav"], "synonyms": ["vada pav", "street food"] }`. A search then keeps the parser's structured filters and lets `$search` rank the remaining words:

```js
{ $search: { index: "restaurants", compound: {
    filter: [ { equals: { path: "tenantId", value: "bite-express" } } ],
    should: [
      { text: { query: "vada pav", path: ["name", "cuisines"], synonyms: "food_terms" } },
      { text: { query: "vada pav", path: "name", fuzzy: { maxEdits: 1 } } },
      { near: { path: "location", origin: { type: "Point", coordinates: [77.0895, 28.4950] },
                pivot: 2000 } } ] } } }
```

This fixes the two weaknesses of the current parser: a dish search is really a category search ("Goli Vada Pav" ranks first only because it is closest), and name search is an unindexed regex. Synonyms and fuzzy matching are separate clauses because Atlas Search does not allow both on one `text` operator.

A further step for complex sentences is an LLM that extracts the same `RestaurantQueryParams` JSON (following MongoDB's natural-language-to-query prompting guidance), with the query still built and validated in code so tenant filters and limits cannot be bypassed, and the rule parser as the fallback.

## Data model and MongoDB design

One `restaurants` collection, one document per restaurant, with cuisines embedded as an array and location as a GeoJSON point.

```json
{
  "tenantId": "bite-express", "externalRestaurantId": 6317637,
  "name": "...", "city": "Makati City", "cityNormalized": "makati city",
  "cuisines": ["French", "Japanese"], "cuisinesNormalized": ["french", "japanese"],
  "country": "Philippines", "countryCode": "PH", "currency": "PHP",
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
- Zomato's own country codes (1 is India, 162 is the Philippines) are mapped to a country name and an ISO 3166 code, using the dataset's `Country-Code.xlsx`.
- **Currency is derived as an ISO 4217 code from the country** (`java.util.Currency`), not copied from the CSV label. The labels are wrong or ambiguous: Philippine restaurants are labelled "Botswana Pula", "Dollar($)" covers four countries, and the UK label is garbled. Every restaurant now has `INR`, `PHP`, `USD`, `GBP` and so on.
- Cuisines are split on commas, trimmed and de-duplicated (5 rows list a cuisine twice).
- **2,148 restaurants (22%) are "Not rated"** with a rating of 0. They are stored with no rating, so they no longer look like the worst restaurants, they sort last, and `minRating` excludes them. A restaurant created without a rating is treated the same way.
- 497 rows have coordinates of (0, 0). These are stored without a location, so they appear in listings but never in distance results.
- "Yes"/"No" flags become booleans. Unparseable numbers become `null` rather than failing the import.

## Tests

```bash
./mvnw test                                           # all tests, needs Docker
./mvnw test -Dtest=RuleBasedNlQueryParserTest         # parser only, no Docker
./mvnw verify                                         # tests plus formatting check
./mvnw spotless:apply                                 # format the code
```

- `RuleBasedNlQueryParserTest` (16): parser rules in isolation, including the three queries from the brief, aliases and the dish map.
- `RestaurantApiIntegrationTest` (15): every endpoint through HTTP against a real MongoDB in Testcontainers. Covers create and validation, lookup by both ids, tenant isolation, each filter and sort, paging, distance and radius, invalid parameters, and search parity with the listing.
- `SeededDatasetIntegrationTest` (9): loads the real CSV and checks counts measured from it (9,551 restaurants, 1,118 in Gurgaon, 70 South Indian in Gurgaon, 2,148 unrated), the brief's queries, every cleanup rule, and index use via the profiler.

## Trade-offs and next steps

- **Name search** is a "contains" regex. It cannot use an index on its own, but it runs after the tenant and city filters have narrowed the set. Atlas Search (above) is the production answer.
- **"Best" means highest rating**, so 3.7 from 25 votes ranks above 3.6 from 289 votes. A vote-weighted (Bayesian) rating stored per restaurant, with its own index, would rank more fairly.
- **Distance ties are not tie-broken.** Other sorts add `_id` so paging is stable; `$geoNear` order is used as is, so restaurants at exactly the same distance could shift across a page boundary. Adding a `$sort` on distance and `_id` fixes it at the cost of an in-memory sort over the radius.
- **Observability** is health checks only. Production needs request logging with a request id and timing, and MongoDB's slow-query profiler, to trace a slow or failing search.
- **Tenancy** is a shared collection keyed by `tenantId`. Tenant identity should come from an authenticated token rather than a plain header once auth exists. Large tenants or data-residency rules can move to their own database or cluster without changing the model.
- **Peak traffic**: the main read paths are index-only and the service is stateless, so it scales horizontally behind a load balancer. Next steps would be a read-preference of `secondaryPreferred` for discovery reads, connection pool sizing from load tests, and caching for the unfiltered home listing.
- **International expansion and compliance**: Atlas global clusters with zone sharding on country would keep each country's data in-region. Each restaurant already stores an ISO country and currency code, which is what payment providers and price formatting need.

## Scope and time

The brief estimates 3 to 5 hours and grades scope prioritization. I prioritised what the brief's goals point at: correct indexes for peak traffic, tenant isolation for "multiple similar businesses", tests against the real data, and a one-command demo. Atlas Search, an LLM query extractor, vote-weighted ranking and request tracing are deliberately left as designs above rather than built, to keep the submission focused and the live demo reliable.

## Use of AI tools

This project was built with help from Claude Code (Anthropic), used for code generation, refactoring, writing tests and drafting this README. `CLAUDE.md` in the repository is the guidance file it used. All design decisions, measurements and test results were reviewed and verified by me.

Other references: Spring Data MongoDB and MongoDB documentation, and the Zomato Restaurants dataset on Kaggle.
