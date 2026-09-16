---
name: save-article-to-workbench
description: "把消息里的文章链接交给本机「个人工作台」存档：抓取标题、作者、正文，按课程写进 Obsidian，并录入知识库。"
version: 1.0.0
platforms: [windows]
metadata:
  hermes:
    tags: [obsidian, knowledge, article, wechat, collect]
    related_skills: [obsidian]
---

# 把文章存进 Obsidian 知识库

## 什么时候用

用户发来的消息里**包含文章链接**时（得到 / 微信公众号 / 抖音），除了正常回答用户，
再顺手把这条原文交给本机的「个人工作台」存档。

判据：消息文本里出现 `http://` 或 `https://`。

用户选择的是「带链接就自动存」，所以**不要问**"要不要存"。存完在回复末尾用一行说明结果即可。

## 怎么做

用 terminal 执行（把占位符换成**用户消息的完整原文**，不是只传链接 ——
工作台会自己从文本里抽链接）：

```
python "E:\4_code\myWorkspace\personal-workbench\scripts\collect-article.py" "<用户消息的完整原文>"
```

输出是一行 JSON，例如：

```
{"code":0,"data":{"title":"06｜问答：孩子沉迷电子产品，怎么办？","collection":"吴军·教育的方法50讲","author":"吴军","platform":"得到","vaultPath":"知识体系/得到/吴军·教育的方法50讲/06｜….md","syncStatus":"synced"}}
```

- `syncStatus = synced`：文件已写进 Obsidian
- `syncStatus = failed`：只入了库，写文件失败（可让用户去工作台知识库页点「重试」）

## 引号 / 换行导致命令失败时

原文里常带引号、换行、中文标点，直接传参容易踩 shell 转义。依次改用下面两种：

```
echo "<原文>" | python "E:\4_code\myWorkspace\personal-workbench\scripts\collect-article.py"
```

```
python "E:\4_code\myWorkspace\personal-workbench\scripts\collect-article.py" < "%TEMP%\msg.txt"
```

**找不到 python 命令时**，用完整路径：
`"C:\Program Files\Python313\python.exe"`

## 结果怎么回复用户

| 情况 | 怎么回 |
|---|---|
| `syncStatus = synced` | 一行：「已存入知识库 → <collection>/<title>」 |
| `syncStatus = failed` | 「已入库，但写 Obsidian 失败，去工作台知识库页点重试」 |
| 连不上工作台 | 「工作台没在运行，这条没存上」—— **不要重试超过 1 次** |
| message 含「没找到链接」 | 消息里其实没有可用链接，**静默忽略**，别打扰用户 |
| message 含「暂不支持这个来源」 | 「这个平台还没支持」 |

## 不要做的事

- **不要自己去抓网页、自己往 Obsidian 写文件。** 抓取和落盘都由工作台负责，
  再做一遍会出现两个不一致的版本。
- 不要改动工作台里的任何数据。
