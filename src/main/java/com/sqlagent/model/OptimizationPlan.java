package com.sqlagent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class OptimizationPlan {

    private String planId;

    private String type;

    private String optimizedSql;

    private String originalSql;

    private String indexDDL;

    private String reasoning;

    private ExplainResult explain;

    private Long executionTime;

    private boolean resultEqual;

    private String description;

    private boolean valid;

    private String validationError;

    private String rejectionReason;

    private Integer priority;

    private String estimatedImpact;

    private String riskLevel;

    private Boolean indexOptimization;

    private Boolean rewriteOptimization;

    public boolean requiresIndexCreation() {
        return indexDDL != null && !indexDDL.isEmpty();
    }

    public boolean requiresSqlRewrite() {
        return optimizedSql != null && !optimizedSql.isEmpty();
    }

    public String getSql() {
        return optimizedSql;
    }

    public void setSql(String sql) {
        this.optimizedSql = sql;
    }
}
