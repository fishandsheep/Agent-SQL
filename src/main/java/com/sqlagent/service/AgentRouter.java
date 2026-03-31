package com.sqlagent.service;

import com.sqlagent.agent.SqlMultiPlanAgent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Slf4j
@Service
public class AgentRouter {

    private final Map<String, SqlMultiPlanAgent> sqlMultiPlanAgentMap;
    private final SqlMultiPlanAgent defaultSqlMultiPlanAgent;

    public AgentRouter(
        @Qualifier("sqlMultiPlanAgentMap") Map<String, SqlMultiPlanAgent> sqlMultiPlanAgentMap,
        @Qualifier("sqlMultiPlanAgent") SqlMultiPlanAgent defaultSqlMultiPlanAgent
    ) {
        this.sqlMultiPlanAgentMap = sqlMultiPlanAgentMap;
        this.defaultSqlMultiPlanAgent = defaultSqlMultiPlanAgent;
        log.info("[agent-router] initialized: modelCount={}", sqlMultiPlanAgentMap.size());
    }

    public SqlMultiPlanAgent getAgent(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            log.debug("[agent-router] model not specified, using default agent");
            return defaultSqlMultiPlanAgent;
        }

        log.debug("[agent-router] resolving model: requested='{}'", modelName);
        SqlMultiPlanAgent agent = sqlMultiPlanAgentMap.get(modelName);
        if (agent != null) {
            log.info("[agent-router] matched model: requested='{}'", modelName);
            return agent;
        }

        String trimmedModelName = modelName.trim();
        if (!trimmedModelName.equals(modelName)) {
            log.debug("[agent-router] retrying model resolution after trim: requested='{}', trimmed='{}'", modelName, trimmedModelName);
            agent = sqlMultiPlanAgentMap.get(trimmedModelName);
            if (agent != null) {
                log.info("[agent-router] matched trimmed model: requested='{}', resolved='{}'", modelName, trimmedModelName);
                return agent;
            }
        }

        log.warn("[agent-router] model not found, falling back to default agent: requested='{}', availableModels={}", modelName, sqlMultiPlanAgentMap.keySet());
        return defaultSqlMultiPlanAgent;
    }

    public String getDefaultModelName() {
        Optional<String> firstModel = sqlMultiPlanAgentMap.keySet().stream().findFirst();
        return firstModel.orElse("unknown");
    }

    public boolean isModelAvailable(String modelName) {
        return sqlMultiPlanAgentMap.containsKey(modelName);
    }

    public Set<String> getAvailableModels() {
        return sqlMultiPlanAgentMap.keySet();
    }
}