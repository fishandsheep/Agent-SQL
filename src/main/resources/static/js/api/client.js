async function validateSqlRequest(sqls, maxCount) {
    const response = await fetch('/SQLAgent/api/sql/validate', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
            sqls,
            maxCount
        })
    });

    const data = await response.json();
    if (data.valid) {
        return null;
    }

    const firstInvalidItem = Array.isArray(data.items)
        ? data.items.find(item => item && item.valid === false)
        : null;
    return firstInvalidItem?.message || data.message || t('api.validateFailed', {}, 'SQL validation failed');
}
