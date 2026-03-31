package com.sqlagent.controller;

import com.sqlagent.config.OptimizationSampleConfiguration;
import com.sqlagent.config.OpenAiCompatibleModelProperties;
import com.sqlagent.config.RuntimeFeatureProperties;
import com.sqlagent.model.OptimizationSampleResponse;
import com.sqlagent.model.SqlValidationRequest;
import com.sqlagent.model.SqlValidationResponse;
import com.sqlagent.model.TableDetailResult;
import com.sqlagent.model.TableListResult;
import com.sqlagent.model.ToolInfo;
import com.sqlagent.service.SqlQueryValidationService;
import com.sqlagent.service.TableService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class MetadataController {

    private final OpenAiCompatibleModelProperties modelProperties;
    private final OptimizationSampleConfiguration optimizationSampleConfiguration;
    private final RuntimeFeatureProperties runtimeFeatureProperties;
    private final SqlQueryValidationService sqlQueryValidationService;
    private final TableService tableService;

    @GetMapping("/tools")
    public ResponseEntity<List<ToolInfo>> getTools() {
        List<ToolInfo> tools = List.of(
            new ToolInfo("explain", "explainPlan", "执行计划分析",
                "获取 SQL 的执行计划，分析是否使用索引、扫描行数和访问类型。", "分析"),
            new ToolInfo("ddl", "getDDL", "表结构读取",
                "获取表的 DDL 和索引定义，帮助理解当前表结构与约束信息。", "结构"),
            new ToolInfo("execute", "executeSql", "SQL 执行",
                "执行 SQL 并测量耗时，获取真实返回行数和执行结果。", "执行"),
            new ToolInfo("compare", "compareResult", "结果校验",
                "比较两条 SQL 的结果是否一致，确保优化后的 SQL 不改变业务语义。", "等价性"),
            new ToolInfo("index", "manageIndex", "索引管理",
                "管理索引，可用于创建、删除或查看索引，常用于验证优化方案。", "优化"),
            new ToolInfo("statistics", "analyzeStatistics", "统计信息分析",
                "分析表统计信息，包括基数、选择性和列分布情况，用于判断索引价值。", "分析"),
            new ToolInfo("covering", "analyzeCoveringIndex", "覆盖索引分析",
                "分析是否可以通过覆盖索引减少回表，帮助评估高频查询的优化空间。", "优化"),
            new ToolInfo("skew", "analyzeDataSkew", "数据倾斜分析",
                "分析列的数据倾斜情况，避免在高倾斜列和低选择性列上做错误优化。", "分析"),
            new ToolInfo("recommend", "recommendIndex", "索引推荐",
                "根据 SQL 推荐候选索引，给出创建语句、优先级和预估收益。", "优化")
        );
        return ResponseEntity.ok(tools);
    }

    @GetMapping("/models")
    public ResponseEntity<Map<String, Object>> getAvailableModels() {
        Map<String, Object> response = new HashMap<>();
        String defaultModel = modelProperties.getModel();
        response.put("defaultModel", defaultModel);

        List<Map<String, Object>> models = new ArrayList<>();
        for (String modelId : modelProperties.getAvailableModelsList()) {
            Map<String, Object> model = new HashMap<>();
            model.put("id", modelId);
            model.put("name", modelProperties.getModelDisplayName(modelId));
            model.put("isDefault", modelId.equals(defaultModel));
            models.add(model);
        }

        response.put("models", models);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/optimization-samples")
    public ResponseEntity<OptimizationSampleResponse> getOptimizationSamples() {
        List<OptimizationSampleResponse.SampleItem> samples = optimizationSampleConfiguration.getEffectiveSamples().stream()
            .map(item -> new OptimizationSampleResponse.SampleItem(
                item.getTitle(),
                item.getDescription(),
                item.getSql()
            ))
            .toList();
        return ResponseEntity.ok(new OptimizationSampleResponse(samples));
    }

    @GetMapping("/features")
    public ResponseEntity<Map<String, Object>> getRuntimeFeatures() {
        Map<String, Object> response = new HashMap<>();
        response.put("readOnlyDemo", runtimeFeatureProperties.getDemo().isReadOnly());
        response.put("mutationEnabled", runtimeFeatureProperties.getFeatures().isMutationEndpointsEnabled());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/sql/validate")
    public ResponseEntity<SqlValidationResponse> validateSql(@RequestBody SqlValidationRequest request) {
        int maxCount = request != null && request.getMaxCount() > 0 ? request.getMaxCount() : 1;
        SqlValidationResponse response = sqlQueryValidationService.validateBatch(
            request != null ? request.getSqls() : null,
            maxCount
        );
        return response.isValid()
            ? ResponseEntity.ok(response)
            : ResponseEntity.badRequest().body(response);
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        Map<String, String> status = new HashMap<>();
        status.put("status", "UP");
        status.put("service", "SQL ReAct Agent");
        status.put("framework", "LangChain4j");
        status.put("mode", "ReAct");
        return ResponseEntity.ok(status);
    }

    @GetMapping("/tables")
    public ResponseEntity<TableListResult> getTables() {
        log.info("[tables] list requested");
        return ResponseEntity.ok(tableService.getAllTables());
    }

    @GetMapping("/table/{tableName}/detail")
    public ResponseEntity<TableDetailResult> getTableDetail(@PathVariable String tableName) {
        log.info("[tables] detail requested: table={}", tableName);
        return ResponseEntity.ok(tableService.getTableDetail(tableName));
    }

    @PostMapping("/index/drop")
    public ResponseEntity<String> dropIndex(@RequestBody Map<String, Object> request) {
        if (!runtimeFeatureProperties.getFeatures().isMutationEndpointsEnabled()) {
            return ResponseEntity.status(403).body("""
                {"success":false,"error":"当前运行模式为只读演示，已禁用索引删除能力"}
                """.trim());
        }
        String tableName = (String) request.get("tableName");
        String indexName = (String) request.get("indexName");
        log.info("[tables] drop index requested: table={}, index={}", tableName, indexName);
        return ResponseEntity.ok(tableService.dropIndex(tableName, indexName));
    }
}
