package com.sqlagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlagent.agent.WorkloadOptimizationAgent;
import com.sqlagent.gateway.ToolGateway;
import com.sqlagent.model.AgentExecutionStep;
import com.sqlagent.model.IndexRecommendation;
import com.sqlagent.model.ToolCallLog;
import com.sqlagent.model.WorkloadOptimizationRequest;
import com.sqlagent.model.WorkloadOptimizationResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Workload 优化服务。
 * 负责调用 Agent、解析响应，并把真实工具调用轨迹整理给前端。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkloadOptimizationService {

    private final WorkloadOptimizationAgent workloadOptimizationAgent;
    private final SqlInputValidationService sqlInputValidationService;
    private final ObjectMapper objectMapper;
    private final ToolGateway toolGateway;

    public WorkloadOptimizationResponse optimize(WorkloadOptimizationRequest request) {
        String sessionId = "workload-" + UUID.randomUUID().toString().substring(0, 8);
        int sqlCount = request.getSqls() != null ? request.getSqls().size() : 0;
        log.info("[workload] optimization started: sessionId={}, sqlCount={}", sessionId, sqlCount);

        if (!request.isValid()) {
            log.warn("[workload] invalid request: sessionId={}, message={}", sessionId, request.getValidationError());
            return createValidationFailureResponse(request.getSqls(), request.getValidationError());
        }

        SqlInputValidationService.ValidationResult validationResult =
            sqlInputValidationService.validateWorkloadSqls(request.getSqls());
        if (!validationResult.isValid()) {
            log.warn("[workload] validation failed: sessionId={}, message={}", sessionId, validationResult.getMessage());
            return createValidationFailureResponse(request.getSqls(), validationResult.getMessage());
        }

        toolGateway.activateSession(sessionId);

        try {
            String userMessage = buildUserMessage(request);
            long agentStartTime = System.currentTimeMillis();
            log.info("[workload] invoking agent: sessionId={}, maxIndexes={}, allowCompositeIndex={}, analyzeSelectivity={}",
                sessionId, request.getMaxIndexes(), request.isAllowCompositeIndex(), request.isAnalyzeSelectivity());

            String agentResponse = workloadOptimizationAgent.optimizeWorkload(userMessage);
            long totalAgentTime = System.currentTimeMillis() - agentStartTime;

            log.info("[workload] agent completed: sessionId={}, durationMs={}", sessionId, totalAgentTime);
            log.debug("[workload] raw agent response: sessionId={}, response={}", sessionId, agentResponse);

            WorkloadOptimizationResponse response = parseResponse(request.getSqls(), agentResponse);
            response.setTotalTime(totalAgentTime);
            response.setExecution(buildExecutionSteps(sessionId, totalAgentTime));
            return response;
        } finally {
            toolGateway.clearSession(sessionId);
        }
    }

    private String buildUserMessage(WorkloadOptimizationRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("请为以下 ").append(request.getSqls().size()).append(" 条 SQL 设计一组全局最优索引建议：\n\n");

        for (int i = 0; i < request.getSqls().size(); i++) {
            sb.append("SQL[").append(i).append("]: ").append(request.getSqls().get(i)).append("\n");
        }

        sb.append("\n要求：\n");
        sb.append("- 索引数量限制：").append(request.getMaxIndexes()).append("\n");
        sb.append("- 允许复合索引：").append(request.isAllowCompositeIndex()).append("\n");
        sb.append("- 分析字段选择性：").append(request.isAnalyzeSelectivity()).append("\n");
        sb.append("- 优先少而精的索引，不要为了覆盖率机械增加索引\n");
        sb.append("- 复合索引字段顺序遵循数据库优化原则：等值过滤列优先，范围列其次，排序/分组列放后面\n");
        sb.append("- 对低选择性字段（如 gender/status/type/flag）要非常谨慎，除非与高选择性列组合且收益明确，否则不要单独建索引\n");
        sb.append("- 若 SQL 使用 YEAR(col)、DATE(col) 等函数导致索引失效，要明确识别并优先改写为可走索引的范围查询\n");
        sb.append("- 避免冗余索引：如果更强的复合索引已覆盖前缀场景，不要再推荐弱前缀索引\n");
        sb.append("- 对每个推荐索引，说明它覆盖了哪些 SQL、为什么该字段顺序更优、为什么没有选择看似相近但更差的顺序\n");
        sb.append("\n只输出 JSON。");
        return sb.toString();
    }

    private WorkloadOptimizationResponse parseResponse(List<String> inputSqls, String response) {
        try {
            log.debug("[workload] parsing response: responseLength={}", response != null ? response.length() : 0);

            String jsonContent = extractJson(response);
            JsonNode root = objectMapper.readTree(jsonContent);

            WorkloadOptimizationResponse result = new WorkloadOptimizationResponse();
            result.setInputSqls(inputSqls);
            result.setSuccess(true);

            JsonNode indexesNode = root.path("recommendedIndexes");
            if (indexesNode.isArray()) {
                List<IndexRecommendation> indexes = new ArrayList<>();
                for (JsonNode indexNode : indexesNode) {
                    indexes.add(parseIndexRecommendation(indexNode));
                }
                result.setRecommendedIndexes(indexes);
            }

            result.setCoveredSqlCount(root.path("coveredSqlCount").asInt(0));
            result.setCoverageRate(root.path("coverageRate").asDouble(0.0));
            if (result.getCoverageRate() == 0.0 && result.getCoveredSqlCount() > 0) {
                result.calculateCoverageRate();
            }

            result.setReason(root.path("reason").asText());

            JsonNode detailsNode = root.path("details");
            if (detailsNode.isObject()) {
                Map<String, Object> details = new HashMap<>();
                detailsNode.fields().forEachRemaining(entry ->
                    details.put(entry.getKey(), parseJsonValue(entry.getValue()))
                );
                result.setDetails(details);
            }

            log.info("[workload] parsed recommendations: indexCount={}, coveredSqlCount={}, coverageRate={}",
                result.getTotalIndexCount(), result.getCoveredSqlCount(), result.getCoverageRate());
            return result;
        } catch (Exception e) {
            log.error("[workload] failed to parse agent response", e);
            log.debug("[workload] raw response on parse failure: {}", response);
            return createErrorResponse(inputSqls, "解析响应失败: " + e.getMessage());
        }
    }

    private IndexRecommendation parseIndexRecommendation(JsonNode indexNode) {
        IndexRecommendation index = new IndexRecommendation();
        index.setIndexName(indexNode.path("indexName").asText());
        index.setTableName(indexNode.path("tableName").asText());
        index.setReason(indexNode.path("reason").asText());
        index.setPriorityScore(indexNode.path("priorityScore").asInt(0));
        index.setCreateSql(indexNode.path("createSql").asText());
        index.setIndexType("BTREE");
        index.setUnique(false);

        JsonNode columnsNode = indexNode.path("columns");
        if (columnsNode.isArray()) {
            List<String> columns = new ArrayList<>();
            for (JsonNode columnNode : columnsNode) {
                columns.add(columnNode.asText());
            }
            index.setColumns(columns);
        }

        JsonNode coveredSqlsNode = indexNode.path("coveredSqls");
        if (coveredSqlsNode.isArray()) {
            List<Integer> coveredSqls = new ArrayList<>();
            for (JsonNode sqlIndexNode : coveredSqlsNode) {
                coveredSqls.add(sqlIndexNode.asInt());
            }
            index.setCoveredSqls(coveredSqls);
        }

        return index;
    }

    private Object parseJsonValue(JsonNode node) {
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isInt()) {
            return node.asInt();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isDouble()) {
            return node.asDouble();
        }
        if (node.isArray()) {
            List<Object> list = new ArrayList<>();
            node.forEach(item -> list.add(parseJsonValue(item)));
            return list;
        }
        if (node.isObject()) {
            Map<String, Object> map = new HashMap<>();
            node.fields().forEachRemaining(entry ->
                map.put(entry.getKey(), parseJsonValue(entry.getValue()))
            );
            return map;
        }
        return null;
    }

    private List<AgentExecutionStep> buildExecutionSteps(String sessionId, long totalAgentTime) {
        List<ToolCallLog> records = toolGateway.getSessionLogs(sessionId);
        List<AgentExecutionStep> steps = new ArrayList<>();

        if (records.isEmpty()) {
            AgentExecutionStep step = new AgentExecutionStep();
            step.setStepNumber(1);
            step.setStepType("thought");
            step.setStepName("agentAnalysis");
            step.setSourceType("agent");
            step.setDataSource("agent");
            step.setStatus("SUCCESS");
            step.setToolName("analysis");
            step.setDescription("AI 完成 Workload 分析，未显式调用工具");
            step.setExecutionTimeMs(totalAgentTime);
            step.setStartTime(System.currentTimeMillis() - totalAgentTime);
            step.setEndTime(System.currentTimeMillis());
            step.setTimestamp(step.getEndTime());
            steps.add(step);
            return steps;
        }

        for (int i = 0; i < records.size(); i++) {
            ToolCallLog record = records.get(i);
            long timestamp = record.getTimestamp() != null
                ? record.getTimestamp().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                : System.currentTimeMillis();
            long duration = record.getDuration() != null ? record.getDuration() : 0L;

            AgentExecutionStep step = new AgentExecutionStep();
            step.setStepNumber(i + 1);
            step.setStepType("tool_call");
            step.setStepName("toolCall");
            step.setSourceType("tool");
            step.setDataSource("tool");
            step.setStatus(record.isPermitted() ? "SUCCESS" : "FAILED");
            step.setToolName(record.getToolName());
            step.setDescription(record.isPermitted()
                ? "调用工具: " + record.getToolName()
                : "工具调用被拒绝: " + record.getToolName());
            step.setInputSummary(record.getArguments());
            step.setOutputSummary(record.isPermitted() ? "authorized" : record.getErrorMessage());
            step.setExecutionTimeMs(duration);
            step.setStartTime(Math.max(0L, timestamp - duration));
            step.setEndTime(timestamp);
            step.setTimestamp(timestamp);
            steps.add(step);
        }

        return steps;
    }

    private String extractJson(String response) {
        String cleaned = response.replaceAll("```json\\s*", "")
            .replaceAll("```\\s*", "")
            .trim();

        int start = cleaned.indexOf("{");
        int end = cleaned.lastIndexOf("}");

        if (start != -1 && end != -1 && end > start) {
            return cleaned.substring(start, end + 1);
        }

        return cleaned;
    }

    private WorkloadOptimizationResponse createValidationFailureResponse(List<String> sqls, String errorMessage) {
        WorkloadOptimizationResponse response = new WorkloadOptimizationResponse();
        response.setInputSqls(sqls);
        response.setSuccess(false);
        response.setErrorMessage(errorMessage);
        response.setRecommendedIndexes(new ArrayList<>());
        return response;
    }

    private WorkloadOptimizationResponse createErrorResponse(List<String> sqls, String errorMessage) {
        WorkloadOptimizationResponse response = new WorkloadOptimizationResponse();
        response.setInputSqls(sqls);
        response.setSuccess(false);
        response.setErrorMessage(errorMessage);
        response.setRecommendedIndexes(new ArrayList<>());
        return response;
    }
}
