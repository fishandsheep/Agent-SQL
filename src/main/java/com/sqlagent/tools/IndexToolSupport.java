package com.sqlagent.tools;

import com.sqlagent.model.IndexResult;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Component
public class IndexToolSupport {

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("^[a-zA-Z0-9_]+$");

    public void validateIdentifier(String identifier, String fieldName) {
        if (identifier == null || identifier.trim().isEmpty()) {
            throw new IllegalArgumentException(fieldName + "不能为空");
        }

        if (identifier.length() > 64) {
            throw new IllegalArgumentException(fieldName + "长度不能超过64字符");
        }

        if (!IDENTIFIER_PATTERN.matcher(identifier).matches()) {
            throw new IllegalArgumentException(
                fieldName + "只能包含字母、数字和下划线: " + identifier
            );
        }
    }

    /**
     * 校验列名列表
     *
     * @param columns 列名列表
     */
    public void validateColumns(List<String> columns) {
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("列名列表不能为空");
        }

        if (columns.size() > 16) {
            throw new IllegalArgumentException("复合索引列数不能超过16个");
        }

        Set<String> seen = new HashSet<>();
        for (String column : columns) {
            validateIdentifier(column, "列名");
            if (seen.contains(column)) {
                throw new IllegalArgumentException("列名重复: " + column);
            }
            seen.add(column);
        }
    }

    /**
     * 查询表的所有索引
     *
     * @param conn      数据库连接
     * @param tableName 表名
     * @return 索引列表
     */
    public List<IndexResult.IndexInfo> getIndexInfo(Connection conn, String tableName)
        throws SQLException {

        String sql =
            "SELECT INDEX_NAME, COLUMN_NAME, SEQ_IN_INDEX, NON_UNIQUE, " +
                "INDEX_TYPE " +
                "FROM INFORMATION_SCHEMA.STATISTICS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? " +
                "ORDER BY INDEX_NAME, SEQ_IN_INDEX";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableName);
            ResultSet rs = ps.executeQuery();

            // 按索引名分组
            Map<String, IndexResult.IndexInfo> indexMap = new LinkedHashMap<>();

            while (rs.next()) {
                String indexName = rs.getString("INDEX_NAME");
                String columnName = rs.getString("COLUMN_NAME");
                boolean nonUnique = rs.getInt("NON_UNIQUE") == 1;
                String indexType = rs.getString("INDEX_TYPE");
                int seqInIndex = rs.getInt("SEQ_IN_INDEX");

                IndexResult.IndexInfo indexInfo = indexMap.computeIfAbsent(
                    indexName,
                    k -> {
                        IndexResult.IndexInfo info = new IndexResult.IndexInfo();
                        info.setIndexName(indexName);
                        info.setColumns(new ArrayList<>());
                        info.setUnique(!nonUnique);
                        info.setIndexType(indexType);
                        info.setPrimaryKey("PRIMARY".equals(indexName));
                        return info;
                    }
                );

                // 添加列名
                indexInfo.getColumns().add(columnName);

                // 设置首列（第一列）
                if (seqInIndex == 1) {
                    indexInfo.setLeadingColumn(columnName);
                }
            }

            return new ArrayList<>(indexMap.values());
        }
    }

    /**
     * 查询索引数量（不包括主键）
     *
     * @param conn      数据库连接
     * @param tableName 表名
     * @return 索引数量
     */
    public int getIndexCount(Connection conn, String tableName) throws SQLException {
        String sql =
            "SELECT COUNT(DISTINCT INDEX_NAME) " +
                "FROM INFORMATION_SCHEMA.STATISTICS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? " +
                "AND INDEX_NAME != 'PRIMARY'";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableName);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getInt(1);
            }
        }
        return 0;
    }

    /**
     * 检测重复或冗余索引
     *
     * @param existingIndexes 已存在的索引列表
     * @param newColumns      新索引的列列表
     * @param newIndexName    新索引名称
     * @return 冗余索引信息，如果没有则返回null
     */
    public RedundancyCheckResult checkRedundantIndex(
        List<IndexResult.IndexInfo> existingIndexes,
        List<String> newColumns,
        String newIndexName) {

        // 1. 检查完全重复
        for (IndexResult.IndexInfo existing : existingIndexes) {
            if (existing.getIndexName().equalsIgnoreCase(newIndexName)) {
                return new RedundancyCheckResult(
                    "DUPLICATE_NAME",
                    existing,
                    "索引名称已存在: " + newIndexName
                );
            }

            if (existing.getColumns().equals(newColumns)) {
                return new RedundancyCheckResult(
                    "DUPLICATE_COLUMNS",
                    existing,
                    String.format(
                        "索引列完全相同: %s (已存在索引 %s)",
                        newColumns,
                        existing.getIndexName()
                    )
                );
            }
        }

        // 2. 检查前缀冗余关系
        for (IndexResult.IndexInfo existing : existingIndexes) {
            List<String> existingColumns = existing.getColumns();

            // 跳过主键
            if (existing.isPrimaryKey()) {
                continue;
            }

            // 判断前缀关系
            if (isPrefixRelation(existingColumns, newColumns)) {
                // existingColumns 是 newColumns 的前缀
                // 例如：已存在 (user_id)，创建 (user_id, status)
                return new RedundancyCheckResult(
                    "PREFIX_REDUNDANT",
                    existing,
                    String.format(
                        "检测到前缀冗余: 已存在索引 %s(%s)，新索引 %s(%s) 的前几列被已存在索引覆盖",
                        existing.getIndexName(),
                        existingColumns,
                        newIndexName,
                        newColumns
                    )
                );
            }

            if (isPrefixRelation(newColumns, existingColumns)) {
                // newColumns 是 existingColumns 的前缀
                // 例如：已存在 (user_id, status)，创建 (user_id)
                return new RedundancyCheckResult(
                    "SUFFIX_REDUNDANT",
                    existing,
                    String.format(
                        "检测到后缀冗余: 已存在索引 %s(%s)，新索引 %s(%s) 是已存在索引的前缀，无必要创建",
                        existing.getIndexName(),
                        existingColumns,
                        newIndexName,
                        newColumns
                    )
                );
            }
        }

        return null;
    }

    /**
     * 判断两个列列表是否存在前缀关系
     *
     * @param list1 列列表1
     * @param list2 列列表2
     * @return 是否存在前缀关系
     */
    private boolean isPrefixRelation(List<String> list1, List<String> list2) {
        if (list1.size() >= list2.size()) {
            return false; // list1 不可能是 list2 的前缀
        }

        // 检查 list1 是否是 list2 的前缀
        for (int i = 0; i < list1.size(); i++) {
            if (!list1.get(i).equalsIgnoreCase(list2.get(i))) {
                return false;
            }
        }

        return true;
    }

    /**
     * 构建创建索引SQL
     */
    public String buildCreateIndexSql(
        String tableName,
        String indexName,
        List<String> columns,
        boolean unique) {

        String indexType = unique ? "UNIQUE INDEX" : "INDEX";
        String columnsStr = String.join(", ", columns);

        return String.format(
            "CREATE %s %s ON %s (%s)",
            indexType,
            indexName,
            tableName,
            columnsStr
        );
    }

    /**
     * 构建删除索引SQL
     */
    public String buildDropIndexSql(String tableName, String indexName) {
        return String.format("DROP INDEX %s ON %s", indexName, tableName);
    }

    /**
     * 生成索引优化建议
     */
    public void generateIndexSuggestions(
        IndexResult result,
        List<IndexResult.IndexInfo> existingIndexes,
        List<String> newColumns) {

        // 建议1：检查是否可以合并索引
        for (IndexResult.IndexInfo existing : existingIndexes) {
            if (existing.getLeadingColumn().equals(newColumns.get(0))) {
                result.getSuggestions().add(
                    String.format(
                        "新索引与现有索引 %s 的首列相同，考虑合并为联合索引以减少索引数量",
                        existing.getIndexName()
                    )
                );
            }
        }

        // 建议2：高基数字段优先
        result.getSuggestions().add(
            "确保索引列具有高基数（高区分度），低基数字段（如性别、状态）索引效果差"
        );

        // 建议3：复合索引列顺序
        if (newColumns.size() > 1) {
            result.getSuggestions().add(
                "复合索引列顺序建议：高基数字段在前，常用于WHERE条件的字段在前"
            );
        }
    }

    /**
     * 将 IndexResult 转换为 JSON 字符串
     */

    @Data
    @AllArgsConstructor
    public static class RedundancyCheckResult {
        private String type; // DUPLICATE_NAME, DUPLICATE_COLUMNS, PREFIX_REDUNDANT, SUFFIX_REDUNDANT
        private IndexResult.IndexInfo existingIndex;
        private String message;
    }
}