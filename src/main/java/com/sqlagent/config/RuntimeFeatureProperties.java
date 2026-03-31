package com.sqlagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "sql-agent")
public class RuntimeFeatureProperties {

    private final Demo demo = new Demo();
    private final Features features = new Features();
    private final Optimization optimization = new Optimization();

    @Data
    public static class Demo {
        private boolean readOnly = true;
    }

    @Data
    public static class Features {
        private boolean mutationEndpointsEnabled = false;
    }

    @Data
    public static class Optimization {
        private int maxAiReplanAttempts = 1;
        private double minAcceptableTotalScore = 35.0d;
    }
}
