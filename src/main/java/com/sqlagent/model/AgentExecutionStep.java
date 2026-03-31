package com.sqlagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentExecutionStep {

    private int stepNumber;
    private String stepType;
    private String sourceType;
    /**
     * 数据来源标记：tool / app / system / agent / inferred
     */
    private String dataSource;
    private String status;
    private String toolName;
    private String stepName;
    private String toolIcon;
    private String description;
    private String details;
    private String inputSummary;
    private String outputSummary;
    private ExplainResult explain;
    private long executionTimeMs;
    private long startTime;
    private long endTime;
    private long timestamp;
    private List<SubStep> subSteps = new ArrayList<>();

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SubStep {
        private String title;
        private String content;
        private String type;
    }
}
