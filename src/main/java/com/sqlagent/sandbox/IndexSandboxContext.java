package com.sqlagent.sandbox;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndexSandboxContext {

    private String sandboxId;

    private String sessionId;

    private List<IndexRecord> indexes;
}
