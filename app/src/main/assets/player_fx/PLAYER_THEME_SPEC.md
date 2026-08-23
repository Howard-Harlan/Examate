# 播放页网页主题规范(PLAYER THEME SPEC)

播放页是一个"视图容器",其内容(背景特效 / 整个播放器 / 歌词页)可以用网页替代。
网页主题与原生通过两条桥通信,页面只需实现事件接收、按需调用动作即可。

## 主题与特效的区别

- 主题 = 播放器本体:替换整个播放页(封面/控制/歌词),选了网页主题后原生播放页被整页替换。
- 特效 = 背景氛围层:叠在播放页背景上(如 3D 粒子),不参与控制,与任何主题可叠加。
- 导入的 HTML 自动识别:含 player-theme-mode=player 元标记的归为主题,其余归为特效,无需手动选择。

## 一、页面声明

- 单个 HTML 文件,经「我的 → 外观与特效」导入。
- **背景特效页**(叠在原生播放页背景层):无需特殊声明(默认)。
- **网页播放器页**(替换整个播放页内容,含歌词):在 `<head>` 中加入
  `<meta name="player-theme-mode" content="player">`
- **收起按钮位置**(可选,仅网页播放器页):在 `<head>` 中加入
  `<meta name="player-theme-close" content="top-right">`,
  可选值:`top-left`(默认) / `top-right` / `bottom-left` / `bottom-right` / `none`
  (`none` 时原生不显示兜底收起按钮,页面需自行调用 `{"a":"close"}` 收起)。
- 页面以 `file:///android_asset/player_fx/` 为 baseUrl 加载;
  可用相对路径 `../libs/three.min.js` 引用内置 three.js。
- 页面背景建议透明(`background: transparent`),可叠加在原生主题渐变之上。

## 二、原生 → 页面:状态事件

原生调用 `window.__onPlayerEvent(jsonStr)`(页面需定义该函数)。字段:

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| isPlaying | boolean | 是否播放中 |
| positionMs | number | 当前播放位置(毫秒) |
| durationMs | number | 总时长(毫秒) |
| progress | number | 进度 0..1 |
| title / artist | string | 曲名 / 艺术家 |
| playMode | string | list_loop / single_loop / shuffle / order |
| speed | number | 播放倍率 |
| isFavorite | boolean | 是否已收藏 |
| lyricIndex | number | 当前歌词行下标(-1 无) |
| lyrics | array | 仅切歌/加载时推送:`[{t: 毫秒, text: 文本}]` |
| colors | object | `{accent, bgTop, bgBottom}` 主题色(封面 Palette 派生) |

推送时机:切歌、播放/暂停、播放模式、倍速、收藏变化、主题色变化时全量推送(含歌词);
播放中每 500ms 推送进度(不含 lyrics 数组,页面应缓存上次的歌词)。
页面发起控制动作后无需自己猜状态:原生执行后会经上述时机自动回推最新状态,
页面直接按收到的事件刷新按钮状态即可(如收藏/模式按钮高亮)。

## 三、页面 → 原生:控制动作

调用 `window.PlayerBridge.action(jsonStr)`:

| 动作 | 参数 | 说明 |
| --- | --- | --- |
| `{"a":"toggle"}` | — | 播放/暂停 |
| `{"a":"next"}` / `{"a":"prev"}` | — | 下一首 / 上一首 |
| `{"a":"seek","v":毫秒}` | v:number | 跳转进度 |
| `{"a":"mode","v":"shuffle"}` | v:模式 id | 切换播放模式 |
| `{"a":"speed","v":1.25}` | v:number | 设置倍速 |
| `{"a":"favorite"}` | — | 切换收藏 |
| `{"a":"queue"}` | — | 打开原生播放列表面板 |
| `{"a":"close"}` | — | 收起播放页 |

## 四、约束与建议

- 收起按钮:原生保留一个兜底收起按钮,位置可用 `player-theme-close` 元标记调整
  (top-left 默认 / top-right / bottom-left / bottom-right);设为 `none` 后页面需自己实现收起入口。
- 进度条建议跟手拖动、松手再 seek(与原生一致)。
- 动画只动 transform/opacity,遵循系统减弱动态偏好。
- 歌词页属于播放器的一部分:用推送的 lyrics/lyricIndex 自行渲染
  (可完全自定义歌词样式,即"歌词页面也可用网页自定义")。
- 内置默认预设:当前原生播放页(THEME_NATIVE);
  内置示例预设:`web_player_demo.html`(完整实现本规范的参考实现)。
