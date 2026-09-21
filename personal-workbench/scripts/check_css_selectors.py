#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""扫描 CSS 中「注释夹在选择器中间」导致的规则静默失效。

背景
----
CSS 里注释等价于空白。若注释被夹在两个选择器片段之间：

    .favorite-theme-work /* 说明 */.favorite-theme-life /* 说明 *//* 说明 */.favorite-card{...}

浏览器会把这些片段拼成**一个**选择器：

    .favorite-theme-work .favorite-theme-life .favorite-card

结果紧跟其后的那条规则永远匹配不到任何元素 —— 样式**静默全部失效**。
控制台不报错、渲染脚本也查不出来，只有肉眼看截图才发现「卡片没间隙 / 文字贴着边框」。

本脚本用静态扫描把这类病变点全部列出来。

用法
----
    python scripts/check_css_selectors.py [css 或 html 文件路径]

不带参数时默认检查 server/src/main/resources/static/style.css。
传 .html（如 setup.html —— 这个页面样式**内联**、不走 style.css）时只扫其中的
`<style>` 块，其余内容/标签替换成等量空行，报出的行号仍是原文件行号。
（2026-09-19 补：不做抽取的话，`<!doctype html> <html ...>` 会被当成一个超长
「选择器」，长度体检误报 1 处、退出码 1，看着像样式坏了。）
"""

from __future__ import print_function

import io
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_CSS = os.path.join(
    os.path.dirname(HERE), "server", "src", "main", "resources", "static", "style.css"
)

# 构成选择器的字符集合；注释两侧都是这类字符时，说明注释夹在了选择器中间
SELECTOR_CHARS = set(
    "abcdefghijklmnopqrstuvwxyz"
    "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    "0123456789_-.#:[\\](*>+~)"
)

# 单条选择器超过这个长度就值得怀疑（多半是被拼接了）
LONG_SELECTOR_THRESHOLD = 120


def is_selector_char(ch):
    return ch in SELECTOR_CHARS


def scan(src):
    """返回病变点列表 [(行号, 前段, 注释, 后段), ...]"""
    hits = []
    for match in re.finditer(r"/\*", src):
        start = match.start()
        end = src.find("*/", start)
        if end < 0:
            continue

        # 注释前一个非空白字符
        before = start - 1
        while before >= 0 and src[before] in " \t\r\n":
            before -= 1

        # 注释后一个非空白字符
        after = end + 2
        while after < len(src) and src[after] in " \t\r\n":
            after += 1

        if (
            before >= 0
            and after < len(src)
            and is_selector_char(src[before])
            and is_selector_char(src[after])
        ):
            line = src.count("\n", 0, start) + 1
            hits.append(
                (line, src[max(0, start - 60):start], src[start:end + 2], src[after:after + 40])
            )
    return hits


def extract_style_blocks(src):
    """HTML 页面先抽出 <style> 块再扫。

    setup.html 这类页面样式内联，直接整页扫会把 `<!doctype html> <html ...>` 当成
    选择器（长度体检误报）。这里把非 <style> 部分替换成**等量空行**，
    于是报出的行号与开发者在本文件里看到的行号一致。
    返回 (可扫描文本, 是否抽取过)。
    """
    if "<style" not in src.lower():
        return src, False
    out = []
    pos = 0
    for match in re.finditer(r"<style[^>]*>(.*?)</style>", src, flags=re.S | re.I):
        out.append(re.sub(r"[^\n]", " ", src[pos:match.start(1)]))
        out.append(match.group(1))
        pos = match.end(1)
    out.append(re.sub(r"[^\n]", " ", src[pos:]))
    return "".join(out), True


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_CSS
    if not os.path.isfile(path):
        print("找不到文件：%s" % path)
        return 2

    raw = io.open(path, encoding="utf-8").read()
    src, from_html = extract_style_blocks(raw)
    if from_html and not src.strip():
        print("检查文件：%s（HTML 里没有可扫描的 <style> 块）" % path)
        return 0
    print("检查文件：%s%s" % (path, "（HTML：仅扫 <style> 块）" if from_html else ""))

    print("\n== 扫描「注释夹在选择器中间」的病变点 ==\n")
    hits = scan(src)
    for line, pre, comment, post in hits:
        print("行 %d：" % line)
        print("   前段 ...%s" % pre)
        print("   注释   %s" % comment)
        print("   后段   %s..." % post)
        print()
    print("命中 %d 处\n" % len(hits))

    print("== 选择器长度体检（异常长的多半是被拼接了）==")
    # 先把注释整体剥掉再体检：注释正文里同样不含 { }，
    # 若不剔除，正则会把大段注释误当成选择器，产生满屏假阳性。
    stripped = re.sub(r"/\*.*?\*/", " ", src, flags=re.S)
    long_ones = []
    for match in re.finditer(r"([^{}]*)\{([^{}]*)\}", stripped):
        selector = match.group(1).strip().replace("\n", " ")
        if len(selector) > LONG_SELECTOR_THRESHOLD:
            long_ones.append(selector)
            print("  [长 %d] %s" % (len(selector), selector[:160]))
    if not long_ones:
        print("  （无）")

    return 1 if (hits or long_ones) else 0


if __name__ == "__main__":
    sys.exit(main())
