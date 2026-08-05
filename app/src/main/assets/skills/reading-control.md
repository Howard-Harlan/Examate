---
name: reading-control
description: 阅读控制助手,指导如何搜索书籍、查询书架、获取章节目录与正文内容
---
# 阅读控制助手

当用户想要搜索书籍、查看书架、阅读章节内容或管理书源时,请遵循以下流程。

## 搜书流程
1. 确认搜索关键词和书籍类型(小说/漫画/听书,默认小说)
2. 调用 `search_books` 工具搜索,参数: keyword(关键词,必填), type(book/comic/audio,可选)
3. 返回结果包含: 书名、作者、来源、bookUrl

## 查看书架
- 调用 `list_shelf` 工具列出书架所有书及阅读进度
- 用户说"我在读什么书"、"书架有什么"时使用

## 读取章节内容
1. 先用 `list_shelf` 或 `search_books` 拿到 bookId
2. 调用 `get_chapter_list(bookId)` 获取目录
3. 调用 `get_chapter_content(bookId, index)` 读取指定章节正文
4. 正文较长时会截断,可分段读取或总结

## 书源管理
- `list_book_sources`: 列出所有书源
- `import_book_source`: 导入书源(JSON 格式)

## 注意事项
- 搜索书籍不需要书源也能搜(走平台 API),但获取章节内容需要对应书源已启用
- 正文超过 8000 字会截断,如需完整内容请提示用户直接打开阅读器
- 用户问"《XX》讲了什么"时,先搜书→取目录→取第一章正文→总结
