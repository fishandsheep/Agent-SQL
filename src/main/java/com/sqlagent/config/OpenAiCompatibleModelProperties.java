package com.sqlagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Data
@Component
@ConfigurationProperties(prefix = "openai.api")
public class OpenAiCompatibleModelProperties {

    private String url;
    private String key;
    private String model;
    private String availableModels;
    private int timeout = 60;
    private double temperature = 0.7;
    private int maxTokens = 2000;
    private boolean requestLoggingEnabled = false;
    private boolean responseLoggingEnabled = false;

    public List<String> getAvailableModelsList() {
        List<String> models = new ArrayList<>();
        if (availableModels == null || availableModels.isBlank()) {
            return models;
        }

        for (String part : availableModels.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                models.add(trimmed);
            }
        }
        return models;
    }

    public String getModelDisplayName(String modelId) {
        if (modelId == null || modelId.isEmpty()) {
            return modelId;
        }
        String[] parts = modelId.split("/");
        return parts[parts.length - 1];
    }
}
