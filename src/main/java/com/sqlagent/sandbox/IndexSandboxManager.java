package com.sqlagent.sandbox;

import com.sqlagent.config.DatabaseConnectionManager;
import com.sqlagent.exception.SandboxException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class IndexSandboxManager {

    private static final int MAX_INDEX_NAME_LENGTH = 64;
    private static final Pattern CREATE_INDEX_PATTERN = Pattern.compile(
        "(?i)^\\s*CREATE\\s+INDEX\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?(`?[^`\\s]+`?)\\s+ON\\s+([^\\s(]+)\\s*(\\(.*\\))\\s*;?\\s*$"
    );
    private static final Pattern ON_TABLE_PATTERN = Pattern.compile("(?i)\\bON\\s+([^\\s(]+)");

    private final DatabaseConnectionManager databaseConnectionManager;
    private final ConcurrentHashMap<String, IndexSandboxContext> sandboxes = new ConcurrentHashMap<>();

    public IndexSandboxContext createSandbox(String sessionId) {
        String sandboxId = UUID.randomUUID().toString().substring(0, 8);
        IndexSandboxContext context = new IndexSandboxContext(sandboxId, sessionId, new ArrayList<>());
        sandboxes.put(sessionId, context);
        log.info("[sandbox] created: sessionId={}, sandboxId={}", sessionId, sandboxId);
        return context;
    }

    public String executeIndexDDL(String sessionId, String ddl) {
        IndexSandboxContext context = sandboxes.get(sessionId);
        if (context == null) {
            throw new SandboxException("Sandbox does not exist: " + sessionId);
        }

        try {
            String originalIndexName = extractIndexName(ddl);
            String tableName = extractTableName(ddl);
            String tmpIndexName = buildTemporaryIndexName(context.getSandboxId(), originalIndexName);
            String tmpDDL = buildTemporaryCreateIndexDDL(ddl, tmpIndexName);

            executeDDL(tmpDDL);

            boolean exists = indexExists(tableName, tmpIndexName);
            listAllIndexes(tableName);

            IndexRecord record = new IndexRecord(tmpIndexName, originalIndexName, tmpDDL, ddl);
            context.getIndexes().add(record);

            if (!exists) {
                log.warn("[sandbox] temporary index not visible after creation: {}", tmpIndexName);
            }

            log.info("[sandbox] temporary index created: indexName={}", tmpIndexName);
            return tmpIndexName;
        } catch (SQLException e) {
            log.error("[sandbox] temporary index creation failed: ddl={}", ddl, e);
            throw new SandboxException.IndexExecutionException(ddl, "索引 DDL 执行失败: " + e.getMessage(), e);
        }
    }

    public List<String> executeIndexDDLs(String sessionId, String ddlBundle) {
        List<String> statements = splitIndexDdls(ddlBundle);
        List<String> created = new ArrayList<>();
        for (String statement : statements) {
            created.add(executeIndexDDL(sessionId, statement));
        }
        return created;
    }

    public void dropIndex(String sessionId, String tmpIndexName) {
        IndexSandboxContext context = sandboxes.get(sessionId);
        if (context == null) {
            log.warn("[sandbox] skip drop because sandbox is missing: {}", tmpIndexName);
            return;
        }

        IndexRecord targetRecord = context.getIndexes().stream()
            .filter(record -> record.getTmpIndexName().equals(tmpIndexName))
            .findFirst()
            .orElse(null);

        if (targetRecord == null) {
            log.warn("[sandbox] temporary index record not found: {}", tmpIndexName);
            return;
        }

        try {
            String tableName = extractTableName(targetRecord.getTmpDDL());
            if (!indexExists(tableName, tmpIndexName)) {
                context.getIndexes().remove(targetRecord);
                return;
            }

            executeDDL(buildDropIndexDDL(tmpIndexName, tableName));
            context.getIndexes().remove(targetRecord);
            log.info("[sandbox] temporary index deleted: {}", tmpIndexName);
        } catch (SQLException e) {
            log.error("[sandbox] temporary index deletion failed: {}", tmpIndexName, e);
            throw new SandboxException("删除临时索引失败: " + tmpIndexName + " - " + e.getMessage(), e);
        }
    }

    public int cleanupSandbox(String sessionId) {
        IndexSandboxContext context = sandboxes.remove(sessionId);
        if (context == null) {
            return 0;
        }

        int deletedCount = 0;
        int failedCount = 0;
        List<String> errors = new ArrayList<>();

        for (IndexRecord record : context.getIndexes()) {
            try {
                String tableName = extractTableName(record.getTmpDDL());
                executeDDL(buildDropIndexDDL(record.getTmpIndexName(), tableName));
                deletedCount++;
            } catch (SQLException e) {
                failedCount++;
                errors.add(record.getTmpIndexName() + ": " + e.getMessage());
                log.error("[sandbox] cleanup failed for {}", record.getTmpIndexName(), e);
            }
        }

        if (failedCount > 0) {
            throw new SandboxException.SandboxCleanupException(
                failedCount,
                "部分索引清理失败: " + String.join("; ", errors),
                null
            );
        }

        return deletedCount;
    }

    public void cleanupAllSandboxes() {
        for (String sessionId : new ArrayList<>(sandboxes.keySet())) {
            try {
                cleanupSandbox(sessionId);
            } catch (Exception e) {
                log.error("[sandbox] emergency cleanup failed: sessionId={}", sessionId, e);
            }
        }
    }

    public IndexSandboxContext getSandbox(String sessionId) {
        return sandboxes.get(sessionId);
    }

    private void executeDDL(String ddl) throws SQLException {
        DataSource dataSource = databaseConnectionManager.getDefaultDataSource();
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute(ddl);
        }
    }

    private List<String> splitIndexDdls(String ddlBundle) {
        List<String> statements = new ArrayList<>();
        if (ddlBundle == null || ddlBundle.isBlank()) {
            return statements;
        }

        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        for (int i = 0; i < ddlBundle.length(); i++) {
            char ch = ddlBundle.charAt(i);
            if (ch == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
            } else if (ch == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
            }

            if (ch == ';' && !inSingleQuote && !inDoubleQuote) {
                appendStatement(statements, current);
                current.setLength(0);
                continue;
            }
            current.append(ch);
        }
        appendStatement(statements, current);
        return statements;
    }

    private void appendStatement(List<String> statements, StringBuilder current) {
        String statement = current.toString().trim();
        if (!statement.isEmpty()) {
            statements.add(statement);
        }
    }

    private String extractIndexName(String ddl) {
        Matcher matcher = CREATE_INDEX_PATTERN.matcher(ddl);
        if (!matcher.matches()) {
            throw new SandboxException("无效的索引 DDL: " + ddl);
        }
        return normalizeIdentifier(matcher.group(1));
    }

    private String extractTableName(String ddl) {
        Matcher matcher = ON_TABLE_PATTERN.matcher(ddl);
        if (!matcher.find()) {
            throw new SandboxException("无法从 DDL 提取表名: " + ddl);
        }
        return matcher.group(1).trim();
    }

    private String buildDropIndexDDL(String tmpIndexName, String tableName) {
        return String.format("DROP INDEX %s ON %s", tmpIndexName, tableName);
    }

    private String buildTemporaryCreateIndexDDL(String originalDDL, String tmpIndexName) {
        Matcher matcher = CREATE_INDEX_PATTERN.matcher(originalDDL);
        if (!matcher.matches()) {
            throw new SandboxException("无效的索引 DDL: " + originalDDL);
        }

        String tableName = matcher.group(2).trim();
        String columnsClause = matcher.group(3).trim();
        return String.format("CREATE INDEX %s ON %s %s", tmpIndexName, tableName, columnsClause);
    }

    private String buildTemporaryIndexName(String sandboxId, String originalIndexName) {
        String normalizedOriginal = normalizeIdentifier(originalIndexName).replaceAll("[^A-Za-z0-9_]", "_");
        String prefix = "tmp_idx_" + sandboxId + "_";
        int remainingLength = MAX_INDEX_NAME_LENGTH - prefix.length();
        if (remainingLength <= 0) {
            return prefix.substring(0, MAX_INDEX_NAME_LENGTH);
        }
        if (normalizedOriginal.length() > remainingLength) {
            normalizedOriginal = normalizedOriginal.substring(0, remainingLength);
        }
        return prefix + normalizedOriginal;
    }

    private boolean indexExists(String tableName, String indexName) {
        String sql = "SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS " +
            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?";

        try (Connection conn = databaseConnectionManager.getDefaultDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, normalizeTableName(tableName));
            ps.setString(2, normalizeIdentifier(indexName));

            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            log.error("[sandbox] failed to check index existence: {}", indexName, e);
            return false;
        }
    }

    private void listAllIndexes(String tableName) {
        String sql = "SELECT INDEX_NAME, COLUMN_NAME, SEQ_IN_INDEX " +
            "FROM INFORMATION_SCHEMA.STATISTICS " +
            "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? " +
            "ORDER BY INDEX_NAME, SEQ_IN_INDEX";

        try (Connection conn = databaseConnectionManager.getDefaultDataSource().getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, normalizeTableName(tableName));

            try (ResultSet rs = ps.executeQuery()) {
                String currentIndex = "";
                StringBuilder columns = new StringBuilder();

                while (rs.next()) {
                    String indexName = rs.getString("INDEX_NAME");
                    String columnName = rs.getString("COLUMN_NAME");

                    if (!indexName.equals(currentIndex)) {
                        if (!currentIndex.isEmpty()) {
                            log.info("[sandbox] index {}: ({})", currentIndex, columns);
                        }
                        currentIndex = indexName;
                        columns = new StringBuilder(columnName);
                    } else {
                        columns.append(", ").append(columnName);
                    }
                }

                if (!currentIndex.isEmpty()) {
                    log.info("[sandbox] index {}: ({})", currentIndex, columns);
                }
            }
        } catch (SQLException e) {
            log.error("[sandbox] failed to list indexes for {}", tableName, e);
        }
    }

    private String normalizeTableName(String tableName) {
        String normalized = normalizeIdentifier(tableName);
        int dotIndex = normalized.lastIndexOf('.');
        return dotIndex >= 0 ? normalizeIdentifier(normalized.substring(dotIndex + 1)) : normalized;
    }

    private String normalizeIdentifier(String identifier) {
        if (identifier == null) {
            return null;
        }
        return identifier.replace("`", "").replace("\"", "").trim();
    }
}
