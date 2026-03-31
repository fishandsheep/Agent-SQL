async function copyTextToClipboard(text) {
    const value = String(text || '');
    if (!value.trim()) {
        throw new Error(t('results.copyEmpty', {}, 'No content available to copy'));
    }

    if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(value);
        return;
    }

    const textarea = document.createElement('textarea');
    textarea.value = value;
    textarea.setAttribute('readonly', 'readonly');
    textarea.style.position = 'fixed';
    textarea.style.opacity = '0';
    document.body.appendChild(textarea);
    textarea.select();
    document.execCommand('copy');
    document.body.removeChild(textarea);
}

let initialResultsTemplateHtml = '';

function getResultsTemplateHtml() {
    if (!initialResultsTemplateHtml) {
        const resultsRoot = document.getElementById('results');
        initialResultsTemplateHtml = resultsRoot?.innerHTML || '';
    }
    return initialResultsTemplateHtml;
}

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', getResultsTemplateHtml, { once: true });
} else {
    getResultsTemplateHtml();
}

function getResultElement(container, id) {
    return container?.querySelector(`[data-role="${id}"]`) || container?.querySelector(`#${id}`) || null;
}

function getPrimaryResultsContainer() {
    return document.getElementById('results');
}

function bindResultCopyButtons(container, copySources) {
    const buttons = container.querySelectorAll('[data-copy-field]');
    buttons.forEach(button => {
        const field = button.dataset.copyField || '';
        const value = copySources[field] || '';
        button.style.display = value ? 'inline-flex' : 'none';
        button.disabled = !value;
        button.onclick = async () => {
            try {
                await copyTextToClipboard(value);
                showToast(t('results.copySuccess', { label: button.dataset.copyLabel || t('common.copy', {}, 'Copy') }, `${button.dataset.copyLabel || 'Copy'} copied`), 'success');
            } catch (error) {
                console.error('copy failed:', error);
                showToast(t('results.copyFailed', { message: error.message }, `Copy failed: ${error.message}`), 'error');
            }
        };
    });
}

function summarizeExecutionOverview(data) {
    const executionSteps = getExecutionSteps(data.execution || data).map(normalizeTraceStep);
    const validatedPlanIds = new Set(
        executionSteps
            .filter(step => step.stepType === 'plan_execution' && step.toolName && step.toolName !== 'BASELINE')
            .map(step => step.toolName)
    );

    const candidateCount = Number(data?.planSelection?.candidateCount || data?.summary?.candidateCount || data?.plans?.length || 0);
    const validatedCount = validatedPlanIds.size;
    const selectedPlanId = data?.bestPlanId || data?.planSelection?.selectedPlanId || '-';
    const totalTimeMs = Number(data?.totalTime || 0) || executionSteps.reduce((sum, step) => sum + (Number(step.executionTimeMs) || 0), 0);
    const selectedPlan = getBestPlanFromResponse(data);

    return {
        candidateCount,
        validatedCount,
        selectedPlanId,
        selectedPlanType: selectedPlan?.type || '',
        totalTimeMs
    };
}

function renderPhaseSummaryCard(container, data) {
    const summaryCard = getResultElement(container, 'phaseSummaryCard');
    const summaryGrid = getResultElement(container, 'phaseSummaryGrid');
    const summaryBadge = getResultElement(container, 'phaseSummaryBadge');
    if (!summaryCard || !summaryGrid || !summaryBadge) return;

    const overview = summarizeExecutionOverview(data);
    const selectedTypeText = overview.selectedPlanType ? formatPlanType(overview.selectedPlanType) : '未选定';
    const totalTimeText = overview.totalTimeMs > 0 ? formatExecutionTime(overview.totalTimeMs) : '-';

    summaryGrid.innerHTML = `
        <div class="result-phase-summary-item">
            <div class="result-phase-summary-label">生成候选</div>
            <div class="result-phase-summary-value">${overview.candidateCount || 0}</div>
            <div class="result-phase-summary-note">当前分析链路中实际生成的候选方案数量。</div>
        </div>
        <div class="result-phase-summary-item">
            <div class="result-phase-summary-label">完成验证</div>
            <div class="result-phase-summary-value">${overview.validatedCount || 0}</div>
            <div class="result-phase-summary-note">${overview.candidateCount > 0 ? `已验证 ${overview.validatedCount} / ${overview.candidateCount} 个候选方案。` : '当前结果未返回候选验证明细。'}</div>
        </div>
        <div class="result-phase-summary-item">
            <div class="result-phase-summary-label">最终入选</div>
            <div class="result-phase-summary-value">${escapeHtml(overview.selectedPlanId)}</div>
            <div class="result-phase-summary-note">${escapeHtml(selectedTypeText)}</div>
        </div>
        <div class="result-phase-summary-item">
            <div class="result-phase-summary-label">总耗时</div>
            <div class="result-phase-summary-value">${escapeHtml(totalTimeText)}</div>
            <div class="result-phase-summary-note">含候选生成、验证、评分与结果整理。</div>
        </div>
    `;

    const success = Boolean(data?.success);
    summaryBadge.textContent = success
        ? `已完成 ${overview.validatedCount || 0}${overview.candidateCount > 0 ? ` / ${overview.candidateCount}` : ''}`
        : '结果未完成';
    summaryBadge.className = `soft-badge ${success ? 'primary' : ''}`.trim();
    summaryCard.style.display = 'block';
}

function displayResults(data, targetContainerId = 'results') {
    const container = document.getElementById(targetContainerId);
    if (!container) return;
    container.dataset.scope = targetContainerId === 'results' ? 'primary' : (container.dataset.scope || 'secondary');

    if (!data.success) {
        showError(data.error || data.errorMessage || t('results.optimizeFailed', {}, 'Optimization failed'));
        return;
    }

    const bestPlan = getBestPlanFromResponse(data);
    const outcomeStatus = data.outcomeStatus || (data.improvementPercentage > 0 ? 'SUCCESS' : 'NO_NEED');
    const needsOptimization = outcomeStatus !== 'NO_NEED';
    const noOptimizationBanner = getResultElement(container, 'noOptimizationBanner');
    const originalSqlCard = getResultElement(container, 'originalSqlCard');
    const optimizedSqlCard = getResultElement(container, 'optimizedSqlCard');
    const timeComparisonCard = getResultElement(container, 'timeComparisonCard');
    const improvementCard = getResultElement(container, 'improvementCard');
    const statusBadge = getResultElement(container, 'statusBadge');
    const phaseSummaryCard = getResultElement(container, 'phaseSummaryCard');
    const timeComparisonElement = getResultElement(container, 'timeComparison');
    const rowsComparisonElement = getResultElement(container, 'rowsComparison');
    const timeTitleElement = getResultElement(container, 'timeComparisonTitle');
    const rowsTitleElement = getResultElement(container, 'rowsComparisonTitle');

    if (!needsOptimization) {
        if (noOptimizationBanner) noOptimizationBanner.style.display = 'block';
        if (originalSqlCard) originalSqlCard.style.display = 'none';
        if (optimizedSqlCard) optimizedSqlCard.style.display = 'none';
        if (timeComparisonCard) timeComparisonCard.style.display = 'block';
        if (improvementCard) improvementCard.style.display = 'none';
        if (phaseSummaryCard) phaseSummaryCard.style.display = 'none';
        if (statusBadge) statusBadge.innerHTML = `<span class="soft-badge success">${t('results.good', {}, 'Healthy Performance')}</span>`;
    } else {
        if (noOptimizationBanner) noOptimizationBanner.style.display = 'none';
        if (originalSqlCard) originalSqlCard.style.display = 'block';
        if (optimizedSqlCard) optimizedSqlCard.style.display = 'block';
        renderPhaseSummaryCard(container, data);
    }

    const originalSqlElement = getResultElement(container, 'originalSql');
    const originalSqlValue = data.originalSql || '';
    if (originalSqlElement) {
        originalSqlElement.textContent = originalSqlValue;
    }

    const optimizedSqlElement = getResultElement(container, 'optimizedSql');
    const optimizedSqlValue = bestPlan?.optimizedSql || data.optimizedSql || data.originalSql || '';
    if (optimizedSqlElement) {
        optimizedSqlElement.textContent = optimizedSqlValue;
    }

    if (statusBadge && needsOptimization) {
        if (outcomeStatus === 'SUCCESS') {
            statusBadge.innerHTML = `<span class="soft-badge success">${t('results.status.success', {}, 'Optimization Succeeded')}</span>`;
        } else if (outcomeStatus === 'PARTIAL_SUCCESS') {
            statusBadge.innerHTML = `<span class="soft-badge warning">${t('results.status.partial', {}, 'Partially Succeeded')}</span>`;
        } else if (outcomeStatus === 'NO_NEED') {
            statusBadge.innerHTML = `<span class="soft-badge">${t('results.status.none', {}, 'No Optimization Needed')}</span>`;
        } else {
            statusBadge.innerHTML = `<span class="soft-badge error">${t('results.status.failed', {}, 'Optimization Failed')}</span>`;
        }
    }

    syncToolHighlights(data.execution);

    const beforeTime = data.summary?.baselineExecutionTime || data.baselineExecutionTime || 0;
    const afterTime = data.summary?.optimizedExecutionTime || data.bestPlanExecutionTime || 0;
    if (timeComparisonCard && timeComparisonElement && beforeTime > 0) {
        timeComparisonCard.style.display = 'block';
        if (timeTitleElement) {
            timeTitleElement.textContent = needsOptimization ? t('results.executionTime', {}, 'Execution Time') : t('results.currentTime', {}, 'Current Time');
        }
        timeComparisonElement.textContent = needsOptimization && afterTime > 0
            ? `${formatExecutionTime(beforeTime)} -> ${formatExecutionTime(afterTime)}`
            : formatExecutionTime(beforeTime);
    } else if (timeComparisonCard) {
        timeComparisonCard.style.display = 'none';
    }

    const bestPlanExplain = bestPlan?.explain;
    const baselineExplain = getBaselineExplain(data);
    const baselineRows = baselineExplain?.rows || data.summary?.baselineRows || 0;
    if (rowsComparisonElement) {
        if (rowsTitleElement) {
            rowsTitleElement.textContent = needsOptimization ? t('results.rows', {}, 'Scanned Rows') : t('results.currentRows', {}, 'Current Scanned Rows');
        }
        if (!needsOptimization && baselineRows > 0) {
            rowsComparisonElement.textContent = formatNumber(baselineRows);
        } else if (bestPlanExplain?.rows) {
            rowsComparisonElement.textContent = baselineRows > 0
                ? `${formatNumber(baselineRows)} -> ${formatNumber(bestPlanExplain.rows)}`
                : formatNumber(bestPlanExplain.rows);
        } else {
            rowsComparisonElement.textContent = baselineRows > 0 ? formatNumber(baselineRows) : '-';
        }
    }

    let improvement = data.improvementPercentage || 0;
    if (improvement === 0 && data.summary?.improvement) {
        const match = data.summary.improvement.match(/([\d.]+)/);
        if (match) improvement = parseFloat(match[1]);
    }

    const improvementValueElement = getResultElement(container, 'improvementValue');
    if (improvementCard && improvementValueElement && needsOptimization && improvement !== 0) {
        improvementCard.style.display = 'block';
        const symbol = improvement > 0 ? '+' : '-';
        const valueClass = improvement > 0 ? 'color: var(--success-color);' : 'color: var(--error-color);';
        improvementValueElement.innerHTML = `<span style="${valueClass}">${symbol} ${Math.abs(improvement).toFixed(1)}%</span>`;
    } else if (improvementCard) {
        improvementCard.style.display = 'none';
    }

    const strategyElement = getResultElement(container, 'strategyInfo');
    if (strategyElement && data.summary?.strategy) {
        strategyElement.textContent = t('results.strategy', { strategy: data.summary.strategy }, `Strategy: ${data.summary.strategy}`);
        strategyElement.style.display = 'block';
    } else if (strategyElement) {
        strategyElement.style.display = 'none';
    }

    notifySqlRewriteAdvisory(data.sqlRewriteAdvisory);

    if (bestPlanExplain) {
        displayExplainTableForPlan(bestPlanExplain, targetContainerId);
    } else {
        const explainCard = getResultElement(container, 'explainCard');
        if (explainCard) {
            explainCard.style.display = 'none';
        }
    }

    const indexOperationCard = getResultElement(container, 'indexOperationCard');
    const indexOperationContent = getResultElement(container, 'indexOperationContent');
    const indexOperationBadge = getResultElement(container, 'indexOperationBadge');
    const indexDdlValue = bestPlan?.indexDDL || '';
    if (indexOperationCard && indexOperationContent && indexOperationBadge) {
        indexOperationContent.innerHTML = '';
        if (indexDdlValue) {
            indexOperationCard.style.display = 'block';
            indexOperationBadge.textContent = t('results.recommendedIndex', {}, 'Recommended Index');
            indexOperationBadge.className = 'soft-badge success';
            indexOperationContent.innerHTML = `
                <div class="soft-card-inset p-3">
                    <div class="flex items-center gap-2 mb-2">
                        <span class="material-symbols-outlined" style="color: var(--success-color);">add_circle</span>
                        <span class="text-sm font-bold" style="color: var(--text-primary);">${t('results.recommendedIndexTitle', {}, 'Suggested Index to Create')}</span>
                    </div>
                    <pre class="soft-code-block p-3 text-xs code-font overflow-x-auto"><code style="color: #e2e8f0;">${escapeHtml(indexDdlValue)}</code></pre>
                </div>
            `;
        } else {
            indexOperationCard.style.display = 'none';
        }
    }

    displayExplainComparison(data, targetContainerId);
    const explainComparisonCard = getResultElement(container, 'explainComparisonCard');
    if (explainComparisonCard && (!baselineExplain || !bestPlanExplain)) {
        explainComparisonCard.style.display = 'none';
    }

    const problemsCard = getResultElement(container, 'problemsCard');
    const suggestionsCard = getResultElement(container, 'suggestionsCard');
    const explanationsCard = getResultElement(container, 'explanationsCard');
    if (problemsCard) problemsCard.style.display = 'none';
    if (suggestionsCard) suggestionsCard.style.display = 'none';
    if (explanationsCard) explanationsCard.style.display = 'none';

    renderPlanDecisionWorkbench(data, container, true);
    updateDetailedAnalysisButton(data, container);

    const executionSteps = getExecutionSteps(data.execution || data);
    const timelineCard = getResultElement(container, 'timelineCard');
    if (timelineCard) {
        timelineCard.style.display = executionSteps.length > 0 ? 'block' : 'none';
    }
    if (executionSteps.length > 0) {
        container.__timelinePayload = data.execution || data;
        displayTimeline(data.execution || data, container);
    } else {
        container.__timelinePayload = null;
    }

    bindResultCopyButtons(container, {
        originalSql: originalSqlValue,
        optimizedSql: optimizedSqlValue,
        indexDDL: indexDdlValue
    });

    container.classList.add('show');
    updateApplyOptimizationButton(data, container);
    requestAnimationFrame(syncOpenCollapsibleHeights);
}

function updateApplyOptimizationButton(data, container) {
    const applyBtn = getResultElement(container, 'applyOptimizationBtn');
    if (!applyBtn) return;

    if (container.id !== 'results') {
        applyBtn.classList.add('hidden');
        return;
    }

    if (!runtimeFeatures?.mutationEnabled) {
        applyBtn.classList.add('hidden');
        return;
    }

    const bestPlan = data.plans && data.plans.length > 0
        ? data.plans.find(p => p.planId === data.bestPlanId)
        : null;
    const hasIndex = bestPlan && bestPlan.indexDDL && bestPlan.indexDDL.trim() !== '';

    if (hasIndex && data.success) {
        applyBtn.classList.remove('hidden');
        applyBtn.disabled = false;
        applyBtn.innerHTML = `<span class="material-symbols-outlined text-sm align-middle mr-1">build</span>${t('results.apply', {}, 'Apply Optimization')}`;
    } else {
        applyBtn.classList.add('hidden');
    }
}

async function applyOptimization() {
    if (!runtimeFeatures?.mutationEnabled) {
        showToast(t('results.applyDisabled', {}, 'Read-only demo mode is enabled, so real index changes are disabled'), 'info');
        return;
    }

    if (!currentOptimizationResult || !currentSessionId) {
        showToast(t('results.noApplicablePlan', {}, 'No applicable optimization plan was found'), 'error');
        return;
    }

    const applyBtn = getResultElement(getPrimaryResultsContainer(), 'applyOptimizationBtn');
    if (!applyBtn) return;
    applyBtn.disabled = true;
    applyBtn.innerHTML = `<span class="material-symbols-outlined text-sm align-middle mr-1 soft-loading">hourglass_empty</span>${t('results.applying', {}, 'Applying...')}`;

    try {
        const requestData = {
            planId: currentOptimizationResult.bestPlanId,
            sessionId: currentSessionId
        };

        const response = await fetch('/SQLAgent/api/apply-optimization', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(requestData)
        });

        const result = await response.json();

        if (result.success) {
            showToast(`✓ ${result.message || t('results.applySuccess', {}, 'Optimization applied successfully')}`, 'success');
            applyBtn.innerHTML = `<span class="material-symbols-outlined text-sm align-middle mr-1">check</span>${t('results.applied', {}, 'Applied')}`;
        } else {
            showToast(`✗ ${result.error || t('results.applyFailed', {}, 'Failed to apply optimization')}`, 'error');
            applyBtn.disabled = false;
            applyBtn.innerHTML = `<span class="material-symbols-outlined text-sm align-middle mr-1">build</span>${t('common.retry', {}, 'Retry')}`;
        }
    } catch (error) {
        console.error('应用优化失败:', error);
        showToast(`✗ ${t('results.applyFailed', {}, 'Failed to apply optimization')}: ${error.message}`, 'error');
        applyBtn.disabled = false;
        applyBtn.innerHTML = `<span class="material-symbols-outlined text-sm align-middle mr-1">build</span>${t('common.retry', {}, 'Retry')}`;
    }
}

function updateDetailedAnalysisButton(data, container) {
    let button = getResultElement(container, 'detailedAnalysisBtn');

    if (!button) {
        const optimizedSqlCard = getResultElement(container, 'optimizedSqlCard');

        button = document.createElement('button');
        button.setAttribute('data-role', 'detailedAnalysisBtn');
        button.className = 'soft-button w-full mt-3';
        button.onclick = () => toggleDetailedAnalysis(data, container);

        if (optimizedSqlCard?.parentNode) {
            optimizedSqlCard.parentNode.insertBefore(button, optimizedSqlCard.nextSibling);
        }
    } else {
        button.onclick = () => toggleDetailedAnalysis(data, container);
    }

    button.innerHTML = (getResultElement(container, 'planWorkbenchCard')?.dataset?.expanded === 'true')
        ? `<span class="material-symbols-outlined">expand_less</span><span>${t('results.detailCollapse', {}, 'Hide plan decision details')}</span>`
        : `<span class="material-symbols-outlined">search</span><span>${t('results.detailExpand', {}, 'Show plan decision details')}</span>`;
    button.style.display = (data?.plans?.length || 0) > 0 ? 'block' : 'none';
}

function toggleDetailedAnalysis(data, container) {
    const button = getResultElement(container, 'detailedAnalysisBtn');
    const workbenchCard = getResultElement(container, 'planWorkbenchCard');
    const nextExpanded = !(workbenchCard?.dataset?.expanded === 'true');

    renderPlanDecisionWorkbench(data, container, nextExpanded);

    if (button) {
        button.innerHTML = nextExpanded
            ? `<span class="material-symbols-outlined">expand_less</span><span>${t('results.detailCollapse', {}, 'Hide plan decision details')}</span>`
            : `<span class="material-symbols-outlined">search</span><span>${t('results.detailExpand', {}, 'Show plan decision details')}</span>`;
    }

    requestAnimationFrame(syncOpenCollapsibleHeights);
}

window.getResultsTemplateHtml = getResultsTemplateHtml;
