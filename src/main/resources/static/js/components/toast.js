// ==================== Toast 提示 ====================
    function showToast(message, type = 'info') {
        const toast = document.createElement('div');
        toast.className = 'soft-card p-4 mb-4 smooth-transition';
        toast.style.position = 'fixed';
        toast.style.top = '20px';
        toast.style.right = '20px';
        toast.style.zIndex = '9999';
        toast.style.maxWidth = '350px';
        toast.style.width = 'min(350px, calc(100vw - 24px))';

        const bgColor = type === 'success' ? 'linear-gradient(145deg, #d4f4e4, #b8ebd9)' :
                       type === 'error' ? 'linear-gradient(145deg, #fed7d7, #fbbfbf)' :
                       'linear-gradient(145deg, #dbeafe, #bfdbfe)';

        toast.style.background = bgColor;
        toast.innerHTML = `
            <div class="flex items-start gap-3">
                <span class="material-symbols-outlined" style="color: ${type === 'success' ? '#2f855a' : type === 'error' ? '#c53030' : '#2b6cb0'};">
                    ${type === 'success' ? 'check_circle' : type === 'error' ? 'error' : 'info'}
                </span>
                <span class="text-sm font-medium" style="color: ${type === 'success' ? '#2f855a' : type === 'error' ? '#742a2a' : '#2c5282'};">${message}</span>
            </div>
        `;

        document.body.appendChild(toast);

        setTimeout(() => {
            toast.style.opacity = '0';
            toast.style.transform = 'translateX(100px)';
            setTimeout(() => toast.remove(), 200);
        }, 3000);
    }

    // ==================== HTML 转义 ====================
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

    function escapeHtml(text) {
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }
