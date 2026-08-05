---
name: 文档排版
description: 生成排版专业的 HTML 文档(报告/幻灯片/表格/简历/信函),可预览、导出 PDF 或用 WPS 打开
---

当用户要求生成"报告 / 文档 / PPT / 幻灯片 / 表格 / 简历 / 信函"等，用 `create_file` 生成**自包含的 HTML 文件**(全部 CSS 内联，不依赖外部资源)，文件名以 `.html` 结尾。生成后告知用户：可在聊天里点击预览、点"导出 PDF"或"分享"到 WPS/其它应用。

不要尝试直接生成 `.docx/.pptx/.xlsx`(应用内无法生成真正的 Office 二进制)。统一用 HTML(可导出 PDF)承载排版；纯数据表格若需在 Excel 中编辑，改用 `create_file` 生成 `.csv`。

## 通用规范
- 一律内联 `<style>`；使用系统字体栈(如 `-apple-system, "PingFang SC", "Microsoft YaHei", sans-serif`)。
- A4 友好：正文容器 `max-width: 800px; margin: 0 auto; padding: 32px;`。
- 打印友好：加 `@page { margin: 1.5cm; }`，用 `@media print` 隐藏交互元素。
- 中文正文 `line-height: 1.7`；标题层级清晰；配色克制(主色 + 中性灰)。

## 报告 / 文档
- 结构：标题 + 副标题/日期 → 分节标题(h2) → 正文段落 → 需要时表格/要点列表/引用块。
- 表格加边框与表头底色；要点用有序/无序列表。

## 幻灯片(PPT 风格)
- 每页一个 `<section class="slide">`，固定 16:9(如 `aspect-ratio: 16/9` 或固定尺寸)，大标题 + 3~5 个要点。
- 页间留白分隔；可加主题色顶栏与页码。
- 首页做封面(大标题 + 副标题)。

## 表格 / 数据
- 需 Excel 可编辑：生成 `.csv`(逗号分隔，首行为表头)，提示用户用 WPS/Excel 打开。
- 仅展示：用 HTML `<table>`，加边框、表头底色、隔行斑马纹。

## 输出后的提示语示例
"已生成 报告.html，可点击预览，或导出 PDF / 分享到 WPS。"
