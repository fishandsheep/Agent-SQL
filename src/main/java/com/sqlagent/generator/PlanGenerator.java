package com.sqlagent.generator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlagent.agent.SqlMultiPlanAgent;
import com.sqlagent.gateway.ToolGateway;
import com.sqlagent.model.AgentExecutionStep;
import com.sqlagent.model.PlanCandidate;
import com.sqlagent.service.AgentRouter;
import com.sqlagent.service.ExecutionTraceService;
import com.sqlagent.tools.SqlPatternAdvisor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class PlanGenerator {
    private static final Pattern CREATE_INDEX_PATTERN = Pattern.compile(
        "(?i)^create\\s+index\\s+[`\\w]+\\s+on\\s+([`\\w.]+)\\s*\\(([^)]+)\\)"
    );

    private final AgentRouter agentRouter;
    private final ToolGateway toolGateway;
    private final ObjectMapper objectMapper;
    private final ExecutionTraceService executionTraceService;
    private final SqlPatternAdvisor sqlPatternAdvisor;

    public List<PlanCandidate> generatePlans(
        String originalSql,
        String sessionId,
        String model,
        int requestedMaxPlans
    ) {
        return generatePlans(originalSql, sessionId, model, requestedMaxPlans, null);
    }

    public List<PlanCandidate> generatePlans(
        String originalSql,
        String sessionId,
        String model,
        int requestedMaxPlans,
        String retryGuidance
    ) {
        toolGateway.activateSession(sessionId);
        int maxPlans = Math.max(1, Math.min(requestedMaxPlans, 5));

        executionTraceService.recordPhase(
            sessionId,
            "plan_generation",
            "生成候选方案",
            "RUNNING",
            "LLM 正在分析 SQL 并生成候选优化方案",
            "agent",
            truncate(originalSql, 180),
            "等待模型返回",
            0L
        );

        SqlMultiPlanAgent agent = agentRouter.getAgent(model);
        log.info("[PlanGenerator] use agent model={}", model != null ? model : "default");

        String agentResponse;
        long generationStart = System.currentTimeMillis();
        try {
            agentResponse = agent.generateMultiPlan(buildUserMessage(originalSql, maxPlans, retryGuidance));
        } catch (NullPointerException e) {
            log.error("[PlanGenerator] model response invalid", e);
            throw new RuntimeException("AI 返回了无效响应，请检查模型配置", e);
        } catch (Exception e) {
            log.error("[PlanGenerator] generate failed", e);
            throw new RuntimeException("方案生成失败: " + e.getMessage(), e);
        }

        List<PlanCandidate> candidates;
        try {
            candidates = parseAgentResponse(agentResponse, maxPlans, originalSql);
        } catch (Exception e) {
            log.error("[PlanGenerator] parse failed: {}", agentResponse, e);
            throw new RuntimeException("方案解析失败: " + e.getMessage(), e);
        }

        executionTraceService.recordPhase(
            sessionId,
            "plan_generation",
            "生成候选方案",
            "SUCCESS",
            "候选方案已生成",
            "agent",
            truncate(originalSql, 180),
            "共生成 " + candidates.size() + " 个候选方案",
            System.currentTimeMillis() - generationStart
        );

        for (PlanCandidate candidate : candidates) {
            executionTraceService.recordStep(sessionId, buildCandidateStep(candidate));
        }

        log.info("[PlanGenerator] generated {} candidates", candidates.size());
        return candidates;
    }

    private String buildUserMessage(String sql, int maxPlans, String retryGuidance) {
        String retrySection = (retryGuidance == null || retryGuidance.isBlank())
            ? ""
            : "\n补充要求（这是唯一一次补充生成）：" + "\n" + retryGuidance.trim() + "\n";
        return String.format("""
            请为下面这条 SQL 生成 1-%d 个高质量优化方案：
            %s
            %s

            当前执行方式：
            - 你可以调用只读分析工具获取更多证据。
            - 系统后续会自动执行并验证你的候选方案。
            - 你只负责提出方案，不负责真正执行 SQL 或创建索引。

            你的职责：
            - 你是数据库性能优化专家，负责分析 SQL 并提出优化方案。
            - 你只能做分析和建议，不能实际执行变更。
            - 系统后续会根据你的方案做执行、验证和评估。

            候选方案要求：
            - 方案类型优先使用：sql_rewrite、index_optimization、mixed_strategy
            - 每个方案都必须有清晰 reasoning，说明为什么它会更优
            - 每个方案都必须有 priority，1 为最高优先级
            - 最多生成 %d 个方案，但不要为了凑满数量生成弱方案
            - 如果只有 1-2 个方案真正成立，就只返回 1-2 个

            输出说明：
            - optimizedSql：优化后的 SQL；如果该方案不改写 SQL，则为 null 或空
            - indexDDL：推荐索引 DDL；如果该方案不建索引，则为 null 或空
            - 只返回 JSON，不要输出任何额外说明

            额外约束：
            - 如果基线 EXPLAIN 已命中有效索引，且没有明确性能瓶颈，不要输出“确认索引存在”或重复建同类索引作为优化方案。
            - 如果判断结果是“当前 SQL 基本无需优化”，最多只保留 1 个可选的 SQL 规范化建议，不要为了凑数量生成多个方案。
            - 必须遵守 MySQL 语法，不要输出 INCLUDE 之类 MySQL 不支持的索引语法。
            """, maxPlans, sql.trim(), retrySection, maxPlans);
    }

    @SuppressWarnings("unchecked")
    private List<PlanCandidate> parseAgentResponse(String response, int maxPlans, String originalSql) {
        String cleaned = response.trim();
        int jsonStart = cleaned.indexOf("{");
        if (jsonStart > 0) {
            cleaned = cleaned.substring(jsonStart);
        }

        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(3);
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.substring(0, cleaned.length() - 3);
        }
        cleaned = cleaned.trim();

        try {
            Map<String, Object> json = objectMapper.readValue(cleaned, new TypeReference<>() {});
            List<Map<String, Object>> plans = (List<Map<String, Object>>) json.get("plans");
            if (plans == null || plans.isEmpty()) {
                return new ArrayList<>();
            }
            if (plans.size() > maxPlans) {
                plans = plans.subList(0, maxPlans);
            }

            List<PlanCandidate> candidates = new ArrayList<>();
            SqlPatternAdvisor.Analysis originalAnalysis = sqlPatternAdvisor.analyze(originalSql, null);
            for (Map<String, Object> plan : plans) {
                PlanCandidate candidate = PlanCandidate.builder()
                    .planId((String) plan.get("planId"))
                    .type(normalizePlanType((String) plan.get("type")))
                    .optimizedSql((String) plan.get("sql"))
                    .indexDDL((String) plan.get("indexDDL"))
                    .description((String) plan.get("description"))
                    .reasoning((String) plan.get("reasoning"))
                    .priority(parseInteger(plan.get("priority")))
                    .estimatedImpact((String) plan.get("estimatedImpact"))
                    .riskLevel((String) plan.get("riskLevel"))
                    .build();
                candidates.add(applyDeterministicRewrite(candidate, originalSql, originalAnalysis));
            }
            return supplementDeterministicCandidates(candidates, originalSql, originalAnalysis, maxPlans);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("无效的 JSON 响应: " + e.getMessage(), e);
        }
    }

    private List<PlanCandidate> supplementDeterministicCandidates(
        List<PlanCandidate> candidates,
        String originalSql,
        SqlPatternAdvisor.Analysis originalAnalysis,
        int maxPlans
    ) {
        List<PlanCandidate> supplemented = new ArrayList<>(candidates);
        if (originalAnalysis == null) {
            return supplemented;
        }

        String joinRewrite = containsJoinRewrite(originalAnalysis.getSuggestedSql()) ? originalAnalysis.getSuggestedSql() : null;
        String existsRewrite = sqlPatternAdvisor.rewriteSimpleInSubqueryToExists(originalSql);
        boolean hasDeterministicMixed = joinRewrite != null
            && originalAnalysis.getSuggestedIndexDdls() != null
            && !originalAnalysis.getSuggestedIndexDdls().isEmpty();

        if (hasDeterministicMixed && supplemented.stream().noneMatch(this::isMixedCandidate)) {
            PlanCandidate mixedCandidate = applyDeterministicRewrite(
                PlanCandidate.builder()
                    .planId(findReusablePlanIdForPureRewrite(supplemented))
                    .type("mixed_strategy")
                    .optimizedSql(joinRewrite)
                    .description("规则补充：半连接改写并同步优化主表访问路径")
                    .reasoning("模型未给出稳定的混合方案时，系统补充一个基于半连接改写和访问路径索引排序的保底候选，避免只优化小表或只做单一改动。")
                    .priority(nextPriority(supplemented))
                    .build(),
                originalSql,
                originalAnalysis
            );
            upsertCandidate(supplemented, mixedCandidate, maxPlans);
        }

        if (existsRewrite != null && supplemented.stream().noneMatch(candidate -> containsExistsRewrite(candidate.getOptimizedSql()))) {
            PlanCandidate existsCandidate = applyDeterministicRewrite(
                PlanCandidate.builder()
                    .planId(findReusableJoinRewritePlanId(supplemented))
                    .type("sql_rewrite")
                    .optimizedSql(existsRewrite)
                    .description("规则补充：将简单 IN 子查询改写为 EXISTS")
                    .reasoning("模型未覆盖 EXISTS 形态时，系统补充一个语义等价的相关子查询写法，用于和 JOIN + DISTINCT 对比优化器选择。")
                    .priority(nextPriority(supplemented))
                    .build(),
                originalSql,
                originalAnalysis
            );
            upsertCandidate(supplemented, existsCandidate, maxPlans);
        }

        return supplemented;
    }

    private void upsertCandidate(List<PlanCandidate> candidates, PlanCandidate candidate, int maxPlans) {
        if (candidate == null || candidate.getPlanId() == null || candidate.getPlanId().isBlank()) {
            return;
        }

        for (int i = 0; i < candidates.size(); i++) {
            PlanCandidate existing = candidates.get(i);
            if (existing != null && candidate.getPlanId().equals(existing.getPlanId())) {
                candidates.set(i, candidate);
                return;
            }
        }

        if (candidates.size() < maxPlans) {
            candidate.setPlanId(nextPlanId(candidates));
            candidates.add(candidate);
        }
    }

    private boolean isMixedCandidate(PlanCandidate candidate) {
        return candidate != null
            && candidate.getType() != null
            && candidate.getType().toLowerCase(Locale.ROOT).contains("mixed");
    }

    private boolean containsExistsRewrite(String sql) {
        return sql != null && sql.toLowerCase(Locale.ROOT).contains("exists (select 1");
    }

    private String findReusableJoinRewritePlanId(List<PlanCandidate> candidates) {
        for (PlanCandidate candidate : candidates) {
            if (candidate != null
                && candidate.requiresRewrite()
                && !candidate.requiresIndex()
                && containsJoinRewrite(candidate.getOptimizedSql())) {
                return candidate.getPlanId();
            }
        }
        return nextPlanId(candidates);
    }

    private String findReusablePlanIdForPureRewrite(List<PlanCandidate> candidates) {
        for (PlanCandidate candidate : candidates) {
            if (candidate != null && candidate.requiresRewrite() && !candidate.requiresIndex()) {
                return candidate.getPlanId();
            }
        }
        return nextPlanId(candidates);
    }

    private String nextPlanId(List<PlanCandidate> candidates) {
        char maxId = '@';
        for (PlanCandidate candidate : candidates) {
            if (candidate == null || candidate.getPlanId() == null || candidate.getPlanId().isBlank()) {
                continue;
            }
            char current = Character.toUpperCase(candidate.getPlanId().charAt(0));
            if (current > maxId) {
                maxId = current;
            }
        }
        return String.valueOf((char) (maxId + 1));
    }

    private Integer nextPriority(List<PlanCandidate> candidates) {
        int maxPriority = candidates.stream()
            .map(PlanCandidate::getPriority)
            .filter(value -> value != null)
            .max(Integer::compareTo)
            .orElse(0);
        return Math.min(5, maxPriority + 1);
    }

    private PlanCandidate applyDeterministicRewrite(PlanCandidate candidate, String originalSql, SqlPatternAdvisor.Analysis originalAnalysis) {
        if (candidate == null) {
            return null;
        }

        String deterministicOriginalRewrite = originalAnalysis != null ? originalAnalysis.getSuggestedSql() : null;
        String optimizedSql = candidate.getOptimizedSql();
        if (shouldPromoteOriginalRewrite(candidate, originalAnalysis) && containsUnionAll(deterministicOriginalRewrite)) {
            optimizedSql = deterministicOriginalRewrite;
        } else if (optimizedSql != null && !optimizedSql.isBlank()) {
            SqlPatternAdvisor.Analysis candidateAnalysis = sqlPatternAdvisor.analyze(optimizedSql, null);
            if (shouldApplyCandidateRewrite(candidateAnalysis)) {
                optimizedSql = candidateAnalysis.getSuggestedSql();
            }
        } else if (shouldPromoteOriginalRewrite(candidate, originalAnalysis) && shouldApplyCandidateRewrite(originalAnalysis)) {
            optimizedSql = deterministicOriginalRewrite;
        }

        candidate.setOptimizedSql(optimizedSql);
        candidate.setIndexDDL(mergeDeterministicIndexDdls(candidate, originalAnalysis));
        return candidate;
    }

    private boolean shouldPromoteOriginalRewrite(PlanCandidate candidate, SqlPatternAdvisor.Analysis analysis) {
        if (analysis == null || analysis.getSuggestedSql() == null || analysis.getSuggestedSql().isBlank()) {
            return false;
        }
        String type = candidate.getType() != null ? candidate.getType().toLowerCase(Locale.ROOT) : "";
        return type.contains("rewrite") || type.contains("mixed");
    }

    private boolean containsUnionAll(String sql) {
        return sql != null && sql.toLowerCase(Locale.ROOT).contains("union all");
    }

    private boolean containsJoinRewrite(String sql) {
        if (sql == null) {
            return false;
        }
        String normalized = sql.toLowerCase(Locale.ROOT);
        return normalized.contains("join (select distinct") || normalized.contains("exists (select 1");
    }

    private boolean shouldApplyCandidateRewrite(SqlPatternAdvisor.Analysis analysis) {
        if (analysis == null || analysis.getSuggestedSql() == null || analysis.getSuggestedSql().isBlank()) {
            return false;
        }
        if (containsUnionAll(analysis.getSuggestedSql())) {
            return true;
        }
        if (containsJoinRewrite(analysis.getSuggestedSql())) {
            return true;
        }
        return analysis.isHasDateFunctionPredicate() || analysis.isHasImplicitTypeConversion();
    }

    private String mergeDeterministicIndexDdls(PlanCandidate candidate, SqlPatternAdvisor.Analysis analysis) {
        if (candidate == null) {
            return null;
        }

        List<IndexDdlSpec> merged = new ArrayList<>();
        appendDdlStatements(merged, candidate.getIndexDDL(), true);

        String type = candidate.getType() != null ? candidate.getType().toLowerCase(Locale.ROOT) : "";
        if (type.contains("mixed") && analysis != null && analysis.getSuggestedIndexDdls() != null) {
            for (String ddl : analysis.getSuggestedIndexDdls()) {
                appendDdlStatements(merged, ddl, false);
            }
        }

        List<String> selected = selectBestIndexPerTable(merged, analysis);
        return selected.isEmpty() ? candidate.getIndexDDL() : String.join(";\n", selected);
    }

    private void appendDdlStatements(List<IndexDdlSpec> merged, String ddlBundle, boolean fromCandidate) {
        if (ddlBundle == null || ddlBundle.isBlank()) {
            return;
        }
        for (String statement : ddlBundle.split(";")) {
            String normalized = statement.trim();
            if (!normalized.isEmpty() && merged.stream().noneMatch(existing -> existing.statement().equalsIgnoreCase(normalized))) {
                merged.add(parseIndexDdl(normalized, fromCandidate));
            }
        }
    }

    private List<String> selectBestIndexPerTable(List<IndexDdlSpec> ddlSpecs, SqlPatternAdvisor.Analysis analysis) {
        if (ddlSpecs == null || ddlSpecs.isEmpty()) {
            return List.of();
        }

        Map<String, IndexDdlSpec> bestByTable = new LinkedHashMap<>();
        List<String> passthrough = new ArrayList<>();
        for (IndexDdlSpec spec : ddlSpecs) {
            if (spec.tableName() == null || spec.tableName().isBlank()) {
                if (passthrough.stream().noneMatch(existing -> existing.equalsIgnoreCase(spec.statement()))) {
                    passthrough.add(spec.statement());
                }
                continue;
            }

            String tableKey = spec.tableName().toLowerCase(Locale.ROOT);
            IndexDdlSpec existing = bestByTable.get(tableKey);
            if (existing == null || compareIndexPriority(spec, existing, analysis) > 0) {
                bestByTable.put(tableKey, spec);
            }
        }

        List<String> selected = new ArrayList<>(passthrough);
        bestByTable.values().stream()
            .map(IndexDdlSpec::statement)
            .filter(statement -> selected.stream().noneMatch(existing -> existing.equalsIgnoreCase(statement)))
            .forEach(selected::add);
        return selected;
    }

    private int compareIndexPriority(IndexDdlSpec left, IndexDdlSpec right, SqlPatternAdvisor.Analysis analysis) {
        int leftScore = scoreIndexDdl(left, analysis);
        int rightScore = scoreIndexDdl(right, analysis);
        if (leftScore != rightScore) {
            return Integer.compare(leftScore, rightScore);
        }
        if (left.fromCandidate() != right.fromCandidate()) {
            return left.fromCandidate() ? 1 : -1;
        }
        if (left.columns().size() != right.columns().size()) {
            return Integer.compare(right.columns().size(), left.columns().size());
        }
        return 0;
    }

    private int scoreIndexDdl(IndexDdlSpec spec, SqlPatternAdvisor.Analysis analysis) {
        if (spec == null) {
            return Integer.MIN_VALUE;
        }
        if (analysis == null || spec.columns().isEmpty()) {
            return spec.fromCandidate() ? 1 : 0;
        }

        int score = 0;
        boolean rangeSeen = false;
        for (int i = 0; i < spec.columns().size(); i++) {
            String column = spec.columns().get(i);
            int positionWeight = switch (i) {
                case 0 -> 100;
                case 1 -> 70;
                case 2 -> 40;
                default -> 20;
            };

            int columnScore = 0;
            if (containsIgnoreCase(analysis.getEqualityColumns(), column)) {
                columnScore += 4 * positionWeight;
            } else if (containsIgnoreCase(analysis.getInColumns(), column)) {
                columnScore += 3 * positionWeight;
            } else if (containsIgnoreCase(analysis.getRangeColumns(), column)) {
                columnScore += 2 * positionWeight;
            }

            if (rangeSeen) {
                columnScore /= 2;
            }
            if (containsIgnoreCase(analysis.getWeakFilterColumns(), column)) {
                columnScore -= (i == 0 ? 5 : 2) * positionWeight;
            }

            score += columnScore;
            if (containsIgnoreCase(analysis.getRangeColumns(), column)) {
                rangeSeen = true;
            }
        }
        return score;
    }

    private boolean containsIgnoreCase(List<String> values, String target) {
        if (values == null || target == null) {
            return false;
        }
        return values.stream().anyMatch(value -> value != null && value.equalsIgnoreCase(target));
    }

    private IndexDdlSpec parseIndexDdl(String statement, boolean fromCandidate) {
        Matcher matcher = CREATE_INDEX_PATTERN.matcher(statement);
        if (!matcher.find()) {
            return new IndexDdlSpec(statement, null, List.of(), fromCandidate);
        }

        String tableName = matcher.group(1).replace("`", "").trim();
        List<String> columns = new ArrayList<>();
        for (String column : matcher.group(2).split(",")) {
            String normalized = column.replace("`", "").trim();
            if (!normalized.isEmpty()) {
                columns.add(normalized);
            }
        }
        return new IndexDdlSpec(statement, tableName, columns, fromCandidate);
    }

    private record IndexDdlSpec(String statement, String tableName, List<String> columns, boolean fromCandidate) {
    }

    private Integer parseInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Integer integer) {
            return integer;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String normalizePlanType(String type) {
        if (type == null) {
            return null;
        }
        String normalized = type.trim().toLowerCase();
        if ("hybrid_optimization".equals(normalized) || "hybrid_strategy".equals(normalized)) {
            return "mixed_strategy";
        }
        return normalized;
    }

    private AgentExecutionStep buildCandidateStep(PlanCandidate candidate) {
        AgentExecutionStep step = new AgentExecutionStep();
        step.setStepType("candidate_plan");
        step.setStepName("candidatePlan");
        step.setSourceType("agent");
        step.setDataSource("agent");
        step.setStatus("SUCCESS");
        step.setToolName(candidate.getPlanId());
        step.setDescription(String.format("候选方案 %s：%s", candidate.getPlanId(), safe(candidate.getDescription(), "未提供方案摘要")));
        step.setInputSummary("type=" + safe(candidate.getType(), "-"));
        step.setOutputSummary(buildCandidateOutput(candidate));
        step.setDetails(truncate(candidate.getReasoning(), 260));
        step.setExecutionTimeMs(0L);
        step.setStartTime(System.currentTimeMillis());
        step.setEndTime(step.getStartTime());
        step.setTimestamp(step.getEndTime());
        return step;
    }

    private String buildCandidateOutput(PlanCandidate candidate) {
        List<String> tags = new ArrayList<>();
        if (candidate.requiresRewrite()) {
            tags.add("SQL 改写");
        }
        if (candidate.requiresIndex()) {
            tags.add("索引优化");
        }
        if (candidate.getPriority() != null) {
            tags.add("priority=" + candidate.getPriority());
        }
        if (candidate.getRiskLevel() != null && !candidate.getRiskLevel().isBlank()) {
            tags.add("risk=" + candidate.getRiskLevel());
        }
        return tags.isEmpty() ? "生成候选方案" : String.join(" | ", tags);
    }

    private String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() > maxLength ? value.substring(0, maxLength) + "..." : value;
    }
}
