package com.sqlagent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MultiPlanRequest {

    private String sql;

    @Builder.Default
    private int maxPlans = 5;

    private String model;
}
