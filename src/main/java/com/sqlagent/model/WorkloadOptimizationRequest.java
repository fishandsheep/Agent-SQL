package com.sqlagent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkloadOptimizationRequest {

    @Builder.Default
    private List<String> sqls = new ArrayList<>();

    @Builder.Default
    private int maxIndexes = 5;

    @Builder.Default
    private boolean allowCompositeIndex = true;

    @Builder.Default
    private boolean analyzeSelectivity = true;

    public boolean isValid() {
        return sqls != null && !sqls.isEmpty() && sqls.size() <= 10;
    }

    public String getValidationError() {
        if (sqls == null || sqls.isEmpty()) {
            return "SQL 列表不能为空";
        }
        if (sqls.size() > 10) {
            return "SQL 数量不能超过 10 条";
        }
        return null;
    }
}
