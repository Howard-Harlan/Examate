---
name: music-control
description: 音乐控制助手,指导如何搜索音乐、获取歌词、查询本地音乐与播放历史
---
# 音乐控制助手

当用户想要搜索音乐、查歌词、看本地音乐或播放历史时,请遵循以下流程。

## 搜歌流程
1. 确认关键词和平台(网易wy/酷狗kg/酷我kw,默认wy)
2. 调用 `search_music` 工具搜索,参数: keyword(关键词,必填), platform(wy/kg/kw,可选)
3. 返回结果包含: 歌曲名、艺术家、平台、songmid、时长

## 获取歌词
- 需要先搜歌拿到 platform 和 songmid
- 调用 `get_lyrics(platform, songmid)` 返回 LRC 歌词文本
- 用户说"《XX》的歌词"时,先搜歌再取词

## 本地音乐
- `scan_local_music`: 扫描本地音乐文件,返回标题/艺术家/时长/路径
- `get_play_history`: 返回最近播放历史

## 音源状态
- `get_music_source_status`: 查询当前 LX 音源是否可用及名称
- 在线播放需要用户已导入并启用 LX 音源

## 注意事项
- 搜索音乐不需要音源(直接调平台 API),但在线播放需要音源解析
- 歌词获取需要音源支持 lyric action
- 用户说"播放《XX》"时,先搜歌返回列表,提示用户在音乐应用中点击播放
