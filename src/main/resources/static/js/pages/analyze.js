async function loadAvailableModels() {
    try {
        const response = await fetch('/SQLAgent/api/models');
        if (!response.ok) {
            throw new Error(t('analyze.modelLoadFailed', {}, 'Failed to load model list'));
        }
        const data = await response.json();

        modelSelect.innerHTML = '';

        data.models.forEach(model => {
            const option = document.createElement('option');
            option.value = model.id;
            option.textContent = model.name;
            if (model.isDefault) {
                option.selected = true;
            }
            modelSelect.appendChild(option);
        });

        console.log('模型列表加载成功，默认模型:', data.defaultModel);
    } catch (error) {
        console.error('加载模型列表失败:', error);
        modelSelect.innerHTML = `
            <option value="moonshotai/Kimi-K2.5">Kimi-K2.5</option>
            <option value="Qwen/Qwen3.5-397B-A17B">Qwen3.5-397B-A17B</option>
            <option value="deepseek-ai/DeepSeek-V3.2">DeepSeek-V3.2</option>
        `;
    }
}

function updateSqlCharCount() {
    const length = sqlInput.value.length;
    charCount.textContent = `${length} / 1000`;
    if (length > 900) {
        charCount.style.color = 'var(--error-color)';
    } else if (length > 700) {
        charCount.style.color = 'var(--warning-color)';
    } else {
        charCount.style.color = 'var(--text-tertiary)';
    }
}

function stripTrailingSemicolons(value) {
    return String(value || '').replace(/[;\s]+$/g, '').trim();
}

function maskQuotedSqlContent(value) {
    return String(value || '')
        .replace(/'(?:''|[^'])*'/g, "''")
        .replace(/"(?:[^"]|"")*"/g, '""')
        .replace(/`(?:``|[^`])*`/g, '``');
}

function compressSql(sql) {
    return String(sql || '')
        .replace(/\s+/g, ' ')           // 多个空白字符替换为单个空格
        .replace(/\(\s+/g, '(')         // 左括号后的空格
        .replace(/\s+\)/g, ')')         // 右括号前的空格
        .replace(/\s*,\s*/g, ',')       // 逗号前后的空格
        .replace(/\s*=\s*/g, '=')       // 等号前后的空格
        .trim();
}

function validateSingleSqlInput(sql) {
    const normalized = stripTrailingSemicolons(maskQuotedSqlContent(sql));
    if (!normalized) {
        return t('analyze.validation.empty', {}, 'Please enter a SQL statement');
    }
    if (/(--|\/\*|\*\/|#)/.test(normalized)) {
        return t('analyze.validation.comment', {}, 'SQL comments are not allowed');
    }
    if (normalized.includes(';')) {
        return t('analyze.validation.multi', {}, 'Only one SQL statement is allowed per optimization analysis');
    }
    if (!/^(select|with)\b/i.test(normalized)) {
        return t('analyze.validation.selectOnly', {}, 'Optimization analysis only supports SELECT or WITH statements');
    }
    if (/\b(insert|update|delete|drop|alter|truncate|create|replace|merge|call|execute|exec|grant|revoke|use|show|set|desc|describe|handler|load|outfile|infile|analyze|optimize|repair)\b/i.test(normalized)) {
        return t('analyze.validation.dangerous', {}, 'The SQL contains dangerous keywords and was rejected');
    }
    return null;
}

function showLoading(show) {
    if (show) {
        loading.classList.remove('hidden');
        analyzeBtn.disabled = true;
    } else {
        loading.classList.add('hidden');
        analyzeBtn.disabled = false;
    }
}

function showError(message) {
    errorMessage.textContent = message;
    errorMessage.classList.remove('hidden');
}

function hideError() {
    errorMessage.classList.add('hidden');
}

const sqlInput = document.getElementById('sqlInput');
const charCount = document.getElementById('charCount');
const analyzeBtn = document.getElementById('analyzeBtn');
const loading = document.getElementById('loading');
const results = document.getElementById('results');
const errorMessage = document.getElementById('errorMessage');
const modelSelect = document.getElementById('modelSelect');
const streamProgressPanel = document.getElementById('streamProgressPanel');
const sqlSamplesContainer = document.getElementById('sqlSamplesContainer');
const formatSqlBtn = document.getElementById('formatSqlBtn');

let currentSessionId = null;
let currentOptimizationResult = null;
let currentTimelineSource = 'all';
let streamPhaseCards = new Map();
let streamActiveToolId = '';
let sqlEditor = null;

// 初始化 CodeMirror
function initSqlEditor() {
    if (typeof CodeMirror === 'undefined') {
        console.warn('CodeMirror 未加载，使用普通 textarea');
        return;
    }

    const wrapper = document.getElementById('sqlEditorWrapper');
    if (!wrapper) return;

    // 创建 CodeMirror 实例
    sqlEditor = CodeMirror.fromTextArea(sqlInput, {
        mode: 'text/x-sql',
        theme: 'default',
        lineNumbers: false,
        matchBrackets: true,
        autoCloseBrackets: true,
        indentUnit: 4,
        tabSize: 4,
        indentWithTabs: false,
        lineWrapping: true,
        scrollbarStyle: 'native',
        viewportMargin: Infinity
    });

    // 设置高度
    sqlEditor.setSize(null, 160);

    // 监听内容变化
    sqlEditor.on('change', function() {
        updateSqlCharCount();
        // 同步到原 textarea
        sqlInput.value = sqlEditor.getValue();
    });

    // 暴露到全局，供其他模块使用
    window.sqlEditor = sqlEditor;

    console.log('SQL 编辑器初始化成功');
}

// 格式化 SQL
function formatSql() {
    const sql = sqlEditor ? sqlEditor.getValue() : sqlInput.value;

    if (!sql.trim()) {
        return;
    }

    try {
        const formatted = sqlFormatter.format(sql, {
            language: 'sql',
            tabWidth: 4,
            keywordCase: 'lower',  // 使用小写，避免表名大小写问题
            indentStyle: 'standard'
        });

        if (sqlEditor) {
            sqlEditor.setValue(formatted);
            sqlEditor.focus();
        } else {
            sqlInput.value = formatted;
        }

        updateSqlCharCount();
    } catch (error) {
        console.error('SQL 格式化失败:', error);
    }
}

loadOptimizationSamples();
loadAvailableModels();

// 页面加载完成后初始化编辑器
if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', function() {
        initSqlEditor();
    });
} else {
    initSqlEditor();
}

// 绑定格式化按钮
if (formatSqlBtn) {
    formatSqlBtn.addEventListener('click', formatSql);
}

sqlInput.addEventListener('input', function() {
    updateSqlCharCount();
});
updateSqlCharCount();

analyzeBtn.addEventListener('click', async function() {
    const sql = sqlInput.value.trim();
    const validationError = validateSingleSqlInput(sql);
    if (validationError) {
        showError(validationError);
        return;
    }
    if (sql.length > 1000) {
        showError(t('analyze.validation.tooLong', {}, 'SQL exceeds the limit (max 1000 characters)'));
        return;
    }

    // 压缩 SQL 用于后端校验和执行（格式化后的 SQL 可能无法通过后端校验）
    const compressedSql = compressSql(sql);

    resetToolStatus();
    const requestData = {
        sql: compressedSql,
        model: modelSelect.value,
        maxPlans: 3
    };

    showLoading(true);
    hideError();
    results.classList.remove('show');

    try {
        const validationErrorFromServer = await validateSqlRequest([compressedSql], 1);
        if (validationErrorFromServer) {
            showError(validationErrorFromServer);
            return;
        }

        await optimizeWithStream(requestData);
    } catch (error) {
        console.error('优化失败:', error);
        showError(t('analyze.optimizeFailed', { message: error.message }, `Optimization failed: ${error.message}`));
    } finally {
        showLoading(false);
    }
});
