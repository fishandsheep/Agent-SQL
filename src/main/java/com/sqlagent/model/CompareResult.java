package com.sqlagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

/**
 * SQL comparison result.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CompareResult {
    private String sql1;
    private String sql2;
    private int rowCount1;
    private int rowCount2;
    private boolean consistent;
    private Boolean reliable;
    private String comparisonMode;
    private String note;
    private Set<String> onlyInSql1;
    private Set<String> onlyInSql2;
    private boolean success;
    private String error;
}
