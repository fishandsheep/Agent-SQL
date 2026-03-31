package com.sqlagent.tools;

import com.sqlagent.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 索引推荐引擎
 *
 * 基于SQL、统计信息和执行计划，推荐最优索引组合
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IndexRecommendationEngine {

    private final StatisticsTool statisticsTool;
    private final ExplainTool explainTool;
    private final DDLTool ddlTool;
    private final SqlPatternAdvisor sqlPatternAdvisor;

    // 索引评分权重
    private static final int SELECTIVITY_WEIGHT = 40;
    private static final int EXECUTION_PLAN_WEIGHT = 30;
    private static final int COVERAGE_WEIGHT = 20;
    private static final int CARDINALITY_WEIGHT = 10;

    /**
     * 推荐索引
     *
     * @param sql SQL语句
     * @param tableName 表名
     * @return 索引推荐结果
     */
    public IndexRecommendationResult recommendIndex(String sql, String tableName) {
        IndexRecommendationResult result = new IndexRecommendationResult();
        result.setTableName(tableName);
        result.setAnalyzedSql(sql);

        if (sql == null || sql.trim().isEmpty()) {
            result.setError("SQL为空");
            return result;
        }

        if (tableName == null || tableName.trim().isEmpty()) {
            result.setError("表名为空");
            return result;
        }

        try {
            // 1. 获取执行计划（JSON字符串）
            String explainJson = explainTool.explain(sql);
            if (explainJson == null || explainJson.contains("\"error\"")) {
                result.setError("无法获取执行计划");
                return result;
            }
            // 从JSON中提取评分（简化处理）
            int currentScore = 50; // 默认值
            if (explainJson.contains("\"score\"")) {
                // 简单提取score值
                int scoreIdx = explainJson.indexOf("\"score\"");
                if (scoreIdx > 0 && scoreIdx < explainJson.length() - 10) {
                    String scoreStr = explainJson.substring(scoreIdx + 8, scoreIdx + 12).split("[,}\\\"]")[0];
                    try {
                        currentScore = Integer.parseInt(scoreStr.trim());
                    } catch (Exception e) {
                        // 使用默认值
                    }
                }
            }
            result.setCurrentScore(currentScore);

            // 2. 获取表统计信息
            StatisticsResult statsResult = statisticsTool.analyzeTableStatistics(tableName);
            if (statsResult == null || statsResult.getError() != null) {
                result.setError("无法获取统计信息");
                return result;
            }

            // 3. 解析SQL，提取相关列
            SqlPatternAdvisor.Analysis patternAnalysis = sqlPatternAdvisor.analyze(sql, statsResult.getColumns());
            List<String> whereColumns = patternAnalysis.getWhereColumns();
            List<String> selectColumns = extractSelectColumns(sql);
            List<String> joinColumns = extractJoinColumns(sql);
            List<String> orderColumns = extractOrderColumns(sql);

            // 4. 获取现有索引
            List<IndexInfo> existingIndexes = getExistingIndexes(tableName);

            // 5. 生成推荐方案
            List<IndexRecommendation> recommendations = new ArrayList<>();

            // 推荐方案1：基于WHERE条件的单列/复合索引
            if (!whereColumns.isEmpty()) {
                IndexRecommendation rec1 = recommendForWhereColumns(whereColumns, statsResult, explainJson, patternAnalysis);
                if (rec1 != null && !isIndexExists(rec1.getColumns(), existingIndexes)) {
                    recommendations.add(rec1);
                }
            }

            // 推荐方案2：覆盖索引
            if (!selectColumns.isEmpty() && !selectColumns.contains("*")) {
                IndexRecommendation rec2 = recommendCoveringIndex(whereColumns, selectColumns, statsResult);
                if (rec2 != null && !isIndexExists(rec2.getColumns(), existingIndexes)) {
                    recommendations.add(rec2);
                }
            }

            // 推荐方案3：JOIN优化索引
            if (!joinColumns.isEmpty()) {
                IndexRecommendation rec3 = recommendForJoinColumns(joinColumns, statsResult);
                if (rec3 != null && !isIndexExists(rec3.getColumns(), existingIndexes)) {
                    recommendations.add(rec3);
                }
            }

            result.setRecommendations(recommendations);

            // 6. 选择最佳推荐
            if (!recommendations.isEmpty()) {
                IndexRecommendation best = recommendations.stream()
                        .max(Comparator.comparingInt(IndexRecommendation::getPriorityScore))
                        .orElse(null);
                result.setBestRecommendation(best);
                result.setEstimatedScore(best.getPriorityScore());

                result.setAnalysis(generateAnalysis(result, explainJson, statsResult, patternAnalysis));
            } else {
                String diagnostics = patternAnalysis.getDiagnostics().isEmpty()
                    ? "当前SQL已有较优的索引配置，暂无额外推荐"
                    : "当前未形成更强的索引推荐，但检测到如下风险：\n- " + String.join("\n- ", patternAnalysis.getDiagnostics());
                result.setAnalysis(diagnostics);
            }

            log.info("索引推荐完成: table={}, recommendations={}", tableName, recommendations.size());

        } catch (Exception e) {
            log.error("索引推荐失败: {}", e.getMessage(), e);
            result.setError("推荐失败: " + e.getMessage());
        }

        return result;
    }

    /**
     * 推荐基于WHERE条件的索引
     */
    private IndexRecommendation recommendForWhereColumns(List<String> whereColumns,
                                                          StatisticsResult statsResult,
                                                          String explainJson,
                                                          SqlPatternAdvisor.Analysis patternAnalysis) {
        if (whereColumns.isEmpty()) {
            return null;
        }

        List<String> sortedColumns = whereColumns.stream()
                .distinct()
                .sorted((a, b) -> compareIndexPriority(a, b, statsResult, patternAnalysis))
                .collect(Collectors.toList());

        if (sortedColumns.isEmpty()) {
            return null;
        }

        List<String> indexColumns = new ArrayList<>();
        List<String> selectiveColumns = sortedColumns.stream()
                .filter(col -> !patternAnalysis.getWeakFilterColumns().contains(col))
                .toList();

        if (!selectiveColumns.isEmpty()) {
            indexColumns.add(selectiveColumns.get(0));
            sortedColumns.stream()
                    .filter(col -> !indexColumns.contains(col))
                    .limit(1)
                    .forEach(indexColumns::add);
        } else {
            indexColumns.addAll(sortedColumns.stream().limit(Math.min(2, sortedColumns.size())).toList());
        }

        IndexRecommendation rec = new IndexRecommendation();
        rec.setIndexName("idx_where_" + generateSuffix(indexColumns));
        rec.setColumns(indexColumns);
        rec.setUnique(false);
        rec.setIndexType(IndexRecommendation.INDEX_BTREE);

        // 计算评分
        int score = calculateScore(indexColumns, statsResult, explainJson);
        rec.setPriorityScore(score);

        rec.setReason(indexColumns.size() > 1
                ? "基于 WHERE 条件推荐的复合索引，可先利用高选择性列缩小范围，再结合其他过滤列进一步减少扫描"
                : "基于 WHERE 条件推荐的索引，可以提升查询过滤效率");
        rec.setCreateSql(generateCreateSql(rec.getIndexName(), statsResult.getTableName(), rec.getColumns()));
        rec.setEstimatedBenefit(score / 100.0);

        return rec;
    }

    private int compareIndexPriority(String left,
                                     String right,
                                     StatisticsResult statsResult,
                                     SqlPatternAdvisor.Analysis patternAnalysis) {
        int leftPriority = getPredicatePriority(left, statsResult, patternAnalysis);
        int rightPriority = getPredicatePriority(right, statsResult, patternAnalysis);
        if (leftPriority != rightPriority) {
            return Integer.compare(rightPriority, leftPriority);
        }
        return compareSelectivity(left, right, statsResult);
    }

    private int getPredicatePriority(String column,
                                     StatisticsResult statsResult,
                                     SqlPatternAdvisor.Analysis patternAnalysis) {
        if (patternAnalysis.getWeakFilterColumns().contains(column)) {
            return 0;
        }
        int priority = 0;
        if (patternAnalysis.getEqualityColumns().contains(column)) {
            priority += 300;
        }
        if (patternAnalysis.getInColumns().contains(column)) {
            priority += 180;
        }
        if (patternAnalysis.getRangeColumns().contains(column)) {
            priority += 120;
        }
        if (patternAnalysis.getFunctionColumns().contains(column)) {
            priority -= 20;
        }
        priority += (int) Math.round(getSelectivity(column, statsResult) * 100);
        return priority;
    }

    /**
     * 推荐覆盖索引
     */
    private IndexRecommendation recommendCoveringIndex(List<String> whereColumns,
                                                       List<String> selectColumns,
                                                       StatisticsResult statsResult) {
        List<String> indexColumns = new ArrayList<>();

        // 1. 添加WHERE条件列（高选择性优先）
        whereColumns.stream()
                .filter(col -> isHighSelectivity(col, statsResult))
                .sorted((a, b) -> compareSelectivity(a, b, statsResult))
                .forEach(indexColumns::add);

        // 2. 添加SELECT列（避免回表）
        for (String col : selectColumns) {
            if (!col.equals("*") && !indexColumns.contains(col)) {
                indexColumns.add(col);
            }
        }

        if (indexColumns.isEmpty()) {
            return null;
        }

        IndexRecommendation rec = new IndexRecommendation();
        rec.setIndexName("idx_covering_" + generateSuffix(indexColumns.subList(0, Math.min(2, indexColumns.size()))));
        rec.setColumns(indexColumns);
        rec.setUnique(false);
        rec.setIndexType(IndexRecommendation.INDEX_BTREE);

        // 覆盖索引额外加分
        int score = calculateScore(indexColumns, statsResult, null) + 10;
        rec.setPriorityScore(Math.min(score, 100));

        rec.setReason("覆盖索引，可避免回表操作，显著提升性能");
        rec.setCreateSql(generateCreateSql(rec.getIndexName(), statsResult.getTableName(), rec.getColumns()));
        rec.setEstimatedBenefit(0.4 + (score / 200.0)); // 额外收益

        return rec;
    }

    /**
     * 推荐JOIN优化索引
     */
    private IndexRecommendation recommendForJoinColumns(List<String> joinColumns,
                                                        StatisticsResult statsResult) {
        List<String> sortedColumns = joinColumns.stream()
                .filter(col -> isHighSelectivity(col, statsResult))
                .sorted((a, b) -> compareSelectivity(a, b, statsResult))
                .collect(Collectors.toList());

        if (sortedColumns.isEmpty()) {
            return null;
        }

        IndexRecommendation rec = new IndexRecommendation();
        rec.setIndexName("idx_join_" + generateSuffix(sortedColumns));
        rec.setColumns(sortedColumns);
        rec.setUnique(false);
        rec.setIndexType(IndexRecommendation.INDEX_BTREE);

        int score = calculateScore(sortedColumns, statsResult, null);
        rec.setPriorityScore(score);

        rec.setReason("JOIN优化索引，可提升多表关联性能");
        rec.setCreateSql(generateCreateSql(rec.getIndexName(), statsResult.getTableName(), rec.getColumns()));
        rec.setEstimatedBenefit(score / 150.0);

        return rec;
    }

    /**
     * 计算索引评分
     */
    private int calculateScore(List<String> columns, StatisticsResult statsResult, String explainJson) {
        int score = 0;

        // 1. 选择性评分（40分）
        double avgSelectivity = columns.stream()
                .mapToDouble(col -> getSelectivity(col, statsResult))
                .average()
                .orElse(0);
        score += (int) (avgSelectivity * SELECTIVITY_WEIGHT);

        // 2. 执行计划评分（30分）- 基于JSON字符串判断
        if (explainJson != null) {
            if (explainJson.contains("\"type\":\"ALL\"") || explainJson.contains("\"type\": \"ALL\"")) {
                score += EXECUTION_PLAN_WEIGHT; // 全表扫描，建索引收益大
            } else if (explainJson.contains("\"key\":null") || explainJson.contains("\"key\": null")) {
                score += EXECUTION_PLAN_WEIGHT / 2;
            }
        }

        // 3. 覆盖度评分（20分）
        score += Math.min(columns.size() * 5, COVERAGE_WEIGHT);

        // 4. 基数评分（10分）
        long totalRows = statsResult.getTotalRows();
        if (totalRows > 100000) {
            score += CARDINALITY_WEIGHT;
        } else if (totalRows > 10000) {
            score += CARDINALITY_WEIGHT / 2;
        }

        return Math.min(score, 100);
    }

    /**
     * 判断是否为高选择性列
     */
    private boolean isHighSelectivity(String columnName, StatisticsResult statsResult) {
        if (statsResult.getColumns() == null) {
            return false;
        }
        return statsResult.getColumns().stream()
                .filter(col -> col.getColumnName().equals(columnName))
                .anyMatch(col -> "HIGH".equals(col.getSelectivityLevel()) ||
                                "MEDIUM".equals(col.getSelectivityLevel()));
    }

    /**
     * 获取列的选择性
     */
    private double getSelectivity(String columnName, StatisticsResult statsResult) {
        if (statsResult.getColumns() == null) {
            return 0.0;
        }
        return statsResult.getColumns().stream()
                .filter(col -> col.getColumnName().equals(columnName))
                .map(ColumnStatistics::getSelectivity)
                .findFirst()
                .orElse(0.0);
    }

    /**
     * 比较两列的选择性
     */
    private int compareSelectivity(String col1, String col2, StatisticsResult statsResult) {
        double s1 = getSelectivity(col1, statsResult);
        double s2 = getSelectivity(col2, statsResult);
        return Double.compare(s2, s1); // 降序
    }

    /**
     * 判断索引是否已存在
     */
    private boolean isIndexExists(List<String> columns, List<IndexInfo> existingIndexes) {
        Set<String> colSet = new HashSet<>(columns);

        for (IndexInfo index : existingIndexes) {
            if (new HashSet<>(index.getColumns()).equals(colSet)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 生成索引名称后缀
     */
    private String generateSuffix(List<String> columns) {
        return String.join("_", columns.subList(0, Math.min(2, columns.size())));
    }

    /**
     * 生成CREATE INDEX语句
     */
    private String generateCreateSql(String indexName, String tableName, List<String> columns) {
        return String.format("CREATE INDEX %s ON %s (%s)",
                indexName, tableName, String.join(", ", columns));
    }

    /**
     * 生成分析说明
     */
    private String generateAnalysis(IndexRecommendationResult result,
                                    String explainJson,
                                    StatisticsResult statsResult,
                                    SqlPatternAdvisor.Analysis patternAnalysis) {
        StringBuilder sb = new StringBuilder();
        sb.append("基于以下分析生成推荐：\n");
        sb.append(String.format("- 当前执行计划评分: %d\n", result.getCurrentScore()));
        sb.append(String.format("- 表总行数: %d\n", statsResult.getTotalRows()));
        sb.append(String.format("- 生成推荐方案: %d个\n", result.getRecommendations().size()));
        if (!patternAnalysis.getDiagnostics().isEmpty()) {
            sb.append("- 识别到的关键风险:\n");
            for (String diagnostic : patternAnalysis.getDiagnostics()) {
                sb.append("  * ").append(diagnostic).append("\n");
            }
        }

        if (result.getBestRecommendation() != null) {
            sb.append(String.format("\n最佳推荐: %s\n", result.getBestRecommendation().getIndexName()));
            sb.append(String.format("推荐理由: %s\n", result.getBestRecommendation().getReason()));
            sb.append(String.format("预计评分提升: %d -> %d\n",
                    result.getCurrentScore(), result.getEstimatedScore()));
        }

        return sb.toString();
    }

    /**
     * 提取SELECT列
     */
    private List<String> extractSelectColumns(String sql) {
        List<String> columns = new ArrayList<>();
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            if (statement instanceof Select) {
                Select select = (Select) statement;
                if (select.getSelectBody() instanceof PlainSelect) {
                    PlainSelect plainSelect = (PlainSelect) select.getSelectBody();
                    if (plainSelect.getSelectItems() != null) {
                        for (Object item : plainSelect.getSelectItems()) {
                            columns.add(item.toString());
                        }
                    }
                }
            }
        } catch (JSQLParserException e) {
            log.warn("解析SELECT失败: {}", e.getMessage());
        }
        return columns;
    }

    /**
     * 提取JOIN列（简化实现）
     */
    private List<String> extractJoinColumns(String sql) {
        // 简化版本：实际应该从AST中提取JOIN条件
        return new ArrayList<>();
    }

    /**
     * 提取ORDER BY列
     */
    private List<String> extractOrderColumns(String sql) {
        List<String> columns = new ArrayList<>();
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            if (statement instanceof Select) {
                Select select = (Select) statement;
                if (select.getSelectBody() instanceof PlainSelect) {
                    PlainSelect plainSelect = (PlainSelect) select.getSelectBody();
                    if (plainSelect.getOrderByElements() != null) {
                        for (Object elem : plainSelect.getOrderByElements()) {
                            columns.add(elem.toString());
                        }
                    }
                }
            }
        } catch (JSQLParserException e) {
            log.warn("解析ORDER BY失败: {}", e.getMessage());
        }
        return columns;
    }

    /**
     * 获取现有索引（简化实现，实际应该调用DDLTool）
     */
    private List<IndexInfo> getExistingIndexes(String tableName) {
        // 这里应该调用DDLTool获取索引信息
        // 简化返回空列表
        return new ArrayList<>();
    }

    /**
     * 索引信息内部类
     */
    private static class IndexInfo {
        private final String name;
        private final List<String> columns;

        public IndexInfo(String name, List<String> columns) {
            this.name = name;
            this.columns = columns;
        }

        public String getName() {
            return name;
        }

        public List<String> getColumns() {
            return columns;
        }
    }
}
