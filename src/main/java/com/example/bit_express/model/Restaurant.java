package com.example.bit_express.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.GeoSpatialIndexType;
import org.springframework.data.mongodb.core.index.GeoSpatialIndexed;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.List;

@Document(collection = "restaurants")
@CompoundIndexes({
        @CompoundIndex(name = "idx_tenant_city_rating", def = "{'tenantId': 1, 'cityNormalized': 1, 'aggregateRating': -1}"),
        @CompoundIndex(name = "idx_tenant_cuisine_rating", def = "{'tenantId': 1, 'cuisinesNormalized': 1, 'aggregateRating': -1}"),
        @CompoundIndex(name = "idx_tenant_city_cost", def = "{'tenantId': 1, 'cityNormalized': 1, 'averageCostForTwo': 1}")
})
public class Restaurant {

    @Id
    private String id;

    private String tenantId = "bite-express";

    @Indexed(unique = true, sparse = true)
    private Long externalRestaurantId;

    private String name;
    private String city;
    private String cityNormalized;
    private String address;
    private String country;
    private List<String> cuisines;
    private List<String> cuisinesNormalized;
    private Integer averageCostForTwo;
    private String currency;
    private Double aggregateRating;
    private Boolean hasOnlineDelivery;
    private Boolean hasTableBooking;

    @GeoSpatialIndexed(type = GeoSpatialIndexType.GEO_2DSPHERE)
    private GeoJsonPoint location; // GeoJSON order: [longitude, latitude]

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public Long getExternalRestaurantId() { return externalRestaurantId; }
    public void setExternalRestaurantId(Long externalRestaurantId) { this.externalRestaurantId = externalRestaurantId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getCity() { return city; }
    public void setCity(String city) {
        this.city = city;
        this.cityNormalized = city != null ? city.trim().toLowerCase() : null;
    }

    public String getCityNormalized() { return cityNormalized; }
    public void setCityNormalized(String cityNormalized) { this.cityNormalized = cityNormalized; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }

    public List<String> getCuisines() { return cuisines; }
    public void setCuisines(List<String> cuisines) {
        this.cuisines = cuisines;
        this.cuisinesNormalized = cuisines != null
                ? cuisines.stream().map(c -> c.trim().toLowerCase()).toList()
                : List.of();
    }

    public List<String> getCuisinesNormalized() { return cuisinesNormalized; }
    public void setCuisinesNormalized(List<String> cuisinesNormalized) { this.cuisinesNormalized = cuisinesNormalized; }

    public Integer getAverageCostForTwo() { return averageCostForTwo; }
    public void setAverageCostForTwo(Integer averageCostForTwo) { this.averageCostForTwo = averageCostForTwo; }

    public String getCurrency() { return currency; }
    public void setCurrency(String currency) { this.currency = currency; }

    public Double getAggregateRating() { return aggregateRating; }
    public void setAggregateRating(Double aggregateRating) { this.aggregateRating = aggregateRating; }

    public Boolean getHasOnlineDelivery() { return hasOnlineDelivery; }
    public void setHasOnlineDelivery(Boolean hasOnlineDelivery) { this.hasOnlineDelivery = hasOnlineDelivery; }

    public Boolean getHasTableBooking() { return hasTableBooking; }
    public void setHasTableBooking(Boolean hasTableBooking) { this.hasTableBooking = hasTableBooking; }

    public GeoJsonPoint getLocation() { return location; }
    public void setLocation(GeoJsonPoint location) { this.location = location; }
}