#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""把一段文本（含文章链接）交给本机「个人工作台」存档。

这个是给 Hermes / OpenClaw 这类本机 Agent 用的桥接脚本：Agent 收到微信里的
文章链接后调用它，工作台负责抓取、按课程归档到 Obsidian、并录入知识库。

用法：
    python collect-article.py "我分享给你一个知识红包 https://www.dedao.cn/share/packet?packetId=xxx"
    type 消息.txt | python collect-article.py

输出：工作台返回的 JSON（含 title / collection / vaultPath / syncStatus）。
退出码：0 成功；1 工作台报错或连不上；2 没收到内容。

为什么不直接在 skill 里写 curl：微信分享文案里带引号、换行、中文标点，
在 Windows 上穿过 cmd / PowerShell 两层转义极易出错。脚本走 stdin/argv，
由 Python 负责编码，Agent 不用跟引号搏斗。
"""

import json
import sys
import urllib.error
import urllib.request

ENDPOINT = "http://127.0.0.1:18080/api/v1/collect/agent"


def fail(message, code=1, **extra):
    payload = {"ok": False, "message": message}
    payload.update(extra)
    print(json.dumps(payload, ensure_ascii=False))
    return code


def main():
    text = sys.argv[1] if len(sys.argv) > 1 else sys.stdin.read()
    text = (text or "").strip()
    if not text:
        return fail("没有收到任何内容", 2)

    body = json.dumps({"content": text}).encode("utf-8")
    request = urllib.request.Request(
        ENDPOINT, data=body, headers={"Content-Type": "application/json"}, method="POST")

    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            print(response.read().decode("utf-8", "replace"))
            return 0
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", "replace")
        return fail("工作台返回 HTTP %s" % error.code, 1, httpStatus=error.code, detail=detail)
    except Exception as error:
        return fail("连不上工作台（确认它正在运行）：%s" % error)


if __name__ == "__main__":
    sys.exit(main())
