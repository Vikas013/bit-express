package com.example.bit_express.model;

import java.util.List;
import java.util.Locale;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.geo.GeoJsonPoint;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.GeoSpatialIndexType;
import org.springframework.data.mongodb.core.index.GeoSpatialIndexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "restaurants")
@CompoundIndex(
    name = "idx_tenant_rating",
    def = "{'tenantId': 1, 'aggregateRating': -1, '_id': -1}")
@CompoundIndex(
    name = "idx_tenant_city_rating",
    def = "{'tenantId': 1, 'cityNormalized': 1, 'aggregateRating': -1, '_id': -1}")
@CompoundIndex(
    name = "idx_tenant_cuisine_rating",
    def = "{'tenantId': 1, 'cuisinesNormalized': 1, 'aggregateRating': -1, '_id': -1}")
@CompoundIndex(
    name = "idx_tenant_city_cost",
    def = "{'tenantId': 1, 'cityNormalized': 1, 'averageCostForTwo': 1, '_id': 1}")
@CompoundIndex(
    name = "idx_tenant_external_id",
    def = "{'tenantId': 1, 'externalRestaurantId': 1}",
    unique = true,
    partialFilter = "{'externalRestaurantId': {'$exists': true}}")
public class Restaurant {

  @Id private String id;

  private String tenantId;
  private Long externalRestaurantId;
  private String name;
  private String address;
  private String locality;
  private String city;
  private String cityNormalized;
  private String country;
  private String countryCode;
  private List<String> cuisines;
  private List<String> cuisinesNormalized;
  private Integer averageCostForTwo;
  private String currency;
  private Integer priceRange;
  private Double aggregateRating;
  private String ratingText;
  private Integer votes;
  private Boolean hasOnlineDelivery;
  private Boolean hasTableBooking;

  @GeoSpatialIndexed(type = GeoSpatialIndexType.GEO_2DSPHERE)
  private GeoJsonPoint location;

  public static String normalize(String value) {
    return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
  }

  public String getId() {
    return id;
  }

  public void setId(String id) {
    this.id = id;
  }

  public String getTenantId() {
    return tenantId;
  }

  public void setTenantId(String tenantId) {
    this.tenantId = tenantId;
  }

  public Long getExternalRestaurantId() {
    return externalRestaurantId;
  }

  public void setExternalRestaurantId(Long externalRestaurantId) {
    this.externalRestaurantId = externalRestaurantId;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getAddress() {
    return address;
  }

  public void setAddress(String address) {
    this.address = address;
  }

  public String getLocality() {
    return locality;
  }

  public void setLocality(String locality) {
    this.locality = locality;
  }

  public String getCity() {
    return city;
  }

  public void setCity(String city) {
    this.city = city;
    this.cityNormalized = normalize(city);
  }

  public String getCityNormalized() {
    return cityNormalized;
  }

  public void setCityNormalized(String cityNormalized) {
    this.cityNormalized = cityNormalized;
  }

  public String getCountry() {
    return country;
  }

  public void setCountry(String country) {
    this.country = country;
  }

  public String getCountryCode() {
    return countryCode;
  }

  public void setCountryCode(String countryCode) {
    this.countryCode = countryCode;
  }

  public List<String> getCuisines() {
    return cuisines;
  }

  public void setCuisines(List<String> cuisines) {
    this.cuisines = cuisines;
    this.cuisinesNormalized =
        cuisines == null ? List.of() : cuisines.stream().map(Restaurant::normalize).toList();
  }

  public List<String> getCuisinesNormalized() {
    return cuisinesNormalized;
  }

  public void setCuisinesNormalized(List<String> cuisinesNormalized) {
    this.cuisinesNormalized = cuisinesNormalized;
  }

  public Integer getAverageCostForTwo() {
    return averageCostForTwo;
  }

  public void setAverageCostForTwo(Integer averageCostForTwo) {
    this.averageCostForTwo = averageCostForTwo;
  }

  public String getCurrency() {
    return currency;
  }

  public void setCurrency(String currency) {
    this.currency = currency;
  }

  public Integer getPriceRange() {
    return priceRange;
  }

  public void setPriceRange(Integer priceRange) {
    this.priceRange = priceRange;
  }

  public Double getAggregateRating() {
    return aggregateRating;
  }

  public void setAggregateRating(Double aggregateRating) {
    this.aggregateRating = aggregateRating;
  }

  public String getRatingText() {
    return ratingText;
  }

  public void setRatingText(String ratingText) {
    this.ratingText = ratingText;
  }

  public Integer getVotes() {
    return votes;
  }

  public void setVotes(Integer votes) {
    this.votes = votes;
  }

  public Boolean getHasOnlineDelivery() {
    return hasOnlineDelivery;
  }

  public void setHasOnlineDelivery(Boolean hasOnlineDelivery) {
    this.hasOnlineDelivery = hasOnlineDelivery;
  }

  public Boolean getHasTableBooking() {
    return hasTableBooking;
  }

  public void setHasTableBooking(Boolean hasTableBooking) {
    this.hasTableBooking = hasTableBooking;
  }

  public GeoJsonPoint getLocation() {
    return location;
  }

  public void setLocation(GeoJsonPoint location) {
    this.location = location;
  }
}
