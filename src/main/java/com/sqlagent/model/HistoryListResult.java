package com.sqlagent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HistoryListResult {

    private boolean success;
    private String error;
    private List<HistoryItem> histories;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class HistoryItem {
        private Long id;
        private String originalSqlFirstLine;
        private String originalSql;
        private String optimizedSql;
        private String bestPlanId;
        private String strategy;
        private Double improvementPercentage;
        private Long baselineExecutionTime;
        private Long optimizedExecutionTime;
        private Long baselineRows;
        private Long optimizedRows;
        private Long totalTime;
        private boolean optimizationSuccessful;
        private String modelName;
        private String createdAt;
    }
}
