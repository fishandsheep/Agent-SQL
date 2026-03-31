async function optimizeWorkload() {
    const sqlInput = document.getElementById('workloadSqlInput');
    const sqls = sqlInput.value.split('\n')
        .map(s => s.trim())
        .filter(s => s.length > 0);

    if (sqls.length === 0) {
        showToast(t('workload.emptyInput', {}, 'Please enter at least one SQL statement'), 'error');
        return;
    }

    if (sqls.length > 10) {
        showToast(t('workload.tooMany', {}, 'No more than 10 SQL statements are allowed'), 'error');
        return;
    }

    document.getElementById('workloadLoading').classList.remove('hidden');
    document.getElementById('workloadResults').classList.remove('show');

    try {
        const maxIndexes = parseInt(document.getElementById('maxIndexes').value, 10) || 5;

        for (let i = 0; i < sqls.length; i++) {
            const validationError = validateSingleSqlInput(sqls[i]);
            if (validationError) {
                showToast(t('workload.invalidItem', { index: i + 1, message: validationError }, `SQL #${i + 1} is invalid: ${validationError}`), 'error');
                return;
            }
        }

        const validationErrorFromServer = await validateSqlRequest(sqls, 10);
        if (validationErrorFromServer) {
            showToast(validationErrorFromServer, 'error');
            return;
        }

        const response = await fetch('/SQLAgent/api/analyze/workload', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                sqls,
                maxIndexes,
                allowCompositeIndex: true,
                analyzeSelectivity: true
            })
        });

        const data = await response.json();

        if (data.success) {
            displayWorkloadResults(data);
        } else {
            showToast(data.errorMessage || t('workload.failed', {}, 'Index experiment failed'), 'error');
        }
    } catch (error) {
        console.error('Error:', error);
        showToast(t('workload.requestFailed', { message: error.message }, `Experiment request failed: ${error.message}`), 'error');
    } finally {
        document.getElementById('workloadLoading').classList.add('hidden');
    }
}

function displayWorkloadResults(data) {
    document.getElementById('totalIndexesCount').textContent = data.recommendedIndexes?.length || 0;
    document.getElementById('coveredSqlCount').textContent = data.coveredSqlCount || 0;
    document.getElementById('coverageRate').textContent = (data.coverageRate || 0).toFixed(1) + '%';

    const container = document.getElementById('recommendedIndexesContainer');
    container.innerHTML = '';

    if (data.recommendedIndexes && data.recommendedIndexes.length > 0) {
        data.recommendedIndexes.forEach((index, i) => {
            const card = document.createElement('div');
            card.className = 'soft-card p-5 mb-3 workload-index-card';

            const coveredSqlsText = index.coveredSqls?.map(idx => `SQL[${idx}]`).join(', ') || '-';

            card.innerHTML = `
                <div class="flex items-start justify-between mb-3 workload-index-header">
                    <div class="flex items-center gap-2 flex-wrap">
                        <span class="soft-badge primary">${t('workload.candidate', { index: i + 1 }, `Candidate Index ${i + 1}`)}</span>
                        <span class="font-bold text-lg" style="color: var(--text-primary);">${index.indexName}</span>
                    </div>
                    <span class="soft-badge success">${t('workload.priority', { score: index.priorityScore }, `Experiment Priority ${index.priorityScore}`)}</span>
                </div>

                <div class="mb-3">
                    <div class="text-sm font-medium mb-1" style="color: var(--text-secondary);">${t('workload.table', {}, 'Table')}</div>
                    <div class="text-sm" style="color: var(--text-primary);">${index.tableName || '-'}</div>
                </div>

                <div class="mb-3">
                    <div class="text-sm font-medium mb-1" style="color: var(--text-secondary);">${t('workload.fields', {}, 'Columns')}</div>
                    <div class="text-sm code-font" style="color: var(--text-primary);">${index.columns?.join(', ') || '-'}</div>
                </div>

                <div class="mb-3">
                    <div class="text-sm font-medium mb-1" style="color: var(--text-secondary);">${t('workload.coveredSql', {}, 'Covered SQL')}</div>
                    <div class="text-sm" style="color: var(--text-primary);">${coveredSqlsText}</div>
                </div>

                <div class="mb-3">
                    <div class="text-sm font-medium mb-1" style="color: var(--text-secondary);">${t('workload.reason', {}, 'Reason')}</div>
                    <div class="text-sm" style="color: var(--text-primary);">${index.reason || '-'}</div>
                </div>

                <div class="soft-code-block p-4 code-font text-sm">${index.createSql || ''}</div>
            `;
            container.appendChild(card);
        });
    } else {
        container.innerHTML = `<div class="text-center py-8" style="color: var(--text-secondary);">${t('workload.emptyRecommendations', {}, 'No index recommendations were generated in this experiment')}</div>`;
    }

    const details = data.details || {};
    let detailsHtml = '<div class="grid grid-cols-1 sm:grid-cols-2 gap-4 workload-details-grid">';

    if (details.fieldCooccurrence) {
        detailsHtml += `<div><div class="text-sm font-medium mb-2" style="color: var(--text-secondary);">${t('workload.cooccurrence', {}, 'Field Co-occurrence')}</div>`;
        for (const [field, count] of Object.entries(details.fieldCooccurrence)) {
            detailsHtml += `<div class="text-sm mb-1" style="color: var(--text-primary);">${field}: ${count}</div>`;
        }
        detailsHtml += '</div>';
    }

    if (details.fieldSelectivity) {
        detailsHtml += `<div><div class="text-sm font-medium mb-2" style="color: var(--text-secondary);">${t('workload.selectivity', {}, 'Field Selectivity')}</div>`;
        for (const [field, selectivity] of Object.entries(details.fieldSelectivity)) {
            detailsHtml += `<div class="text-sm mb-1" style="color: var(--text-primary);">${field}: ${(selectivity * 100).toFixed(1)}%</div>`;
        }
        detailsHtml += '</div>';
    }

    detailsHtml += '</div>';
    document.getElementById('workloadDetails').innerHTML = detailsHtml;

    document.getElementById('workloadResults').classList.add('show');
}
