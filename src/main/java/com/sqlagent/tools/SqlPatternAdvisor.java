package com.sqlagent.tools;

import com.sqlagent.config.DatabaseConnectionManager;
import com.sqlagent.model.ColumnStatistics;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class SqlPatternAdvisor {

    private static final Pattern SELECT_STAR_PATTERN = Pattern.compile("(?i)\\bselect\\s+\\*");
    private static final Pattern SIMPLE_SELECT_STAR_PATTERN = Pattern.compile(
        "(?is)^select\\s+\\*\\s+from\\s+(`?[a-zA-Z_][a-zA-Z0-9_]*`?)(?:\\s+(?:as\\s+)?([a-zA-Z_][a-zA-Z0-9_]*))?(.*)$"
    );
    private static final Pattern QUALIFIED_SELECT_STAR_PATTERN = Pattern.compile(
        "(?is)^select\\s+([a-zA-Z_][a-zA-Z0-9_]*|`?[a-zA-Z_][a-zA-Z0-9_]*`?)\\.\\*\\s+from\\s+(`?[a-zA-Z_][a-zA-Z0-9_]*`?)(?:\\s+(?:as\\s+)?([a-zA-Z_][a-zA-Z0-9_]*))?(.*)$"
    );
    private static final Pattern WHERE_CLAUSE_PATTERN = Pattern.compile(
        "(?is)\\bwhere\\b\\s+(.*?)(?:\\border\\s+by\\b|\\bgroup\\s+by\\b|\\bhaving\\b|\\blimit\\b|$)"
    );
    private static final Pattern DATE_FORMAT_PATTERN = Pattern.compile(
        "(?i)date_format\\s*\\(\\s*((?:[a-zA-Z_][a-zA-Z0-9_]*\\.)?[a-zA-Z_][a-zA-Z0-9_]*)\\s*,\\s*'(%Y%m)'\\s*\\)\\s*=\\s*'(\\d{6})'"
    );
    private static final Pattern NUMERIC_ID_LITERAL_PATTERN = Pattern.compile(
        "(?i)(((?:[a-zA-Z_][a-zA-Z0-9_]*\\.)?[a-zA-Z_][a-zA-Z0-9_]*_id)\\s*(=|<>|!=|<|>|<=|>=)\\s*)'([-+]?\\d+(?:\\.\\d+)?)'"
    );
    private static final Pattern NUMERIC_LITERAL_PATTERN = Pattern.compile("^[-+]?\\d+(?:\\.\\d+)?$");
    private static final Pattern SIMPLE_SELECT_WITH_WHERE_PATTERN = Pattern.compile(
        "(?is)^(select\\s+.+?\\s+from\\s+.+?\\s+where\\s+)(.+)$"
    );
    private static final Pattern SIMPLE_EQUALITY_TERM_PATTERN = Pattern.compile(
        "(?is)((?:[a-zA-Z_][a-zA-Z0-9_]*\\.)?[a-zA-Z_][a-zA-Z0-9_]*)\\s*=\\s*('(?:''|[^'])*'|\"(?:[^\"]|\"\")*\"|[-+]?\\d+(?:\\.\\d+)?)"
    );
    private static final Pattern SIMPLE_IN_SUBQUERY_TERM_PATTERN = Pattern.compile(
        "(?is)^((?:[a-zA-Z_][a-zA-Z0-9_]*\\.)?[a-zA-Z_][a-zA-Z0-9_]*)\\s+in\\s*\\(\\s*select\\s+((?:[a-zA-Z_][a-zA-Z0-9_]*\\.)?[a-zA-Z_][a-zA-Z0-9_]*)\\s+from\\s+(`?[a-zA-Z_][a-zA-Z0-9_]*`?)(?:\\s+(?:as\\s+)?([a-zA-Z_][a-zA-Z0-9_]*))?\\s+where\\s+(.+)\\)$"
    );
    private static final DateTimeFormatter YEAR_MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyyMM");
    private static final Set<String> RESERVED_ALIAS_KEYWORDS = Set.of(
        "where", "group", "order", "having", "limit", "join", "left", "right", "inner", "outer", "cross", "union", "straight_join"
    );
    private static final Set<String> LOW_CARDINALITY_COLUMN_HINTS = Set.of(
        "status", "flag", "deleted", "is_deleted", "deleted_flag", "is_active", "active", "enabled", "type"
    );

    private final DatabaseConnectionManager databaseConnectionManager;

    public Analysis analyze(String sql, List<ColumnStatistics> columns) {
        Analysis analysis = new Analysis();
        String normalizedSql = normalizeSql(sql);
        String rewrittenSql = normalizedSql;

        analysis.setHasSelectStar(SELECT_STAR_PATTERN.matcher(normalizedSql).find());
        if (analysis.isHasSelectStar()) {
            analysis.getDiagnostics().add("检测到 SELECT *；建议使用明确的列名列表，以减少不必要的行查找和网络传输。");
            rewrittenSql = tryExpandSelectStar(rewrittenSql);
        }

        rewrittenSql = detectDateFormatIssue(rewrittenSql, analysis);
        String whereClause = extractWhereClause(rewrittenSql);
        detectImplicitConversionWithoutSchema(whereClause, analysis);
        rewrittenSql = rewriteImplicitNumericComparisons(rewrittenSql);
        rewrittenSql = tryRewriteSimpleInSubqueryToJoin(rewrittenSql, analysis);
        rewrittenSql = tryRewriteTopLevelOrToUnionAll(rewrittenSql, analysis);

        if (columns != null) {
            for (ColumnStatistics column : columns) {
                String columnName = column.getColumnName();
                if (columnName == null || columnName.isBlank()) {
                    continue;
                }

                ColumnUsage usage = analyzeColumnUsage(whereClause, columnName, column.getDataType(), column.getCardinality());
                if (usage.isReferenced()) {
                    analysis.getWhereColumns().add(columnName);
                }
                if (usage.isEqualityPredicate()) {
                    analysis.getEqualityColumns().add(columnName);
                }
                if (usage.isInPredicate()) {
                    analysis.getInColumns().add(columnName);
                }
                if (usage.isRangePredicate()) {
                    analysis.getRangeColumns().add(columnName);
                }
                if (usage.isFunctionWrapped()) {
                    analysis.getFunctionColumns().add(columnName);
                }
                if (usage.isWeakFilter()) {
                    analysis.getWeakFilterColumns().add(columnName);
                }
                if (usage.getInListValueCount() != null) {
                    analysis.getInListValueCounts().put(columnName, usage.getInListValueCount());
                }
                if (usage.getImplicitConversionMessage() != null) {
                    analysis.setHasImplicitTypeConversion(true);
                    analysis.getDiagnostics().add(usage.getImplicitConversionMessage());
                }
            }
        }

        if (!rewrittenSql.equals(normalizedSql)) {
            analysis.setSuggestedSql(rewrittenSql);
        }

        analysis.setWhereColumns(new ArrayList<>(new LinkedHashSet<>(analysis.getWhereColumns())));
        analysis.setEqualityColumns(new ArrayList<>(new LinkedHashSet<>(analysis.getEqualityColumns())));
        analysis.setInColumns(new ArrayList<>(new LinkedHashSet<>(analysis.getInColumns())));
        analysis.setRangeColumns(new ArrayList<>(new LinkedHashSet<>(analysis.getRangeColumns())));
        analysis.setFunctionColumns(new ArrayList<>(new LinkedHashSet<>(analysis.getFunctionColumns())));
        analysis.setWeakFilterColumns(new ArrayList<>(new LinkedHashSet<>(analysis.getWeakFilterColumns())));
        analysis.setSuggestedIndexDdls(new ArrayList<>(new LinkedHashSet<>(analysis.getSuggestedIndexDdls())));
        analysis.setDiagnostics(new ArrayList<>(new LinkedHashSet<>(analysis.getDiagnostics())));
        return analysis;
    }

    public String expandSelectStarOnly(String sql) {
        if (sql == null || sql.isBlank()) {
            return sql;
        }
        String normalizedSql = normalizeSql(sql);
        if (!SELECT_STAR_PATTERN.matcher(normalizedSql).find()) {
            return normalizedSql;
        }
        return tryExpandSelectStar(normalizedSql);
    }

    public String rewriteSimpleInSubqueryToExists(String sql) {
        if (sql == null || sql.isBlank()) {
            return null;
        }

        String normalizedSql = expandSelectStarOnly(sql);
        SimpleInSubqueryShape shape = extractSimpleInSubqueryShape(normalizedSql);
        if (shape == null) {
            return null;
        }

        StringBuilder rewritten = new StringBuilder();
        rewritten.append("SELECT ")
            .append(shape.selectProjection())
            .append(" FROM ")
            .append(shape.outerFromClause())
            .append(" WHERE EXISTS (SELECT 1 FROM ")
            .append(shape.innerSource())
            .append(" WHERE ")
            .append(shape.innerWhereClause())
            .append(" AND ")
            .append(shape.innerExpression())
            .append(" = ")
            .append(shape.outerExpression())
            .append(")");

        if (!shape.remainingTerms().isEmpty()) {
            rewritten.append(" AND ").append(String.join(" AND ", shape.remainingTerms()));
        }
        return rewritten.toString();
    }

    private String tryExpandSelectStar(String sql) {
        String qualifiedExpansion = tryExpandQualifiedSelectStar(sql);
        if (!qualifiedExpansion.equals(sql)) {
            return qualifiedExpansion;
        }

        Matcher matcher = SIMPLE_SELECT_STAR_PATTERN.matcher(sql);
        if (!matcher.matches()) {
            return sql;
        }

        String tableToken = matcher.group(1);
        String alias = matcher.group(2);
        String tail = matcher.group(3) != null ? matcher.group(3) : "";
        if (alias != null && RESERVED_ALIAS_KEYWORDS.contains(alias.toLowerCase())) {
            tail = " " + alias + tail;
            alias = null;
        }
        if (tail.toLowerCase().contains(" join ")) {
            return sql;
        }

        List<String> columns = loadTableColumns(tableToken.replace("`", ""));
        if (columns.isEmpty()) {
            return sql;
        }

        String qualifier = alias != null && !alias.isBlank() ? alias : null;
        List<String> projection = new ArrayList<>();
        for (String column : columns) {
            projection.add(qualifier != null ? qualifier + ".`" + column + "`" : "`" + column + "`");
        }

        StringBuilder builder = new StringBuilder("SELECT ");
        builder.append(String.join(", ", projection));
        builder.append(" FROM ").append(tableToken);
        if (alias != null && !alias.isBlank()) {
            builder.append(' ').append(alias);
        }
        builder.append(tail);
        return builder.toString();
    }

    private String tryExpandQualifiedSelectStar(String sql) {
        Matcher matcher = QUALIFIED_SELECT_STAR_PATTERN.matcher(sql);
        if (!matcher.matches()) {
            return sql;
        }

        String projectionQualifier = stripIdentifierQuotes(matcher.group(1));
        String tableToken = matcher.group(2);
        String alias = matcher.group(3);
        String tail = matcher.group(4) != null ? matcher.group(4) : "";
        if (alias != null && RESERVED_ALIAS_KEYWORDS.contains(alias.toLowerCase())) {
            tail = " " + alias + tail;
            alias = null;
        }

        String tableName = stripIdentifierQuotes(tableToken);
        String effectiveQualifier = alias != null && !alias.isBlank() ? alias : tableName;
        if (!projectionQualifier.equalsIgnoreCase(effectiveQualifier)
            && !projectionQualifier.equalsIgnoreCase(tableName)) {
            return sql;
        }

        List<String> columns = loadTableColumns(tableName);
        if (columns.isEmpty()) {
            return sql;
        }

        List<String> projection = new ArrayList<>();
        for (String column : columns) {
            projection.add(effectiveQualifier + ".`" + column + "`");
        }

        StringBuilder builder = new StringBuilder("SELECT ");
        builder.append(String.join(", ", projection));
        builder.append(" FROM ").append(tableToken);
        if (alias != null && !alias.isBlank()) {
            builder.append(' ').append(alias);
        }
        builder.append(tail);
        return builder.toString();
    }

    private List<String> loadTableColumns(String tableName) {
        List<String> columns = new ArrayList<>();
        try {
            DataSource dataSource = databaseConnectionManager.getDefaultDataSource();
            try (Connection conn = dataSource.getConnection()) {
                String sql = "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, tableName);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            columns.add(rs.getString(1));
                        }
                    }
                }
            }
        } catch (SQLException ignored) {
            return List.of();
        }
        return columns;
    }

    private String analyzeColumnUsageSql(String value) {
        return value == null ? "" : value;
    }

    private ColumnUsage analyzeColumnUsage(String whereClause, String columnName, String dataType, Long cardinality) {
        ColumnUsage usage = new ColumnUsage();
        String clause = analyzeColumnUsageSql(whereClause);
        String columnRefPattern = exactColumnReference(columnName);

        usage.setReferenced(Pattern.compile(columnRefPattern, Pattern.CASE_INSENSITIVE).matcher(clause).find());
        usage.setEqualityPredicate(Pattern.compile(columnRefPattern + "\\s*=\\s*(?!\\()", Pattern.CASE_INSENSITIVE).matcher(clause).find());
        usage.setRangePredicate(Pattern.compile(columnRefPattern + "\\s*(>=|<=|>|<|between\\b)", Pattern.CASE_INSENSITIVE).matcher(clause).find());

        Matcher inMatcher = Pattern.compile(columnRefPattern + "\\s+(?:not\\s+)?in\\s*\\(([^\\)]*)\\)", Pattern.CASE_INSENSITIVE)
            .matcher(clause);
        if (inMatcher.find()) {
            usage.setInPredicate(true);
            int valueCount = countListValues(inMatcher.group(1));
            usage.setInListValueCount(valueCount);
            if (cardinality != null && cardinality > 0 && valueCount >= cardinality) {
                usage.setWeakFilter(true);
            }
        }

        if (Pattern.compile("(?i)date_format\\s*\\(\\s*" + columnRefPattern + "\\s*,").matcher(clause).find()) {
            usage.setFunctionWrapped(true);
            usage.setRangePredicate(true);
        }

        if (isNumericType(dataType)) {
            Matcher matcher = Pattern.compile(
                columnRefPattern + "\\s*(=|<>|!=|<|>|<=|>=)\\s*'([-+]?\\d+(?:\\.\\d+)?)'",
                Pattern.CASE_INSENSITIVE
            ).matcher(clause);
            if (matcher.find()) {
                usage.setImplicitConversionMessage(String.format(
                    "可能存在隐式类型转换：列 %s 的数据类型为 %s，但条件中使用了带引号的数字常量 '%s'；建议使用不带引号的数字常量进行比较。",
                    columnName,
                    dataType,
                    matcher.group(2)
                ));
            }
        }

        return usage;
    }

    private String detectDateFormatIssue(String sql, Analysis analysis) {
        Matcher matcher = DATE_FORMAT_PATTERN.matcher(sql);
        String rewrittenSql = sql;
        while (matcher.find()) {
            String columnRef = matcher.group(1);
            String yearMonth = matcher.group(3);
            analysis.setHasDateFunctionPredicate(true);
            analysis.getDiagnostics().add(String.format(
                "检测到 DATE_FORMAT 谓词：DATE_FORMAT(%s, '%%Y%%m') = '%s'；这会损害索引的可参数化，建议改写为时间范围查询。",
                columnRef,
                yearMonth
            ));
            String rangeRewrite = buildMonthRangeRewrite(rewrittenSql, matcher.group(0), columnRef, yearMonth);
            if (rangeRewrite != null) {
                rewrittenSql = rangeRewrite;
            }
        }
        return rewrittenSql;
    }

    private void detectImplicitConversionWithoutSchema(String whereClause, Analysis analysis) {
        Matcher matcher = Pattern.compile(
            "(?i)((?:[a-zA-Z_][a-zA-Z0-9_]*\\.)?[a-zA-Z_][a-zA-Z0-9_]*_id)\\s*=\\s*'([-+]?\\d+)'"
        ).matcher(whereClause);
        while (matcher.find()) {
            analysis.setHasImplicitTypeConversion(true);
            analysis.getDiagnostics().add(String.format(
                "可能存在隐式类型转换：%s 使用了带引号的数字常量 '%s'；如果该列是数字类型，建议使用不带引号的比较。",
                matcher.group(1),
                matcher.group(2)
            ));
        }
    }

    private String rewriteImplicitNumericComparisons(String sql) {
        Matcher matcher = NUMERIC_ID_LITERAL_PATTERN.matcher(sql);
        StringBuffer buffer = new StringBuffer();
        boolean changed = false;
        while (matcher.find()) {
            changed = true;
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(matcher.group(1) + matcher.group(3)));
        }
        matcher.appendTail(buffer);
        return changed ? buffer.toString() : sql;
    }

    private String tryRewriteSimpleInSubqueryToJoin(String sql, Analysis analysis) {
        SimpleInSubqueryShape shape = extractSimpleInSubqueryShape(sql);
        if (shape == null) {
            return sql;
        }

        StringBuilder rewritten = new StringBuilder();
        rewritten.append("SELECT ")
            .append(shape.selectProjection())
            .append(" FROM ")
            .append(shape.outerFromClause())
            .append(" INNER JOIN (SELECT DISTINCT ")
            .append(shape.innerExpression())
            .append(" AS __in_key FROM ")
            .append(shape.innerSource())
            .append(" WHERE ")
            .append(shape.innerWhereClause())
            .append(") __in_subq ON ")
            .append(shape.outerExpression())
            .append(" = __in_subq.__in_key");
        if (!shape.remainingTerms().isEmpty()) {
            rewritten.append(" WHERE ").append(String.join(" AND ", shape.remainingTerms()));
        }

        analysis.getDiagnostics().add(
            "检测到可以改写为半连接的简单 IN 子查询；建议使用 JOIN 配合 DISTINCT 派生表，这样子查询中的重复记录不会改变结果语义。"
        );
        analysis.getSuggestedIndexDdls().addAll(buildSemiJoinIndexDdls(
            shape.outerFromClause(),
            shape.remainingTerms(),
            shape.outerExpression(),
            shape.innerTable(),
            shape.innerWhereClause(),
            shape.innerExpression()
        ));
        return rewritten.toString();
    }

    private SimpleInSubqueryShape extractSimpleInSubqueryShape(String sql) {
        String lower = sql.toLowerCase();
        if (!lower.contains(" in (select ")
            || lower.contains(" union ")
            || lower.contains(" having ")
            || lower.contains(" limit ")) {
            return null;
        }

        Matcher selectMatcher = SIMPLE_SELECT_WITH_WHERE_PATTERN.matcher(sql);
        if (!selectMatcher.matches()) {
            return null;
        }

        String outerWhereClause = selectMatcher.group(2).trim();
        List<String> outerTerms = splitTopLevelAnd(outerWhereClause);
        if (outerTerms.isEmpty()) {
            return null;
        }

        int inTermIndex = -1;
        Matcher inMatcher = null;
        for (int i = 0; i < outerTerms.size(); i++) {
            Matcher candidate = SIMPLE_IN_SUBQUERY_TERM_PATTERN.matcher(stripEnclosingParentheses(outerTerms.get(i)));
            if (candidate.matches()) {
                inTermIndex = i;
                inMatcher = candidate;
                break;
            }
        }
        if (inTermIndex < 0 || inMatcher == null) {
            return null;
        }

        String outerFromClause = extractOuterFromClause(sql);
        if (outerFromClause == null || outerFromClause.toLowerCase().contains(" join ")) {
            return null;
        }

        String innerWhereClause = stripEnclosingParentheses(inMatcher.group(5));
        if (containsUnsafeSemijoinKeywords(innerWhereClause)) {
            return null;
        }

        String innerTable = inMatcher.group(3).trim();
        String innerAlias = inMatcher.group(4);
        String innerSource = innerAlias != null && !innerAlias.isBlank()
            ? innerTable + " " + innerAlias.trim()
            : innerTable;

        List<String> remainingTerms = new ArrayList<>(outerTerms);
        remainingTerms.remove(inTermIndex);

        return new SimpleInSubqueryShape(
            extractSelectProjection(sql),
            outerFromClause,
            inMatcher.group(1).trim(),
            inMatcher.group(2).trim(),
            innerTable,
            innerSource,
            innerWhereClause,
            List.copyOf(remainingTerms)
        );
    }

    private boolean containsUnsafeSemijoinKeywords(String clause) {
        String lower = clause.toLowerCase();
        return lower.contains(" group by ")
            || lower.contains(" order by ")
            || lower.contains(" having ")
            || lower.contains(" limit ")
            || lower.contains(" union ")
            || lower.contains(" exists ")
            || lower.contains(" in (select ");
    }

    private String extractSelectProjection(String sql) {
        Matcher matcher = Pattern.compile("(?is)^\\s*select\\s+(.+?)\\s+from\\s+.+$").matcher(sql);
        return matcher.matches() ? matcher.group(1).trim() : "*";
    }

    private String extractOuterFromClause(String sql) {
        Matcher matcher = Pattern.compile("(?is)^\\s*select\\s+.+?\\s+from\\s+(.+?)\\s+where\\s+.+$").matcher(sql);
        return matcher.matches() ? matcher.group(1).trim() : null;
    }

    private List<String> buildSemiJoinIndexDdls(
        String outerFromClause,
        List<String> outerTerms,
        String outerJoinExpression,
        String innerTable,
        String innerWhereClause,
        String innerJoinExpression
    ) {
        List<String> ddls = new ArrayList<>();

        String outerTable = outerFromClause.split("\\s+")[0].trim();
        List<String> outerColumns = buildPreferredIndexColumns(String.join(" AND ", outerTerms), outerJoinExpression);
        String outerDdl = buildIndexDdl(outerTable, outerColumns);
        if (outerDdl != null) {
            ddls.add(outerDdl);
        }

        List<String> innerColumns = buildPreferredIndexColumns(innerWhereClause, innerJoinExpression);
        String innerDdl = buildIndexDdl(innerTable, innerColumns);
        if (innerDdl != null) {
            ddls.add(innerDdl);
        }

        return ddls;
    }

    private List<String> buildPreferredIndexColumns(String expression, String joinExpression) {
        List<String> ordered = new ArrayList<>();
        addOrderedEqualityColumns(ordered, collectPredicateColumns(expression, true, false, false));
        addColumnIfAbsent(ordered, stripQualifier(joinExpression));
        collectPredicateColumns(expression, false, true, false).forEach(column -> addColumnIfAbsent(ordered, column));
        collectPredicateColumns(expression, false, false, true).forEach(column -> addColumnIfAbsent(ordered, column));
        return ordered;
    }

    private void addOrderedEqualityColumns(List<String> target, List<String> columns) {
        List<String> preferred = new ArrayList<>();
        List<String> lowCardinality = new ArrayList<>();
        for (String column : columns) {
            if (column == null || column.isBlank()) {
                continue;
            }
            if (isLowCardinalityHint(column)) {
                addColumnIfAbsent(lowCardinality, column);
            } else {
                addColumnIfAbsent(preferred, column);
            }
        }

        preferred.sort((left, right) -> Boolean.compare(isIdentifierLike(right), isIdentifierLike(left)));
        lowCardinality.sort((left, right) -> Boolean.compare(isIdentifierLike(right), isIdentifierLike(left)));

        preferred.forEach(column -> addColumnIfAbsent(target, column));
        lowCardinality.forEach(column -> addColumnIfAbsent(target, column));
    }

    private boolean isLowCardinalityHint(String column) {
        String normalized = stripQualifier(column);
        return normalized != null && LOW_CARDINALITY_COLUMN_HINTS.contains(normalized.toLowerCase());
    }

    private boolean isIdentifierLike(String column) {
        String normalized = stripQualifier(column);
        return normalized != null && normalized.toLowerCase().endsWith("_id");
    }

    private List<String> collectPredicateColumns(String expression, boolean equality, boolean range, boolean inPredicate) {
        List<String> columns = new ArrayList<>();
        if (expression == null || expression.isBlank()) {
            return columns;
        }

        for (String term : splitTopLevelAnd(expression)) {
            String normalizedTerm = stripEnclosingParentheses(term).trim();
            if (normalizedTerm.isEmpty()) {
                continue;
            }
            if (equality) {
                Matcher equalityMatcher = Pattern.compile(
                    "(?i)^((?:[a-zA-Z_][a-zA-Z0-9_]*\\.)?[a-zA-Z_][a-zA-Z0-9_]*)\\s*=\\s*(?!select\\b).+$"
                ).matcher(normalizedTerm);
                if (equalityMatcher.matches()) {
                    addColumnIfAbsent(columns, stripQualifier(equalityMatcher.group(1)));
                    continue;
                }
            }
            if (inPredicate) {
                Matcher inMatcher = Pattern.compile(
                    "(?i)^((?:[a-zA-Z_][a-zA-Z0-9_]*\\.)?[a-zA-Z_][a-zA-Z0-9_]*)\\s+(?:not\\s+)?in\\b.+$"
                ).matcher(normalizedTerm);
                if (inMatcher.matches()) {
                    addColumnIfAbsent(columns, stripQualifier(inMatcher.group(1)));
                    continue;
                }
            }
            if (range) {
                Matcher rangeMatcher = Pattern.compile(
                    "(?i)^((?:[a-zA-Z_][a-zA-Z0-9_]*\\.)?[a-zA-Z_][a-zA-Z0-9_]*)\\s*(>=|<=|>|<|between\\b|like\\b).+$"
                ).matcher(normalizedTerm);
                if (rangeMatcher.matches()) {
                    addColumnIfAbsent(columns, stripQualifier(rangeMatcher.group(1)));
                }
            }
        }
        return columns;
    }

    private List<String> collectReferencedColumns(String expression) {
        List<String> columns = new ArrayList<>();
        if (expression == null || expression.isBlank()) {
            return columns;
        }
        Matcher matcher = Pattern.compile(
            "(?i)((?:[a-zA-Z_][a-zA-Z0-9_]*\\.)?([a-zA-Z_][a-zA-Z0-9_]*))\\s*(=|<>|!=|<=|>=|<|>|in\\b|between\\b|like\\b)"
        ).matcher(expression);
        while (matcher.find()) {
            addColumnIfAbsent(columns, matcher.group(2));
        }
        return columns;
    }

    private void addColumnIfAbsent(List<String> columns, String column) {
        if (column == null || column.isBlank() || columns.contains(column)) {
            return;
        }
        columns.add(column);
    }

    private String buildIndexDdl(String tableName, List<String> columns) {
        if (tableName == null || tableName.isBlank() || columns == null || columns.isEmpty()) {
            return null;
        }
        List<String> normalizedColumns = columns.stream()
            .map(this::stripQualifier)
            .filter(value -> value != null && !value.isBlank())
            .distinct()
            .limit(4)
            .toList();
        if (normalizedColumns.isEmpty()) {
            return null;
        }
        String indexName = "idx_" + sanitizeIdentifier(stripQualifier(tableName)) + "_" + String.join("_", normalizedColumns);
        return String.format("CREATE INDEX %s ON %s (%s)", indexName, tableName, String.join(", ", normalizedColumns));
    }

    private String sanitizeIdentifier(String value) {
        return value == null ? "" : value.replace("`", "").replaceAll("[^A-Za-z0-9_]", "_");
    }

    private String stripIdentifierQuotes(String value) {
        return value == null ? null : value.replace("`", "").trim();
    }

    private String stripQualifier(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().replace("`", "");
        int dotIndex = normalized.lastIndexOf('.');
        return dotIndex >= 0 ? normalized.substring(dotIndex + 1) : normalized;
    }

    private String tryRewriteTopLevelOrToUnionAll(String sql, Analysis analysis) {
        String lower = sql.toLowerCase();
        if (!lower.contains(" or ")
            || lower.contains(" union ")
            || lower.contains(" distinct ")
            || lower.contains(" group by ")
            || lower.contains(" order by ")
            || lower.contains(" having ")
            || lower.contains(" limit ")
            || lower.contains(" join ")) {
            return sql;
        }

        int whereIndex = lower.indexOf(" where ");
        if (whereIndex < 0) {
            return sql;
        }

        String prefix = sql.substring(0, whereIndex + 7);
        String whereClause = sql.substring(whereIndex + 7).trim();
        List<String> branches = splitTopLevelOr(whereClause);
        if (branches.size() < 2 || !areMutuallyExclusive(branches)) {
            return sql;
        }

        analysis.getDiagnostics().add("检测到可以安全拆分的 OR 谓词；建议改写为 UNION ALL，使每个分支都能使用更稳定的访问路径。");
        List<String> selects = new ArrayList<>();
        for (String branch : branches) {
            selects.add(prefix + branch.trim());
        }
        return String.join(" UNION ALL ", selects);
    }

    private List<String> splitTopLevelOr(String expression) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        for (int i = 0; i < expression.length(); i++) {
            char ch = expression.charAt(i);
            if (ch == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
                current.append(ch);
                continue;
            }
            if (ch == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
                current.append(ch);
                continue;
            }
            if (!inSingleQuote && !inDoubleQuote) {
                if (ch == '(') {
                    depth++;
                } else if (ch == ')' && depth > 0) {
                    depth--;
                }
                if (depth == 0 && i + 4 <= expression.length() && expression.substring(i, i + 4).equalsIgnoreCase(" OR ")) {
                    result.add(current.toString().trim());
                    current.setLength(0);
                    i += 3;
                    continue;
                }
            }
            current.append(ch);
        }
        if (current.length() > 0) {
            result.add(current.toString().trim());
        }
        return result;
    }

    private boolean areMutuallyExclusive(List<String> branches) {
        Map<String, String> baseline = parseEqualityTerms(branches.get(0));
        if (baseline.isEmpty()) {
            return false;
        }

        for (String candidateColumn : baseline.keySet()) {
            Set<String> values = new LinkedHashSet<>();
            values.add(baseline.get(candidateColumn));
            boolean sharedByAll = true;
            for (int i = 1; i < branches.size(); i++) {
                Map<String, String> branchTerms = parseEqualityTerms(branches.get(i));
                String value = branchTerms.get(candidateColumn);
                if (value == null) {
                    sharedByAll = false;
                    break;
                }
                values.add(value);
            }
            if (sharedByAll && values.size() == branches.size()) {
                return true;
            }
        }
        return false;
    }

    private Map<String, String> parseEqualityTerms(String branch) {
        Map<String, String> terms = new LinkedHashMap<>();
        Matcher matcher = SIMPLE_EQUALITY_TERM_PATTERN.matcher(stripEnclosingParentheses(branch));
        while (matcher.find()) {
            terms.put(matcher.group(1).toLowerCase(), matcher.group(2).trim());
        }
        return terms;
    }

    private String stripEnclosingParentheses(String expression) {
        String value = expression == null ? "" : expression.trim();
        while (value.startsWith("(") && value.endsWith(")")) {
            int depth = 0;
            boolean balanced = true;
            for (int i = 0; i < value.length(); i++) {
                char ch = value.charAt(i);
                if (ch == '(') {
                    depth++;
                } else if (ch == ')') {
                    depth--;
                    if (depth == 0 && i < value.length() - 1) {
                        balanced = false;
                        break;
                    }
                }
            }
            if (!balanced || depth != 0) {
                break;
            }
            value = value.substring(1, value.length() - 1).trim();
        }
        return value;
    }
    private List<String> splitTopLevelAnd(String expression) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;

        for (int i = 0; i < expression.length(); i++) {
            char ch = expression.charAt(i);
            if (ch == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
                current.append(ch);
                continue;
            }
            if (ch == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
                current.append(ch);
                continue;
            }
            if (!inSingleQuote && !inDoubleQuote) {
                if (ch == '(') {
                    depth++;
                } else if (ch == ')' && depth > 0) {
                    depth--;
                }
                if (depth == 0 && i + 5 <= expression.length() && expression.substring(i, i + 5).equalsIgnoreCase(" AND ")) {
                    result.add(current.toString().trim());
                    current.setLength(0);
                    i += 4;
                    continue;
                }
            }
            current.append(ch);
        }
        if (current.length() > 0) {
            result.add(current.toString().trim());
        }
        return result;
    }

    private String buildMonthRangeRewrite(String originalSql, String matchedExpression, String columnRef, String yearMonthLiteral) {
        try {
            YearMonth yearMonth = YearMonth.parse(yearMonthLiteral, YEAR_MONTH_FORMATTER);
            String start = yearMonth.atDay(1).toString();
            String end = yearMonth.plusMonths(1).atDay(1).toString();
            String replacement = String.format("%s >= '%s' AND %s < '%s'", columnRef, start, columnRef, end);
            return originalSql.replace(matchedExpression, replacement);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private String extractWhereClause(String sql) {
        Matcher matcher = WHERE_CLAUSE_PATTERN.matcher(sql);
        return matcher.find() ? matcher.group(1) : sql;
    }

    private String normalizeSql(String sql) {
        return sql == null ? "" : sql.trim();
    }

    private String exactColumnReference(String columnName) {
        return "(?<![a-zA-Z0-9_])(?:[a-zA-Z_][a-zA-Z0-9_]*\\.)?" + Pattern.quote(columnName) + "(?![a-zA-Z0-9_])";
    }

    private int countListValues(String content) {
        if (content == null || content.isBlank()) {
            return 0;
        }
        int count = 0;
        for (String token : content.split(",")) {
            if (!token.isBlank()) {
                count++;
            }
        }
        return count;
    }

    private boolean isNumericType(String dataType) {
        if (dataType == null) {
            return false;
        }
        String normalized = dataType.toLowerCase();
        return normalized.contains("int")
            || normalized.contains("decimal")
            || normalized.contains("numeric")
            || normalized.contains("number")
            || normalized.contains("float")
            || normalized.contains("double")
            || normalized.contains("real");
    }

    @Data
    public static class Analysis {
        private boolean hasSelectStar;
        private boolean hasDateFunctionPredicate;
        private boolean hasImplicitTypeConversion;
        private String suggestedSql;
        private List<String> whereColumns = new ArrayList<>();
        private List<String> equalityColumns = new ArrayList<>();
        private List<String> inColumns = new ArrayList<>();
        private List<String> rangeColumns = new ArrayList<>();
        private List<String> functionColumns = new ArrayList<>();
        private List<String> weakFilterColumns = new ArrayList<>();
        private Map<String, Integer> inListValueCounts = new LinkedHashMap<>();
        private List<String> suggestedIndexDdls = new ArrayList<>();
        private List<String> diagnostics = new ArrayList<>();
    }

    private record SimpleInSubqueryShape(
        String selectProjection,
        String outerFromClause,
        String outerExpression,
        String innerExpression,
        String innerTable,
        String innerSource,
        String innerWhereClause,
        List<String> remainingTerms
    ) {
    }

    @Data
    private static class ColumnUsage {
        private boolean referenced;
        private boolean equalityPredicate;
        private boolean inPredicate;
        private boolean rangePredicate;
        private boolean functionWrapped;
        private boolean weakFilter;
        private Integer inListValueCount;
        private String implicitConversionMessage;
    }
}
