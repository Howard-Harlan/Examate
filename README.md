<div align="center">

# Examate

**ex a mate** — 超越一个朋友

智能学习助手 · AI 对话 · 校园服务 · 悬浮窗答题

Kotlin + Jetpack Compose · Android 12+

</div>

---


## 核心功能

### AI 对话

> 多厂商接入 · Markdown + LaTeX 渲染 · Agent 工具调用 · 图片生成

- 兼容 **OpenAI / Anthropic / 兼容接口**，可配置多组 API Key
- 支持 **function calling**：AI 自动查询成绩、课表、考试等信息
- **Skills 技能系统**：导入 SKILL.md 定义 AI 行为模式
- **长期记忆**：记住用户偏好，跨会话生效
- 数学公式用 KaTeX 渲染，代码块高亮，支持 LaTeX 化学式

### 校园服务

> 教务系统 · 一卡通 · 第二课堂 · 内存缓存加速

| 服务 | 能力 |
|------|------|
| 教务系统 | 成绩查询 / 课表 / 考试安排 / 选课 / 教学评价 |
| 一卡通 | 余额 / 账单 / 缴费 |
| 第二课堂 | 成绩明细与汇总 |
| 步道乐跑 | 路线编辑 · 配速计算 · 自动上传 · 记录查询 |

### 悬浮窗

> 无障碍服务 · Shizuku 免 Root · 防截屏

- **框选识别**：屏幕任意区域框选，OCR + AI 解题
- **问问 AI**：长按屏幕文字直接提问
- **自动答题**：识别题目自动作答
- Shizuku 免 Root 截屏，规避应用检测

### 笔记与速记

- **速记画布**：可缩放灵感画布 + 便利贴 + 倒数日 + 快速输入栏
- **笔记编辑器**：Markdown 编辑/预览双模式，多主题切换
- **AI 搜题知识源**：勾选笔记供 AI 渐进读取

---

## 技术栈

```
Kotlin + Jetpack Compose    声明式 UI
Coroutines + StateFlow      异步与状态管理
OkHttp                      网络请求
Shizuku                     免 Root 特权操作
KaTeX                       LaTeX 公式渲染
PdfRenderer                 原生 PDF 预览(零依赖)
```

---

## 下载

| 来源 | 地址 |
|------|------|
| GitHub | `https://github.com/duringk/Examate/releases` |
| Gitee | `https://gitee.com/danfuu/Examate/releases` |

应用内 **关于 → 检查更新** 可自动检测新版本，当天首次打开会静默检查并通知。

---

## 构建

```bash
# 1. 克隆仓库
git clone https://github.com/duringk/Examate.git

# 2. 在 local.properties 中配置签名信息
#   storeFile=...
#   storePassword=...
#   keyAlias=...
#   keyPassword=...

# 3. 构建 Release APK
./gradlew assembleRelease
```

> 产物路径：`app/build/outputs/apk/release/app-release.apk`

---

## 说明

- 所有数据**本地存储**，隐私优先
- 液态玻璃 UI 效果基于 [AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) 库
- 仅供**学习交流**使用

---

<div align="center">

*ex a mate — 不只是工具，更是一个超越普通朋友的智能学习伙伴*

</div>
