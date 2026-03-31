/**
 * Onboarding tour for first-time users.
 * Uses driver.js to highlight key UI elements in the main analysis workflow.
 */

const TOUR_DONE_KEY = 'sqlagent_tour_done';

let _driverInstance = null;

function buildSteps() {
    return [
        {
            element: '#navAnalyze',
            popover: {
                title: t('tour.step1.title', {}, '优化分析入口'),
                description: t('tour.step1.desc', {}, '这是目前打磨最完整的主功能。点击这里进入 SQL 优化分析流程。'),
                side: 'right',
                align: 'start'
            }
        },
        {
            element: '#toolsContainer',
            popover: {
                title: t('tour.step2.title', {}, '可用工具'),
                description: t('tour.step2.desc', {}, 'Agent 在分析过程中会调用这些工具，包括执行计划分析、索引推荐、结果校验等。'),
                side: 'bottom',
                align: 'start'
            }
        },
        {
            element: '#modelSelect',
            popover: {
                title: t('tour.step3.title', {}, '选择 AI 模型'),
                description: t('tour.step3.desc', {}, '可以在这里切换不同的 AI 模型，不同模型在效果和速度上有所差异。'),
                side: 'bottom',
                align: 'start'
            }
        },
        {
            element: '#sqlSamplesContainer',
            popover: {
                title: t('tour.step4.title', {}, 'SQL 样例'),
                description: t('tour.step4.desc', {}, '点击任意样例，可以直接将 SQL 填充到下方编辑器，方便快速体验。'),
                side: 'bottom',
                align: 'start'
            }
        },
        {
            element: '#sqlEditorWrapper',
            popover: {
                title: t('tour.step5.title', {}, '输入 SQL'),
                description: t('tour.step5.desc', {}, '在这里输入你想要优化的 SELECT 查询语句，支持语法高亮和格式化。'),
                side: 'top',
                align: 'start'
            }
        },
        {
            element: '#analyzeBtn',
            popover: {
                title: t('tour.step6.title', {}, '开始分析'),
                description: t('tour.step6.desc', {}, '输入 SQL 后点击这里，Agent 会自动生成候选方案、执行验证并给出优化结论。'),
                side: 'top',
                align: 'end'
            }
        }
    ];
}

function startTour() {
    // driver.js may not have loaded (CDN failure) — degrade silently
    if (typeof window.driver === 'undefined' || typeof window.driver.js === 'undefined') {
        console.warn('[tour] driver.js not available, skipping tour');
        return;
    }

    // Ensure we are on the analyze page before starting
    if (typeof switchPage === 'function') {
        switchPage('analyze');
    }

    // Destroy any existing instance
    if (_driverInstance) {
        try { _driverInstance.destroy(); } catch (_) {}
        _driverInstance = null;
    }

    const { driver } = window.driver.js;

    _driverInstance = driver({
        showProgress: true,
        animate: true,
        overlayColor: 'rgba(0, 0, 0, 0.55)',
        smoothScroll: true,
        nextBtnText: t('tour.next', {}, '下一步'),
        prevBtnText: t('tour.prev', {}, '上一步'),
        doneBtnText: t('tour.done', {}, '完成'),
        // onDestroyStarted fires before driver cleans up the DOM.
        // Calling destroy() here ensures the overlay and popover are always removed,
        // regardless of whether the user clicked Done, X, or the overlay.
        onDestroyStarted: (el, step, { driver: d }) => {
            localStorage.setItem(TOUR_DONE_KEY, '1');
            d.destroy();
        },
        onDestroyed: () => {
            _driverInstance = null;
        },
        steps: buildSteps()
    });

    _driverInstance.drive();
}

function startTourIfFirstVisit() {
    if (localStorage.getItem(TOUR_DONE_KEY)) return;
    // Small delay so the page finishes rendering before the tour starts
    setTimeout(startTour, 600);
}

// When the language changes mid-tour, restart from the beginning with new text
window.addEventListener('sqlagent:languagechange', () => {
    if (_driverInstance) {
        try { _driverInstance.destroy(); } catch (_) {}
        // _driverInstance is cleared by onDestroyed; wait for it then restart
        setTimeout(startTour, 150);
    }
});

window.startTour = startTour;
window.startTourIfFirstVisit = startTourIfFirstVisit;
