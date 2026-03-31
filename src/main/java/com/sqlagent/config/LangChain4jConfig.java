package com.sqlagent.config;

import com.sqlagent.agent.SqlMultiPlanAgent;
import com.sqlagent.agent.WorkloadOptimizationAgent;
import com.sqlagent.tools.SqlTools;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Configuration
@RequiredArgsConstructor
public class LangChain4jConfig {

    private final OpenAiCompatibleModelProperties modelProperties;
    private final SqlTools sqlTools;

    @Bean
    public ChatLanguageModel chatLanguageModel() {
        return createChatLanguageModel(modelProperties.getModel());
    }

    @Bean
    public Map<String, SqlMultiPlanAgent> sqlMultiPlanAgentMap() {
        List<String> availableModels = modelProperties.getAvailableModelsList();
        Map<String, SqlMultiPlanAgent> agentMap = new HashMap<>();

        log.info("[langchain4j] building sql multi-plan agents: modelCount={}", availableModels.size());
        for (String modelName : availableModels) {
            log.info("[langchain4j] creating sql multi-plan agent: model={}", modelName);
            ChatLanguageModel chatModel = createChatLanguageModel(modelName);
            SqlMultiPlanAgent agent = AiServices.builder(SqlMultiPlanAgent.class)
                .chatLanguageModel(chatModel)
                .tools(sqlTools)
                .build();
            agentMap.put(modelName, agent);
        }

        log.info("[langchain4j] sql multi-plan agents ready: models={}", availableModels);
        return agentMap;
    }

    @Bean
    public SqlMultiPlanAgent sqlMultiPlanAgent(ChatLanguageModel chatLanguageModel) {
        log.info("[langchain4j] building default sql multi-plan agent: model={}", modelProperties.getModel());
        return AiServices.builder(SqlMultiPlanAgent.class)
            .chatLanguageModel(chatLanguageModel)
            .tools(sqlTools)
            .build();
    }

    @Bean
    public WorkloadOptimizationAgent workloadOptimizationAgent(ChatLanguageModel chatLanguageModel) {
        log.info("[langchain4j] building workload optimization agent: model={}", modelProperties.getModel());
        return AiServices.builder(WorkloadOptimizationAgent.class)
            .chatLanguageModel(chatLanguageModel)
            .tools(sqlTools)
            .build();
    }

    private ChatLanguageModel createChatLanguageModel(String modelName) {
        log.info(
            "[langchain4j] creating chat model: model={}, requestLoggingEnabled={}, responseLoggingEnabled={}",
            modelName,
            modelProperties.isRequestLoggingEnabled(),
            modelProperties.isResponseLoggingEnabled()
        );

        return OpenAiChatModel.builder()
            .baseUrl(modelProperties.getUrl())
            .apiKey(modelProperties.getKey())
            .modelName(modelName)
            .timeout(Duration.ofSeconds(modelProperties.getTimeout()))
            .temperature(modelProperties.getTemperature())
            .maxTokens(modelProperties.getMaxTokens())
            .logRequests(modelProperties.isRequestLoggingEnabled())
            .logResponses(modelProperties.isResponseLoggingEnabled())
            .maxRetries(2)
            .build();
    }
}
