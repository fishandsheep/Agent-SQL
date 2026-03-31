package com.sqlagent.tools;

import com.sqlagent.config.DatabaseConnectionManager;
import com.sqlagent.model.IndexResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class IndexTool {

    private static final int MAX_INDEXES_PER_TABLE = 8;
    private static final String INDEX_DUPLICATE_WARNING = "检测到重复或冗余索引，建议删除以减少维护成本";

    private final DatabaseConnectionManager databaseConnectionManager;
    private final IndexToolSupport indexToolSupport;

    public String manageIndex(
        String action,
        String tableName,
        String indexName,
        List<String> columns,
        boolean unique
    ) {
        if (action == null || action.trim().isEmpty()) {
            IndexResult result = new IndexResult();
            result.setSuccess(false);
            result.setError("操作类型不能为空，可选值为 create/drop/list");
            return convertToJson(result);
        }

        return switch (action.toLowerCase()) {
            case "create" -> doCreateIndex(tableName, indexName, columns, unique);
            case "drop" -> doDropIndex(tableName, indexName);
            case "list" -> doListIndexes(tableName);
            default -> {
                IndexResult result = new IndexResult();
                result.setSuccess(false);
                result.setError("无效的操作类型: " + action);
                yield convertToJson(result);
            }
        };
    }

    private String doCreateIndex(String tableName, String indexName, List<String> columns, boolean unique) {
        IndexResult result = new IndexResult();
        result.setAction("create");
        result.setExecuted(false);

        try {
            indexToolSupport.validateIdentifier(tableName, "表名");
            indexToolSupport.validateIdentifier(indexName, "索引名");
            indexToolSupport.validateColumns(columns);

            DataSource dataSource = databaseConnectionManager.getDefaultDataSource();
            try (Connection conn = dataSource.getConnection()) {
                long startTime = System.nanoTime();
                List<IndexResult.IndexInfo> existingIndexes = indexToolSupport.getIndexInfo(conn, tableName);
                result.setIndexes(existingIndexes);

                IndexToolSupport.RedundancyCheckResult redundancy = indexToolSupport.checkRedundantIndex(
                    existingIndexes,
                    columns,
                    indexName
                );
                if (redundancy != null) {
                    result.setSuccess(false);
                    result.setMessage(redundancy.getMessage());
                    result.getWarnings().add(INDEX_DUPLICATE_WARNING);
                    result.setSql(indexToolSupport.buildCreateIndexSql(tableName, indexName, columns, unique));
                    return convertToJson(result);
                }

                int indexCount = indexToolSupport.getIndexCount(conn, tableName);
                if (indexCount >= MAX_INDEXES_PER_TABLE) {
                    result.getWarnings().add(
                        String.format("当前表已有 %d 个索引，继续增加可能影响写入性能", indexCount)
                    );
                }

                String sql = indexToolSupport.buildCreateIndexSql(tableName, indexName, columns, unique);
                result.setSql(sql);
                result.setRollbackSql(indexToolSupport.buildDropIndexSql(tableName, indexName));

                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.execute();
                }

                result.setExecuted(true);
                result.setSuccess(true);
                result.setMessage(String.format("索引 %s 创建成功", indexName));
                result.setExecutionTimeMs((System.nanoTime() - startTime) / 1_000_000);
                indexToolSupport.generateIndexSuggestions(result, existingIndexes, columns);
            }
        } catch (IllegalArgumentException e) {
            result.setSuccess(false);
            result.setError("参数错误: " + e.getMessage());
        } catch (SQLException e) {
            log.error("create index failed", e);
            result.setSuccess(false);
            result.setExecuted(false);
            result.setError("数据库错误: " + e.getMessage());
        }

        return convertToJson(result);
    }

    private String doDropIndex(String tableName, String indexName) {
        IndexResult result = new IndexResult();
        result.setAction("drop");
        result.setExecuted(false);

        try {
            indexToolSupport.validateIdentifier(tableName, "表名");
            indexToolSupport.validateIdentifier(indexName, "索引名");

            DataSource dataSource = databaseConnectionManager.getDefaultDataSource();
            try (Connection conn = dataSource.getConnection()) {
                long startTime = System.nanoTime();
                List<IndexResult.IndexInfo> existingIndexes = indexToolSupport.getIndexInfo(conn, tableName);
                result.setIndexes(existingIndexes);

                IndexResult.IndexInfo targetIndex = existingIndexes.stream()
                    .filter(index -> index.getIndexName().equalsIgnoreCase(indexName))
                    .findFirst()
                    .orElse(null);

                if (targetIndex == null) {
                    result.setSuccess(false);
                    result.setError("索引不存在: " + indexName);
                    return convertToJson(result);
                }
                if (targetIndex.isPrimaryKey()) {
                    result.setSuccess(false);
                    result.setError("不能删除主键索引");
                    return convertToJson(result);
                }

                String sql = indexToolSupport.buildDropIndexSql(tableName, indexName);
                result.setSql(sql);
                result.setRollbackSql(
                    indexToolSupport.buildCreateIndexSql(
                        tableName,
                        indexName,
                        targetIndex.getColumns(),
                        targetIndex.isUnique()
                    )
                );

                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.execute();
                }

                result.setExecuted(true);
                result.setSuccess(true);
                result.setMessage(String.format("索引 %s 删除成功", indexName));
                result.setExecutionTimeMs((System.nanoTime() - startTime) / 1_000_000);
            }
        } catch (IllegalArgumentException e) {
            result.setSuccess(false);
            result.setError("参数错误: " + e.getMessage());
        } catch (SQLException e) {
            log.error("drop index failed", e);
            result.setSuccess(false);
            result.setExecuted(false);
            result.setError("数据库错误: " + e.getMessage());
        }

        return convertToJson(result);
    }

    private String doListIndexes(String tableName) {
        IndexResult result = new IndexResult();
        result.setAction("list");
        result.setExecuted(false);

        try {
            indexToolSupport.validateIdentifier(tableName, "表名");

            DataSource dataSource = databaseConnectionManager.getDefaultDataSource();
            try (Connection conn = dataSource.getConnection()) {
                long startTime = System.nanoTime();
                List<IndexResult.IndexInfo> indexes = indexToolSupport.getIndexInfo(conn, tableName);
                result.setIndexes(indexes);
                result.setSuccess(true);
                result.setMessage(String.format("查询成功，共 %d 个索引", indexes.size()));
                result.setExecutionTimeMs((System.nanoTime() - startTime) / 1_000_000);
            }
        } catch (IllegalArgumentException e) {
            result.setSuccess(false);
            result.setError("参数错误: " + e.getMessage());
        } catch (SQLException e) {
            log.error("list indexes failed", e);
            result.setSuccess(false);
            result.setError("数据库错误: " + e.getMessage());
        }

        return convertToJson(result);
    }

    private String convertToJson(IndexResult result) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        } catch (Exception e) {
            log.error("convert index result to json failed", e);
            return "{\"error\": \"JSON 转换失败: " + e.getMessage() + "\"}";
        }
    }
}
