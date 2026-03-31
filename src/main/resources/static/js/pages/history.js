async function loadHistoryList() {
    const btn = document.getElementById('refreshHistoryBtn');
    const dateFrom = document.getElementById('historyFilterFrom')?.value || '';
    const dateTo = document.getElementById('historyFilterTo')?.value || '';

    btn.disabled = true;
    btn.innerHTML = `<span class="material-symbols-outlined text-sm soft-loading">hourglass_empty</span><span class="font-bold text-sm">${t('common.loading', {}, 'Loading...')}</span>`;

    try {
        const params = new URLSearchParams({ limit: '50' });
        if (dateFrom) params.set('dateFrom', dateFrom);
        if (dateTo) params.set('dateTo', dateTo);

        const response = await fetch(`/SQLAgent/api/history/list?${params.toString()}`);
        const data = await response.json();

        if (data.success) {
            displayHistoryList(data.histories);
        } else {
            showToast(t('common.loadFailed', { message: data.error }, `Load failed: ${data.error}`), 'error');
        }
    } catch (error) {
        console.error('加载历史记录失败:', error);
        showToast(t('common.loadFailed', { message: error.message }, `Load failed: ${error.message}`), 'error');
    } finally {
        btn.disabled = false;
        btn.innerHTML = `<span class="material-symbols-outlined text-sm">refresh</span><span class="font-bold text-sm">${t('history.refresh', {}, 'Refresh List')}</span>`;
    }
}

function normalizeScopedResultTemplate(container) {
    container.querySelectorAll('[id]').forEach(element => {
        if (!element.dataset.role) {
            element.dataset.role = element.id;
        }
        element.removeAttribute('id');
    });
}

function displayHistoryList(histories) {
    const container = document.getElementById('historyList');
    container.innerHTML = '';

    if (!histories || histories.length === 0) {
        container.innerHTML = `
            <div class="soft-card-inset p-8 text-center">
                <span class="material-symbols-outlined text-5xl mb-3" style="color: var(--text-tertiary);">history</span>
                <p class="font-medium" style="color: var(--text-secondary);">${t('history.empty', {}, 'No history records yet')}</p>
            </div>
        `;
        return;
    }

    histories.forEach(item => {
        const card = document.createElement('div');
        card.className = 'soft-card p-5 smooth-transition cursor-pointer history-record-card';

        const isNoNeed = item.strategy === '无需优化' || item.strategy === 'No Optimization Needed';
        const isSuccess = !!item.optimizationSuccessful;
        const statusBadge = isNoNeed
            ? `<span class="soft-badge success">${t('history.status.none', {}, 'No Optimization Needed')}</span>`
            : isSuccess
                ? `<span class="soft-badge success">${t('history.status.success', {}, 'Optimization Succeeded')}</span>`
                : `<span class="soft-badge error">${t('history.status.failed', {}, 'Optimization Failed')}</span>`;

        const metricText = isNoNeed || !item.optimizedExecutionTime || item.optimizedExecutionTime === item.baselineExecutionTime
            ? t('history.metric.currentTime', { time: formatExecutionTime(item.baselineExecutionTime || 0) }, `Current time: ${formatExecutionTime(item.baselineExecutionTime || 0)}`)
            : t('history.metric.executionTime', {
                before: formatExecutionTime(item.baselineExecutionTime || 0),
                after: formatExecutionTime(item.optimizedExecutionTime || 0)
            }, `Execution time: ${formatExecutionTime(item.baselineExecutionTime || 0)} -> ${formatExecutionTime(item.optimizedExecutionTime || 0)}`);

        const rowText = isNoNeed || item.optimizedRows == null || item.optimizedRows === item.baselineRows
            ? t('history.metric.rows.single', { rows: item.baselineRows != null ? formatNumber(item.baselineRows) : '-' }, `Scanned rows: ${item.baselineRows != null ? formatNumber(item.baselineRows) : '-'}`)
            : t('history.metric.rows.double', { before: formatNumber(item.baselineRows || 0), after: formatNumber(item.optimizedRows || 0) }, `Scanned rows: ${formatNumber(item.baselineRows || 0)} -> ${formatNumber(item.optimizedRows || 0)}`);

        const deltaBadge = !isNoNeed && item.improvementPercentage != null && item.improvementPercentage !== 0
            ? `<span class="soft-badge ${item.improvementPercentage > 0 ? 'success' : 'warning'}">${item.improvementPercentage > 0 ? t('history.delta.up', { value: Math.abs(item.improvementPercentage).toFixed(1) }, `Improved ${Math.abs(item.improvementPercentage).toFixed(1)}%`) : t('history.delta.down', { value: Math.abs(item.improvementPercentage).toFixed(1) }, `Variance ${Math.abs(item.improvementPercentage).toFixed(1)}%`)}</span>`
            : '';

        card.innerHTML = `
            <div class="flex items-start justify-between gap-4 history-record-layout">
                <div class="flex-1 min-w-0 history-record-main">
                    <div class="flex items-center gap-2 mb-3 flex-wrap">
                        ${statusBadge}
                        ${item.strategy ? `<span class="soft-badge">${escapeHtml(item.strategy)}</span>` : ''}
                        ${deltaBadge}
                        <span class="text-xs" style="color: var(--text-tertiary);">${item.createdAt || ''}</span>
                    </div>
                    <div class="text-sm font-medium mb-2 code-font truncate" style="color: var(--text-primary);">${escapeHtml(item.originalSqlFirstLine || item.originalSql || '')}</div>
                    ${item.optimizedSql && item.optimizedSql !== item.originalSql
                        ? `<div class="text-xs truncate code-font mb-2" style="color: var(--text-secondary);">→ ${escapeHtml(item.optimizedSql)}</div>`
                        : ''}
                    <div class="grid grid-cols-1 md:grid-cols-2 gap-2 text-xs history-record-metrics" style="color: var(--text-tertiary);">
                        <div>${metricText}</div>
                        <div>${rowText}</div>
                    </div>
                    ${item.totalTime != null || item.modelName
                        ? `<div class="text-xs mt-3" style="color: var(--text-tertiary);">
                            ${item.totalTime != null ? t('history.taskTime', { time: formatExecutionTime(item.totalTime) }, `Task time ${formatExecutionTime(item.totalTime)}`) : ''}
                            ${item.totalTime != null && item.modelName ? ' · ' : ''}
                            ${item.modelName ? t('history.model', { name: escapeHtml(item.modelName) }, `Model ${escapeHtml(item.modelName)}`) : ''}
                        </div>`
                        : ''}
                </div>
                <button onclick="event.stopPropagation(); deleteHistory(${item.id})" class="soft-button-secondary py-2 px-3 flex items-center gap-1 history-record-action">
                    <span class="material-symbols-outlined text-sm">delete</span>
                    <span class="font-bold text-xs">${t('common.delete', {}, 'Delete')}</span>
                </button>
            </div>
        `;

        card.addEventListener('click', () => loadHistoryDetail(item.id));
        container.appendChild(card);
    });
}

async function loadHistoryDetail(historyId) {
    try {
        const response = await fetch(`/SQLAgent/api/history/${historyId}`);
        const data = await response.json();

        if (data.success && data.analysis) {
            displayHistoryDetail(data.analysis);
        } else {
            showToast(t('history.detailLoadFailed', { message: data.error || t('history.detailEmpty', {}, 'Detail is empty') }, `Failed to load detail: ${data.error || 'Detail is empty'}`), 'error');
        }
    } catch (error) {
        console.error('加载历史详情失败:', error);
        showToast(t('history.detailLoadFailed', { message: error.message }, `Failed to load detail: ${error.message}`), 'error');
    }
}

function displayHistoryDetail(analysisData) {
    document.getElementById('historyListSection').style.display = 'none';
    document.getElementById('historyDetailSection').style.display = 'block';

    const historyResults = document.getElementById('historyResults');
    const sourceResults = document.getElementById('results');

    // 克隆当前页面的结果模板，确保使用最新的语言和结构
    if (sourceResults) {
        historyResults.innerHTML = sourceResults.innerHTML;
    } else {
        historyResults.innerHTML = typeof getResultsTemplateHtml === 'function' ? getResultsTemplateHtml() : '';
    }
    historyResults.dataset.scope = 'history';

    if (!historyResults.innerHTML) {
        showToast(t('history.templateLoadFailed', {}, 'Failed to load detail template'), 'error');
        return;
    }

    normalizeScopedResultTemplate(historyResults);

    displayResults(analysisData, 'historyResults');
}

async function deleteHistory(historyId) {
    if (!confirm(t('history.deleteConfirm', {}, 'Delete this history record?'))) {
        return;
    }

    try {
        const response = await fetch(`/SQLAgent/api/history/${historyId}`, { method: 'DELETE' });
        const data = await response.json();

        if (data.success) {
            showToast(t('common.deleteSuccess', {}, 'Deleted successfully'), 'success');
            loadHistoryList();
        } else {
            showToast(t('common.deleteFailed', { message: data.message }, `Delete failed: ${data.message}`), 'error');
        }
    } catch (error) {
        console.error('删除历史记录失败:', error);
        showToast(t('common.deleteFailed', { message: error.message }, `Delete failed: ${error.message}`), 'error');
    }
}
