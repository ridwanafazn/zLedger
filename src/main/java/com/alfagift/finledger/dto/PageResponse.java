package com.alfagift.finledger.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Wrapper standar untuk response pagination.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageResponse<T> {

    private List<T> content;
    private PageMetadata page;
    private List<SortInfo> sort;
    private FilterInfo filters;

    @Data
    @Builder
    @AllArgsConstructor
    public static class PageMetadata {
        private int number;
        private int size;
        private long totalElements;
        private int totalPages;
        private boolean first;
        private boolean last;
        private int numberOfElements;
    }

    @Data
    @Builder
    @AllArgsConstructor
    public static class SortInfo {
        private String field;
        private String direction;
    }

    @Data
    @Builder
    @AllArgsConstructor
    public static class FilterInfo {
        // Dynamic fields based on endpoint
    }
}