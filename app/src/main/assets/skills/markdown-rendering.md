---
name: markdown-rendering
description: 指导 AI 使用 Markdown、LaTeX 公式和 SMILES 化学式格式输出内容
---
# 富文本格式输出指南

本应用的聊天界面支持渲染 Markdown、LaTeX 数学公式和 SMILES 化学式。请遵循以下格式规范,确保内容正确渲染。

## Markdown

使用标准 Markdown 语法:
- **粗体**: `**文本**`
- *斜体*: `*文本*`
- 代码块: 三反引号包裹
- 表格: 标准 Markdown 表格语法
- 列表: `-` 或 `1.` 开头
- 引用: `>` 开头

## LaTeX 数学公式

### 行内公式
用单个 `$` 包裹,支持空格:
- `$E = mc^2$`
- `$\frac{a}{b}$`
- `$\sum_{i=1}^{n} x_i$`

### 块级公式
用双 `$$` 包裹,独占一行:
```
$$
\int_0^{\infty} e^{-x^2} dx = \frac{\sqrt{\pi}}{2}
$$
```

支持的 LaTeX 命令:
- 分数: `\frac{a}{b}`
- 上下标: `x^2`, `x_i`
- 求和/积分: `\sum`, `\int`
- 根号: `\sqrt{x}`
- 矩阵: `\begin{pmatrix} a & b \\ c & d \end{pmatrix}`
- 希腊字母: `\alpha`, `\beta`, `\gamma`, `\pi`, `\theta`
- 关系符: `\leq`, `\geq`, `\neq`, `\approx`

## SMILES 化学式

用 `$smiles{...}` 语法嵌入化学结构式:
- 水: `$smiles{O}`
- 乙醇: `$smiles{CCO}`
- 苯: `$smiles{c1ccccc1}`
- 葡萄糖: `$smiles{OC[C@H]1OC(O)[C@H](O)[C@@H](O)[C@@H]1O}`

也可用代码块:
```smiles
CCO
```

## 注意事项

1. 公式中的 `$` 必须成对出现,未闭合的 `$` 会导致渲染异常
2. 块级公式 `$$` 必须独占一行
3. SMILES 字符串中不能包含空格
4. 流式输出期间公式可能暂时显示为纯文本,完成后会正常渲染
5. 不要在普通文本中使用 `$` 符号(如货币),以免误触发公式渲染
