package com.sqlagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL 优化样例响应
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OptimizationSampleResponse {

    private List<SampleItem> samples = new ArrayList<>();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SampleItem {
        private String title;
        private String description;
        private String sql;
    }
}
