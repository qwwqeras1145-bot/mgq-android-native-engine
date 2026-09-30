#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
analyze_port.py —— Windows 视觉小说 / 2D 游戏「能否搬到安卓」可行性分析器

用途
    扫描一个 Windows 游戏目录，识别它的引擎，然后给出：
      1. 引擎判定及其依据（命中的特征文件）
      2. 目录体量统计（总大小、文件数、扩展名分布）
      3. 安卓端可行路线排序，以及每条路线的具体阻碍点
      4. 是否属于「可自由再分发」的明确提示

设计原则（重要）
    本工具 **只读**。它不复制、不打包、不修改任何游戏文件，
    也不会输出可以用于绕开版权保护的产物。
    它回答的是「怎么在我自己的设备上跑起来」，而不是「怎么把别人的游戏重新发行」。

用法
    python analyze_port.py "D:\\Games\\SomeGame"
    python analyze_port.py "D:\\Games\\SomeGame" --json
    python analyze_port.py "D:\\Games\\SomeGame" --deep     # 额外解析 .nsa/.xp3 索引大小
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from collections import Counter
from pathlib import Path

# --------------------------------------------------------------------------
# 引擎特征库
# --------------------------------------------------------------------------
# 每项: (引擎名, 匹配规则列表, 说明)
#   匹配规则 (kind, value):
#     ("file",     "name")   -> 目录内存在精确文件名（不区分大小写）
#     ("ext",      ".nsa")   -> 存在该扩展名的文件
#     ("glob",     "*.xp3")  -> glob 匹配
#     ("contains", "RGSS")   -> 任意文件名包含该子串

ENGINE_SIGNATURES = [
    {
        "engine": "NScripter",
        "rules": [
            ("file", "nscript.dat"),
            ("ext", ".nsa"),
            ("glob", "nscript.*"),
            ("contains", "nslua"),
            ("contains", "nsfont"),
        ],
        "note": "NScripter 及其衍生版（ONScripter / NScripter+NScripterX）",
    },
    {
        "engine": "KiriKiri",
        "rules": [
            ("ext", ".xp3"),
            ("contains", "kirikiri"),
            ("contains", "krkr"),
        ],
        "note": "KiriKiri / 吉里吉里视觉小说引擎",
    },
    {
        "engine": "RPG Maker XP/VX/VX Ace",
        "rules": [
            ("contains", "RGSS"),
            ("file", "Game.ini"),
            ("glob", "Data/*.rxdata"),
            ("glob", "Data/*.rvdata"),
            ("glob", "Data/*.rvdata2"),
        ],
        "note": "RGSS 系（Ruby 脚本，Windows 原生）",
    },
    {
        "engine": "RPG Maker MV/MZ",
        "rules": [
            ("glob", "www/index.html"),
            ("glob", "www/js/rpg_core.js"),
            ("file", "nw.dll"),
        ],
        "note": "MV/MZ 为 JavaScript + NW.js，天生近似跨平台",
    },
    {
        "engine": "Ren'Py",
        "rules": [
            ("ext", ".rpa"),
            ("glob", "renpy/*.py"),
            ("glob", "lib/py3-windows-*"),
        ],
        "note": "Ren'Py 官方自带安卓打包支持",
    },
    {
        "engine": "Unity",
        "rules": [
            ("file", "UnityPlayer.dll"),
            ("file", "globalgamemanagers"),
            ("glob", "*_Data/globalgamemanagers"),
        ],
        "note": "Unity（无源码无法重打包为安卓）",
    },
    {
        "engine": "SiglusEngine (RealLive)",
        "rules": [("file", "SiglusEngine.exe"), ("contains", "siglus")],
        "note": "Key/VisualArts 系",
    },
    {
        "engine": "Majiro",
        "rules": [("ext", ".arc"), ("contains", "majiro")],
        "note": "Majiro Script Engine",
    },
    {
        "engine": "CatSystem2",
        "rules": [("contains", "cs2"), ("file", "cs2conf")],
        "note": "CatSystem2",
    },
]

# --------------------------------------------------------------------------
# 安卓路线知识库
# --------------------------------------------------------------------------
# key 为引擎名前缀匹配
ANDROID_ROUTES = {
    "NScripter": [
        {
            "route": "Winlator（Wine + Box86/Box64 兼容层）",
            "verdict": "推荐",
            "why": "直接运行原 exe，不需要修改游戏任何文件，也不需要重新打包。"
                   "2D 视觉小说无 3D 压力，兼容性最佳。",
            "requirements": "Android 8.0+ / ARM64 / 约 2GB 空间给容器 + 游戏本身体积",
            "blockers": [
                "需要把整个游戏目录（含 .nsa）拷进手机，注意预留空间",
                "中日文字体需确认 NSFont.dll 能正常加载，否则可能显示方框",
            ],
        },
        {
            "route": "ONScripter-Plus（开源 NScripter 重实现）",
            "verdict": "仅在不使用扩展 DLL 时可行",
            "why": "ONScripter 是 NScripter 的开源复刻，有安卓版，原生运行、性能更好。",
            "requirements": "把 nscript.dat / *.nsa 放到应用指定目录",
            "blockers": [
                "不支持 nslua.dll（Lua 扩展）——战斗系统/图鉴/成就等逻辑会失效",
                "不支持游戏自带的私有 DLL 插件",
                "重新实现引擎会丢失原作的系统功能，属于降级方案",
            ],
        },
    ],
    "KiriKiri": [
        {
            "route": "Kirikiroid2",
            "verdict": "推荐",
            "why": "成熟的安卓 KiriKiri 运行时，直接读 .xp3。",
            "requirements": "Android 5.0+",
            "blockers": ["部分使用私有插件的游戏可能不兼容"],
        },
        {
            "route": "Winlator",
            "verdict": "备选",
            "why": "原版 exe 直跑，兼容性兜底。",
            "requirements": "Android 8.0+ / ARM64",
            "blockers": ["性能开销大于 Kirikiroid2"],
        },
    ],
    "RPG Maker XP/VX/VX Ace": [
        {
            "route": "JoiPlay + RPG Maker 插件",
            "verdict": "推荐",
            "why": "JoiPlay 内置 RGSS 运行时，可直接加载游戏目录。",
            "requirements": "Android 5.0+",
            "blockers": ["部分 RGSS 脚本兼容性不佳，可能报错"],
        },
        {
            "route": "Winlator",
            "verdict": "备选",
            "why": "原 exe 直跑。",
            "requirements": "Android 8.0+ / ARM64",
            "blockers": ["较吃性能"],
        },
    ],
    "RPG Maker MV/MZ": [
        {
            "route": "JoiPlay（NW.js 模式）",
            "verdict": "推荐",
            "why": "MV/MZ 本身就是 HTML5 + JS，天然跨平台。",
            "requirements": "Android 5.0+",
            "blockers": ["需匹配 NW.js 版本"],
        },
        {
            "route": "直接用手机浏览器 / 内置 WebView 打开 www/index.html",
            "verdict": "部分可行",
            "why": "去掉 NW.js 专属 API 调用后可纯网页运行。",
            "requirements": "现代浏览器",
            "blockers": ["需要调整存档与文件读写 API"],
        },
    ],
    "Ren'Py": [
        {
            "route": "Ren'Py 官方安卓打包",
            "verdict": "推荐（需源码）",
            "why": "Ren'Py SDK 自带 Build > Android 流程，可产出正规 APK。",
            "requirements": "需要 .rpy 源码，仅有编译后的 .rpyc 时受限",
            "blockers": ["无源码时无法使用；仅 rpyc 反编译涉及授权问题"],
        },
        {
            "route": "JoiPlay（Ren'Py 插件）",
            "verdict": "备选",
            "why": "不改动游戏即可运行。",
            "requirements": "Android 5.0+",
            "blockers": ["版本兼容性依赖插件更新"],
        },
    ],
    "Unity": [
        {
            "route": "无可行路线",
            "verdict": "不可行",
            "why": "Unity 需要工程源码与对应版本编辑器才能导出安卓包；"
                   "反编译重打包既侵权也不稳定。",
            "requirements": "—",
            "blockers": ["无源码", "涉及著作权问题"],
        },
    ],
}

# 扩展名 -> 用途，用于体量报告
EXT_KIND = {
    ".bmp": "图像（未压缩位图，最占体积）",
    ".png": "图像",
    ".jpg": "图像",
    ".jpeg": "图像",
    ".webp": "图像",
    ".ogg": "音频",
    ".mp3": "音频",
    ".wav": "音频",
    ".mpg": "视频",
    ".mp4": "视频",
    ".avi": "视频",
    ".nsa": "NScripter 归档包",
    ".xp3": "KiriKiri 归档包",
    ".rpa": "Ren'Py 归档包",
    ".dat": "数据文件",
    ".exe": "Windows 可执行文件",
    ".dll": "Windows 动态库",
    ".ini": "配置",
    ".txt": "文本",
    ".ttf": "字体",
    ".otf": "字体",
}

BINARY_CLUTTER = {".git", "__pycache__", "node_modules", ".venv", "System Volume Information"}


# --------------------------------------------------------------------------
# 扫描
# --------------------------------------------------------------------------
def scan(root: Path, deep: bool = False) -> dict:
    root = root.resolve()
    if not root.is_dir():
        raise SystemExit(f"不是有效目录: {root}")

    total = 0
    nfiles = 0
    ext_counter: Counter = Counter()
    ext_bytes: Counter = Counter()
    names_lower: set[str] = set()
    all_names_lower: list[str] = []
    top_entries: list[Path] = []

    try:
        top_entries = sorted(root.iterdir(), key=lambda p: p.name.lower())
    except PermissionError:
        pass

    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if d not in BINARY_CLUTTER]
        for fn in filenames:
            fp = Path(dirpath) / fn
            try:
                size = fp.stat().st_size
            except OSError:
                continue
            total += size
            nfiles += 1
            ext = fp.suffix.lower()
            ext_counter[ext] += 1
            ext_bytes[ext] += size
            low = fn.lower()
            names_lower.add(low)
            all_names_lower.append(low)

    hit_names = set(names_lower)
    hit_names |= {p.name.lower() for p in top_entries}

    def has_ext(e: str) -> bool:
        return ext_counter.get(e, 0) > 0

    def has_contains(sub: str) -> bool:
        sub = sub.lower()
        return any(sub in n for n in all_names_lower)

    def has_glob(pat: str) -> bool:
        try:
            return any(root.glob(pat))
        except Exception:
            return False

    matched: list[dict] = []
    for sig in ENGINE_SIGNATURES:
        evidence = []
        for kind, value in sig["rules"]:
            ok = False
            if kind == "file":
                ok = value.lower() in hit_names
            elif kind == "ext":
                ok = has_ext(value)
            elif kind == "glob":
                ok = has_glob(value)
            elif kind == "contains":
                ok = has_contains(value)
            if ok:
                evidence.append(f"{kind}:{value}")
        if evidence:
            matched.append({"engine": sig["engine"], "note": sig["note"], "evidence": evidence})

    # 打分：命中规则越多越可信
    matched.sort(key=lambda m: len(m["evidence"]), reverse=True)

    top_dirs = []
    for p in top_entries:
        try:
            if p.is_dir():
                s = sum(f.stat().st_size for f in p.rglob("*") if f.is_file())
                top_dirs.append({"name": p.name, "bytes": s})
            else:
                top_dirs.append({"name": p.name, "bytes": p.stat().st_size})
        except OSError:
            continue
    top_dirs.sort(key=lambda d: d["bytes"], reverse=True)

    return {
        "root": str(root),
        "total_bytes": total,
        "file_count": nfiles,
        "extensions": [
            {
                "ext": e or "(无扩展名)",
                "count": c,
                "bytes": ext_bytes[e],
                "kind": EXT_KIND.get(e, ""),
            }
            for e, c in ext_counter.most_common()
        ],
        "engine_candidates": matched,
        "top_entries": top_dirs[:15],
    }


def pick_engine(candidates: list[dict]) -> dict | None:
    if not candidates:
        return None
    return candidates[0]


def routes_for(engine: str | None) -> list[dict]:
    if not engine:
        return []
    for key, routes in ANDROID_ROUTES.items():
        if engine.startswith(key):
            return routes
    return []


def redistribution_risk(info: dict) -> dict:
    """
    粗略判断这个目录看起来是否像「可以随便再分发的自由软件」。
    只做启发式提示，不构成法律意见。
    """
    names = {e["ext"] for e in info["extensions"]}
    has_exe = ".exe" in names
    has_archive = bool({".nsa", ".xp3", ".rpa"} & names)

    if has_exe and has_archive:
        return {
            "level": "高",
            "reason": "目录内同时存在 Windows 可执行文件与引擎归档包，"
                      "这是典型「商业游戏的本地安装副本」形态。",
            "advice": "此类内容属于受著作权保护的商业软件。"
                      "你可以把它装在自己的设备上自己玩，"
                      "但不应把游戏本体或其中的素材上传到任何公开仓库/网盘。",
        }
    return {
            "level": "未知",
            "reason": "未能确认是否为自由许可分发的软件。",
            "advice": "除非你能确认该游戏的授权条款允许再分发，否则不要公开上传游戏本体。",
        }


# --------------------------------------------------------------------------
# 输出
# --------------------------------------------------------------------------
def human_bytes(n: int) -> str:
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if n < 1024 or unit == "TB":
            return f"{n:.2f} {unit}" if unit != "B" else f"{n} B"
        n /= 1024.0
    return f"{n:.2f} TB"


def render_text(info: dict) -> str:
    L: list[str] = []
    add = L.append

    add("=" * 72)
    add(" 安卓运行可行性分析报告")
    add("=" * 72)
    add(f"目标目录 : {info['root']}")
    add(f"总体积   : {human_bytes(info['total_bytes'])}")
    add(f"文件总数 : {info['file_count']:,}")
    add("")

    add("-" * 72)
    add(" 一、引擎判定")
    add("-" * 72)
    cands = info["engine_candidates"]
    if not cands:
        add(" 未能识别出已知引擎。可能是自研引擎、加壳程序，或目录结构不完整。")
        add(" 建议人工检查主 exe 的同级目录，寻找脚本/归档文件。")
    else:
        primary = cands[0]
        add(f" 判定结果 : {primary['engine']}")
        add(f" 引擎说明 : {primary['note']}")
        add(f" 命中证据 : {', '.join(primary['evidence'])}")
        if len(cands) > 1:
            add("")
            add(" 次要候选（可能同时包含多个引擎或识别有歧义）：")
            for c in cands[1:]:
                add(f"   - {c['engine']}  证据: {', '.join(c['evidence'])}")
    add("")

    add("-" * 72)
    add(" 二、体量构成")
    add("-" * 72)
    add(f" {'扩展名':<12}{'数量':>8}{'体积':>14}  用途")
    for e in info["extensions"][:12]:
        add(f" {e['ext']:<12}{e['count']:>8}{human_bytes(e['bytes']):>14}  {e['kind']}")
    add("")
    if info["top_entries"]:
        add(" 体积最大的顶层条目：")
        for t in info["top_entries"][:8]:
            add(f"   {human_bytes(t['bytes']):>12}  {t['name']}")
    add("")

    add("-" * 72)
    add(" 三、安卓路线")
    add("-" * 72)
    eng = cands[0]["engine"] if cands else None
    routes = routes_for(eng)
    if not routes:
        add(" 没有针对该引擎的预置路线，请参考「通用兜底方案」：")
        add("   - Winlator（Android 8+/ARM64）：用 Wine+Box86/Box64 直接跑原 exe。")
        add("   - 这是引擎未知时唯一不改动游戏的通用做法。")
    else:
        for i, r in enumerate(routes, 1):
            add(f" [{i}] {r['route']}")
            add(f"     结论 : {r['verdict']}")
            add(f"     理由 : {r['why']}")
            add(f"     条件 : {r['requirements']}")
            for b in r["blockers"]:
                add(f"     ⚠ 阻碍: {b}")
            add("")

    add("-" * 72)
    add(" 四、再分发风险提示")
    add("-" * 72)
    risk = info["redistribution_risk"]
    add(f" 风险等级 : {risk['level']}")
    add(f" 判断依据 : {risk['reason']}")
    add(f" 建议     : {risk['advice']}")
    add("")

    add("=" * 72)
    add(" 提示：本报告只讨论「如何在自己的设备上运行」，")
    add("       不涉及把游戏本体重新打包或公开发布。")
    add("=" * 72)
    return "\n".join(L)


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(
        description="分析 Windows 游戏目录，判断能否搬到安卓以及走哪条路线。",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    ap.add_argument("game_dir", help="游戏根目录")
    ap.add_argument("--json", action="store_true", help="以 JSON 输出")
    ap.add_argument("--deep", action="store_true", help="保留参数（当前扫描已全量递归）")
    args = ap.parse_args(argv)

    info = scan(Path(args.game_dir), deep=args.deep)
    eng = pick_engine(info["engine_candidates"])
    info["routes"] = routes_for(eng["engine"] if eng else None)
    info["redistribution_risk"] = redistribution_risk(info)

    if args.json:
        print(json.dumps(info, ensure_ascii=False, indent=2))
    else:
        print(render_text(info))
    return 0


if __name__ == "__main__":
    sys.exit(main())
