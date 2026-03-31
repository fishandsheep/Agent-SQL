package com.sqlagent.tools;

import com.sqlagent.gateway.ToolGateway;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

@Slf4j
@Component
@RequiredArgsConstructor
public class SqlTools {

    private static final String CURRENT_SESSION = "current";

    private final ExplainTool explainTool;
    private final DDLTool ddlTool;
    private final ExecuteTool executeTool;
    private final CompareTool compareTool;
    private final IndexTool indexTool;
    private final StatisticsTool statisticsTool;
    private final CoveringIndexTool coveringIndexTool;
    private final DataSkewTool dataSkewTool;
    private final IndexRecommendationEngine indexRecommendationEngine;
    private final ToolGateway toolGateway;

    // 中文：获取 SQL 的 EXPLAIN 执行计划。
    @Tool("Get the EXPLAIN execution plan for a SQL statement.")
    public String explainPlan(String sql) {
        return invokeAndFormatTool("explainPlan", new Object[]{sql}, () -> explainTool.explain(sql));
    }

    // 中文：获取表结构和索引定义。
    @Tool("Get table DDL and index definitions.")
    public String getDDL(String tableName) {
        return invokeAndFormatTool("getDDL", new Object[]{tableName}, () -> ddlTool.getDDL(tableName));
    }

    // 中文：执行 SQL 并返回结果摘要。
    @Tool("Execute a SQL statement and return the result summary.")
    public String executeSql(String sql) {
        return invokeAndFormatTool("executeSql", new Object[]{sql}, () -> executeTool.execute(sql));
    }

    // 中文：比较两条 SQL 的结果是否一致。
    @Tool("Compare whether two SQL statements return equivalent results.")
    public String compareResult(String sql1, String sql2) {
        return invokeAndFormatTool("compareResult", new Object[]{sql1, sql2}, () -> compareTool.compare(sql1, sql2));
    }

    // 中文：管理索引，仅供受控流程调用。
    @Tool("Manage indexes for controlled validation workflows.")
    public String manageIndex(
        String action,
        String tableName,
        String indexName,
        String[] columns,
        boolean unique
    ) {
        List<String> columnList = columns != null ? Arrays.asList(columns) : null;
        Object[] args = new Object[]{action, tableName, indexName, columnList, unique};
        return invokeRawTool(
            "manageIndex",
            args,
            () -> indexTool.manageIndex(action, tableName, indexName, columnList, unique)
        );
    }

    // 中文：分析表统计信息。
    @Tool("Analyze table statistics such as cardinality and selectivity.")
    public String analyzeStatistics(String tableName) {
        return invokeAndFormatTool(
            "analyzeStatistics",
            new Object[]{tableName},
            () -> statisticsTool.analyzeTableStatistics(tableName)
        );
    }

    // 中文：分析覆盖索引优化机会。
    @Tool("Analyze whether a query can benefit from a covering index.")
    public String analyzeCoveringIndex(String sql, String tableName) {
        return invokeAndFormatTool(
            "analyzeCoveringIndex",
            new Object[]{sql, tableName},
            () -> coveringIndexTool.analyzeCoveringIndex(sql, tableName)
        );
    }

    // 中文：分析字段的数据倾斜情况。
    @Tool("Analyze data skew for a specific table column.")
    public String analyzeDataSkew(String tableName, String columnName) {
        return invokeAndFormatTool(
            "analyzeDataSkew",
            new Object[]{tableName, columnName},
            () -> dataSkewTool.analyzeDataSkew(tableName, columnName)
        );
    }

    // 中文：根据 SQL 推荐候选索引。
    @Tool("Recommend candidate indexes for a SQL statement.")
    public String recommendIndex(String sql, String tableName) {
        return invokeAndFormatTool(
            "recommendIndex",
            new Object[]{sql, tableName},
            () -> indexRecommendationEngine.recommendIndex(sql, tableName)
        );
    }

    private String invokeAndFormatTool(String toolName, Object[] args, Supplier<Object> supplier) {
        Object result = invokeTool(toolName, args, supplier);
        return formatResult(result);
    }

    private String invokeRawTool(String toolName, Object[] args, Supplier<String> supplier) {
        return invokeTool(toolName, args, supplier);
    }

    private <T> T invokeTool(String toolName, Object[] args, Supplier<T> supplier) {
        long startTime = System.currentTimeMillis();
        log.info("[sql-tools] invoking tool: tool={}, args={}", toolName, Arrays.deepToString(args));
        try {
            T result = supplier.get();
            long duration = System.currentTimeMillis() - startTime;
            toolGateway.logToolCall(CURRENT_SESSION, toolName, args, duration);
            log.info("[sql-tools] tool completed: tool={}, durationMs={}", toolName, duration);
            return result;
        } catch (RuntimeException e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("[sql-tools] tool failed: tool={}, durationMs={}, error={}", toolName, duration, e.getMessage(), e);
            throw e;
        }
    }

    private String formatResult(Object result) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        } catch (Exception e) {
            log.error("failed to format tool result", e);
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }
}
