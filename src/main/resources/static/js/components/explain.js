function notifySqlRewriteAdvisory(advisory) {
    if (!advisory || advisory.autoApplied) return;

    const messages = [];
    if (advisory.hasSelectStar) {
        messages.push(t('explain.advisory.selectStar', {}, 'SELECT * detected. Consider using an explicit column list before validation.'));
    }
    if (advisory.hasDateFunctionPredicate) {
        messages.push(t('explain.advisory.dateFunction', {}, 'A date function wraps the filter column. Consider rewriting it as a range predicate before validation.'));
    }
    if (advisory.hasImplicitTypeConversion) {
        messages.push(t('explain.advisory.implicitCast', {}, 'A possible implicit type conversion was detected. Consider comparing numeric columns with unquoted constants.'));
    }
    if (messages.length === 0 && Array.isArray(advisory.issues) && advisory.issues.length > 0) {
        messages.push(advisory.issues[0]);
    }

    messages.forEach(message => showToast(message, 'info'));
}

/**
 * 获取执行计划的所有行（包括第一行和额外行）
 * @param {Object} explain - 执行计划对象
 * @returns {Array} - 执行计划行数组
 */
function getExplainRows(explain) {
    if (!explain) return [];

    const rows = [explain];

    // 如果有额外行，解析并添加到数组中
    if (explain.additionalRows) {
        try {
            const additionalRows = JSON.parse(explain.additionalRows);
            if (Array.isArray(additionalRows)) {
                rows.push(...additionalRows);
            }
        } catch (e) {
            console.warn('解析 additionalRows 失败:', e);
        }
    }

    return rows;
}

function displayExplainTableForPlan(explain, targetContainerId) {
    const container = document.getElementById(targetContainerId);
    if (!container) return;

    const explainCard = container.querySelector('[data-role="explainCard"]') || container.querySelector('#explainCard');
    const explainTableBody = container.querySelector('[data-role="explainTableBody"]') || container.querySelector('#explainTableBody');

    if (!explainCard || !explainTableBody) return;

    explainTableBody.innerHTML = '';
    const fields = ['id', 'selectType', 'table', 'partitions', 'type', 'possibleKeys', 'key', 'keyLen', 'ref', 'rows', 'filtered', 'extra'];
    const row = document.createElement('tr');

    fields.forEach(field => {
        const td = document.createElement('td');
        const value = explain[field] || explain[camelToSnake(field)] || '-';
        td.textContent = value;

        if (field === 'type' && value !== 'ALL') {
            td.style.color = 'var(--success-color)';
            td.style.fontWeight = 'bold';
        } else if (field === 'type' && value === 'ALL') {
            td.style.color = 'var(--error-color)';
            td.style.fontWeight = 'bold';
        } else if (field === 'key' && value && value !== 'null') {
            td.style.color = 'var(--success-color)';
        }

        row.appendChild(td);
    });

    explainTableBody.appendChild(row);
    explainCard.style.display = 'block';
}

function displayExplainComparison(data, targetContainerId = 'results') {
    const container = document.getElementById(targetContainerId);
    const baselineExplain = getBaselineExplain(data);
    const bestPlan = data.plans && data.plans.length > 0
        ? data.plans.find(p => p.planId === data.bestPlanId)
        : null;
    const optimizedExplain = bestPlan?.explain;

    if (!baselineExplain || !optimizedExplain) {
        return;
    }

    let comparisonCard = container.querySelector('[data-role="explainComparisonCard"]') || container.querySelector('#explainComparisonCard');
    if (!comparisonCard) {
        comparisonCard = document.createElement('div');
        comparisonCard.setAttribute('data-role', 'explainComparisonCard');
        comparisonCard.className = 'soft-card p-6 mb-3';
        const explainCard = container.querySelector('[data-role="explainCard"]') || container.querySelector('#explainCard');
        if (explainCard?.parentNode) {
            explainCard.parentNode.insertBefore(comparisonCard, explainCard.nextSibling);
        } else {
            container.appendChild(comparisonCard);
        }
    }

    comparisonCard.style.display = 'block';

    // 判断是否为多表查询
    const baselineRows = getExplainRows(baselineExplain);
    const optimizedRows = getExplainRows(optimizedExplain);
    const isMultiTable = baselineRows.length > 1 || optimizedRows.length > 1;

    const fields = ['id', 'selectType', 'table', 'type', 'possibleKeys', 'key', 'keyLen', 'ref', 'rows', 'filtered', 'extra'];
    const fieldLabels = {
        id: 'ID',
        selectType: t('explain.field.selectType', {}, 'Select Type'),
        table: t('explain.field.table', {}, 'Table'),
        type: t('explain.field.type', {}, 'Access Type'),
        possibleKeys: t('explain.field.possibleKeys', {}, 'Possible Keys'),
        key: t('explain.field.key', {}, 'Chosen Key'),
        keyLen: t('explain.field.keyLen', {}, 'Key Length'),
        ref: t('explain.field.ref', {}, 'Ref'),
        rows: t('explain.field.rows', {}, 'Rows'),
        filtered: t('explain.field.filtered', {}, 'Filtered'),
        extra: t('explain.field.extra', {}, 'Extra')
    };

    let html = `
        <div class="flex items-center justify-between mb-4">
            <div class="flex items-center gap-3">
                <div class="soft-icon-container w-10 h-10">
                    <span class="material-symbols-outlined" style="color: var(--primary-gradient-start);">compare</span>
                </div>
                <span class="font-bold text-lg" style="color: var(--text-primary);">${t('explain.compareTitle', {}, 'Execution Plan Comparison')}</span>
            </div>
        </div>
        <div class="overflow-x-auto">
            <table class="w-full text-sm">
                <thead>
                    <tr class="border-b-2" style="border-color: var(--border-color); background: var(--background-color);">
                        <th class="py-3 px-4 text-left font-bold" style="color: var(--text-primary);">${t('explain.field', {}, 'Field')}</th>
                        <th class="py-3 px-4 text-left font-bold" style="color: var(--text-primary);">${t('explain.before', {}, 'Before')}</th>
                        <th class="py-3 px-4 text-left font-bold" style="color: var(--success-color);">${t('explain.after', {}, 'After')}</th>
                    </tr>
                </thead>
                <tbody>
    `;

    // 多表查询：显示所有行的执行计划
    if (isMultiTable) {
        // 找出最大行数
        const maxRows = Math.max(baselineRows.length, optimizedRows.length);

        for (let i = 0; i < maxRows; i++) {
            const baselineRow = baselineRows[i] || {};
            const optimizedRow = optimizedRows[i] || {};

            fields.forEach(field => {
                const baselineValue = baselineRow[field] || baselineRow[camelToSnake(field)] || '-';
                const optimizedValue = optimizedRow[field] || optimizedRow[camelToSnake(field)] || '-';

                let improved = false;
                let rowClass = '';
                if (field === 'type') {
                    improved = (baselineValue === 'ALL' && optimizedValue !== 'ALL')
                        || (baselineValue === 'index' && optimizedValue === 'ref');
                    rowClass = improved ? 'background: rgba(16, 185, 129, 0.1);' : '';
                } else if (field === 'rows') {
                    const baselineNum = parseInt(baselineValue, 10) || 0;
                    const optimizedNum = parseInt(optimizedValue, 10) || 0;
                    improved = optimizedNum < baselineNum;
                    rowClass = improved ? 'background: rgba(16, 185, 129, 0.1);' : '';
                } else if (field === 'key') {
                    improved = (baselineValue === 'null' || baselineValue === 'NULL' || baselineValue === '-')
                        && (optimizedValue !== 'null' && optimizedValue !== 'NULL' && optimizedValue !== '-');
                    rowClass = improved ? 'background: rgba(16, 185, 129, 0.1);' : '';
                }

                html += `
                    <tr class="border-b" style="border-color: var(--border-color); ${rowClass}">
                        <td class="py-2 px-4 font-medium" style="color: var(--text-primary);">${fieldLabels[field] || field}</td>
                        <td class="py-2 px-4 code-font" style="color: var(--text-secondary);">${baselineValue}</td>
                        <td class="py-2 px-4 code-font" style="color: ${improved ? 'var(--success-color)' : 'var(--text-secondary)'};">${optimizedValue}</td>
                    </tr>
                `;
            });

            // 在不同表之间添加分隔线
            if (i < maxRows - 1) {
                html += `<tr><td colspan="3" style="border-top: 2px solid var(--border-color);"></td></tr>`;
            }
        }
    } else {
        // 单表查询：保持原有逻辑
        fields.forEach(field => {
            const baselineValue = baselineExplain[field] || baselineExplain[camelToSnake(field)] || '-';
            const optimizedValue = optimizedExplain[field] || optimizedExplain[camelToSnake(field)] || '-';

            let improved = false;
            let rowClass = '';
            if (field === 'type') {
                improved = (baselineValue === 'ALL' && optimizedValue !== 'ALL')
                    || (baselineValue === 'index' && optimizedValue === 'ref');
                rowClass = improved ? 'background: rgba(16, 185, 129, 0.1);' : '';
            } else if (field === 'rows') {
                const baselineNum = parseInt(baselineValue, 10) || 0;
                const optimizedNum = parseInt(optimizedValue, 10) || 0;
                improved = optimizedNum < baselineNum;
                rowClass = improved ? 'background: rgba(16, 185, 129, 0.1);' : '';
            } else if (field === 'key') {
                improved = (baselineValue === 'null' || baselineValue === 'NULL' || baselineValue === '-')
                    && (optimizedValue !== 'null' && optimizedValue !== 'NULL' && optimizedValue !== '-');
                rowClass = improved ? 'background: rgba(16, 185, 129, 0.1);' : '';
            }

            html += `
                <tr class="border-b" style="border-color: var(--border-color); ${rowClass}">
                    <td class="py-2 px-4 font-medium" style="color: var(--text-primary);">${fieldLabels[field] || field}</td>
                    <td class="py-2 px-4 code-font" style="color: var(--text-secondary);">${baselineValue}</td>
                    <td class="py-2 px-4 code-font" style="color: ${improved ? 'var(--success-color)' : 'var(--text-secondary)'};">${optimizedValue}</td>
                </tr>
            `;
        });
    }

    html += `
                </tbody>
            </table>
        </div>
    `;

    comparisonCard.innerHTML = html;
}

// 导出函数供其他模块使用
window.displayExplainTableForPlan = displayExplainTableForPlan;
window.displayExplainComparison = displayExplainComparison;
window.getExplainRows = getExplainRows;
