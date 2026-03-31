function getBestPlanFromResponse(data) {
        if (!data?.plans || !Array.isArray(data.plans)) return null;
        return data.plans.find(plan => plan.planId === data.bestPlanId) || data.plans[0] || null;
    }

function getSelectedReasonText(data, bestPlan) {
        return data?.planSelection?.selectedReason
            || data?.reason
            || data?.analysis?.bestPlanReasoning
            || bestPlan?.reasoning
            || t('plan.defaultSelectedReason', {}, 'The system selected this plan by combining execution plan evidence, latency, result correctness, and implementation complexity.');
    }

function getPlanTraitTags(plan) {
        const tags = [];
        if (!plan) return tags;
        if (plan.optimizedSql) tags.push(t('plan.tag.sqlRewrite', {}, 'SQL Rewrite'));
        if (plan.indexDDL) tags.push(t('plan.tag.indexOptimization', {}, 'Index Optimization'));
        tags.push(plan.valid ? t('plan.tag.valid', {}, 'Validated') : t('plan.tag.invalid', {}, 'Validation Failed'));
        if (plan.priority) tags.push(t('plan.tag.priority', { value: plan.priority }, `Priority P${plan.priority}`));
        if (plan.riskLevel) tags.push(t('plan.tag.risk', { value: plan.riskLevel }, `Risk ${plan.riskLevel}`));
        return tags;
    }

function buildPlanCardHtml(plan, data, selected = false, expanded = false) {
        if (!plan) return '';

        const tone = selected
            ? 'background: rgba(16, 185, 129, 0.08); border: 1px solid rgba(16, 185, 129, 0.24);'
            : 'background: rgba(102, 126, 234, 0.04); border: 1px solid rgba(163, 177, 198, 0.18);';
        const title = selected
            ? t('plan.title.selected', { id: plan.planId }, `Selected Plan ${plan.planId}`)
            : t('plan.title.other', { id: plan.planId }, `Comparison Plan ${plan.planId}`);
        const reasonTitle = selected ? t('plan.reason.selected', {}, 'Why Selected') : t('plan.reason.rejected', {}, 'Why Rejected');
        const reason = selected
            ? getSelectedReasonText(data, plan)
            : (plan.rejectionReason || plan.validationError || t('plan.rejectedDefault', {}, 'Its overall score was lower than the selected final plan.'));
        const explain = plan.explain || {};
        const tags = getPlanTraitTags(plan).map(tag => `<span class="soft-badge">${escapeHtml(tag)}</span>`).join('');

        return `
            <div class="soft-card-inset p-4 mb-3" style="${tone}">
                <div class="flex items-start justify-between gap-4 flex-wrap mb-3">
                    <div>
                        <div class="text-sm font-bold mb-1" style="color: var(--text-primary);">${escapeHtml(title)}</div>
                        <div class="text-xs" style="color: var(--text-secondary);">${escapeHtml(formatPlanType(plan.type || 'unknown'))}</div>
                    </div>
                    <div class="flex items-center gap-2 flex-wrap">
                        ${selected ? `<span class="soft-badge success">${t('plan.badge.selected', {}, 'Final Choice')}</span>` : `<span class="soft-badge">${t('plan.badge.rejected', {}, 'Not Selected')}</span>`}
                        ${tags}
                    </div>
                </div>
                <div class="grid md:grid-cols-3 gap-3 mb-3">
                    <div class="soft-card-inset p-3">
                        <div class="text-xs mb-1" style="color: var(--text-tertiary);">${t('plan.metric.executionTime', {}, 'Execution Time')}</div>
                        <div class="text-sm font-bold" style="color: var(--text-primary);">${plan.executionTime ? formatExecutionTime(plan.executionTime) : t('plan.metric.untested', {}, 'Not measured')}</div>
                    </div>
                    <div class="soft-card-inset p-3">
                        <div class="text-xs mb-1" style="color: var(--text-tertiary);">${t('plan.metric.rows', {}, 'Scanned Rows')}</div>
                        <div class="text-sm font-bold" style="color: var(--text-primary);">${explain.rows ? formatNumber(explain.rows) : '-'}</div>
                    </div>
                    <div class="soft-card-inset p-3">
                        <div class="text-xs mb-1" style="color: var(--text-tertiary);">${t('plan.metric.accessType', {}, 'Access Type')}</div>
                        <div class="text-sm font-bold" style="color: var(--text-primary);">${escapeHtml(explain.type || '-')}</div>
                    </div>
                </div>
                <div class="soft-card-inset p-3">
                    <div class="text-xs mb-1" style="color: var(--text-tertiary);">${reasonTitle}</div>
                    <div class="text-sm leading-6" style="color: var(--text-secondary);">${escapeHtml(reason)}</div>
                </div>
                ${expanded && plan.optimizedSql ? `
                    <div class="mt-3">
                        <div class="text-xs mb-2" style="color: var(--text-tertiary);">${t('plan.candidateSql', {}, 'Candidate SQL')}</div>
                        <pre class="soft-code-block p-3 text-xs code-font overflow-x-auto"><code style="color: #e2e8f0;">${escapeHtml(plan.optimizedSql)}</code></pre>
                    </div>
                ` : ''}
                ${expanded && plan.indexDDL ? `
                    <div class="mt-3">
                        <div class="text-xs mb-2" style="color: var(--text-tertiary);">${t('plan.candidateIndexDDL', {}, 'Candidate Index DDL')}</div>
                        <pre class="soft-code-block p-3 text-xs code-font overflow-x-auto"><code style="color: #e2e8f0;">${escapeHtml(plan.indexDDL)}</code></pre>
                    </div>
                ` : ''}
                ${expanded && plan.reasoning ? `
                    <div class="mt-3 soft-card-inset p-3">
                        <div class="text-xs mb-1" style="color: var(--text-tertiary);">${t('plan.reasoning', {}, 'Plan Notes')}</div>
                        <div class="text-sm leading-6" style="color: var(--text-secondary);">${escapeHtml(plan.reasoning)}</div>
                    </div>
                ` : ''}
            </div>
        `;
    }

function getPlanWorkbenchHint(plans) {
        if ((plans?.length || 0) >= 5) {
            return t('plan.hint.max', {}, 'Up to 5 candidate plans can be shown, and more are added only when the evidence is strong enough.');
        }
        return t('plan.hint.default', {}, 'Candidate plans are generated from actual evidence rather than padded with weak options.');
    }

function buildPlanDecisionMarkup(data, expanded = false) {
        const plans = Array.isArray(data?.plans) ? data.plans : [];
        const bestPlan = getBestPlanFromResponse(data);

        if (!plans.length) {
            return `<div class="text-sm" style="color: var(--text-tertiary);">${t('plan.empty', {}, 'No candidate plan details are available for this result.')}</div>`;
        }

        return `
            <div class="flex items-start justify-between gap-4 flex-wrap mb-4">
                <div>
                    <div class="font-bold text-lg" style="color: var(--text-primary);">${expanded ? t('plan.title.expanded', {}, 'Plan Decision Details') : t('plan.title.collapsed', {}, 'Plan Decision Workbench')}</div>
                    <div class="text-sm" style="color: var(--text-secondary);">${t('plan.subtitle', {}, 'Shows candidate plans, selection reasons, rejection reasons, and SQL/index details.')}</div>
                    <div class="text-xs mt-2" style="color: var(--text-tertiary);">${getPlanWorkbenchHint(plans)}</div>
                </div>
                <div class="flex items-center gap-2 flex-wrap">
                    <span class="soft-badge">${t('plan.candidateCount', { count: plans.length }, `Candidates ${plans.length}`)}</span>
                    <span class="soft-badge">${t('plan.validCount', { count: data?.planSelection?.validCandidateCount ?? plans.filter(plan => plan.valid).length }, `Valid ${data?.planSelection?.validCandidateCount ?? plans.filter(plan => plan.valid).length}`)}</span>
                    ${data?.bestPlanId ? `<span class="soft-badge success">${t('plan.selected', { id: escapeHtml(data.bestPlanId) }, `Selected ${escapeHtml(data.bestPlanId)}`)}</span>` : ''}
                </div>
            </div>
            ${bestPlan ? buildPlanCardHtml(bestPlan, data, true, expanded) : ''}
            <details class="plan-decision-disclosure mb-3" ${expanded ? 'open' : ''}>
                <summary>
                    <div>
                        <div class="text-sm font-bold" style="color: var(--text-primary);">${t('plan.viewAll', {}, 'View all plans')}</div>
                        <div class="text-xs mt-1" style="color: var(--text-secondary);">${t('plan.viewAllDesc', {}, 'Includes the selected plan and rejected plans, with the reason behind each decision.')}</div>
                    </div>
                    <div class="flex items-center gap-2 flex-wrap">
                        <span class="soft-badge">${t('plan.allCount', { count: plans.length }, `All ${plans.length}`)}</span>
                        <span class="material-symbols-outlined plan-decision-icon" style="color: var(--text-secondary);">expand_more</span>
                    </div>
                </summary>
                <div class="plan-decision-panel">
                    ${plans.map(plan => buildPlanCardHtml(plan, data, plan.planId === data.bestPlanId, true)).join('')}
                </div>
            </details>
            ${data?.riskAssessment ? `
                <div class="soft-card-inset p-4">
                    <div class="text-sm font-bold mb-2" style="color: var(--text-primary);">${t('plan.riskTitle', {}, 'Risks and rollout notes')}</div>
                    <div class="text-sm mb-2" style="color: var(--text-secondary);">${escapeHtml(data.riskAssessment.strategyNotes || data.analysis?.tuningNotes || t('plan.riskEmpty', {}, 'No extra notes were provided for this result.'))}</div>
                    ${Array.isArray(data.riskAssessment.cautions) && data.riskAssessment.cautions.length ? `
                        <div class="flex items-center gap-2 flex-wrap">
                            ${data.riskAssessment.cautions.map(item => `<span class="soft-badge">${escapeHtml(item)}</span>`).join('')}
                        </div>
                    ` : ''}
                </div>
            ` : ''}
        `;
    }

function renderPlanDecisionWorkbench(data, container, expanded = false) {
        let card = container.querySelector('[data-role="planWorkbenchCard"]') || container.querySelector('#planWorkbenchCard');

        let anchor = container.querySelector('[data-role="plan-decision-anchor"]');
        if (!anchor) {
            anchor = document.createElement('div');
            anchor.setAttribute('data-role', 'plan-decision-anchor');
            const optimizedSqlCard = container.querySelector('[data-role="optimizedSqlCard"]') || container.querySelector('#optimizedSqlCard');
            if (optimizedSqlCard && optimizedSqlCard.parentNode) {
                optimizedSqlCard.parentNode.insertBefore(anchor, optimizedSqlCard.nextSibling);
            } else {
                container.appendChild(anchor);
            }
        }

        if (!card) {
            card = document.createElement('div');
            card.setAttribute('data-role', 'planWorkbenchCard');
            card.className = 'soft-card p-6 mb-3';
            anchor.parentNode.insertBefore(card, anchor.nextSibling);
        } else if (card.previousElementSibling !== anchor && anchor.parentNode) {
            anchor.parentNode.insertBefore(card, anchor.nextSibling);
        }

        const legacyPanel = container.querySelector('#detailedAnalysisPanel');
        if (legacyPanel) {
            legacyPanel.remove();
        }

        card.dataset.expanded = expanded ? 'true' : 'false';
        card.innerHTML = buildPlanDecisionMarkup(data, expanded);
        card.style.display = (data?.plans?.length || 0) > 0 ? 'block' : 'none';
    }

function formatPlanType(type) {
        const typeMap = {
            'rewrite': t('plan.tag.sqlRewrite', {}, 'SQL Rewrite'),
            'index': t('plan.tag.indexOptimization', {}, 'Index Optimization'),
            'mixed': window.getCurrentLanguage?.() === 'en' ? 'Mixed Optimization' : '混合优化',
            'mixed_strategy': window.getCurrentLanguage?.() === 'en' ? 'Mixed Optimization' : '混合优化',
            'sql_rewrite': t('plan.tag.sqlRewrite', {}, 'SQL Rewrite'),
            'between_optimization': window.getCurrentLanguage?.() === 'en' ? 'Range Optimization' : '范围查询优化',
            'index_optimization': t('plan.tag.indexOptimization', {}, 'Index Optimization')
        };
        return typeMap[type] || type;
    }

window.getBestPlanFromResponse = getBestPlanFromResponse;
window.renderPlanDecisionWorkbench = renderPlanDecisionWorkbench;
window.formatPlanType = formatPlanType;
