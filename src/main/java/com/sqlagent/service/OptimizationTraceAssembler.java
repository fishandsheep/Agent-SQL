package com.sqlagent.service;

import com.sqlagent.model.AgentExecutionStep;
import com.sqlagent.model.ExplainResult;
import com.sqlagent.model.MultiPlanResponse;
import com.sqlagent.model.PlanExecutionResult;
import com.sqlagent.model.ToolCallLog;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
public class OptimizationTraceAssembler {

    public List<MultiPlanResponse.AnalysisStep> buildAnalysisTimeline(List<AgentExecutionStep> executionSteps) {
        return executionSteps.stream()
            .map(step -> MultiPlanResponse.AnalysisStep.builder()
                .id(step.getStepType() + "-" + step.getStepNumber())
                .name(step.getStepName())
                .stepType(step.getStepType())
                .sourceType(step.getSourceType() != null ? step.getSourceType() : step.getDataSource())
                .status(step.getStatus())
                .inputSummary(step.getInputSummary())
                .outputSummary(step.getOutputSummary())
                .startedAt(step.getStartTime())
                .endedAt(step.getEndTime())
                .durationMs(step.getExecutionTimeMs())
                .build())
            .toList();
    }


    public List<AgentExecutionStep> buildExecutionSteps(List<PlanExecutionResult> results, List<ToolCallLog> toolLogs) {
        List<AgentExecutionStep> steps = new ArrayList<>();
        if (toolLogs != null && !toolLogs.isEmpty()) {
            int index = 1;
            for (ToolCallLog log : toolLogs) {
                AgentExecutionStep step = new AgentExecutionStep();
                long timestamp = log.getTimestamp() != null
                    ? log.getTimestamp().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                    : System.currentTimeMillis();
                step.setStepNumber(index++);
                step.setStepType("tool_call");
                step.setSourceType("agent");
                step.setDataSource("tool");
                step.setStatus(log.isPermitted() ? "SUCCESS" : "FAILED");
                step.setStepName("toolCall");
                step.setToolName(log.getToolName());
                step.setDescription(log.isPermitted()
                    ? "Agent 调用工具: " + log.getToolName()
                    : "工具调用被拒绝: " + log.getToolName());
                step.setInputSummary(log.getArguments());
                step.setOutputSummary(log.getErrorMessage());
                step.setExecutionTimeMs(log.getDuration() != null ? log.getDuration() : 0L);
                step.setStartTime(timestamp);
                step.setEndTime(timestamp + step.getExecutionTimeMs());
                step.setTimestamp(timestamp);
                steps.add(step);
            }
        }

        long cursor = steps.isEmpty()
            ? System.currentTimeMillis()
            : steps.stream().mapToLong(AgentExecutionStep::getEndTime).max().orElse(System.currentTimeMillis());
        for (PlanExecutionResult result : results) {
            AgentExecutionStep step = buildExecutionStep(result, cursor);
            cursor = step.getEndTime();
            steps.add(step);
        }
        return steps.stream()
            .sorted(Comparator.comparingLong(AgentExecutionStep::getStartTime))
            .toList();
    }

    private AgentExecutionStep buildExecutionStep(PlanExecutionResult result, long previousEndTime) {
        boolean baseline = "BASELINE".equals(result.getPlanId());
        long duration = result.getExecutionTime() != null ? result.getExecutionTime() : 0L;
        long startTime = previousEndTime;
        long endTime = startTime + duration;

        AgentExecutionStep step = new AgentExecutionStep();
        step.setStepNumber(baseline ? 0 : parseStepNumber(result.getPlanId()));
        step.setStepType(baseline ? "baseline" : "plan_execution");
        step.setSourceType("system");
        step.setDataSource("system");
        step.setStatus(result.isValid() ? "SUCCESS" : "FAILED");
        step.setStepName(baseline ? "measureBaseline" : "evaluatePlan");
        step.setToolName(baseline ? "baselineRunner" : "planExecutionEngine");
        step.setDescription(baseline
            ? "测量原始 SQL 的基线表现"
            : "执行并验证候选方案 " + result.getPlanId());
        step.setExecutionTimeMs(duration);
        step.setStartTime(startTime);
        step.setEndTime(endTime);
        step.setTimestamp(endTime);
        step.setExplain(result.getExplain());
        step.setInputSummary("planId=" + result.getPlanId());
        step.setOutputSummary(buildExecutionOutputSummary(result));
        step.setDetails(buildExecutionDetails(result));
        return step;
    }

    private String buildExecutionOutputSummary(PlanExecutionResult result) {
        ExplainResult explain = result.getExplain();
        return String.format(
            "status=%s, rows=%s, key=%s",
            result.getStatus(),
            explain != null && explain.getRows() != null ? explain.getRows() : "-",
            explain != null && explain.getKey() != null ? explain.getKey() : "-"
        );
    }

    private String buildExecutionDetails(PlanExecutionResult result) {
        StringBuilder details = new StringBuilder();
        if (result.getCreatedIndexName() != null && !result.getCreatedIndexName().isBlank()) {
            details.append("createdIndex=").append(result.getCreatedIndexName());
        }
        if (result.getValidationError() != null && !result.getValidationError().isBlank()) {
            if (!details.isEmpty()) {
                details.append("; ");
            }
            details.append("validationError=").append(result.getValidationError());
        }
        if (result.getError() != null && !result.getError().isBlank()) {
            if (!details.isEmpty()) {
                details.append("; ");
            }
            details.append("error=").append(result.getError());
        }
        return details.isEmpty() ? null : details.toString();
    }

    private int parseStepNumber(String planId) {
        if (planId == null || planId.isBlank()) {
            return 0;
        }
        char first = Character.toUpperCase(planId.charAt(0));
        if (first >= 'A' && first <= 'Z') {
            return first - 'A' + 1;
        }
        return 0;
    }



}