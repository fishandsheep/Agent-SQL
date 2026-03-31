function inferTimelineIcon(step) {
        const toolId = normalizeToolId(step.toolName || step.stepName || step.stepType);
        if (toolId && toolIconMap[toolId]) {
            return toolIconMap[toolId];
        }
        if (step.stepType === 'phase') return 'route';
        if (step.stepType === 'candidate_plan') return 'alt_route';
        if (step.stepType === 'selection') return 'task_alt';
        if (step.stepType === 'plan_execution') return 'terminal';
        return 'build';
    }

function getTimelineDisplayName(step) {
        if (step.stepType === 'phase') {
            return getExecutionStepDisplayName(
                step.stepName,
                getToolDisplayName(step.toolName, t('stream.phase.default', {}, '\u6267\u884c\u9636\u6bb5'))
            );
        }
        if (step.stepType === 'candidate_plan') {
            return step.toolName
                ? t('stream.step.candidatePlanWithId', { id: step.toolName }, `\u5019\u9009\u65b9\u6848 ${step.toolName}`)
                : t('stream.step.candidatePlan', {}, '\u5019\u9009\u65b9\u6848');
        }
        if (step.stepType === 'tool_call') {
            if (step.stepName && step.stepName !== 'toolCall') {
                return getExecutionStepDisplayName(step.stepName, step.stepName);
            }
            return getToolDisplayName(step.toolName, t('stream.step.toolCall', {}, '\u5de5\u5177\u6267\u884c'));
        }
        if (step.stepType === 'selection') {
            return t('stream.step.selectBestPlan', {}, '\u6700\u4f18\u65b9\u6848\u9009\u62e9');
        }
        return getExecutionStepDisplayName(
            step.stepName,
            getToolDisplayName(step.toolName, step.stepType || t('stream.phase.step', {}, '\u6267\u884c\u6b65\u9aa4'))
        );
    }

function displayTimeline(data, container) {
        const timelineContainer = container.querySelector('[data-role="timelineContainer"]') || container.querySelector('#timelineContainer');
        const timelineCard = container.querySelector('[data-role="timelineCard"]') || container.querySelector('#timelineCard');
        const totalAgentTimeElement = container.querySelector('[data-role="totalAgentTime"]') || container.querySelector('#totalAgentTime');
        if (!timelineContainer || !timelineCard) return;

        const sourceFilter = (container.querySelector('[data-role="timelineSourceFilter"]') || container.querySelector('#timelineSourceFilter'))?.value || currentTimelineSource || 'all';
        currentTimelineSource = sourceFilter;

        const steps = getExecutionSteps(data).map(normalizeTraceStep).filter(step => {
            if (sourceFilter === 'all') return true;
            const source = step.sourceType || step.dataSource || 'system';
            return String(source).toLowerCase() === sourceFilter;
        });

        timelineContainer.innerHTML = '';

        if (!steps.length) {
            timelineCard.style.display = 'none';
            return;
        }

        timelineCard.style.display = 'block';
        const totalTime = steps.reduce((sum, step) => sum + (Number(step.executionTimeMs) || 0), 0);
        if (totalAgentTimeElement) {
            totalAgentTimeElement.textContent = totalTime > 0 ? `\u603b\u8ba1: ${formatExecutionTime(totalTime)}` : '\u603b\u8ba1: -';
        }

        steps.forEach(step => {
            const icon = inferTimelineIcon(step);
            const name = getTimelineDisplayName(step);
            const sourceType = step.sourceType || step.dataSource || 'system';
            const toolName = step.toolName || step.stepType || 'step';
            const details = buildStepDetailLines(step);

            const wrapper = document.createElement('div');
            wrapper.className = 'soft-card-inset p-4 mb-3';
            wrapper.innerHTML = `
                <div class="flex items-start gap-3">
                    <div class="soft-icon-container w-10 h-10 flex-shrink-0">
                        <span class="material-symbols-outlined" style="color: var(--primary-gradient-start);">${icon}</span>
                    </div>
                    <div class="flex-1 min-w-0">
                        <div class="flex items-start justify-between gap-3 flex-wrap mb-2">
                            <div>
                                <div class="text-sm font-bold" style="color: var(--text-primary);">${escapeHtml(name)}</div>
                                <div class="text-xs code-font" style="color: var(--text-tertiary);">${escapeHtml(toolName)}</div>
                            </div>
                            <div class="flex items-center gap-2 flex-wrap">
                                ${buildSourceBadge(sourceType)}
                                ${buildStatusBadge(step.status)}
                                ${step.executionTimeMs ? `<span class="text-xs" style="color: var(--text-tertiary);">${formatExecutionTime(step.executionTimeMs)}</span>` : ''}
                                ${step.timestamp ? `<span class="text-xs" style="color: var(--text-tertiary);">${new Date(step.timestamp).toLocaleString()}</span>` : ''}
                            </div>
                        </div>
                        ${details.length ? `
                            <div class="grid gap-2">
                                ${details.map(item => `<div class="text-xs leading-6" style="color: var(--text-secondary);">${escapeHtml(item)}</div>`).join('')}
                            </div>
                        ` : ''}
                    </div>
                </div>
            `;

            timelineContainer.appendChild(wrapper);
        });

        requestAnimationFrame(syncOpenCollapsibleHeights);
    }

function refreshCurrentTimeline(targetContainer = null) {
        const resultsContainer = targetContainer || document.getElementById('results');
        if (!resultsContainer) return;

        const payload = resultsContainer.id === 'results'
            ? (currentOptimizationResult?.execution
                ? { execution: currentOptimizationResult.execution }
                : currentOptimizationResult)
            : resultsContainer.__timelinePayload;

        if (!payload) return;
        displayTimeline(payload, resultsContainer);
    }
