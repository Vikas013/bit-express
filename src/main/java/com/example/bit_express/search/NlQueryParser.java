package com.example.bit_express.search;

import com.example.bit_express.dto.RestaurantSearchCriteria;

import java.util.List;
import java.util.Map;

public interface NlQueryParser {

    record ParseResult(
            RestaurantSearchCriteria criteria,
            Map<String, Object> interpretedFilters,
            List<String> warnings
    ) {}

    ParseResult parse(
            String rawQuery,
            String cityOverride,
            String cuisineOverride,
            Integer maxCostOverride,
            Double minRatingOverride,
            Double latOverride,
            Double lonOverride,
            String sortByOverride,
            String sortDirOverride,
            int page,
            int size
    );
}
