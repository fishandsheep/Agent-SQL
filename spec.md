# Spec: 新用户引导功能（Onboarding Tour）

## 问题陈述

SQL Agent 前端功能较多，首次打开的用户不清楚操作流程。需要一个步骤式引导，帮助新用户快速了解主流程：选择模型 → 输入 SQL → 点击分析。

---

## 技术约束

- 纯原生 HTML + Vanilla JS，无前端框架
- 使用 Tailwind CSS（CDN）
- 已有 i18n 系统（`i18n.js`，支持中英文）
- 所有第三方库通过 CDN 引入

---

## 需求

### 功能需求

1. **引入 driver.js**：通过 CDN 引入 driver.js（v1.x），用于高亮遮罩 + 步骤弹窗。
2. **首次自动触发**：用户首次访问时自动启动引导，使用 `localStorage` 记录状态（key: `sqlagent_tour_done`），已看过则不再自动弹出。
3. **手动触发入口**：Header 区域右侧操作栏（语言切换按钮旁）增加「使用指南」按钮，点击可随时重新启动引导。
4. **引导步骤（仅覆盖优化分析主流程）**：

   | 步骤 | 目标元素 | 说明 |
   |------|----------|------|
   | 1 | `#navAnalyze`（左侧导航-优化分析） | 介绍这是主功能入口 |
   | 2 | `#toolsContainer`（可用工具区） | 介绍 Agent 可调用的工具 |
   | 3 | `#modelSelect`（AI 模型选择） | 介绍如何选择模型 |
   | 4 | `#sqlSamplesContainer`（SQL 样例） | 介绍可点击样例快速填充 |
   | 5 | `#sqlEditorWrapper`（SQL 编辑器） | 介绍在此输入 SQL |
   | 6 | `#analyzeBtn`（分析按钮） | 介绍点击开始分析 |

5. **i18n 支持**：所有引导文案（标题 + 描述）接入现有 `i18n.js`，随语言切换实时更新。新增 i18n key 前缀为 `tour.*`。
6. **语言切换时重启引导**：若引导正在进行中，切换语言后自动以新语言重新启动当前步骤（或从头重启）。

### 非功能需求

- driver.js 通过 CDN 加载，加载失败时静默降级（不报错，不影响主功能）。
- 引导样式与现有 soft UI 风格保持一致（圆角、柔和阴影）。
- 引导触发时确保页面处于「优化分析」页（若当前在其他页面，自动切换回来）。

---

## 验收标准

- [ ] 首次打开页面，引导自动启动，走完后不再自动弹出
- [ ] Header 有「使用指南」按钮，点击可重新触发引导
- [ ] 引导共 6 步，每步高亮对应元素并显示说明文案
- [ ] 切换语言后，引导文案随之变化
- [ ] driver.js CDN 加载失败时，页面功能不受影响
- [ ] 引导触发时若不在优化分析页，自动跳转

---

## 实现步骤

1. **引入 driver.js**：在 `index.html` `<head>` 中添加 driver.js CSS 和 JS 的 CDN 链接（v1.x）。

2. **添加 i18n 文案**：在 `i18n.js` 的 `zh` 和 `en` 对象中添加 `tour.*` 系列 key，包含每步的 `title` 和 `description`，以及「使用指南」按钮文案（`tour.button`）。

3. **创建 `js/tour.js`**：
   - 定义 `startTour()` 函数，内部构建 driver.js steps 数组（文案从 `t()` 读取）
   - 定义 `startTourIfFirstVisit()` 函数，检查 `localStorage` 决定是否自动触发
   - 导出到 `window.startTour` 和 `window.startTourIfFirstVisit`
   - 语言切换事件监听：监听 `sqlagent:languagechange`，若引导正在进行则重启

4. **在 `index.html` 中添加「使用指南」按钮**：在 Header 的 `.app-header-actions` 区域，语言切换按钮之前插入按钮，`onclick="startTour()"`，文案绑定 i18n key `tour.button`。

5. **在 `index.html` 底部引入 `tour.js`**：在其他 JS 文件之后、`</body>` 之前引入 `<script src="js/tour.js"></script>`。

6. **在 `app.js` 的 `DOMContentLoaded` 中调用 `startTourIfFirstVisit()`**：确保页面初始化完成后再触发引导。

7. **验证**：清除 localStorage 后刷新页面，确认引导自动启动；点击「使用指南」按钮确认可重新触发；切换语言确认文案更新。
