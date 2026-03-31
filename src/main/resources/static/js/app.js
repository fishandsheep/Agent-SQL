function switchPage(pageName) {
    currentPage = pageName;
    document.getElementById('analyzePage').style.display = pageName === 'analyze' ? 'block' : 'none';
    document.getElementById('tablesPage').style.display = pageName === 'tables' ? 'block' : 'none';
    document.getElementById('historyPage').style.display = pageName === 'history' ? 'block' : 'none';
    document.getElementById('workloadPage').style.display = pageName === 'workload' ? 'block' : 'none';

    if (pageName === 'history') {
        document.getElementById('historyDetailSection').style.display = 'none';
        document.getElementById('historyListSection').style.display = 'block';
    }
    if (pageName === 'tables') {
        document.getElementById('tableDetailSection').style.display = 'none';
        document.getElementById('tablesListSection').style.display = 'block';
    }

    updateNavigationState(pageName);

    if (pageName === 'analyze') {
        resetToolStatus();
    }
    if (pageName === 'history') {
        loadHistoryList();
    }

    window.scrollTo({ top: 0, behavior: 'smooth' });
}

window.switchPage = switchPage;

function updateNavigationState(pageName = currentPage) {
    const navConfig = [
        { id: 'navAnalyze', page: 'analyze' },
        { id: 'navWorkload', page: 'workload' },
        { id: 'navHistory', page: 'history' },
        { id: 'navTables', page: 'tables' }
    ];

    navConfig.forEach(({ id, page }) => {
        const element = document.getElementById(id);
        if (!element) return;
        const active = page === pageName;
        element.className = active
            ? 'soft-nav-item active flex items-center gap-3 w-full text-left'
            : 'soft-nav-item flex items-center gap-3 w-full text-left';
        element.setAttribute('aria-pressed', active ? 'true' : 'false');
        element.setAttribute('aria-current', active ? 'page' : 'false');
    });
}

function toggleCollapsible(header) {
    const section = header.parentElement;
    const content = section.querySelector('.collapsible-content');
    const willOpen = !section.classList.contains('open');
    section.classList.toggle('open', willOpen);
    header.setAttribute('aria-expanded', willOpen ? 'true' : 'false');
    if (!content) return;
    content.style.maxHeight = willOpen ? `${content.scrollHeight + 48}px` : '0px';
}

function syncOpenCollapsibleHeights() {
    document.querySelectorAll('.collapsible-section.open .collapsible-content').forEach(content => {
        content.style.maxHeight = `${content.scrollHeight + 48}px`;
    });
}

function initializeInteractiveHeaders() {
    document.querySelectorAll('.collapsible-header').forEach(header => {
        header.setAttribute('role', 'button');
        header.setAttribute('tabindex', '0');
        header.setAttribute('aria-expanded', header.parentElement?.classList.contains('open') ? 'true' : 'false');

        if (!header.dataset.keyboardBound) {
            header.addEventListener('keydown', event => {
                if (event.key === 'Enter' || event.key === ' ') {
                    event.preventDefault();
                    toggleCollapsible(header);
                }
            });
            header.dataset.keyboardBound = 'true';
        }
    });
}

async function loadRuntimeFeatures() {
    try {
        const response = await fetch('/SQLAgent/api/features');
        if (!response.ok) {
            throw new Error(t('app.runtimeFeaturesLoadFailed', {}, '获取运行时特性失败'));
        }

        const data = await response.json();
        runtimeFeatures = {
            readOnlyDemo: data.readOnlyDemo !== false,
            mutationEnabled: data.mutationEnabled === true
        };
        window.runtimeFeatures = runtimeFeatures;

        const tablesHint = document.getElementById('tablesFeatureHint');
        if (tablesHint) {
            tablesHint.textContent = runtimeFeatures.mutationEnabled
                ? t('tables.pageDescMutation', {}, 'This mode allows index deletion in a controlled test environment. Please proceed carefully.')
                : t('tables.pageDesc', {}, 'Read-only demo mode is enabled. Only schema, columns, and index information are shown.');
        }
    } catch (error) {
        console.warn('failed to load runtime features, using read-only defaults', error);
        runtimeFeatures = {
            readOnlyDemo: true,
            mutationEnabled: false
        };
        window.runtimeFeatures = runtimeFeatures;
    }
}

document.addEventListener('DOMContentLoaded', function() {
    loadRuntimeFeatures();
    loadToolsList();
    updateNavigationState(currentPage);
    initializeInteractiveHeaders();

    document.getElementById('navAnalyze').addEventListener('click', () => switchPage('analyze'));

    document.getElementById('loadTablesBtn').addEventListener('click', loadTablesList);
    document.getElementById('backToTablesBtn').addEventListener('click', function() {
        document.getElementById('tableDetailSection').style.display = 'none';
        document.getElementById('tablesListSection').style.display = 'block';
    });

    document.getElementById('refreshHistoryBtn').addEventListener('click', loadHistoryList);
    document.getElementById('historyFilterFrom').addEventListener('change', loadHistoryList);
    document.getElementById('historyFilterTo').addEventListener('change', loadHistoryList);
    document.getElementById('backToHistoryBtn').addEventListener('click', function() {
        document.getElementById('historyDetailSection').style.display = 'none';
        document.getElementById('historyListSection').style.display = 'block';
    });

    document.getElementById('workloadBtn').addEventListener('click', optimizeWorkload);
    document.getElementById('workloadSqlInput').addEventListener('input', function() {
        const sqls = this.value.split('\n').filter(s => s.trim()).length;
        document.getElementById('workloadSqlCount').textContent = `${sqls} / 10`;
    });
});

window.addEventListener('sqlagent:languagechange', () => {
    if (typeof loadToolsList === 'function') {
        loadToolsList();
    }
    if (currentPage === 'history' && typeof loadHistoryList === 'function') {
        loadHistoryList();
    }
    if (currentPage === 'tables' && typeof loadTablesList === 'function' && document.getElementById('tablesListSection')?.style.display !== 'none') {
        loadTablesList();
    }
    if (currentOptimizationResult && typeof displayResults === 'function' && document.getElementById('results')?.classList.contains('show')) {
        displayResults(currentOptimizationResult);
    }
    if (window.lastWorkloadResult && typeof displayWorkloadResults === 'function' && document.getElementById('workloadResults')?.classList.contains('show')) {
        displayWorkloadResults(window.lastWorkloadResult);
    }
    initializeInteractiveHeaders();
});
