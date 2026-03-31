// ==================== 工具列表管理 ====================
    function getToolI18nName(toolId, fallback = '') {
        return t(`tools.${toolId}.name`, {}, fallback || toolId);
    }

    function getToolI18nDescription(toolId, fallback = '') {
        return t(`tools.${toolId}.description`, {}, fallback || t('tools.noDescription'));
    }

    async function loadToolsList() {
        try {
            console.log('正在从 /api/tools 加载工具列表...');
            const response = await fetch('/SQLAgent/api/tools');

            if (!response.ok) {
                console.warn('/api/tools 请求失败，状态码:', response.status);
                throw new Error(`Failed to load tools: ${response.status}`);
            }

            const tools = await response.json();
            console.log('成功加载工具列表:', tools);
            displayTools(tools);
        } catch (error) {
            console.error('加载工具列表失败，使用默认工具列表:', error);
            displayDefaultTools();
        }
    }

    function getDefaultToolCatalog() {
        return [
            { id: 'explain', name: getToolI18nName('explain', 'explainPlan'), description: getToolI18nDescription('explain') },
            { id: 'ddl', name: getToolI18nName('ddl', 'getDDL'), description: getToolI18nDescription('ddl') },
            { id: 'execute', name: getToolI18nName('execute', 'executeSql'), description: getToolI18nDescription('execute') },
            { id: 'compare', name: getToolI18nName('compare', 'compareResult'), description: getToolI18nDescription('compare') },
            { id: 'index', name: getToolI18nName('index', 'manageIndex'), description: getToolI18nDescription('index') },
            { id: 'statistics', name: getToolI18nName('statistics', 'analyzeStatistics'), description: getToolI18nDescription('statistics') },
            { id: 'covering', name: getToolI18nName('covering', 'analyzeCoveringIndex'), description: getToolI18nDescription('covering') },
            { id: 'skew', name: getToolI18nName('skew', 'analyzeDataSkew'), description: getToolI18nDescription('skew') },
            { id: 'recommend', name: getToolI18nName('recommend', 'recommendIndex'), description: getToolI18nDescription('recommend') }
        ];
    }

    function normalizeToolId(rawToolId) {
        if (!rawToolId) return '';

        const normalized = String(rawToolId).trim().toLowerCase();
        const mapping = {
            explainplan: 'explain',
            explain: 'explain',
            getddl: 'ddl',
            ddl: 'ddl',
            executesql: 'execute',
            baselinerunner: 'execute',
            planexecutionengine: 'execute',
            execute: 'execute',
            compareresult: 'compare',
            compare: 'compare',
            manageindex: 'index',
            index: 'index',
            analyzestatistics: 'statistics',
            statistics: 'statistics',
            analyzecoveringindex: 'covering',
            covering: 'covering',
            analyzedataskew: 'skew',
            skew: 'skew',
            recommendindex: 'recommend',
            recommend: 'recommend'
        };

        return mapping[normalized] || '';
    }

    function applyToolIntensity(card, count = 0, isActive = false) {
        if (!card) return;

        const safeCount = Math.max(0, Number(count) || 0);
        const level = Math.min(safeCount, 6);
        const borderAlpha = safeCount > 0 ? 0.34 + level * 0.07 : 0.14;
        const fillAlpha = safeCount > 0 ? 0.1 + level * 0.04 : 0.03;
        const glowAlpha = safeCount > 0 ? 0.1 + level * 0.04 : 0.03;
        const badgeAlpha = safeCount > 0 ? 0.3 + level * 0.06 : 0.14;
        const badgeScale = safeCount > 0 ? 1 + Math.min(level, 4) * 0.06 : 0.92;

        card.style.setProperty('--tool-border-alpha', borderAlpha.toFixed(2));
        card.style.setProperty('--tool-fill-alpha', fillAlpha.toFixed(2));
        card.style.setProperty('--tool-glow-alpha', glowAlpha.toFixed(2));
        card.style.setProperty('--tool-badge-alpha', badgeAlpha.toFixed(2));
        card.style.setProperty('--tool-badge-scale', badgeScale.toFixed(2));

        card.classList.toggle('used', safeCount > 0);
        card.classList.toggle('active', Boolean(isActive));
        card.dataset.usageCount = String(safeCount);

        const badge = card.querySelector('.tool-usage-badge');
        if (badge) {
            badge.textContent = String(safeCount);
            badge.style.opacity = safeCount > 0 ? '1' : '0.6';
        }

        const status = card.querySelector('.tool-status');
        if (status) {
            if (isActive) {
                status.textContent = t('tools.status.active', { count: safeCount }, `Running, ${safeCount} calls`);
                status.style.color = 'var(--success-color)';
            } else if (safeCount > 0) {
                status.textContent = t('tools.status.used', { count: safeCount }, `Used, ${safeCount} calls`);
                status.style.color = 'var(--primary-gradient-start)';
            } else {
                status.textContent = t('tools.status.available', {}, 'Available');
                status.style.color = 'var(--text-secondary)';
            }
        }
    }

    function displayTools(tools) {
        const container = document.getElementById('toolsContainer');
        if (!container) return;

        const catalog = getDefaultToolCatalog();
        const catalogMap = new Map(catalog.map(tool => [tool.id, tool]));
        const incoming = Array.isArray(tools) && tools.length > 0 ? tools : catalog;
        const mergedTools = [];
        const seen = new Set();

        incoming.forEach(tool => {
            const normalizedId = normalizeToolId(tool?.id || tool?.name);
            if (!normalizedId || seen.has(normalizedId)) return;
            seen.add(normalizedId);
            const base = catalogMap.get(normalizedId) || {};
            mergedTools.push({
                ...base,
                ...tool,
                id: normalizedId,
                name: getToolI18nName(normalizedId, tool?.name || base.name || normalizedId),
                description: getToolI18nDescription(normalizedId, tool?.description || base.description || t('tools.noDescription'))
            });
        });

        catalog.forEach(tool => {
            if (!seen.has(tool.id)) {
                mergedTools.push(tool);
            }
        });

        container.innerHTML = '';

        if (mergedTools.length === 0) {
            container.innerHTML = `<div class="soft-card-inset p-4 text-center"><div class="text-sm" style="color: var(--text-tertiary);">${t('tools.empty', {}, 'No tools available')}</div></div>`;
            return;
        }

        mergedTools.forEach(tool => {
            const toolCard = document.createElement('div');
            toolCard.className = 'soft-card-inset tool-card p-4';
            toolCard.id = `tool-${tool.id}`;
            toolCard.dataset.toolId = tool.id;
            toolCard.dataset.usageCount = '0';

            const icon = toolIconMap[tool.id] || 'build';
            toolCard.innerHTML = `
                <span class="tool-usage-badge">0</span>
                <div class="soft-icon-container w-10 h-10 mb-3">
                    <span class="material-symbols-outlined text-lg" style="color: var(--primary-gradient-start);">${icon}</span>
                </div>
                <div class="text-sm font-bold mb-1" style="color: var(--text-primary);">${tool.name || tool.id}</div>
                <div class="text-xs leading-5 mb-3" style="color: var(--text-tertiary); min-height: 56px;">${tool.description || t('tools.noDescription', {}, 'No description available')}</div>
                <div class="tool-status">${t('tools.status.available', {}, 'Available')}</div>
            `;

            applyToolIntensity(toolCard, 0, false);
            container.appendChild(toolCard);
        });
    }

    function displayDefaultTools() {
        displayTools(getDefaultToolCatalog());
    }

    function resetToolStatus() {
        streamActiveToolId = '';
        document.querySelectorAll('#toolsContainer .tool-card').forEach(card => {
            applyToolIntensity(card, 0, false);
        });
    }

    function markToolAsUsed(toolId, options = {}) {
        const normalizedId = normalizeToolId(toolId);
        if (!normalizedId) return;

        const toolCard = document.querySelector(`#tool-${normalizedId}`);
        if (!toolCard) return;

        const currentCount = Number(toolCard.dataset.usageCount || '0');
        const nextCount = Math.max(options.count ?? currentCount + 1, 0);
        applyToolIntensity(toolCard, nextCount, Boolean(options.active));
    }

    function syncToolHighlights(executionData, activeToolId = '') {
        const usage = extractToolsFromExecution(executionData);
        resetToolStatus();

        usage.forEach(tool => {
            markToolAsUsed(tool.id, {
                count: tool.count,
                active: tool.id === normalizeToolId(activeToolId)
            });
        });
    }

    function extractToolsFromExecution(executionData) {
        const steps = getExecutionSteps(executionData);
        if (steps.length === 0) return [];

        const toolsMap = new Map();
        steps.forEach(step => {
            const stepType = String(step.stepType || '').toLowerCase();
            if (stepType === 'phase' || stepType === 'candidate_plan' || stepType === 'selection') {
                return;
            }

            const toolId = normalizeToolId(step.toolName || step.tool_name || step.stepName || step.stepType);
            if (!toolId) return;

            const current = toolsMap.get(toolId) || {
                id: toolId,
                name: getDefaultToolCatalog().find(tool => tool.id === toolId)?.name || toolId,
                count: 0
            };
            current.count += 1;
            toolsMap.set(toolId, current);
        });

        return Array.from(toolsMap.values());
    }

    function getExecutionSteps(executionData) {
        if (!executionData) return [];
        if (Array.isArray(executionData)) return executionData;
        if (Array.isArray(executionData.analysisTimeline)) return executionData.analysisTimeline;
        if (executionData.agentExecution && Array.isArray(executionData.agentExecution)) {
            return executionData.agentExecution;
        }
        return [];
    }

    function getBaselineExplain(data) {
        if (data?.explainComparison?.baseline) {
            return data.explainComparison.baseline;
        }
        if (data && data.baselineExplain) {
            return data.baselineExplain;
        }

        const steps = getExecutionSteps(data?.execution);
        const baselineStep = steps.find(step =>
            step.stepName === 'measureBaseline' || step.planId === 'BASELINE' || step.stepType === 'baseline'
        );

        return baselineStep?.explain || null;
    }

    window.getBaselineExplain = getBaselineExplain;
