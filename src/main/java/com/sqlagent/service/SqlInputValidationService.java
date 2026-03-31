package com.sqlagent.service;

import lombok.Getter;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * SQL 输入校验服务
 *
 * 目标：
 * 1. 单条优化分析只允许一条只读 SQL
 * 2. 批量 workload 入口只允许多条只读 SQL
 * 3. 拦截注释、多语句和明显危险关键字
 */
@Service
public class SqlInputValidationService {

    private static final Set<String> DANGEROUS_KEYWORDS = Set.of(
        "insert", "update", "delete", "drop", "alter", "truncate", "create",
        "replace", "merge", "call", "execute", "exec", "grant", "revoke",
        "use", "show", "set", "desc", "describe", "handler", "load",
        "outfile", "infile", "analyze", "optimize", "repair"
    );

    public ValidationResult validateSingleReadOnlySql(String sql, String fieldName) {
        if (sql == null || sql.trim().isEmpty()) {
            return ValidationResult.invalid(fieldName + "不能为空");
        }

        String trimmed = sql.trim();
        String masked = maskQuotedContent(trimmed);
        String normalized = removeTrailingSemicolons(masked).trim();

        if (containsCommentSyntax(normalized)) {
            return ValidationResult.invalid(fieldName + "包含注释语法，已拒绝执行");
        }

        if (normalized.contains(";")) {
            return ValidationResult.invalid(fieldName + "一次只能提交一条SQL，不允许多语句执行");
        }

        if (!startsWithReadOnlyStatement(normalized)) {
            return ValidationResult.invalid(fieldName + "仅支持SELECT或WITH查询语句");
        }

        String dangerousKeyword = findDangerousKeyword(normalized);
        if (dangerousKeyword != null) {
            return ValidationResult.invalid(fieldName + "包含危险关键字 `" + dangerousKeyword + "`，已拒绝执行");
        }

        return ValidationResult.valid();
    }

    public ValidationResult validateWorkloadSqls(List<String> sqls) {
        if (sqls == null || sqls.isEmpty()) {
            return ValidationResult.invalid("SQL列表不能为空");
        }
        if (sqls.size() > 10) {
            return ValidationResult.invalid("SQL数量不能超过10条");
        }

        List<String> errors = new ArrayList<>();
        for (int i = 0; i < sqls.size(); i++) {
            ValidationResult result = validateSingleReadOnlySql(sqls.get(i), "第" + (i + 1) + "条SQL");
            if (!result.isValid()) {
                errors.add(result.getMessage());
            }
        }

        return errors.isEmpty()
            ? ValidationResult.valid()
            : ValidationResult.invalid(String.join("；", errors));
    }

    private boolean startsWithReadOnlyStatement(String sql) {
        String lower = sql.toLowerCase(Locale.ROOT);
        return lower.startsWith("select ") || lower.startsWith("with ");
    }

    private boolean containsCommentSyntax(String sql) {
        return sql.contains("--") || sql.contains("/*") || sql.contains("*/") || sql.contains("#");
    }

    private String findDangerousKeyword(String sql) {
        String lower = sql.toLowerCase(Locale.ROOT);
        for (String keyword : DANGEROUS_KEYWORDS) {
            if (lower.matches("(?s).*\\b" + keyword + "\\b.*")) {
                return keyword;
            }
        }
        return null;
    }

    private String removeTrailingSemicolons(String sql) {
        String normalized = sql;
        while (normalized.endsWith(";")) {
            normalized = normalized.substring(0, normalized.length() - 1).trim();
        }
        return normalized;
    }

    /**
     * 将字符串字面量和标识符引用内容替换为空格，避免误判注释符和关键字。
     */
    private String maskQuotedContent(String sql) {
        StringBuilder builder = new StringBuilder(sql.length());
        boolean inSingle = false;
        boolean inDouble = false;
        boolean inBacktick = false;

        for (int i = 0; i < sql.length(); i++) {
            char current = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : '\0';

            if (inSingle) {
                builder.append(' ');
                if (current == '\'' && next == '\'') {
                    builder.append(' ');
                    i++;
                } else if (current == '\'') {
                    inSingle = false;
                }
                continue;
            }

            if (inDouble) {
                builder.append(' ');
                if (current == '"' && next == '"') {
                    builder.append(' ');
                    i++;
                } else if (current == '"') {
                    inDouble = false;
                }
                continue;
            }

            if (inBacktick) {
                builder.append(' ');
                if (current == '`') {
                    inBacktick = false;
                }
                continue;
            }

            if (current == '\'') {
                inSingle = true;
                builder.append(' ');
                continue;
            }
            if (current == '"') {
                inDouble = true;
                builder.append(' ');
                continue;
            }
            if (current == '`') {
                inBacktick = true;
                builder.append(' ');
                continue;
            }

            builder.append(current);
        }

        return builder.toString();
    }

    @Getter
    public static class ValidationResult {
        private final boolean valid;
        private final String message;

        private ValidationResult(boolean valid, String message) {
            this.valid = valid;
            this.message = message;
        }

        public static ValidationResult valid() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult invalid(String message) {
            return new ValidationResult(false, message);
        }
    }
}
