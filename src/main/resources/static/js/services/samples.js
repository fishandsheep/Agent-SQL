function fillSqlSample(sample) {
        if (!sample?.sql) return;

        // 如果使用 CodeMirror 编辑器
        if (window.sqlEditor && typeof window.sqlEditor.setValue === 'function') {
            window.sqlEditor.setValue(sample.sql);
            window.sqlEditor.focus();
            // 同步到原 textarea
            if (sqlInput) {
                sqlInput.value = sample.sql;
            }
        } else if (sqlInput) {
            // 使用普通 textarea
            sqlInput.value = sample.sql;
            sqlInput.focus();
            sqlInput.setSelectionRange(sqlInput.value.length, sqlInput.value.length);
        }

        if (typeof updateSqlCharCount === 'function') {
            updateSqlCharCount();
        }
    }

function renderSqlSamples(samples) {
        if (!sqlSamplesContainer) return;
        sqlSamplesContainer.innerHTML = '';

        if (!Array.isArray(samples) || samples.length === 0) {
            sqlSamplesContainer.innerHTML = `<span class="soft-badge">${t('samples.empty', {}, 'No samples available')}</span>`;
            return;
        }

        samples.forEach(sample => {
            const button = document.createElement('button');
            button.type = 'button';
            button.className = 'soft-button py-2 px-3';
            button.title = sample.description || sample.title || '';
            button.innerHTML = `
                <span class="material-symbols-outlined" style="font-size: 16px;">code_blocks</span>
                <span>${escapeHtml(sample.title || t('common.sample', {}, 'SQL Sample'))}</span>
            `;
            button.onclick = () => fillSqlSample(sample);
            sqlSamplesContainer.appendChild(button);
        });
    }

async function loadOptimizationSamples() {
        if (!sqlSamplesContainer) return;
        try {
            const response = await fetch('/SQLAgent/api/optimization-samples');
            if (!response.ok) {
                throw new Error(t('samples.fetchFailed', {}, 'Failed to fetch SQL samples'));
            }
            const data = await response.json();
            renderSqlSamples(data.samples || []);
        } catch (error) {
            console.error('加载 SQL 样例失败:', error);
            sqlSamplesContainer.innerHTML = `<span class="soft-badge">${t('samples.loadFailed', {}, 'Failed to load samples')}</span>`;
        }
    }
