# NScripter 游戏安卓运行方案

把 **NScripter 引擎的 Windows 游戏**搬到安卓手机上运行的完整技术方案与实战记录。

> 本仓库**不含**任何游戏本体、脚本、图像、音频或汉化资源。
> 它只包含分析方法、引擎兼容性知识和自动化工具。
> 详见 [合规边界](docs/05-合规边界.md)。

---

## 一句话结论

**不要移植，用兼容层。**

NScripter 是 2D 视觉小说引擎，Windows 版是 32 位原生 x86 程序，没有 3D 依赖。
在安卓上通过 **Winlator**（Wine + Box86/Box64）可以直接运行原始的 `.exe`，
**不需要修改、反编译或重新打包游戏的任何文件**。

这条路同时绕开了三个坑：

| 坑 | 重新打包 APK 会遇到 | Winlator 路线 |
|---|---|---|
| 版权 | 分发游戏本体 = 侵权 | 游戏始终只在你自己的设备上 |
| `nslua.dll` | 开源引擎不实现 Lua 扩展，战斗/图鉴系统直接废掉 | 原 DLL 原样加载 |
| 4.26 GB 素材 | 塞进 APK 体积失控 | 直接读目录，无需转换 |

---

## 它到底是个什么东西

以本项目的测试目标（一款 NScripter 引擎的商业视觉小说，三章整合版）为例，
对该目录运行 `tools/analyze_port.py` 得到的真实结果：

```
总体积   : 4.26 GB
文件总数 : 1,524

判定结果 : NScripter
命中证据 : file:nscript.dat, ext:.nsa, glob:nscript.*, contains:nslua, contains:nsfont

 .bmp             682     835.83 MB  图像（未压缩位图，最占体积）
 .ogg             312     210.34 MB  音频
 .nsa               5       3.10 GB  NScripter 归档包
 .dll               8     806.50 KB  Windows 动态库
```

关键点：**3.10 GB 的资源全在 `.nsa` 归档里**，`.exe` 只有 1 MB
——说明它是个解释器，游戏逻辑在 `nscript.dat`（编译后的脚本）里。

### 引擎特征速查

| 特征文件 | 引擎 |
|---|---|
| `nscript.dat` + `*.nsa` | NScripter |
| `nslua.dll` / `nsfont.dll` | NScripter **带扩展**（关键分歧点） |
| `data.xp3` / `*.xp3` | KiriKiri |
| `Game.exe` + `RGSS*.dll` + `Data/*.rvdata2` | RPG Maker XP/VX/VX Ace |
| `www/index.html` + `nw.dll` | RPG Maker MV/MZ |
| `*.rpa` + `renpy/` | Ren'Py |
| `UnityPlayer.dll` + `*_Data/` | Unity |

---

## 三种路线对比

| 路线 | 适用 | 原理 | 本方案评价 |
|---|---|---|---|
| **Winlator** | 通用 | Wine 翻译 Win32 API，Box86/Box64 翻译 x86→ARM | ✅ **首选** |
| ONScripter-Plus | 无扩展 DLL 的纯 NScripter | 开源重实现，原生 ARM | ⚠️ 装了 `nslua.dll` 就用不了 |
| 重打包成 APK | — | 需要源码或反编译 | ❌ 侵权且技术上不可行 |

### 为什么 ONScripter 对你大概率没用

ONScripter 是 NScripter 的开源复刻，有安卓版，性能远好于 Winlator。
但它的定位是「**NScripter 的一个子集**」：

- ❌ 不支持 `nslua.dll`（Lua 扩展）→ 战斗数值、图鉴、成就、反省会等系统逻辑全部失效
- ❌ 不支持游戏自带的私有 DLL 插件（本测试目标有 8 个 DLL）
- ✅ 纯对话、分支、存档的日式 AVG 可以跑

测试目标用 `nslua` 实现了完整的战斗系统，所以 **ONScripter 只能跑出个残废版**。

---

## 快速开始

### 1. 先分析你的游戏

```bash
python tools/analyze_port.py "D:\Games\YourGame"
```

输出引擎判定、体量构成、安卓路线和再分发风险提示。加 `--json` 得到机器可读结果。

### 2. 按路线操作

- 🥇 **[方案 A：Winlator 直接运行](docs/03-方案A-Winlator直接运行.md)** ← 推荐
- 🥈 [方案 B：ONScripter 路线评估](docs/04-方案B-ONScripter路线评估.md)

### 3. 了解边界

- [为什么不能重打包成 APK](docs/02-为什么不能重打包APK.md)
- [合规边界与风险](docs/05-合规边界.md)

---

## 目录结构

```
.
├── README.md
├── LICENSE
├── docs/
│   ├── 01-引擎识别与勘察.md        # 怎么判断一个游戏是什么引擎
│   ├── 02-为什么不能重打包APK.md    # 技术与法律双重障碍
│   ├── 03-方案A-Winlator直接运行.md # 推荐方案，含逐步操作
│   ├── 04-方案B-ONScripter路线评估.md
│   ├── 05-合规边界.md              # 能做什么、不能做什么
│   └── 06-移植过程全记录.md        # 完整实战过程与踩坑
└── tools/
    └── analyze_port.py             # 引擎识别 + 可行性分析器
```

---

## 环境要求（安卓侧）

| 项目 | 要求 |
|---|---|
| Android 版本 | 8.0 (API 26) 及以上 |
| CPU 架构 | ARM64 (arm64-v8a) |
| 存储空间 | ≥2 GB 给容器，**外加游戏完整体积**（本例 4.26 GB） |
| 图形 | OpenGL ES 2.0 |

> 建议预留 **游戏体积 × 1.5 + 2 GB**。本例即约 8.5 GB。

---

## 常见追问：能不能直接做成 APK？

**不能，而且是技术上不能。**

NScripter 是**闭源解释器**，没有「编译到安卓」这条路径。
唯一能造出 APK 的方式是把整个游戏塞进安装包：

```
APK = Winlator（数百 MB） + 游戏全部文件（4.26 GB） ≈ 4.6 GB
```

算一下账：

| | 打包 APK | 装 Winlator + 拷文件夹 |
|---|---|---|
| 安装体积 | 4.6 GB 单包 | 数百 MB + 4.26 GB |
| 首次启动 | 解压 4.26 GB，几分钟 | 直接可玩 |
| 改一个设置 | 重新打包重装 4.6 GB | 改一下就行 |
| 更新游戏 | 不可能，整包重做 | 只换变动文件 |
| 换手机 | 重传 4.6 GB | 传文件夹 |

**两步做法得到完全相同的体验，且严格更优。**

### 那你想要的「桌面图标点开就玩」呢？

✅ **已经有了。** Winlator 的快捷方式支持：

> **快捷方式 → ⋮ → Add to Home Screen**

安卓桌面上会出现图标，点一下直接进游戏，不用先开 Winlator。
还能自定义图标和显示名称。

效果跟「装了个 App」没有区别 —— 见
[方案 A · 添加到安卓桌面](docs/03-方案A-Winlator直接运行.md#-添加到安卓桌面)。

---

## License

[MIT](LICENSE) —— 仅覆盖本仓库的文档与工具代码。
不覆盖任何第三方游戏内容，本仓库也不包含此类内容。
