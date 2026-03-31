async function loadTablesList() {
    const btn = document.getElementById('loadTablesBtn');
    btn.disabled = true;
    btn.innerHTML = `<span class="material-symbols-outlined soft-loading">hourglass_empty</span><span>${t('common.loading', {}, 'Loading...')}</span>`;

    try {
        const response = await fetch('/SQLAgent/api/tables');
        const data = await response.json();

        if (data.success) {
            displayTables(data.tables, data.database);
        } else {
            showToast(t('common.loadFailed', { message: data.error }, `Load failed: ${data.error}`), 'error');
        }
    } catch (error) {
        console.error('加载表列表失败', error);
        showToast(t('common.loadFailed', { message: error.message }, `Load failed: ${error.message}`), 'error');
    } finally {
        btn.disabled = false;
        btn.innerHTML = `<span class="material-symbols-outlined">table_view</span><span>${t('tables.load', {}, 'Load Tables')}</span>`;
    }
}

function displayTables(tables, database) {
    document.getElementById('tablesListSection').style.display = 'block';

    const container = document.getElementById('tablesList');
    container.innerHTML = '';

    if (!tables || tables.length === 0) {
        container.innerHTML = `<div class="soft-card-inset p-6 text-center" style="color: var(--text-secondary);">${t('tables.empty', {}, 'No tables found in the database')}</div>`;
        return;
    }

    tables.forEach(table => {
        const card = document.createElement('div');
        card.className = 'soft-card p-5 smooth-transition cursor-pointer schema-table-card';
        card.innerHTML = `
            <div class="flex items-center gap-2 mb-3">
                <span class="material-symbols-outlined" style="color: var(--primary-gradient-start);">table_chart</span>
                <h4 class="font-bold" style="color: var(--text-primary);">${table.tableName}</h4>
            </div>
            <div class="space-y-2 text-sm schema-table-metrics" style="color: var(--text-secondary);">
                <div class="flex justify-between schema-table-row">
                    <span>${t('tables.engine', {}, 'Engine:')}</span>
                    <span class="font-medium" style="color: var(--text-primary);">${table.engine || '-'}</span>
                </div>
                <div class="flex justify-between schema-table-row">
                    <span>${t('tables.rowCount', {}, 'Rows:')}</span>
                    <span class="font-medium" style="color: var(--text-primary);">${table.rowCount !== null ? table.rowCount.toLocaleString() : '-'}</span>
                </div>
                <div class="flex justify-between schema-table-row">
                    <span>${t('tables.size', {}, 'Size:')}</span>
                    <span class="font-medium" style="color: var(--text-primary);">${table.dataSize || '-'}</span>
                </div>
                <div class="flex justify-between schema-table-row">
                    <span>${t('tables.indexCount', {}, 'Indexes:')}</span>
                    <span class="font-medium" style="color: var(--text-primary);">${table.indexCount || 0}</span>
                </div>
            </div>
        `;
        card.addEventListener('click', () => loadTableDetail(table.tableName));
        container.appendChild(card);
    });
}

async function loadTableDetail(tableName) {
    try {
        const response = await fetch(`/SQLAgent/api/table/${tableName}/detail`);
        const data = await response.json();

        if (data.success) {
            displayTableDetail(data);
        } else {
            showToast(t('tables.detailLoadFailed', { message: data.error }, `Failed to load table detail: ${data.error}`), 'error');
        }
    } catch (error) {
        console.error('加载表详情失败', error);
        showToast(t('tables.detailLoadFailed', { message: error.message }, `Failed to load table detail: ${error.message}`), 'error');
    }
}

function displayTableDetail(data) {
    document.getElementById('tablesListSection').style.display = 'none';
    document.getElementById('tableDetailSection').style.display = 'block';
    document.getElementById('detailTableName').textContent = data.tableName;

    const columnsBody = document.getElementById('columnsTableBody');
    columnsBody.innerHTML = '';

    data.columns.forEach(column => {
        const row = document.createElement('tr');
        row.className = 'border-b';
        row.style.borderColor = 'var(--border-color)';
        row.innerHTML = `
            <td class="py-3 px-4 font-medium" style="color: var(--text-primary);">${column.columnName}</td>
            <td class="py-3 px-4 code-font" style="color: var(--text-secondary);">${column.columnType}</td>
            <td class="py-3 px-4" style="color: var(--text-secondary);">${column.nullable ? t('common.yes', {}, 'Yes') : t('common.no', {}, 'No')}</td>
            <td class="py-3 px-4">
                ${column.primaryKey ? `<span class="soft-badge primary">${t('tables.primary', {}, 'Primary')}</span>` : ''}
                ${column.uniqueKey ? `<span class="soft-badge" style="background: linear-gradient(145deg, #e0e7ff, #c7d2fe); color: #4338ca;">${t('tables.unique', {}, 'Unique')}</span>` : ''}
                ${!column.primaryKey && !column.uniqueKey ? '-' : ''}
            </td>
        `;
        columnsBody.appendChild(row);
    });

    const indexesBody = document.getElementById('indexesTableBody');
    indexesBody.innerHTML = '';

    data.indexes.forEach(index => {
        const row = document.createElement('tr');
        row.className = 'border-b';
        row.style.borderColor = 'var(--border-color)';

        let typeBadge = '';
        if (index.primaryKey) {
            typeBadge = `<span class="soft-badge primary">${t('tables.primary', {}, 'Primary')}</span>`;
        } else if (index.unique) {
            typeBadge = `<span class="soft-badge" style="background: linear-gradient(145deg, #e0e7ff, #c7d2fe); color: #4338ca;">${t('tables.unique', {}, 'Unique')}</span>`;
        } else {
            typeBadge = `<span class="soft-badge" style="background: linear-gradient(145deg, #f5f7fa, #e8ecf1); color: var(--text-secondary);">${t('tables.normal', {}, 'Normal')}</span>`;
        }

        const deleteBtn = index.primaryKey
            ? `<span style="color: var(--text-tertiary); font-size: 12px;">${t('tables.notDeletable', {}, 'Cannot delete')}</span>`
            : runtimeFeatures?.mutationEnabled
                ? `<button onclick="dropIndex('${data.tableName}', '${index.indexName}')" class="soft-button-secondary py-1 px-3 text-xs font-bold">${t('common.delete', {}, 'Delete')}</button>`
                : `<span style="color: var(--text-tertiary); font-size: 12px;">${t('common.readOnly', {}, 'Read-only mode')}</span>`;

        row.innerHTML = `
            <td class="py-3 px-4 font-medium" style="color: var(--text-primary);">${index.indexName}</td>
            <td class="py-3 px-4" style="color: var(--text-secondary);">${index.columns.join(', ')}</td>
            <td class="py-3 px-4">${typeBadge}</td>
            <td class="py-3 px-4">${deleteBtn}</td>
        `;
        indexesBody.appendChild(row);
    });
}

async function dropIndex(tableName, indexName) {
    if (!runtimeFeatures?.mutationEnabled) {
        showToast(t('tables.deleteDisabled', {}, 'Read-only demo mode is enabled, so index deletion is disabled'), 'info');
        return;
    }

    const confirmed = prompt(t('tables.deletePrompt', { tableName, indexName }, `Enter table name "${tableName}" to confirm deleting index "${indexName}"`));
    if (confirmed !== tableName) {
        showToast(t('common.confirmFailed', {}, 'Confirmation failed, operation cancelled'), 'error');
        return;
    }

    try {
        const response = await fetch('/SQLAgent/api/index/drop', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ tableName, indexName })
        });

        const resultText = await response.text();
        const result = JSON.parse(resultText);

        if (result.success) {
            showToast(result.message || t('common.deleteSuccess', {}, 'Deleted successfully'), 'success');
            loadTableDetail(tableName);
        } else {
            showToast(t('common.deleteFailed', { message: result.error }, `Delete failed: ${result.error}`), 'error');
        }
    } catch (error) {
        console.error('删除索引失败:', error);
        showToast(t('common.deleteFailed', { message: error.message }, `Delete failed: ${error.message}`), 'error');
    }
}
