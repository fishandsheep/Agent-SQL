package com.sqlagent.tools;

import com.sqlagent.config.DatabaseConnectionManager;
import com.sqlagent.model.CoveringIndexResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 覆盖索引分析工具
 *
 * 分析SQL是否可以使用覆盖索引优化，避免回表操作
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CoveringIndexTool {

    private final DatabaseConnectionManager databaseConnectionManager;
    private final SqlPatternAdvisor sqlPatternAdvisor;

    /**
     * 分析覆盖索引优化机会
     *
     * @param sql SQL语句
     * @param tableName 表名
     * @return 覆盖索引分析结果
     */
    public CoveringIndexResult analyzeCoveringIndex(String sql, String tableName) {
        CoveringIndexResult result = new CoveringIndexResult();
        result.setOriginalSql(sql);
        result.setTableName(tableName);

        if (sql == null || sql.trim().isEmpty()) {
            result.setError("SQL为空");
            return result;
        }

        try {
            // 解析SQL，提取SELECT列和WHERE列
            List<String> selectedColumns = extractSelectedColumns(sql);
            DataSource dataSource = databaseConnectionManager.getDefaultDataSource();
            List<String> whereColumns = extractWhereColumns(sql, dataSource, tableName);

            if (selectedColumns == null) {
                result.setError("SQL解析失败");
                return result;
            }

            result.setSelectedColumns(selectedColumns);
            result.setWhereColumns(whereColumns);

            // 获取现有索引
            List<IndexInfo> existingIndexes = getIndexes(dataSource, tableName);

            List<String> indexNames = new ArrayList<>();
            for (IndexInfo index : existingIndexes) {
                indexNames.add(index.getName() + "(" + String.join(",", index.getColumns()) + ")");
            }
            result.setExistingIndexes(indexNames);

            // 检查是否存在覆盖索引
            boolean hasCoveringIndex = false;
            String coveringIndexName = null;

            for (IndexInfo index : existingIndexes) {
                if (isCoveringIndex(selectedColumns, whereColumns, index.getColumns())) {
                    hasCoveringIndex = true;
                    coveringIndexName = index.getName();
                    break;
                }
            }

            result.setHasCoveringIndex(hasCoveringIndex);
            result.setCoveringIndexName(coveringIndexName);

            // 判断是否存在回表问题
            // 如果SELECT * 或者有列不在索引中，则存在回表
            boolean hasTableLookup = selectedColumns.contains("*") ||
                                    (!hasCoveringIndex && !selectedColumns.isEmpty());
            result.setHasTableLookup(hasTableLookup);

            // 推荐覆盖索引
            if (!hasCoveringIndex && !selectedColumns.isEmpty() && !selectedColumns.contains("*")) {
                List<String> recommendedColumns = new ArrayList<>();
                recommendedColumns.addAll(whereColumns);
                // 添加SELECT列中不在WHERE中的列
                for (String col : selectedColumns) {
                    if (!col.equals("*") && !recommendedColumns.contains(col)) {
                        recommendedColumns.add(col);
                    }
                }

                if (!recommendedColumns.isEmpty()) {
                    String indexDef = String.format("CREATE INDEX idx_covering_%s ON %s (%s)",
                            tableName.substring(0, Math.min(3, tableName.length())),
                            tableName,
                            String.join(", ", recommendedColumns));
                    result.setRecommendedIndex(indexDef);
                }
            }

            // 收益分析
            result.setBenefitAnalysis(generateBenefitAnalysis(result));

            log.info("覆盖索引分析完成: table={}, hasCoveringIndex={}", tableName, hasCoveringIndex);

        } catch (Exception e) {
            log.error("覆盖索引分析失败: {}", e.getMessage(), e);
            result.setError("分析失败: " + e.getMessage());
        }

        return result;
    }

    /**
     * 提取SELECT列表中的列名
     */
    private List<String> extractSelectedColumns(String sql) {
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            if (statement instanceof Select) {
                Select select = (Select) statement;
                if (select.getSelectBody() instanceof PlainSelect) {
                    PlainSelect plainSelect = (PlainSelect) select.getSelectBody();
                    List<SelectItem> selectItems = plainSelect.getSelectItems();

                    List<String> columns = new ArrayList<>();
                    for (SelectItem item : selectItems) {
                        String itemStr = item.toString();
                        // 处理别名
                        if (itemStr.contains(" AS ")) {
                            itemStr = itemStr.substring(0, itemStr.indexOf(" AS "));
                        } else if (itemStr.contains(" as ")) {
                            itemStr = itemStr.substring(0, itemStr.indexOf(" as "));
                        }
                        // 去除表名前缀
                        if (itemStr.contains(".")) {
                            itemStr = itemStr.substring(itemStr.indexOf(".") + 1);
                        }
                        columns.add(itemStr.trim());
                    }
                    return columns;
                }
            }
        } catch (JSQLParserException e) {
            log.warn("解析SQL SELECT列表失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 提取WHERE条件中的列名（简单实现）
     */
    private List<String> extractWhereColumns(String sql, DataSource dataSource, String tableName) throws SQLException {
        List<com.sqlagent.model.ColumnStatistics> columns = new ArrayList<>();
        for (String columnName : getTableColumns(dataSource, tableName)) {
            com.sqlagent.model.ColumnStatistics statistics = new com.sqlagent.model.ColumnStatistics();
            statistics.setColumnName(columnName);
            columns.add(statistics);
        }
        return sqlPatternAdvisor.analyze(sql, columns).getWhereColumns();
    }

    /**
     * 判断索引是否为覆盖索引
     */
    private boolean isCoveringIndex(List<String> selectedColumns,
                                    List<String> whereColumns,
                                    List<String> indexColumns) {
        if (selectedColumns.isEmpty() || indexColumns.isEmpty()) {
            return false;
        }

        // 如果是SELECT *，需要检查索引是否包含所有列
        if (selectedColumns.contains("*")) {
            return false; // 无法确定所有列，暂不认为是覆盖索引
        }

        // 检查SELECT列是否都在索引中
        Set<String> indexCols = new HashSet<>(indexColumns);
        for (String col : selectedColumns) {
            if (!col.equals("*") && !indexCols.contains(col)) {
                return false;
            }
        }

        return true;
    }

    /**
     * 获取表的索引信息
     */
    private List<IndexInfo> getIndexes(DataSource dataSource, String tableName) throws SQLException {
        List<IndexInfo> indexes = new ArrayList<>();

        String sql = "SELECT INDEX_NAME, COLUMN_NAME, SEQ_IN_INDEX " +
                     "FROM INFORMATION_SCHEMA.STATISTICS " +
                     "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? " +
                     "ORDER BY INDEX_NAME, SEQ_IN_INDEX";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableName);
            ResultSet rs = ps.executeQuery();

            String currentIndex = null;
            List<String> columns = new ArrayList<>();

            while (rs.next()) {
                String indexName = rs.getString("INDEX_NAME");
                String columnName = rs.getString("COLUMN_NAME");

                if (!indexName.equals(currentIndex)) {
                    if (currentIndex != null) {
                        indexes.add(new IndexInfo(currentIndex, new ArrayList<>(columns)));
                    }
                    currentIndex = indexName;
                    columns.clear();
                }
                columns.add(columnName);
            }

            // 添加最后一个索引
            if (currentIndex != null) {
                indexes.add(new IndexInfo(currentIndex, columns));
            }
        }

        return indexes;
    }

    private List<String> getTableColumns(DataSource dataSource, String tableName) throws SQLException {
        List<String> columns = new ArrayList<>();
        String sql = "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableName);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                columns.add(rs.getString("COLUMN_NAME"));
            }
        }
        return columns;
    }

    /**
     * 生成收益分析
     */
    private String generateBenefitAnalysis(CoveringIndexResult result) {
        StringBuilder sb = new StringBuilder();

        if (result.isHasCoveringIndex()) {
            sb.append("✅ 已存在覆盖索引: ").append(result.getCoveringIndexName());
            sb.append("\n当前SQL可以使用覆盖索引，避免回表操作，性能优异。");
        } else if (result.isHasTableLookup() && result.getRecommendedIndex() != null) {
            sb.append("⚠️ 存在回表问题，建议创建覆盖索引");
            sb.append("\n创建覆盖索引后可以：");
            sb.append("\n1. 避免回表查询，减少IO操作");
            sb.append("\n2. 提升查询性能，特别是大表场景");
            sb.append("\n3. 降低CPU使用率");
        } else {
            sb.append("ℹ️ 当前SQL无明显的覆盖索引优化机会");
        }

        return sb.toString();
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
