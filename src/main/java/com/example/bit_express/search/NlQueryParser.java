package com.example.bit_express.search;

import com.example.bit_express.dto.RestaurantQueryParams;
import java.util.List;

public interface NlQueryParser {

  record ParseResult(RestaurantQueryParams params, List<String> warnings) {}

  ParseResult parse(String query, RestaurantQueryParams explicit);
}
