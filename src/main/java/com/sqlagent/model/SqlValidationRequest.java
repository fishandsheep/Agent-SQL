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
public class SqlValidationRequest {

    @Builder.Default
    private List<String> sqls = new ArrayList<>();

    @Builder.Default
    private int maxCount = 1;
}
