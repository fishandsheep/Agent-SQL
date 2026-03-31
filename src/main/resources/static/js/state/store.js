let currentPage = 'analyze';
let runtimeFeatures = {
    readOnlyDemo: true,
    mutationEnabled: false
};

const toolIconMap = {
    explain: 'query_stats',
    ddl: 'schema',
    execute: 'play_arrow',
    compare: 'compare_arrows',
    index: 'vpn_key',
    statistics: 'bar_chart',
    covering: 'menu_book',
    skew: 'trending_up',
    recommend: 'lightbulb',
    validate: 'verified'
};

const toolDescriptionMap = {
    explainPlan: 'stream.tool.explainPlan',
    getDDL: 'stream.tool.getDDL',
    executeSql: 'stream.tool.executeSql',
    compareResult: 'stream.tool.compareResult',
    manageIndex: 'stream.tool.manageIndex',
    planExecutionEngine: 'stream.tool.planExecutionEngine',
    baselineRunner: 'stream.tool.baselineRunner',
    analyzeStatistics: 'stream.tool.analyzeStatistics',
    analyzeCoveringIndex: 'stream.tool.analyzeCoveringIndex',
    analyzeDataSkew: 'stream.tool.analyzeDataSkew',
    recommendIndex: 'stream.tool.recommendIndex',
    analysis: 'stream.tool.analysis'
};

function getToolDisplayName(toolName, fallback = '') {
    const key = toolDescriptionMap[toolName];
    if (key) {
        return t(key, {}, fallback || toolName || '');
    }
    return fallback || toolName || '';
}

function getExecutionStepDisplayName(stepName, fallback = '') {
    const keyMap = {
        toolCall: 'stream.step.toolCall',
        evaluatePlan: 'stream.step.evaluatePlan',
        measureBaseline: 'stream.step.measureBaseline',
        candidatePlan: 'stream.step.candidatePlan',
        selectBestPlan: 'stream.step.selectBestPlan',
        agentAnalysis: 'stream.step.agentAnalysis'
    };
    const key = keyMap[stepName];
    if (key) {
        return t(key, {}, fallback || stepName || '');
    }
    return fallback || stepName || '';
}

function formatNumber(num) {
    if (num === undefined || num === null) return t('common.na', {}, 'N/A');
    if (window.getCurrentLanguage?.() === 'zh' && num >= 10000) return (num / 10000).toFixed(1) + '\u4e07';
    return num.toLocaleString();
}

function hasRealExecutionTime(comparison) {
    if (!comparison) return false;
    const beforeTime = comparison.before?.executionTime;
    const afterTime = comparison.after?.executionTime;
    return (beforeTime > 0) || (afterTime > 0);
}

function formatExecutionTime(ms) {
    if (!ms || ms <= 0) return '< 1ms';
    if (ms < 1000) return `${Math.round(ms)}ms`;
    return `${(ms / 1000).toFixed(2)}s`;
}

function camelToSnake(value) {
    return String(value || '')
        .replace(/([a-z0-9])([A-Z])/g, '$1_$2')
        .replace(/[-\s]+/g, '_')
        .toLowerCase();
}

window.runtimeFeatures = runtimeFeatures;
window.formatNumber = formatNumber;
window.formatExecutionTime = formatExecutionTime;
window.camelToSnake = camelToSnake;
