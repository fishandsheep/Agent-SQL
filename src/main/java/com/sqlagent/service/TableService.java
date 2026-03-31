package com.sqlagent.service;

import com.sqlagent.config.DatabaseConnectionManager;
import com.sqlagent.model.IndexResult;
import com.sqlagent.model.TableDetailResult;
import com.sqlagent.model.TableListResult;
import com.sqlagent.tools.IndexTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class TableService {

    private final DatabaseConnectionManager databaseConnectionManager;
    private final IndexTool indexTool;

    public TableListResult getAllTables() {
        TableListResult result = new TableListResult();

        try {
            DataSource dataSource = databaseConnectionManager.getDefaultDataSource();

            try (Connection conn = dataSource.getConnection()) {
                String database = conn.getCatalog();
                result.setDatabase(database);

                String sql = "SELECT TABLE_NAME, ENGINE, TABLE_ROWS, DATA_LENGTH, INDEX_LENGTH " +
                             "FROM INFORMATION_SCHEMA.TABLES " +
                             "WHERE TABLE_SCHEMA = DATABASE() " +
                             "AND TABLE_TYPE = 'BASE TABLE' " +
                             "ORDER BY TABLE_NAME";

                try (PreparedStatement ps = conn.prepareStatement(sql);
                     ResultSet rs = ps.executeQuery()) {

                    List<TableListResult.TableInfo> tables = new ArrayList<>();

                    while (rs.next()) {
                        String tableName = rs.getString("TABLE_NAME");
                        String engine = rs.getString("ENGINE");
                        long rowCount = rs.getLong("TABLE_ROWS");
                        long dataLength = rs.getLong("DATA_LENGTH");
                        long indexLength = rs.getLong("INDEX_LENGTH");

                        TableListResult.TableInfo tableInfo = new TableListResult.TableInfo();
                        tableInfo.setTableName(tableName);
                        tableInfo.setEngine(engine);
                        tableInfo.setRowCount(rowCount);
                        tableInfo.setDataSize(formatSize(dataLength + indexLength));
                        tableInfo.setIndexCount(getIndexCount(conn, tableName));
                        tables.add(tableInfo);
                    }

                    result.setTables(tables);
                    result.setSuccess(true);
                    log.info("loaded tables: database={}, count={}", database, tables.size());
                }
            }
        } catch (SQLException e) {
            log.error("failed to load tables", e);
            result.setSuccess(false);
            result.setError("加载表信息失败: " + e.getMessage());
        }

        return result;
    }

    public TableDetailResult getTableDetail(String tableName) {
        TableDetailResult result = new TableDetailResult();
        result.setTableName(tableName);

        try {
            List<TableDetailResult.ColumnInfo> columns = getColumns(tableName);
            List<IndexResult.IndexInfo> indexes = getIndexes(tableName);

            result.setColumns(columns);
            result.setIndexes(indexes);
            result.setSuccess(true);

            log.info("loaded table detail: table={}, columns={}, indexes={}",
                tableName, columns.size(), indexes.size());
        } catch (SQLException e) {
            log.error("failed to load table detail: table={}", tableName, e);
            result.setSuccess(false);
            result.setError("加载表详情失败: " + e.getMessage());
        }

        return result;
    }

    public String dropIndex(String tableName, String indexName) {
        log.info("drop index requested: table={}, index={}", tableName, indexName);
        return indexTool.manageIndex("drop", tableName, indexName, null, false);
    }

    private List<TableDetailResult.ColumnInfo> getColumns(String tableName) throws SQLException {
        DataSource dataSource = databaseConnectionManager.getDefaultDataSource();

        try (Connection conn = dataSource.getConnection()) {
            String sql = "SELECT COLUMN_NAME, DATA_TYPE, COLUMN_TYPE, IS_NULLABLE, " +
                         "COLUMN_KEY, COLUMN_DEFAULT " +
                         "FROM INFORMATION_SCHEMA.COLUMNS " +
                         "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? " +
                         "ORDER BY ORDINAL_POSITION";

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, tableName);
                ResultSet rs = ps.executeQuery();

                List<TableDetailResult.ColumnInfo> columns = new ArrayList<>();
                while (rs.next()) {
                    TableDetailResult.ColumnInfo column = new TableDetailResult.ColumnInfo();
                    column.setColumnName(rs.getString("COLUMN_NAME"));
                    column.setDataType(rs.getString("DATA_TYPE"));
                    column.setColumnType(rs.getString("COLUMN_TYPE"));
                    column.setNullable("YES".equals(rs.getString("IS_NULLABLE")));
                    column.setDefaultValue(rs.getString("COLUMN_DEFAULT"));
                    column.setPrimaryKey("PRI".equals(rs.getString("COLUMN_KEY")));
                    column.setUniqueKey("UNI".equals(rs.getString("COLUMN_KEY")));
                    columns.add(column);
                }

                return columns;
            }
        }
    }

    private List<IndexResult.IndexInfo> getIndexes(String tableName) throws SQLException {
        DataSource dataSource = databaseConnectionManager.getDefaultDataSource();

        try (Connection conn = dataSource.getConnection()) {
            String sql = "SELECT INDEX_NAME, COLUMN_NAME, SEQ_IN_INDEX, NON_UNIQUE, INDEX_TYPE " +
                         "FROM INFORMATION_SCHEMA.STATISTICS " +
                         "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? " +
                         "ORDER BY INDEX_NAME, SEQ_IN_INDEX";

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, tableName);
                ResultSet rs = ps.executeQuery();

                java.util.Map<String, IndexResult.IndexInfo> indexMap = new java.util.LinkedHashMap<>();
                while (rs.next()) {
                    String indexName = rs.getString("INDEX_NAME");
                    String columnName = rs.getString("COLUMN_NAME");
                    boolean nonUnique = rs.getInt("NON_UNIQUE") == 1;
                    String indexType = rs.getString("INDEX_TYPE");
                    int seqInIndex = rs.getInt("SEQ_IN_INDEX");

                    IndexResult.IndexInfo indexInfo = indexMap.computeIfAbsent(indexName, key -> {
                        IndexResult.IndexInfo info = new IndexResult.IndexInfo();
                        info.setIndexName(indexName);
                        info.setColumns(new ArrayList<>());
                        info.setUnique(!nonUnique);
                        info.setIndexType(indexType);
                        info.setPrimaryKey("PRIMARY".equals(indexName));
                        return info;
                    });

                    indexInfo.getColumns().add(columnName);
                    if (seqInIndex == 1) {
                        indexInfo.setLeadingColumn(columnName);
                    }
                }

                return new ArrayList<>(indexMap.values());
            }
        }
    }

    private int getIndexCount(Connection conn, String tableName) throws SQLException {
        String sql = "SELECT COUNT(DISTINCT INDEX_NAME) " +
                     "FROM INFORMATION_SCHEMA.STATISTICS " +
                     "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, tableName);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getInt(1);
            }
        }
        return 0;
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        } else {
            return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
        }
    }
}
