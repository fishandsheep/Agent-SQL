const STREAM_PROGRESS_PHASES = [
        { key: 'request', label: '接收请求', weight: 8, hint: '系统正在建立执行上下文，并准备开始优化链路。' },
        { key: 'candidate_generation', label: '生成候选', weight: 24, hint: '先读取表结构、执行计划和统计信息，再生成候选方案。' },
        { key: 'baseline', label: '基线测量', weight: 16, hint: '先测原始 SQL，后面的提升值才有可比性。' },
        { key: 'candidate_execution', label: '验证方案', weight: 32, hint: '逐个执行候选方案，并校验结果是否一致。' },
        { key: 'evaluation', label: '选择最优', weight: 14, hint: '结合执行时间、执行计划和一致性结果选择最优方案。' },
        { key: 'finalize', label: '整理结果', weight: 6, hint: '正在整理最终 SQL、指标摘要和执行时间线。' }
    ];

const STREAM_PROGRESS_PROFILE_KEY = 'sql-agent-stream-progress-profile-v1';
const STREAM_PROGRESS_CONFIDENCE_TARGET = 8;
const STREAM_PROGRESS_STABLE_ALPHA = 0.2;
const STREAM_PROGRESS_DEFAULT_DURATIONS = {
        request: 1800,
        candidate_generation: 90000,
        baseline: 5000,
        candidate_execution: 45000,
        evaluation: 5000,
        finalize: 2500
    };

let streamProgressState = createInitialStreamProgressState();

function loadStreamProgressProfile() {
        try {
            const raw = window.localStorage?.getItem(STREAM_PROGRESS_PROFILE_KEY);
            if (!raw) return {};
            const parsed = JSON.parse(raw);
            return parsed && typeof parsed === 'object' ? parsed : {};
        } catch (error) {
            console.warn('load stream progress profile failed', error);
            return {};
        }
    }

function saveStreamProgressProfile(profile) {
        try {
            window.localStorage?.setItem(STREAM_PROGRESS_PROFILE_KEY, JSON.stringify(profile));
        } catch (error) {
            console.warn('save stream progress profile failed', error);
        }
    }

function createInitialStreamProgressState() {
        return {
            phases: Object.fromEntries(
                STREAM_PROGRESS_PHASES.map((phase, index) => [phase.key, {
                    progress: 0,
                    status: index === 0 ? 'active' : 'pending'
                }])
            ),
            title: '准备中...',
            hint: '等待系统建立执行链路',
            candidateCount: 0,
            completedPlanIds: new Set(),
            phaseStartedAt: {},
            learnedProfile: loadStreamProgressProfile()
        };
    }

function getProgressPhaseMeta(phaseKey) {
        return STREAM_PROGRESS_PHASES.find(phase => phase.key === phaseKey) || STREAM_PROGRESS_PHASES[0];
    }

function getProgressPhaseLabel(phaseKey) {
        return getProgressPhaseMeta(phaseKey).label;
    }

function getLearnedPhaseStats(phaseKey) {
        const profile = streamProgressState.learnedProfile?.[phaseKey];
        return {
            avgMs: Number(profile?.avgMs || 0),
            samples: Number(profile?.samples || 0)
        };
    }

function getLearnedPhaseConfidence(phaseKey) {
        const { samples } = getLearnedPhaseStats(phaseKey);
        if (samples <= 0) return 0;
        return Math.min(1, samples / STREAM_PROGRESS_CONFIDENCE_TARGET);
    }

function getBlendedPhaseDurationMs(phaseKey) {
        const defaultDuration = STREAM_PROGRESS_DEFAULT_DURATIONS[phaseKey] || 4000;
        const { avgMs } = getLearnedPhaseStats(phaseKey);
        const confidence = getLearnedPhaseConfidence(phaseKey);

        if (avgMs <= 0 || confidence <= 0) {
            return defaultDuration;
        }

        return Math.round(defaultDuration + ((avgMs - defaultDuration) * confidence));
    }

function getPhaseExpectedDurationMs(phaseKey) {
        return getBlendedPhaseDurationMs(phaseKey);
    }

function getEffectiveProgressWeight(phaseKey) {
        const baseWeight = getProgressPhaseMeta(phaseKey).weight;
        const blendedDuration = getBlendedPhaseDurationMs(phaseKey);
        const baselineDuration = STREAM_PROGRESS_DEFAULT_DURATIONS[phaseKey] || 4000;
        const relativeWeight = baseWeight * (blendedDuration / baselineDuration);
        return Math.max(4, Math.min(42, relativeWeight));
    }

function clampProgress(value) {
        return Math.min(1, Math.max(0, Number(value) || 0));
    }

function completeProgressPhasesBefore(phaseKey) {
        const targetIndex = STREAM_PROGRESS_PHASES.findIndex(phase => phase.key === phaseKey);
        if (targetIndex <= 0) return;

        STREAM_PROGRESS_PHASES.slice(0, targetIndex).forEach(phase => {
            const current = streamProgressState.phases[phase.key];
            current.progress = 1;
            current.status = 'completed';
        });
    }

function markProgressPhase(phaseKey, progress, status = 'active', completePrevious = false) {
        const phase = streamProgressState.phases[phaseKey];
        if (!phase) return;

        if (completePrevious) {
            completeProgressPhasesBefore(phaseKey);
        }

        if (status === 'completed') {
            phase.progress = 1;
            phase.status = 'completed';
            delete streamProgressState.phaseStartedAt[phaseKey];
            return;
        }

        if (phase.status !== 'completed') {
            phase.progress = Math.max(phase.progress, clampProgress(progress));
            phase.status = 'active';
            if (!streamProgressState.phaseStartedAt[phaseKey]) {
                streamProgressState.phaseStartedAt[phaseKey] = Date.now();
            }
        }
    }

function inferActiveProgressPhase() {
        const activeEntry = STREAM_PROGRESS_PHASES.find(phase => streamProgressState.phases[phase.key]?.status === 'active');
        if (activeEntry) {
            return activeEntry.key;
        }

        const pendingEntry = STREAM_PROGRESS_PHASES.find(phase => streamProgressState.phases[phase.key]?.status !== 'completed');
        return pendingEntry?.key || STREAM_PROGRESS_PHASES[STREAM_PROGRESS_PHASES.length - 1].key;
    }

function buildProgressHint(phaseKey) {
        const expected = getPhaseExpectedDurationMs(phaseKey);
        const confidence = getLearnedPhaseConfidence(phaseKey);
        const { samples } = getLearnedPhaseStats(phaseKey);
        const expectedText = expected > 0 ? `历史均值约 ${formatExecutionTime(expected)}` : '';
        const sampleText = samples > 0
            ? `已积累 ${samples} 次样本${confidence < 1 ? `，置信度 ${Math.round(confidence * 100)}%` : ''}`
            : '';
        if (phaseKey === 'candidate_execution') {
            const total = streamProgressState.candidateCount;
            const completed = streamProgressState.completedPlanIds.size;
            if (total > 0) {
                return `已完成 ${completed} / ${total} 个候选方案验证，进度会随真实验证结果推进${expectedText ? `，${expectedText}` : ''}${sampleText ? `，${sampleText}` : ''}。`;
            }
        }
        return `${getProgressPhaseMeta(phaseKey).hint}${expectedText ? ` ${expectedText}` : ''}${sampleText ? `，${sampleText}` : ''}。`;
    }

function calculateStreamProgressPercent() {
        const totalWeight = STREAM_PROGRESS_PHASES.reduce((sum, phase) => sum + getEffectiveProgressWeight(phase.key), 0);
        const weightedProgress = STREAM_PROGRESS_PHASES.reduce((sum, phase) => {
            const state = streamProgressState.phases[phase.key];
            return sum + getEffectiveProgressWeight(phase.key) * clampProgress(state?.progress);
        }, 0);

        return totalWeight > 0 ? (weightedProgress / totalWeight) * 100 : 0;
    }

function updateProgressPhasePills(activePhase) {
        const pills = document.querySelectorAll('[data-phase-key]');
        pills.forEach(pill => {
            const phaseKey = pill.getAttribute('data-phase-key');
            const phaseState = streamProgressState.phases[phaseKey];
            const isCompleted = phaseState?.status === 'completed';
            const isActive = !isCompleted && activePhase === phaseKey;

            pill.classList.toggle('completed', isCompleted);
            pill.classList.toggle('active', isActive);
            pill.classList.toggle('pending', !isCompleted && !isActive);
            pill.setAttribute('aria-current', isActive ? 'step' : 'false');
        });
    }

function renderStreamProgress(options = {}) {
        const activePhase = options.activePhase || inferActiveProgressPhase();
        const percent = options.percent ?? calculateStreamProgressPercent();
        const title = options.text || streamProgressState.title || getProgressPhaseLabel(activePhase);
        const hint = options.hint || streamProgressState.hint || buildProgressHint(activePhase);

        streamProgressState.title = title;
        streamProgressState.hint = hint;
        updateProgressBar(percent, title, { hint, activePhase });
    }

function resetStreamProgress(preserveExecutionState = false) {
        streamPhaseCards = new Map();
        streamActiveToolId = '';
        if (!preserveExecutionState) {
            currentOptimizationResult = { execution: [] };
            streamProgressState = createInitialStreamProgressState();
        }

        if (streamProgressPanel) {
            streamProgressPanel.innerHTML = '<div class="stream-shell" id="streamShell"></div>';
        }

        if (!preserveExecutionState) {
            updateProgressBar(0, streamProgressState.title, {
                hint: streamProgressState.hint,
                activePhase: 'request'
            });
        }
    }

    /**
     * 更新进度条
     * @param {number} percent - 进度百分比 (0-100)
     * @param {string} text - 进度文本
     * @param {{hint?: string, activePhase?: string}} options - 进度附加信息
     */
    function updateProgressBar(percent, text, options = {}) {
        const progressBar = document.getElementById('progressBar');
        const progressText = document.getElementById('progressText');
        const progressPercent = document.getElementById('progressPercent');
        const progressHint = document.getElementById('progressHint');
        const normalizedPercent = Math.min(100, Math.max(0, percent));

        if (progressBar) {
            progressBar.style.width = `${normalizedPercent}%`;
        }
        if (progressText) {
            progressText.textContent = text || '处理中...';
        }
        if (progressPercent) {
            progressPercent.textContent = `${Math.round(normalizedPercent)}%`;
        }
        if (progressHint) {
            progressHint.textContent = options.hint || '正在同步执行进度...';
        }

        updateProgressPhasePills(options.activePhase || inferActiveProgressPhase());
    }

function ensureStreamShell() {
        if (!streamProgressPanel) return null;

        let shell = streamProgressPanel.querySelector('#streamShell');
        if (!shell) {
            streamProgressPanel.innerHTML = '<div class="stream-shell" id="streamShell"></div>';
            shell = streamProgressPanel.querySelector('#streamShell');
        }
        return shell;
    }

function normalizeExecutionStatus(status) {
        const normalized = String(status || 'RUNNING').toUpperCase();
        const aliases = {
            EXECUTING: 'RUNNING',
            COMPLETED: 'SUCCESS',
            COMPLETE: 'SUCCESS'
        };
        return aliases[normalized] || normalized;
    }

function getExecutionStatusText(status) {
        const normalized = normalizeExecutionStatus(status);
        const labelMap = {
            SUCCESS: t('stream.status.success', {}, 'Success'),
            RUNNING: t('stream.status.running', {}, 'Running'),
            FAILED: t('stream.status.failed', {}, 'Failed'),
            VALIDATION_ERROR: t('stream.status.validation', {}, 'Validation Failed'),
            PENDING: t('stream.status.pending', {}, 'Pending'),
            UNKNOWN: t('stream.status.unknown', {}, 'Unknown')
        };
        return labelMap[normalized] || normalized;
    }

function buildStatusBadge(status) {
        const normalized = normalizeExecutionStatus(status);
        const toneMap = {
            SUCCESS: 'background: rgba(16, 185, 129, 0.16); color: var(--success-color);',
            RUNNING: 'background: rgba(102, 126, 234, 0.14); color: var(--primary-gradient-start);',
            VALIDATION_ERROR: 'background: rgba(237, 137, 54, 0.18); color: var(--warning-color);',
            FAILED: 'background: rgba(239, 68, 68, 0.14); color: var(--error-color);',
            PENDING: 'background: rgba(148, 163, 184, 0.18); color: var(--text-secondary);',
            UNKNOWN: 'background: rgba(148, 163, 184, 0.18); color: var(--text-secondary);'
        };

        return `<span class="soft-badge" style="${toneMap[normalized] || toneMap.UNKNOWN}">${getExecutionStatusText(normalized)}</span>`;
    }

function buildSourceBadge(sourceType) {
        const source = String(sourceType || 'system').toLowerCase();
        if (source === 'system' || source === 'tool') {
            return '';
        }
        const labelMap = {
            agent: t('stream.source.agent', {}, 'Analysis'),
            inferred: t('stream.source.inferred', {}, 'Decision')
        };
        return `<span class="soft-badge">${labelMap[source] || source}</span>`;
    }

function normalizeTraceStep(step) {
        return {
            ...step,
            sourceType: step?.sourceType || step?.dataSource || 'system',
            status: normalizeExecutionStatus(step?.status),
            stepType: step?.stepType || 'trace_step',
            stepName: step?.stepName || '',
            toolName: step?.toolName || '',
            description: step?.description || '',
            details: step?.details || '',
            inputSummary: step?.inputSummary || '',
            outputSummary: step?.outputSummary || '',
            executionTimeMs: Number(step?.executionTimeMs) || 0,
            timestamp: step?.timestamp || step?.endTime || step?.startTime || Date.now()
        };
    }

function looksLikeSqlSnippet(value) {
        const text = String(value || '').trim().toLowerCase();
        return text.length > 40 && /(select|update|delete|insert|replace|with)\s/.test(text);
    }

function parseSummaryTokens(value) {
        return String(value || '')
            .split(/\s*[|;,]\s*/)
            .map(item => item.trim())
            .filter(Boolean)
            .map(item => {
                const index = item.indexOf('=');
                if (index === -1) {
                    return { key: '', value: item };
                }
                return {
                    key: item.substring(0, index).trim(),
                    value: item.substring(index + 1).trim()
                };
            });
    }

function extractSummaryValue(step, keys) {
        const lookup = new Set([].concat(keys || []).map(key => String(key || '').toLowerCase()));
        const sources = [step?.inputSummary, step?.outputSummary, step?.details];

        for (const source of sources) {
            const tokens = parseSummaryTokens(source);
            for (const token of tokens) {
                if (lookup.has(String(token.key || '').toLowerCase())) {
                    return token.value;
                }
            }
        }

        return '';
    }

function toPositiveInteger(value) {
        const parsed = Number.parseInt(value, 10);
        return Number.isFinite(parsed) && parsed > 0 ? parsed : 0;
    }

function syncStreamProgressMetrics(step) {
        const candidateCount = toPositiveInteger(extractSummaryValue(step, ['candidateCount']));
        if (candidateCount > 0) {
            streamProgressState.candidateCount = Math.max(streamProgressState.candidateCount, candidateCount);
        }

        if (step.stepType === 'plan_execution' && step.toolName && step.toolName !== 'BASELINE' && step.status === 'SUCCESS') {
            streamProgressState.completedPlanIds.add(step.toolName);
        }
    }

function syncStreamProgressMetricsFromResult(payload) {
        const candidateCount = Number(payload?.planSelection?.candidateCount || payload?.candidateCount || 0);
        if (candidateCount > 0) {
            streamProgressState.candidateCount = Math.max(streamProgressState.candidateCount, candidateCount);
        }
    }

function buildPhaseDurationSample(executionData) {
        const sample = {};
        const steps = getExecutionSteps(executionData).map(normalizeTraceStep);

        steps.forEach(step => {
            const phaseKey = resolveProgressPhaseKey(step);
            const duration = Number(step.executionTimeMs || 0);
            if (!phaseKey || duration <= 0) return;

            if (phaseKey === 'candidate_execution') {
                if (step.stepType !== 'plan_execution' || step.toolName === 'BASELINE') {
                    return;
                }
            } else if (step.stepType !== 'phase') {
                return;
            }

            sample[phaseKey] = (sample[phaseKey] || 0) + duration;
        });

        return sample;
    }

function persistStreamProgressProfile(executionData) {
        const sample = buildPhaseDurationSample(executionData);
        const nextProfile = { ...streamProgressState.learnedProfile };

        Object.entries(sample).forEach(([phaseKey, duration]) => {
            if (!duration || duration <= 0) return;
            const previous = nextProfile[phaseKey] || { avgMs: 0, samples: 0 };
            const previousSamples = Number(previous.samples || 0);
            const previousAvg = Number(previous.avgMs || 0);
            const nextSamples = Math.min(previousSamples + 1, 50);
            const alpha = previousSamples < STREAM_PROGRESS_CONFIDENCE_TARGET
                ? 1 / (previousSamples + 1)
                : STREAM_PROGRESS_STABLE_ALPHA;
            const nextAvg = previousSamples > 0
                ? Math.round(previousAvg + ((duration - previousAvg) * alpha))
                : Math.round(duration);

            nextProfile[phaseKey] = {
                avgMs: nextAvg,
                samples: nextSamples
            };
        });

        streamProgressState.learnedProfile = nextProfile;
        saveStreamProgressProfile(nextProfile);
    }

function estimateActivePhaseProgress(phaseKey) {
        const startedAt = streamProgressState.phaseStartedAt[phaseKey];
        if (!startedAt) return 0;

        const elapsed = Date.now() - startedAt;
        const expected = getPhaseExpectedDurationMs(phaseKey);
        if (expected <= 0) return 0;

        return Math.min(0.92, Math.max(0, elapsed / expected));
    }

function tickPredictedProgress() {
        const activePhase = inferActiveProgressPhase();
        const phaseState = streamProgressState.phases[activePhase];
        if (!phaseState || phaseState.status === 'completed') return;

        const predicted = estimateActivePhaseProgress(activePhase);
        if (activePhase === 'candidate_execution') {
            phaseState.progress = Math.max(phaseState.progress, getCandidateExecutionProgress(), predicted);
        } else {
            phaseState.progress = Math.max(phaseState.progress, predicted);
        }

        renderStreamProgress({
            text: streamProgressState.title || getProgressPhaseLabel(activePhase),
            hint: buildProgressHint(activePhase),
            activePhase
        });
    }

function getCandidateExecutionProgress() {
        const total = streamProgressState.candidateCount;
        const completed = streamProgressState.completedPlanIds.size;
        if (total > 0) {
            return Math.min(1, Math.max(0.18, completed / total));
        }
        return completed > 0 ? 0.5 : 0.18;
    }

function resolveProgressPhaseKey(step) {
        const stepName = String(step.stepName || '');
        const toolName = String(step.toolName || '');

        if (toolName === 'request_received' || stepName.includes('接收优化请求')) {
            return 'request';
        }
        if (toolName === 'plan_generation' || stepName.includes('生成候选方案') || step.stepType === 'candidate_plan') {
            return 'candidate_generation';
        }
        if (toolName === 'baseline' || toolName === 'BASELINE' || stepName.includes('基线') || (step.stepType === 'plan_execution' && toolName === 'BASELINE')) {
            return 'baseline';
        }
        if (step.stepType === 'plan_execution' && toolName !== 'BASELINE') {
            return 'candidate_execution';
        }
        if (toolName.startsWith('candidate_execution_') || stepName.includes('执行候选方案')) {
            return 'candidate_execution';
        }
        if (toolName === 'evaluation' || toolName === 'selectBestPlan' || step.stepType === 'selection' || stepName.includes('评估并选择最优方案')) {
            return 'evaluation';
        }
        if (toolName === 'sandbox_cleanup' || stepName.includes('清理临时沙箱')) {
            return 'finalize';
        }
        return '';
    }

function updateStreamProgressFromStep(step, eventName) {
        syncStreamProgressMetrics(step);

        const phaseKey = resolveProgressPhaseKey(step);
        const status = normalizeExecutionStatus(step.status);

        if (!phaseKey) {
            renderStreamProgress();
            return;
        }

        if (phaseKey === 'request') {
            markProgressPhase('request', status === 'SUCCESS' ? 1 : 0.35, status === 'SUCCESS' ? 'completed' : 'active');
            renderStreamProgress({
                text: getProgressPhaseLabel('request'),
                hint: status === 'SUCCESS' ? '请求已进入优化链路，接下来开始生成候选方案。' : buildProgressHint('request'),
                activePhase: status === 'SUCCESS' ? 'candidate_generation' : 'request'
            });
            return;
        }

        if (phaseKey === 'candidate_generation') {
            markProgressPhase('candidate_generation', status === 'SUCCESS' ? 1 : 0.42, status === 'SUCCESS' ? 'completed' : 'active', true);
            renderStreamProgress({
                text: getProgressPhaseLabel('candidate_generation'),
                hint: status === 'SUCCESS'
                    ? `候选方案已生成，准备进入基线测量${streamProgressState.candidateCount > 0 ? `，共 ${streamProgressState.candidateCount} 个候选` : ''}。`
                    : buildProgressHint('candidate_generation'),
                activePhase: status === 'SUCCESS' ? 'baseline' : 'candidate_generation'
            });
            return;
        }

        if (phaseKey === 'baseline') {
            markProgressPhase('baseline', status === 'SUCCESS' ? 1 : 0.38, status === 'SUCCESS' ? 'completed' : 'active', true);
            renderStreamProgress({
                text: getProgressPhaseLabel('baseline'),
                hint: status === 'SUCCESS' ? '原始 SQL 基线已测量完成，接下来逐个验证候选方案。' : buildProgressHint('baseline'),
                activePhase: status === 'SUCCESS' ? 'candidate_execution' : 'baseline'
            });
            return;
        }

        if (phaseKey === 'candidate_execution') {
            if (status === 'SUCCESS' && step.stepType === 'phase' && step.stepName.includes('执行候选方案')) {
                markProgressPhase('candidate_execution', 1, 'completed', true);
                renderStreamProgress({
                    text: getProgressPhaseLabel('candidate_execution'),
                    hint: `候选方案验证完成${streamProgressState.candidateCount > 0 ? `，共验证 ${streamProgressState.candidateCount} 个候选` : ''}。`,
                    activePhase: 'evaluation'
                });
                return;
            }

            markProgressPhase('candidate_execution', getCandidateExecutionProgress(), 'active', true);
            renderStreamProgress({
                text: getProgressPhaseLabel('candidate_execution'),
                hint: buildProgressHint('candidate_execution'),
                activePhase: 'candidate_execution'
            });
            return;
        }

        if (phaseKey === 'evaluation') {
            markProgressPhase('evaluation', status === 'SUCCESS' ? 1 : 0.45, status === 'SUCCESS' ? 'completed' : 'active', true);
            renderStreamProgress({
                text: getProgressPhaseLabel('evaluation'),
                hint: status === 'SUCCESS' ? '最优方案已确定，正在整理最终结果。' : buildProgressHint('evaluation'),
                activePhase: status === 'SUCCESS' ? 'finalize' : 'evaluation'
            });
            return;
        }

        if (phaseKey === 'finalize') {
            markProgressPhase('finalize', status === 'SUCCESS' ? 1 : 0.72, status === 'SUCCESS' ? 'completed' : 'active', true);
            renderStreamProgress({
                text: getProgressPhaseLabel('finalize'),
                hint: status === 'SUCCESS' ? '临时资源已清理完成，准备展示最终结果。' : buildProgressHint('finalize'),
                activePhase: 'finalize'
            });
        }
    }

function formatDurationText(value) {
        if (!value) return '';
        return /ms$/i.test(String(value)) ? String(value) : `${value}ms`;
    }

function formatTraceToken(key, value, step) {
        if (!value || value === '-' || value === 'null') return '';
        const normalizedKey = String(key || '').toLowerCase();
        switch (normalizedKey) {
            case 'candidatecount':
                return t('stream.token.candidateCount', { value }, `Candidate plans: ${value}`);
            case 'resultcount':
                return t('stream.token.resultCount', { value }, `Completed results: ${value}`);
            case 'scores':
                return t('stream.token.scores', { value }, `Plans scored: ${value}`);
            case 'type':
                return step.toolName === 'explainPlan'
                    ? t('stream.token.accessType', { value }, `Access type: ${value}`)
                    : t('stream.token.planType', { value: formatPlanType(value) }, `Plan type: ${formatPlanType(value)}`);
            case 'planid':
                return value === 'BASELINE'
                    ? t('stream.token.baseline', {}, 'Target: baseline SQL')
                    : t('stream.token.plan', { value }, `Target: candidate plan ${value}`);
            case 'bestplanid':
                return t('stream.token.bestPlan', { value }, `Selected plan: ${value}`);
            case 'time':
                return t('stream.token.time', { value: formatDurationText(value) }, `Time: ${formatDurationText(value)}`);
            case 'rows':
                return step.toolName === 'compareResult'
                    ? t('stream.token.compareRows', { value }, `Compared rows: ${value}`)
                    : t('stream.token.rows', { value }, `Scanned rows: ${value}`);
            case 'key':
                return t('stream.token.key', { value }, `Index hit: ${value}`);
            case 'status':
                return t('stream.token.status', { value: getExecutionStatusText(value) }, `Status: ${getExecutionStatusText(value)}`);
            case 'consistent':
            case 'resultequal':
                return value === 'true'
                    ? t('stream.token.resultEqualTrue', {}, 'Result validation passed')
                    : t('stream.token.resultEqualFalse', {}, 'Result validation failed');
            case 'createdindex':
                return t('stream.token.createdIndex', { value }, `Temporary index created: ${value}`);
            case 'deletedindexes':
                return t('stream.token.deletedIndexes', { value }, `Temporary indexes cleaned: ${value}`);
            case 'index':
                return t('stream.token.index', { value }, `Temporary index: ${value}`);
            case 'deleted':
                return value === 'deleted' ? t('stream.token.deleted', {}, 'Temporary index deleted') : '';
            case 'validationerror':
                return t('stream.token.validationError', { value }, `Validation note: ${value}`);
            case 'error':
                return t('stream.token.error', { value }, `Error note: ${value}`);
            case 'success':
                return value === 'true'
                    ? t('stream.token.successTrue', {}, 'Execution succeeded')
                    : t('stream.token.successFalse', {}, 'Execution failed');
            case 'mode':
            case 'sessionid':
            case 'sandboxid':
                return '';
            default:
                return normalizedKey ? '' : value;
        }
    }

function formatTraceSummary(value, step) {
        const raw = String(value || '').trim();
        if (!raw || raw === '[]' || raw.toLowerCase() === 'logged' || looksLikeSqlSnippet(raw)) {
            return [];
        }

        if (raw.startsWith('baseline, planId=')) {
            return [t('stream.token.baseline', {}, 'Target: baseline SQL')];
        }
        if (raw.startsWith('candidate, planId=')) {
            return [t('stream.token.plan', { value: raw.split('planId=')[1] || '' }, `Target: candidate plan ${raw.split('planId=')[1] || ''}`).trim()];
        }

        const lines = parseSummaryTokens(raw)
            .map(item => formatTraceToken(item.key, item.value, step))
            .filter(Boolean);

        return Array.from(new Set(lines));
    }

function isGenericStepDescription(step) {
        const description = String(step.description || '').trim();
        const toolName = step.toolName || '';
        if (!description) return true;
        return description === step.stepName
            || description === `鐠嬪啰鏁ゅ銉ュ徔 ${toolName}`
            || description === `鐠嬪啰鏁ゅ銉ュ徔: ${toolName}`
            || description === `瀹搞儱鍙跨拫鍐暏鐞氼偅瀚嗙紒?${toolName}`
            || description === `瀹搞儱鍙跨拫鍐暏鐞氼偅瀚嗙紒? ${toolName}`;
    }

function buildStepDetailLines(step) {
        const lines = [];
        if (!isGenericStepDescription(step)) {
            lines.push(step.description);
        }
        lines.push(...formatTraceSummary(step.inputSummary, step));
        lines.push(...formatTraceSummary(step.outputSummary, step));
        lines.push(...formatTraceSummary(step.details, step));
        return Array.from(new Set(lines.filter(Boolean)));
    }

function buildTraceStepFromEnvelope(envelope) {
        const payload = envelope.payload || {};
        return {
            stepNumber: payload.stepNumber || envelope.eventId || Date.now(),
            toolName: payload.toolName || '',
            stepName: payload.stepName || payload.toolName || envelope.stepType || envelope.eventType,
            stepType: envelope.stepType || payload.stepType || 'trace_step',
            sourceType: envelope.sourceType || payload.sourceType || 'system',
            status: normalizeExecutionStatus(payload.status || (envelope.eventType === 'phase_update' ? 'RUNNING' : 'SUCCESS')),
            executionTimeMs: payload.durationMs ?? payload.executionTimeMs ?? 0,
            description: payload.description || payload.message || '',
            details: payload.details || '',
            inputSummary: payload.inputSummary || '',
            outputSummary: payload.outputSummary || payload.message || '',
            timestamp: payload.timestamp || envelope.ts || Date.now(),
            startTime: payload.startTime || payload.timestamp || envelope.ts || Date.now(),
            endTime: payload.endTime || payload.timestamp || envelope.ts || Date.now()
        };
    }

function getStreamPhaseKey(step) {
        if (step.stepType === 'phase') {
            return step.toolName || step.stepName || 'phase';
        }
        if (step.stepType === 'candidate_plan') {
            return 'candidate_plan_group';
        }
        if (step.stepType === 'tool_call') {
            return 'tool_call_group';
        }
        if (step.stepType === 'selection') {
            return 'selection_group';
        }
        if (step.stepType === 'plan_execution') {
            return step.toolName === 'BASELINE' ? 'baseline_group' : `plan_${step.toolName || 'execution'}`;
        }
        return `${step.sourceType || 'system'}_${step.stepType || 'trace'}`;
    }

function getStreamPhaseTitle(step) {
        if (step.stepType === 'phase') {
            return getExecutionStepDisplayName(
                step.stepName,
                getToolDisplayName(step.toolName, t('stream.phase.default', {}, 'Execution Phase'))
            );
        }
        if (step.stepType === 'candidate_plan') {
            return t('stream.phase.candidate', {}, 'Candidate Plan Generation');
        }
        if (step.stepType === 'tool_call') {
            return t('stream.phase.tool', {}, 'Tool Execution');
        }
        if (step.stepType === 'selection') {
            return t('stream.phase.selection', {}, 'Plan Scoring and Selection');
        }
        if (step.stepType === 'plan_execution') {
            return step.toolName === 'BASELINE'
                ? t('stream.phase.baseline', {}, 'Baseline Measurement')
                : t('stream.phase.plan', { name: step.toolName || '' }, `Candidate Plan ${step.toolName || ''}`).trim();
        }
        return getExecutionStepDisplayName(
            step.stepName,
            getToolDisplayName(step.toolName, t('stream.phase.step', {}, 'Execution Step'))
        );
    }

function ensureStreamPhaseCard(step) {
        const shell = ensureStreamShell();
        if (!shell) return null;

        const key = getStreamPhaseKey(step);
        if (streamPhaseCards.has(key)) {
            return streamPhaseCards.get(key);
        }

        const detailLines = buildStepDetailLines(step);
        const initialDescription = detailLines[0]
            || step.description
            || (step.stepType === 'tool_call'
                ? getToolDisplayName(step.toolName, t('stream.phase.tool', {}, 'Tool Execution'))
                : getStreamPhaseTitle(step));

        const card = document.createElement('div');
        card.className = 'stream-phase-card';
        card.dataset.phaseKey = key;
        card.innerHTML = `
            <div class="stream-phase-meta">
                <div class="stream-phase-title">${getStreamPhaseTitle(step)}</div>
                <div class="flex items-center gap-2 flex-wrap" data-role="badges"></div>
            </div>
            <div class="stream-phase-desc" data-role="description">${escapeHtml(initialDescription)}</div>
            <div class="grid gap-3 mt-3" data-role="steps"></div>
        `;

        shell.appendChild(card);
        const refs = {
            root: card,
            badges: card.querySelector('[data-role="badges"]'),
            description: card.querySelector('[data-role="description"]'),
            steps: card.querySelector('[data-role="steps"]')
        };
        streamPhaseCards.set(key, refs);
        return refs;
    }

function updateStreamPhaseCard(step) {
        const card = ensureStreamPhaseCard(step);
        if (!card) return;

        card.root.querySelector('.stream-phase-title').textContent = getStreamPhaseTitle(step);
        card.badges.innerHTML = `${buildSourceBadge(step.sourceType)}${buildStatusBadge(step.status)}`;

        const detailLines = buildStepDetailLines(step);
        card.description.textContent = detailLines[0] || t('stream.phase.created', {}, 'Phase created, waiting for more context...');
    }

function buildStreamStepBodyMarkup(step, detailLines) {
        if (step.stepType === 'tool_call') {
            const summaryLine = detailLines[0]
                || getToolDisplayName(step.toolName, t('stream.step.toolCall', {}, '工具执行'));
            const detailBody = detailLines.slice(1);

            if (detailBody.length === 0) {
                return `<div class="stream-step-summary">${escapeHtml(summaryLine)}</div>`;
            }

            return `
                <div class="stream-step-summary">${escapeHtml(summaryLine)}</div>
                <details class="stream-step-disclosure">
                    <summary>${escapeHtml(getToolDisplayName(step.toolName, t('stream.step.toolCall', {}, '工具执行')))}</summary>
                    <div class="stream-step-disclosure-body">
                        ${detailBody.map(line => `<div>${escapeHtml(line)}</div>`).join('')}
                    </div>
                </details>
            `;
        }

        return detailLines.map(line => `<div>${escapeHtml(line)}</div>`).join('');
    }

function appendStreamStep(step) {
        const card = ensureStreamPhaseCard(step);
        if (!card) return;

        const stepElement = document.createElement('div');
        const status = normalizeExecutionStatus(step.status);
        const detailLines = buildStepDetailLines(step);
        stepElement.className = `stream-step${status === 'RUNNING' ? ' active' : ''}${status === 'FAILED' ? ' failed' : ''}${status === 'VALIDATION_ERROR' ? ' validation' : ''}`;
        stepElement.innerHTML = `
            <div class="stream-step-header">
                <div class="stream-step-name">${getTimelineDisplayName(step)}</div>
                <div class="stream-step-meta">
                    ${buildSourceBadge(step.sourceType)}
                    ${buildStatusBadge(step.status)}
                    ${step.executionTimeMs ? `<span>${formatExecutionTime(step.executionTimeMs)}</span>` : ''}
                    ${step.timestamp ? `<span>${new Date(step.timestamp).toLocaleTimeString()}</span>` : ''}
                </div>
            </div>
            <div class="stream-step-body">
                ${buildStreamStepBodyMarkup(step, detailLines)}
            </div>
        `;

        card.steps.appendChild(stepElement);
        card.description.textContent = detailLines[0] || card.description.textContent;
        streamProgressPanel.scrollTop = streamProgressPanel.scrollHeight;
    }

function renderStreamTrace(executionData) {
        resetStreamProgress(true);
        const steps = getExecutionSteps(executionData).map(normalizeTraceStep);
        steps.forEach(step => {
            if (step.stepType === 'phase') {
                updateStreamPhaseCard(step);
            } else {
                appendStreamStep(step);
            }
        });
        syncToolHighlights(steps);
    }

async function optimizeWithStream(requestData) {
        resetStreamProgress();

        const response = await fetch('/SQLAgent/api/optimize/stream', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(requestData)
        });
        if (!response.ok || !response.body) {
            throw new Error(`HTTP error! status: ${response.status}`);
        }

        markProgressPhase('request', 0.12, 'active');
        renderStreamProgress({
            text: '连接到服务器...',
            hint: '流式响应已建立，等待服务端返回阶段事件。',
            activePhase: 'request',
            percent: Math.max(3, calculateStreamProgressPercent())
        });

        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';
        let eventName = '';

        while (true) {
            const { done, value } = await reader.read();
            if (done) break;
            buffer += decoder.decode(value, { stream: true });
            const lines = buffer.split('\n');
            buffer = lines.pop() || '';

            for (const line of lines) {
                if (line.startsWith('event:')) {
                    eventName = line.substring(6).trim();
                } else if (line.startsWith('data:')) {
                    const dataLine = line.substring(5).trim();
                    handleStreamEvent(eventName, dataLine);
                }
            }
        }
    }

function handleStreamEvent(eventName, rawData) {
        if (!rawData) return;

        try {
            const envelope = JSON.parse(rawData);
            const payload = envelope.payload || {};
            currentSessionId = envelope.sessionId || currentSessionId;

            if (eventName === 'heartbeat') {
                tickPredictedProgress();
                return;
            }

            // 处理错误事件
            if (eventName === 'error') {
                const errorMessage = payload.error || envelope.error || '未知错误';
                console.error('优化过程出错:', errorMessage);

                // 检查是否是字段不存在错误
                if (errorMessage.includes('字段验证失败') || errorMessage.includes('不存在')) {
                    // 格式化错误消息，使其更友好
                    const formattedError = formatFieldError(errorMessage);
                    renderStreamProgress({
                        text: '验证失败',
                        hint: '优化链路已中断，请根据错误信息修正 SQL 或表结构假设。',
                        activePhase: inferActiveProgressPhase(),
                        percent: 100
                    });
                    showLoading(false);
                    showError(formattedError);
                    showToast(formattedError, 'error');
                } else {
                    renderStreamProgress({
                        text: '执行失败',
                        hint: '执行链路提前结束，请查看错误信息定位问题。',
                        activePhase: inferActiveProgressPhase(),
                        percent: 100
                    });
                    showLoading(false);
                    showError(errorMessage);
                    showToast(errorMessage, 'error');
                }
                return;
            }

            if (eventName === 'phase_update') {
                const phaseStep = buildTraceStepFromEnvelope(envelope);
                currentOptimizationResult.execution = [...(currentOptimizationResult.execution || []), phaseStep];
                updateStreamProgressFromStep(normalizeTraceStep(phaseStep), eventName);
                updateStreamPhaseCard(phaseStep);
                return;
            }

            if (eventName === 'trace_step') {
                const step = buildTraceStepFromEnvelope(envelope);
                currentOptimizationResult.execution = [...(currentOptimizationResult.execution || []), step];
                updateStreamProgressFromStep(normalizeTraceStep(step), eventName);
                appendStreamStep(step);
                streamActiveToolId = normalizeToolId(step.toolName);
                syncToolHighlights(currentOptimizationResult.execution, streamActiveToolId);
                return;
            }

            if (eventName === 'final_result') {
                syncStreamProgressMetricsFromResult(payload);
                persistStreamProgressProfile(payload.execution || payload);
                STREAM_PROGRESS_PHASES.forEach(phase => {
                    markProgressPhase(phase.key, 1, 'completed', true);
                });
                currentOptimizationResult = payload;
                currentSessionId = payload.sessionId || currentSessionId;
                renderStreamProgress({
                    text: '整理结果...',
                    hint: '正在回放完整时间线并渲染最终分析结果。',
                    activePhase: 'finalize'
                });
                renderStreamTrace(payload.execution);
                displayResults(payload);
                renderStreamProgress({
                    text: '分析完成',
                    hint: `已完成${streamProgressState.candidateCount > 0 ? ` ${streamProgressState.candidateCount} 个候选方案` : ''}的生成、验证与评分。`,
                    activePhase: 'finalize',
                    percent: 100
                });
                return;
            }

            appendStreamProgress(envelope);
        } catch (error) {
            console.warn('stream event parse failed', eventName, rawData, error);
        }
    }

    /**
     * 格式化字段错误信息，使其更友好
     */
    function formatFieldError(errorMessage) {
        // 提取字段名和表名
        const fieldMatch = errorMessage.match(/字段 '([^']+)' 在表 '([^']+)' 中不存在/);
        if (fieldMatch) {
            const fieldName = fieldMatch[1];
            const tableName = fieldMatch[2];

            // 检查是否有建议的字段
            const suggestionMatch = errorMessage.match(/您是否想用 '([^']+)'/);
            if (suggestionMatch) {
                const suggestedField = suggestionMatch[1];
                return `⚠️ 字段名错误：表中不存在字段 "${fieldName}"\n\n💡 建议：请使用 "${suggestedField}" 代替\n\n📋 这可能是因为 AI 在生成优化方案时没有查询实际的表结构`;
            }

            // 提取可用字段列表
            const fieldsMatch = errorMessage.match(/表中可用的字段: (.+)/);
            if (fieldsMatch) {
                const availableFields = fieldsMatch[1];
                return `⚠️ 字段名错误：表中不存在字段 "${fieldName}"\n\n📋 ${tableName} 表可用的字段:\n${availableFields}\n\n💡 提示：AI 应该在展开 SELECT * 前先查询表结构`;
            }
        }

        // 默认返回原始错误
        return errorMessage;
    }

    /**
     * 根据步骤数获取阶段文本
     */
    function getPhaseText(stepCount) {
        const phases = [
            '分析 SQL 语句...',
            '获取执行计划...',
            '分析表结构...',
            '评估索引策略...',
            '生成优化方案...',
            '验证优化效果...',
            '准备结果...',
            '即将完成...'
        ];
        return phases[Math.min(stepCount - 1, phases.length - 1)] || '处理中...';
    }

function appendStreamProgress(event) {
        const messageStep = buildTraceStepFromEnvelope(event);
        messageStep.stepType = 'phase';
        messageStep.stepName = messageStep.stepName || t('stream.phase.systemMessage', {}, 'System Message');
        messageStep.toolName = messageStep.toolName || 'system_message';
        updateStreamPhaseCard(messageStep);
    }
