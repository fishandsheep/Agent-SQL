package com.sqlagent.config;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL 优化分析页样例配置
 *
 * 支持两种配置方式：
 * 1. application.yml 中的 sql-agent.optimize.samples 配置
 * 2. 环境变量中的 SQL_AGENT_SAMPLE_X_* 配置（优先级更高）
 */
@Data
@Component
@ConfigurationProperties(prefix = "sql-agent.optimize")
public class OptimizationSampleConfiguration {

    /**
     * 输入框旁展示的 SQL 样例（从 yml 读取）
     */
    private List<SampleItem> samples = new ArrayList<>();

    /**
     * 从环境变量读取的样例1
     */
    @Value("${SQL_AGENT_SAMPLE_1_TITLE:}")
    private String sample1Title;
    @Value("${SQL_AGENT_SAMPLE_1_DESC:}")
    private String sample1Desc;
    @Value("${SQL_AGENT_SAMPLE_1_SQL:}")
    private String sample1Sql;

    /**
     * 从环境变量读取的样例2
     */
    @Value("${SQL_AGENT_SAMPLE_2_TITLE:}")
    private String sample2Title;
    @Value("${SQL_AGENT_SAMPLE_2_DESC:}")
    private String sample2Desc;
    @Value("${SQL_AGENT_SAMPLE_2_SQL:}")
    private String sample2Sql;

    /**
     * 从环境变量读取的样例3
     */
    @Value("${SQL_AGENT_SAMPLE_3_TITLE:}")
    private String sample3Title;
    @Value("${SQL_AGENT_SAMPLE_3_DESC:}")
    private String sample3Desc;
    @Value("${SQL_AGENT_SAMPLE_3_SQL:}")
    private String sample3Sql;

    /**
     * 从环境变量读取的样例4
     */
    @Value("${SQL_AGENT_SAMPLE_4_TITLE:}")
    private String sample4Title;
    @Value("${SQL_AGENT_SAMPLE_4_DESC:}")
    private String sample4Desc;
    @Value("${SQL_AGENT_SAMPLE_4_SQL:}")
    private String sample4Sql;

    /**
     * 从环境变量读取的样例5
     */
    @Value("${SQL_AGENT_SAMPLE_5_TITLE:}")
    private String sample5Title;
    @Value("${SQL_AGENT_SAMPLE_5_DESC:}")
    private String sample5Desc;
    @Value("${SQL_AGENT_SAMPLE_5_SQL:}")
    private String sample5Sql;

    /**
     * 获取所有样例（优先返回环境变量中的样例）
     */
    public List<SampleItem> getEffectiveSamples() {
        List<SampleItem> effectiveSamples = new ArrayList<>();

        // 从环境变量组装样例
        if (StringUtils.hasText(sample1Sql)) {
            effectiveSamples.add(new SampleItem(
                sample1Title != null ? sample1Title : "样例1",
                sample1Desc != null ? sample1Desc : "",
                sample1Sql
            ));
        }
        if (StringUtils.hasText(sample2Sql)) {
            effectiveSamples.add(new SampleItem(
                sample2Title != null ? sample2Title : "样例2",
                sample2Desc != null ? sample2Desc : "",
                sample2Sql
            ));
        }
        if (StringUtils.hasText(sample3Sql)) {
            effectiveSamples.add(new SampleItem(
                sample3Title != null ? sample3Title : "样例3",
                sample3Desc != null ? sample3Desc : "",
                sample3Sql
            ));
        }
        if (StringUtils.hasText(sample4Sql)) {
            effectiveSamples.add(new SampleItem(
                sample4Title != null ? sample4Title : "样例4",
                sample4Desc != null ? sample4Desc : "",
                sample4Sql
            ));
        }
        if (StringUtils.hasText(sample5Sql)) {
            effectiveSamples.add(new SampleItem(
                sample5Title != null ? sample5Title : "样例5",
                sample5Desc != null ? sample5Desc : "",
                sample5Sql
            ));
        }

        // 如果环境变量中没有样例，则使用 yml 中的配置
        if (effectiveSamples.isEmpty() && samples != null && !samples.isEmpty()) {
            return samples;
        }

        return effectiveSamples;
    }

    @Data
    public static class SampleItem {
        /**
         * 样例标题
         */
        private String title;

        /**
         * 样例说明
         */
        private String description;

        /**
         * SQL 内容
         */
        private String sql;

        public SampleItem() {}

        public SampleItem(String title, String description, String sql) {
            this.title = title;
            this.description = description;
            this.sql = sql;
        }
    }
}
