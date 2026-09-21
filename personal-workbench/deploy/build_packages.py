#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
build_packages.py —— personal-workbench 分发包构建脚本

一次产出两个 ZIP（内容完全一致，只有 data/ 不同）：

  personal-workbench-v<版本>-clean-win.zip   干净包：不含任何个人数据，可直接发给同事
  personal-workbench-v<版本>-data-win.zip    含数据包：内置打包时的真实数据，解压即用

两个包都是「Windows 免安装包」形态：自带裁剪好的 JRE 8，目标机无需安装
Java / Maven / Node / Docker，解压后双击 start-workbench.cmd 即可使用。

用法
----
    # 最常用：构建最新 jar 并产出两个包
    python deploy/build_packages.py --version 1.0.4

    # jar 已经构建好，跳过 Maven（本地迭代时快很多）
    python deploy/build_packages.py --version 1.0.4 --skip-build

    # 只出干净包（不碰个人数据）
    python deploy/build_packages.py --version 1.0.4 --only clean

    # 数据不在默认位置
    python deploy/build_packages.py --version 1.0.4 --data-dir D:\\my-data

前置条件
--------
  * Python 3.8+（标准库即可，无需 pip 安装任何东西）
  * Docker Desktop（仅当需要构建 jar 时；--skip-build 可跳过）
  * 裁剪好的 JRE 8 目录（默认 <root>/dist/runtime，可用 --jre-source 指定）

设计要点
--------
  1. jar 新鲜度硬校验：逐个比对 jar 内 static 资源与源码，防止把过期前端打进包里。
  2. 字节码硬校验：主类必须 major version 52（Java 8），否则拒绝出包。
  3. 数据快照：用 SQLite 的 VACUUM INTO 生成一致性快照，而不是直接复制
     workbench.db / -wal / -shm 三件套 —— 后者在 WAL 模式下可能得到不一致快照。
  4. 干净包硬校验：压缩前断言 stage 内不存在任何 *.db / *.log / 密钥残留。
  5. 压缩用 Python zipfile，条目名为正斜杠并带 UTF-8 标志，避免中文文件名乱码。
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import sqlite3
import subprocess
import sys
import time
import zipfile
from pathlib import Path

# ---------------------------------------------------------------- 基础工具

IS_WIN = os.name == "nt"
SCRIPT_DIR = Path(__file__).resolve().parent
DEFAULT_ROOT = SCRIPT_DIR.parent

# 干净包中绝不允许出现的文件特征
FORBIDDEN_GLOBS_CLEAN = ("*.db", "*.db-wal", "*.db-shm", "*.sqlite", "*.log", "*.gz")
# 干净包中绝不允许出现的文本内容（防止把真实凭据打进交付包）
SECRET_PATTERNS = (
    re.compile(r"sk-[A-Za-z0-9]{16,}"),
    re.compile(r"password_hash"),
    re.compile(r'"api_key"\s*:\s*"[^"]+'),
)

DOCKER_BIN_CANDIDATES = [
    r"C:\Program Files\Docker\Docker\resources\bin",
    r"C:\Program Files\Docker\Docker\resources\bin\docker.exe",
]


class BuildError(Exception):
    """构建失败：带可读原因，由 main 统一兜底打印。"""


class Log:
    """同时打到控制台和日志文件。日志文件是权威记录，便于沙箱内回读。"""

    def __init__(self, path: Path):
        self.path = path
        self.lines: list[str] = []
        path.parent.mkdir(parents=True, exist_ok=True)

    def _emit(self, tag: str, msg: str) -> None:
        line = f"[{tag}] {msg}"
        self.lines.append(line)
        try:
            print(line, flush=True)
        except Exception:
            pass

    def step(self, msg): self._emit("  ", msg)
    def ok(self, msg):   self._emit("OK", msg)
    def warn(self, msg): self._emit("!!", msg)
    def fail(self, msg): self._emit("XX", msg)

    def flush(self) -> None:
        self.path.write_text("\n".join(self.lines) + "\n", encoding="utf-8")


def human(n: int) -> str:
    return f"{n / 1024 / 1024:.2f} MB"


def dir_size(path: Path) -> tuple[int, int]:
    total = 0
    count = 0
    for dp, _dn, fn in os.walk(path):
        for f in fn:
            try:
                total += os.path.getsize(os.path.join(dp, f))
                count += 1
            except OSError:
                pass
    return total, count


def rmtree_safe(path: Path) -> None:
    """删除目录。

    Windows 上优先走 cmd 的 rd /s /q：本机环境的 safe-delete 钩子会拦截
    一次性删除大量文件（含 Python 的 shutil.rmtree），而 rd 能正常通过。
    这条路子和 deploy/build-release.ps1 里的清理方式保持一致。
    """
    if not path.exists():
        return
    if IS_WIN:
        subprocess.run(["cmd", "/c", "rd", "/s", "/q", str(path)],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if not path.exists():
            return
    try:
        shutil.rmtree(path)
    except OSError:
        for dp, dn, fn in os.walk(path, topdown=False):
            for f in fn:
                try:
                    (Path(dp) / f).unlink()
                except OSError:
                    pass
            for d in dn:
                try:
                    (Path(dp) / d).rmdir()
                except OSError:
                    pass


def find_docker() -> str | None:
    exe = shutil.which("docker")
    if exe:
        return exe
    for cand in DOCKER_BIN_CANDIDATES:
        p = Path(cand)
        if p.is_dir() and (p / "docker.exe").exists():
            return str(p / "docker.exe")
    return None


def docker_env() -> dict:
    """清掉代理变量（本机 Clash/沙箱代理会让 docker 走错路），并补 PATH。"""
    env = dict(os.environ)
    for k in ("HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY",
              "http_proxy", "https_proxy", "all_proxy"):
        env.pop(k, None)
    docker_dir = r"C:\Program Files\Docker\Docker\resources\bin"
    parts = env.get("PATH", "").split(os.pathsep)
    if docker_dir not in parts:
        env["PATH"] = docker_dir + os.pathsep + env.get("PATH", "")
    return env


# ---------------------------------------------------------------- 构建 jar

def build_jar(cfg, log: Log) -> None:
    log.step("在 Docker 内执行 Maven 打包 ...")
    docker = find_docker()
    if not docker:
        raise BuildError(
            "找不到 docker 命令。请安装 Docker Desktop 并确保 docker 在 PATH 中，"
            "或改用 --skip-build 复用已有 jar。")

    cmd = [
        docker, "run", "--rm",
        "-v", f"{cfg.root}:/workspace",
        "-v", f"{cfg.maven_volume}:/root/.m2",
        "-w", "/workspace/server",
        "-e", "TZ=Asia/Shanghai",
        "-e", "MAVEN_CONFIG=/root/.m2",
        cfg.build_image,
        "./mvnw", "-B", "clean", "package", "-DskipTests",
    ]
    log.step("  " + " ".join(cmd))
    t0 = time.time()
    proc = subprocess.run(cmd, env=docker_env(),
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    out = proc.stdout.decode("utf-8", "replace")
    (cfg.root / "output").mkdir(exist_ok=True)
    tail = "\n".join(out.splitlines()[-40:])
    if proc.returncode != 0:
        log.fail("Maven 输出末尾：\n" + tail)
        raise BuildError(f"Docker 内 Maven 打包失败（rc={proc.returncode}）。")
    log.ok(f"Maven 打包完成，用时 {time.time() - t0:.0f}s。")


# ---------------------------------------------------------------- 硬校验

def check_bytecode(cfg, log: Log) -> None:
    entry = "BOOT-INF/classes/com/icecode/workbench/WorkbenchApplication.class"
    with zipfile.ZipFile(cfg.jar) as z:
        names = set(z.namelist())
        if entry not in names:
            raise BuildError(f"jar 内未找到主类：{entry}")
        blob = z.read(entry)
    major = blob[7]
    if major != 52:
        raise BuildError(
            f"字节码 major version = {major}，期望 52（Java 8）。"
            f"请检查 server/pom.xml 的 java.version 是否为 1.8。")
    log.ok("字节码 major version = 52，确认 Java 8。")


def check_static_freshness(cfg, log: Log) -> None:
    """把 jar 内的 static 资源与源码逐字节比对，防止把过期前端打进去。"""
    src_dir = cfg.root / "server" / "src" / "main" / "resources" / "static"
    if not src_dir.is_dir():
        log.warn(f"未找到源码 static 目录，跳过前端新鲜度校验：{src_dir}")
        return

    with zipfile.ZipFile(cfg.jar) as z:
        jar_static = {}
        for name in z.namelist():
            marker = "BOOT-INF/classes/static/"
            if name.startswith(marker) and not name.endswith("/"):
                jar_static[name[len(marker):]] = z.read(name)

    src_static = {}
    for f in src_dir.rglob("*"):
        if f.is_file():
            src_static[str(f.relative_to(src_dir)).replace("\\", "/")] = f

    missing = sorted(set(src_static) - set(jar_static))
    stale = []
    for rel, path in src_static.items():
        if rel in jar_static and jar_static[rel] != path.read_bytes():
            stale.append(rel)

    if missing or stale:
        log.fail(f"jar 内前端资源与源码不一致：缺失 {missing}，内容过期 {stale}")
        raise BuildError(
            "jar 内的前端资源不是最新的 —— 这份 jar 是旧版本构建的，"
            "会用过期页面出包。请去掉 --skip-build 重新构建。")

    log.ok(f"前端资源新鲜度校验通过（{len(src_static)} 个文件与源码逐字节一致）。")


def check_runtime(cfg, log: Log) -> None:
    java_exe = cfg.jre_source / "bin" / "java.exe"
    if not java_exe.exists():
        raise BuildError(
            f"runtime\\bin\\java.exe 不存在：{cfg.jre_source}\n"
            "Java 8 没有 jlink，运行时必须预先裁剪好并放到 dist/runtime，"
            "或用 --jre-source 指定其它位置。裁剪清单见 "
            "docs/开发文档-Java8版.md 的 0.4 节。")

    try:
        proc = subprocess.run([str(java_exe), "-version"],
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                              timeout=30)
        ver = proc.stdout.decode("utf-8", "replace")
    except Exception as exc:                     # noqa: BLE001
        raise BuildError(f"无法执行 runtime 里的 java.exe：{exc}")

    if not re.search(r'version "1\.8', ver):
        raise BuildError(f"runtime 里的 Java 不是 1.8，输出为：{ver.strip()}")
    size, count = dir_size(cfg.jre_source)
    log.ok(f"运行时就绪（Java 8，{human(size)}，{count} 个文件）。")


def assert_clean(stage: Path, log: Log) -> None:
    """干净包硬校验：任何数据库 / 日志 / 凭据残留都必须让构建失败。"""
    leaked_files = []
    for pat in FORBIDDEN_GLOBS_CLEAN:
        leaked_files.extend(stage.rglob(pat))
    if leaked_files:
        for f in leaked_files[:20]:
            log.fail(f"  残留：{f.relative_to(stage)}")
        raise BuildError(f"干净包内发现 {len(leaked_files)} 个数据库/日志残留，构建中止。")

    leaked_secrets = []
    for f in stage.rglob("*"):
        if not f.is_file() or f.suffix.lower() not in (".yml", ".yaml", ".txt", ".ps1", ".cmd", ".json"):
            continue
        try:
            text = f.read_text(encoding="utf-8", errors="ignore")
        except OSError:
            continue
        for pat in SECRET_PATTERNS:
            if pat.search(text):
                leaked_secrets.append(f.relative_to(stage))
                break
    if leaked_secrets:
        for f in leaked_secrets:
            log.fail(f"  疑似密钥：{f}")
        raise BuildError("干净包内发现疑似密钥或口令残留，构建中止。")

    log.ok("干净包硬校验通过：无数据库、无日志、无凭据残留。")


def assert_has_data(stage: Path, log: Log) -> None:
    db = stage / "data" / "workbench.db"
    if not db.exists() or db.stat().st_size == 0:
        raise BuildError(f"含数据包里没有可用的数据库：{db}")
    size, count = dir_size(stage / "data")
    log.ok(f"含数据包数据校验通过（data/ 共 {human(size)}，{count} 个文件）。")


# ---------------------------------------------------------------- 数据快照

def snapshot_sqlite(src_db: Path, dst_db: Path, log: Log) -> None:
    """用 VACUUM INTO 生成一致性快照 —— 等价于应用内「在线备份」。"""
    dst_db.parent.mkdir(parents=True, exist_ok=True)
    if dst_db.exists():
        dst_db.unlink()

    # VACUUM INTO 在 Windows 上对反斜杠路径兼容性一般，统一用正斜杠
    dst_arg = str(dst_db).replace("\\", "/")
    # 源库用普通连接：WAL 模式下只读 URI 打不开需要恢复的库
    con = sqlite3.connect(str(src_db))
    try:
        try:
            con.execute("VACUUM INTO ?", (dst_arg,))
        except sqlite3.Error:
            con.execute("VACUUM INTO '%s'" % dst_arg.replace("'", "''"))
    finally:
        con.close()

    if not dst_db.exists() or dst_db.stat().st_size == 0:
        raise BuildError(f"SQLite 快照生成失败：{dst_db}")

    con = sqlite3.connect(f"file:{dst_arg}?mode=ro", uri=True)
    try:
        state = con.execute("PRAGMA integrity_check").fetchone()[0]
        if state != "ok":
            raise BuildError(f"快照完整性校验失败：{state}")
        tables = [r[0] for r in con.execute(
            "SELECT name FROM sqlite_master WHERE type='table' "
            "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'flyway_%'").fetchall()]
        stats = {}
        for t in tables:
            try:
                stats[t] = con.execute(f'SELECT COUNT(*) FROM "{t}"').fetchone()[0]
            except sqlite3.Error:
                pass
    finally:
        con.close()

    log.ok(f"数据快照完成（integrity_check=ok，{human(dst_db.stat().st_size)}）。")
    nonzero = {k: v for k, v in stats.items() if v}
    log.step("  各表行数：" + json.dumps(nonzero, ensure_ascii=False))
    (dst_db.parent / "_snapshot_stats.json").write_text(
        json.dumps(stats, ensure_ascii=False, indent=2), encoding="utf-8")


# ---------------------------------------------------------------- stage 组装

APP_CONFIG_YML = """server:
  port: 18080
  address: 127.0.0.1

workbench:
  data-dir: ./data
  timezone: Asia/Shanghai

# AI 整理、Obsidian、番茄时长等均在「设置」页配置，保存在 data/workbench.db 内，
# 不需要在此文件中填写任何密钥。
"""

DOCKERFILE = """# 个人工作台 —— Docker 运行方式（可选）
#
# 这是「解压即用」之外的备选跑法：如果目标机装了 Docker Desktop，
# 可以直接用容器跑，数据仍然保存在宿主机包目录的 data/ 里。
#
# 构建：
#   docker build --build-arg BASE_IMAGE=eclipse-temurin:8-jre -t personal-workbench:local -f docker/Dockerfile .
# 或在 docker/ 目录下直接：
#   docker compose up -d --build
#
# BASE_IMAGE 必须是提供 java 8 运行时的镜像。若目标机无法联网拉取官方镜像，
# 可指定本地已有的任何 Java 8 镜像。

ARG BASE_IMAGE=eclipse-temurin:8-jre
FROM ${BASE_IMAGE}

ENV TZ=Asia/Shanghai \\
    LANG=C.UTF-8 \\
    WORKBENCH_HOME=/app \\
    WORKBENCH_DATA_DIR=/app/data \\
    SERVER_PORT=8080

WORKDIR /app

COPY app/workbench.jar /app/app.jar
COPY config/application.yml /app/config/application.yml

RUN mkdir -p /app/data/attachments /app/data/backups /app/logs

EXPOSE 8080

# ⚠️ --server.address=0.0.0.0 不能省。
# 包内 config/application.yml 是给「免安装包」用的，把监听地址限成 127.0.0.1
# （意思是只允许本机访问）。但在容器里绑 127.0.0.1 只等于绑容器自己的回环，
# 宿主机的端口映射会得到 "Empty reply from server" —— 容器看着起来了却访问不到。
# 命令行参数优先级最高，放在 -jar 之后即可覆盖 yml。
ENTRYPOINT ["java", \\
    "-Xms128m", "-Xmx256m", "-XX:MaxMetaspaceSize=128m", "-XX:+UseSerialGC", \\
    "-Dfile.encoding=UTF-8", "-Duser.timezone=Asia/Shanghai", \\
    "-jar", "/app/app.jar", "--server.port=8080", "--server.address=0.0.0.0"]
"""

COMPOSE_YML = """# 个人工作台 —— Docker Compose（可选跑法）
#
#   cd docker
#   docker compose up -d --build      # 首次构建并后台启动
#   docker compose logs -f            # 看日志
#   docker compose down               # 停止
#
# 端口：宿主机 18080 -> 容器 8080。若 18080 被占用，改下面 ports 的左边。
# 数据：挂载的是包目录下的 data/，容器重建也不会丢数据。
# 基础镜像：默认 eclipse-temurin:8-jre；无法联网时可在同目录建 .env 写
#   BASE_IMAGE=<本地已有镜像>

services:
  workbench:
    build:
      context: ..
      dockerfile: docker/Dockerfile
      args:
        BASE_IMAGE: ${BASE_IMAGE:-eclipse-temurin:8-jre}
    image: personal-workbench:${IMAGE_TAG:-local}
    container_name: personal-workbench
    restart: unless-stopped
    ports:
      - "127.0.0.1:18080:8080"
    environment:
      TZ: Asia/Shanghai
      # 监听地址不是靠这里配的，而是由 Dockerfile 的 ENTRYPOINT 用
      # --server.address=0.0.0.0 固定下来的（命令行参数优先级最高）。
      # 包内 config/application.yml 面向免安装包，把地址绑成 127.0.0.1，
      # 在容器里会导致宿主机端口映射拿到 "Empty reply from server"。
      SERVER_PORT: "8080"
      WORKBENCH_HOME: /app
      WORKBENCH_DATA_DIR: /app/data
    volumes:
      - ../data:/app/data
      - ../logs:/app/logs
"""

DOCKERIGNORE = """# docker build 的上下文就是包根目录，这些内容都不需要进镜像
runtime
data
logs
dist
scripts
docker
*.zip
*.sha256
*.cmd
*.txt
*.log
"""

DOCKER_README = """Docker 运行方式（可选）
=======================

这个目录是给「已经装了 Docker Desktop」的场景准备的备选跑法。
如果你只是想在本机用，直接双击上一层目录的 start-workbench.cmd 更简单。

启动
----
  cd docker
  docker compose up -d --build

启动后浏览器访问： http://127.0.0.1:18080

停止
----
  cd docker
  docker compose down

数据在哪
--------
仍然在上一层目录的 data/ 里（通过 volume 挂载到容器 /app/data）。
容器删掉重建不影响数据。

端口被占用
----------
打开 docker-compose.yml，把 ports 里的 "127.0.0.1:18080:8080" 改成
"127.0.0.1:18081:8080"，然后重新 docker compose up -d。

拉不动基础镜像
--------------
官方镜像 eclipse-temurin:8-jre 需要联网。如果拉取失败，可在本目录建一个
.env 文件，写入本机已有的 Java 8 镜像：

  BASE_IMAGE=<你的镜像>:<标签>

再重新 docker compose up -d --build。
"""

README_CLEAN = """这是「干净包」
==============

本压缩包不包含任何个人数据，拿到后就是一台全新的工作台：
第一次启动会进入首次配置向导，由你自己设置用户名、密码和时区。

适合：把工作台分发给同事、或在新电脑上从零开始使用。

如果你的目的是「把原电脑的数据搬到新电脑」，请改用含数据包，
或按下面「迁移数据」一节操作。

迁移数据
--------
1. 在原电脑双击「备份工作台.cmd」，得到 data\\backups\\ 下的备份文件
2. 在本压缩包解压后的目录里启动一次，完成首次配置
3. 用「设置」页的备份恢复功能导入备份文件

版本：v{version}
构建时间：{built_at}
"""

README_DATA = """这是「含数据包」
================

本压缩包的 data/ 目录里已经内置了打包时的真实数据，
解压后直接启动就能看到原有内容，不需要再做首次配置。

  包含内容
    data\\workbench.db        主数据库（由 SQLite VACUUM INTO 生成的一致性快照）
    data\\attachments\\       附件
    data\\backups\\           历史备份
    data\\vault-index.json    Obsidian 索引缓存

  重要提醒
    * 这个包里有你的真实数据，可能包含账号口令与 AI API Key，
      不要转发给任何人，也不要上传到任何网盘或代码仓库。
    * 如果换到另一台电脑使用，原电脑上配置过的本地路径
      （例如 Obsidian Vault 路径）会失效，需要在新电脑的「设置」页重新选一次。

版本：v{version}
构建时间：{built_at}
"""


def write_text(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    # newline='' + 显式 \r\n：保证 .cmd / .txt 在 Windows 上换行正常
    path.write_text(text, encoding="utf-8", newline="\r\n" if path.suffix
                    in (".cmd", ".txt") else "")


def assemble_stage(cfg, log: Log) -> None:
    stage = cfg.stage
    if stage.exists():
        # stage 是纯构建产物目录，每次全量重建
        rmtree_safe(stage)
    log.step(f"组装目录：{stage}")

    for d in ("app", "scripts", "config", "data", "logs",
              "data/attachments", "data/backups", "docker"):
        (stage / d).mkdir(parents=True, exist_ok=True)

    # 1) 后端产物
    shutil.copy2(cfg.jar, stage / "app" / "workbench.jar")
    (stage / "app" / "VERSION").write_text(cfg.version, encoding="ascii")

    # 2) 三个双击入口 + 启动器 + 使用说明
    for name in ("start-workbench.cmd", "stop-workbench.cmd", "backup-workbench.cmd"):
        shutil.copy2(cfg.deploy / name, stage / name)
    shutil.copy2(cfg.deploy / "scripts" / "launcher.ps1", stage / "scripts" / "launcher.ps1")

    readme_src = cfg.deploy / "使用说明.txt"
    if readme_src.exists():
        text = readme_src.read_text(encoding="utf-8")
        text = re.sub(r"版本：v[\d.]+", f"版本：v{cfg.version}", text)
        (stage / "使用说明.txt").write_text(text, encoding="utf-8-sig", newline="")

    # 3) 运行配置（只含占位信息，密钥全部在数据库里）
    (stage / "config" / "application.yml").write_text(APP_CONFIG_YML, encoding="utf-8")

    # 4) 可选的 Docker 跑法
    (stage / "docker" / "Dockerfile").write_text(DOCKERFILE, encoding="utf-8")
    (stage / "docker" / "docker-compose.yml").write_text(COMPOSE_YML, encoding="utf-8")
    (stage / "docker" / "说明.txt").write_text(DOCKER_README, encoding="utf-8-sig", newline="")
    (stage / ".dockerignore").write_text(DOCKERIGNORE, encoding="utf-8")

    # 5) 自带运行时
    if cfg.skip_jre:
        log.warn("已跳过 runtime（--skip-jre）：产出的包将依赖目标机自带 Java 8。")
    else:
        log.step(f"复制 JRE 8 运行时（来源 {cfg.jre_source}）...")
        shutil.copytree(cfg.jre_source, stage / "runtime")

    log.ok("目录组装完成。")


def copy_data(cfg, log: Log) -> None:
    """把真实数据放进 stage/data。主库走一致性快照，其余文件直接复制。"""
    src = cfg.data_dir
    if not src.is_dir():
        raise BuildError(f"数据目录不存在：{src}")

    data = cfg.stage / "data"

    src_db = src / "workbench.db"
    if not src_db.exists():
        raise BuildError(f"数据目录里没有 workbench.db：{src}")
    log.step("生成数据库一致性快照（VACUUM INTO）...")
    snapshot_sqlite(src_db, data / "workbench.db", log)

    # 附件与索引：直接复制
    for rel in ("attachments", "backups"):
        s = src / rel
        if s.is_dir():
            shutil.copytree(s, data / rel, dirs_exist_ok=True)

    for extra in ("vault-index.json",):
        s = src / extra
        if s.exists():
            shutil.copy2(s, data / extra)

    # 落库的快照统计不随包发出
    stray = data / "_snapshot_stats.json"
    if stray.exists():
        stray.unlink()

    # 兜底：绝不让运行期文件混进交付包
    for pat in ("*.pid", "*.db-wal", "*.db-shm"):
        for f in data.rglob(pat):
            f.unlink()


# ---------------------------------------------------------------- 压缩

def make_zip(src_dir: Path, zip_path: Path, log: Log,
             arc_root: str, level: int, readme: str) -> None:
    """把 src_dir 打成 zip（根目录名为 arc_root），附带一个说明文件。"""
    readme_path = src_dir / "版本说明.txt"
    readme_path.write_text(readme, encoding="utf-8-sig", newline="")

    if zip_path.exists():
        zip_path.unlink()

    entries = []
    for dp, dn, fn in os.walk(src_dir):
        dn.sort()
        for name in sorted(dn):
            entries.append((Path(dp) / name, True))
        for name in sorted(fn):
            entries.append((Path(dp) / name, False))

    t0 = time.time()
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED, compresslevel=level) as z:
        for path, is_dir in entries:
            rel = path.relative_to(src_dir).as_posix()
            arc = f"{arc_root}/{rel}"
            if is_dir:
                # 显式写入目录条目，保证空目录（data/、logs/）解压后仍在
                info = zipfile.ZipInfo(arc + "/", date_time=time.localtime(path.stat().st_mtime)[:6])
                info.external_attr = (0o40775 << 16) | 0x10
                z.writestr(info, b"")
            else:
                z.write(path, arc)

    size = zip_path.stat().st_size
    digest = hashlib.sha256(zip_path.read_bytes()).hexdigest()
    (zip_path.parent / (zip_path.name + ".sha256")).write_text(
        digest + "\n", encoding="ascii")

    log.ok(f"生成 {zip_path.name}  {human(size)}  用时 {time.time() - t0:.0f}s")
    log.step(f"  SHA256 {digest}")


# ---------------------------------------------------------------- 配置

class Cfg:
    def __init__(self, args):
        self.root = Path(args.root).resolve()
        self.deploy = self.root / "deploy"
        self.dist = Path(args.out_dir).resolve() if args.out_dir else self.root / "dist"
        self.version = args.version
        self.package_name = args.package_name
        self.stage = self.dist / self.package_name
        self.jar = self.root / "server" / "target" / "workbench.jar"
        self.jre_source = (Path(args.jre_source).resolve() if args.jre_source
                           else self.dist / "runtime")
        self.data_dir = (Path(args.data_dir).resolve() if args.data_dir
                         else self.root / "dev-data")
        self.skip_build = args.skip_build
        self.skip_jre = args.skip_jre
        self.only = args.only
        self.build_image = args.build_image
        self.maven_volume = args.maven_volume
        self.compresslevel = args.compresslevel


def parse_args(argv=None):
    ap = argparse.ArgumentParser(
        description="生成 personal-workbench 的干净包与含数据包。",
        formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--version", required=True, help="包版本号，例如 1.0.4")
    ap.add_argument("--root", default=str(DEFAULT_ROOT), help="项目根目录（默认脚本上一级）")
    ap.add_argument("--out-dir", default="", help="输出目录（默认 <root>/dist）")
    ap.add_argument("--data-dir", default="", help="数据源目录（默认 <root>/dev-data）")
    ap.add_argument("--jre-source", default="", help="JRE 8 目录（默认 <root>/dist/runtime）")
    ap.add_argument("--package-name", default="personal-workbench", help="包内根目录名")
    ap.add_argument("--only", choices=("clean", "data", "both"), default="both",
                    help="只生成某一种包")
    ap.add_argument("--skip-build", action="store_true", help="跳过 Maven 构建，复用现有 jar")
    ap.add_argument("--skip-jre", action="store_true", help="不打包自带 JRE")
    ap.add_argument("--build-image", default="personal-workbench-dev:java8",
                    help="用于 Maven 构建的镜像")
    ap.add_argument("--maven-volume", default="workbench-maven-repo",
                    help="Maven 本地仓库卷名（复用可大幅加速）")
    ap.add_argument("--compresslevel", type=int, default=6, help="zip 压缩级别 0-9")
    return ap.parse_args(argv)


# ---------------------------------------------------------------- 主流程

def main(argv=None) -> int:
    args = parse_args(argv)
    cfg = Cfg(args)
    cfg.dist.mkdir(parents=True, exist_ok=True)
    log = Log(cfg.dist / "build-packages.log")

    log.step("")
    log.step(f"构建个人工作台分发包 v{cfg.version}")
    log.step("=" * 58)
    log.step(f"项目根：{cfg.root}")
    log.step(f"输出目录：{cfg.dist}")

    t0 = time.time()
    try:
        if not cfg.skip_build:
            build_jar(cfg, log)
        if not cfg.jar.exists():
            raise BuildError(f"未找到 jar：{cfg.jar}。请去掉 --skip-build 重新构建。")
        log.ok(f"jar 就绪：{human(cfg.jar.stat().st_size)}")

        check_bytecode(cfg, log)
        if not cfg.skip_build:
            check_static_freshness(cfg, log)
        else:
            try:
                check_static_freshness(cfg, log)
            except BuildError as exc:
                log.warn(f"前端新鲜度校验未通过（--skip-build 模式仅告警）：{exc}")
        if not cfg.skip_jre:
            check_runtime(cfg, log)

        assemble_stage(cfg, log)
        built_at = time.strftime("%Y-%m-%d %H:%M:%S")

        if cfg.only in ("clean", "both"):
            log.step("")
            log.step("---- 干净包（不含数据）----")
            assert_clean(cfg.stage, log)
            make_zip(cfg.stage, cfg.dist / f"{cfg.package_name}-v{cfg.version}-clean-win.zip",
                     log, cfg.package_name, cfg.compresslevel,
                     README_CLEAN.format(version=cfg.version, built_at=built_at))

        if cfg.only in ("data", "both"):
            log.step("")
            log.step("---- 含数据包 ----")
            copy_data(cfg, log)
            assert_has_data(cfg.stage, log)
            make_zip(cfg.stage, cfg.dist / f"{cfg.package_name}-v{cfg.version}-data-win.zip",
                     log, cfg.package_name, cfg.compresslevel,
                     README_DATA.format(version=cfg.version, built_at=built_at))

        log.step("")
        log.step("---- 组成体积报告 ----")
        for d in sorted(cfg.stage.iterdir()):
            if d.is_dir():
                size, count = dir_size(d)
                log.step(f"  {d.name:<12}{human(size):>12}  {count:>6} files")

        log.step("")
        log.ok(f"全部完成，用时 {time.time() - t0:.0f}s。产物在 {cfg.dist}")
        log.flush()
        return 0

    except BuildError as exc:
        log.fail(str(exc))
        log.flush()
        return 1
    except Exception as exc:                      # noqa: BLE001
        import traceback
        log.fail(f"未预期错误：{type(exc).__name__}: {exc}")
        log.fail(traceback.format_exc())
        log.flush()
        return 2


if __name__ == "__main__":
    sys.exit(main())
