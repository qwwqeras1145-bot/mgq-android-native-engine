# MGQ Android 原生引擎

用 **Kotlin 从零重写的 NScripter 解释器**，目标是让 NScripter 日式 AVG（以
《勇者大战魔物娘》三章整合版为验证目标）在安卓上**原生运行**——不是模拟器，不是 Wine，
不是兼容层。

> 本仓库**不含任何游戏本体、脚本、图像、音频或汉化资源**。
> 它只包含引擎实现、格式分析工具与移植方法。
> 游戏数据由使用者自己拥有，通过系统文件选择器授权给本应用读取。

---

## 一句话结论

**这条路可行，而且旧结论是错的。**

同一台电脑上的前一个项目（见 [history/](docs/history/)）结论是
「NScripter 用了 `nslua.dll`，开源引擎实现不了，只能靠 Winlator 跑 exe」。

**实测把这条结论推翻了**：对解包后的 382,325 行脚本全文 + 原始字节流做两次独立扫描，
搜索 `lua` / `nslua` / `luac` / `.lua`——

> **Lua 引用次数 = 0。**

`nslua.dll` 只是引擎发行包附带的可选扩展，游戏**根本没用到**。
战斗、图鉴、技能统计全部用 NScripter 自己的 `defsub` 宏实现：

```
defsub name / vspl / cspl / damage / damage_nobr / skillname /
       sean_change / count / screen_clear / screen_clear2 /
       screen_vanish / screen_appear / hseanwave / hseanvoice /
       skillcount1 / skillcount2 / skillcount3
```

**所以不需要 Lua 运行时，原生实现完全可行。**

---

## 当前状态（诚实披露）

| 模块 | 状态 | 依据 |
|---|---|---|
| `nscript.dat` 解码器 | ✅ 完成 | 对 9,736,822 字节真数据解码，sha256 与原厂工具产出**逐字节一致** |
| 脚本解析器 | ✅ 完成 | 真脚本 **441,712** 条指令、9,771 标签、**UNKNOWN=0**、仅 6 处原文笔误 |
| VM 执行器 | ✅ 可用 | 含阻塞语义、`defsub` 优先分派、间接变量 `%%name` |
| `.nsa` 归档读取 | ⚠️ 部分 | 数据精确定位；文件名尽力恢复（见下） |
| Android 应用层 | ✅ 可编译 | 可在 Android Studio / Gradle 构建出 APK |
| **完整可通玩的 APK** | ❌ **未达成** | 见「已知阻断项」 |

**这是一份引擎实现 + 格式逆向的工程记录，不是成品游戏。**

---

## 引擎架构

核心设计：**引擎核心零 Android 依赖**。

```
core/     纯 Kotlin / JVM —— 解析器、VM、归档读取、格式解码
          ↑ 可以在电脑上直接对真游戏数据跑回归测试
android/  薄壳 —— Canvas 渲染、MediaPlayer 音频、SAF 存储
```

这不是为了好看。NScripter 脚本有 38 万行，**如果引擎依赖 Android，
每次验证都要装 APK、连手机，回归测试根本没法做**。
分离之后，本项目所有「已验证」的结论都是在一台普通电脑上跑出来的。

```
core/src/main/kotlin/mgq/core/
├── script/    NsDecoder（XOR 0x84）、ScriptParser、ExprParser、Commands
├── vm/        Vm（执行器）、Variables（含 %%name 间接变量）
├── archive/   NsaArchive（签名扫描）、AssetResolver
├── platform/  Platform / Audio / Scene —— 平台抽象契约
└── cli/       Main —— 桌面验证工具
```

---

## 快速开始

### 1. 桌面验证（不需要手机、不需要 Android SDK）

```bash
# 结构报告：标签、指令统计、未识别指令、解析问题
./gradlew :core:run --args="dump <已解包的脚本.txt>"

# 解码 nscript.dat 并与参考明文逐字节比对
./gradlew :core:run --args="decoder <nscript.dat> out.txt <参考明文.txt>"

# 检查 .nsa 归档结构
./gradlew :core:run --args="nsa <arc4.nsa> [输出目录]"

# 无头跑 VM，打印对话转写
./gradlew :core:run --args="run <脚本.txt> 5000"
```

### 2. 构建 APK

```bash
# 需要 Android SDK（platform 34 + build-tools 34.0.0）与 JDK 17+
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew :android:assembleDebug
# 产物：android/build/outputs/apk/debug/android-debug.apk
```

### 3. 手机上做

1. 安装 APK
2. 把游戏目录（含 `nscript.dat` 与 `arc*.nsa`）放到手机上，或用 USB 拷贝
3. 打开应用 → 「选择游戏目录」→ 指向游戏根目录
4. 应用会把必要的文件镜像到私有目录（只读一次），之后直接运行

---

## 技术要点

### `nscript.dat` 编码：XOR 0x84

```
raw[i] = dat[i] XOR 0x84
```

等长、无状态、位置无关、常数密钥、**无压缩**。
明文比密文大 382,324 字节，纯粹因为原厂 `NSDEC.EXE` 在 CRT 文本模式下把 `LF` 写成了 `CRLF`——
**那是工具的副作用，不是格式的一部分**。

**验证**：解 9,736,822 字节 → 与原厂工具产出 sha256 完全相同
（`43ea4b72…fcfb39`），且 `encode(decode(x)) == x` 严格成立。

详见 [nscript.dat 编码格式](docs/ref/nscript.dat编码格式.md)。

### `.nsa` 归档：为什么用签名扫描而不是解析索引

**如实说明**：我尝试过三种索引格式假设，每种都能解释一个归档、却在另一个上崩掉。
这些归档由不同版本的打包器产生（文件名槽宽 24 / 28 字节、记录跨距 33 / 37 字节），
`byte[1]` 看起来像条目数，对 `arc4.nsa` 是 2，但实际有 14 个载荷对象。

最终选择：

> **载荷是未压缩、连续存放的，每个资源都有明确魔数。**

所以引擎流式扫描归档、用魔数定位资源，索引只用来**尽力恢复文件名**。

**这是一个有代价的取舍**：

- ✅ 对**数据**精确（区间由签名 + 文件长度界定）
- ⚠️ 对**文件名**尽力（`nameConfidence()` 会报告恢复率）

实测：`arc4.nsa` 找到 14 个对象、首个在 @68（JPEG Exif）；
`arc3.nsa` 找到 46 个、首个在 @279637（BMP）。

### 安卓构建的两个坑

| 坑 | 症状 | 解决 |
|---|---|---|
| 中文路径 | AGP 直接报 `path contains non-ASCII characters` | `android.overridePathCheck=true` |
| 只有 JDK 21 | `jvmToolchain(17)` 找不到工具链 | 用 JDK 21 编译，`jvmTarget` 设 17 |

---

## 指令集覆盖

对目标游戏真脚本（382,325 行）的完整统计：

| 项 | 数量 |
|---|---|
| 不同指令 | 163 |
| 指令调用总数 | 311,935 |
| `defsub` 自定义宏 | 18 |
| 标签 | 9,771 |
| `numalias` 别名 | 1,200 |
| 资源引用 | 30,732 次 / 7,247 个文件 |

引擎已实现解析的指令 **UNKNOWN = 0**。
详见 [脚本指令使用画像](docs/ref/脚本指令使用画像.md) 与
[NScripter 指令语义参考](docs/ref/NScripter指令语义参考.md)（183 个命令，源码级语义）。

### 几个容易写错的语义

| 项 | 错误做法 | 正确做法 |
|---|---|---|
| `lsp2` 坐标 | 左上角 | **图片中心** |
| 精灵 Z 序 | 号大靠前 | **号小靠前** |
| 旋转 | 顺时针 | **逆时针为正** |
| `print 10,3000` | 翻页等待 | **10 号特效持续 3000ms** |
| `return *label` | 忽略参数 | **返回并跳转**（1,672 处） |
| `defsub` 分派 | 先查内建 | **先查 defsub**，行首 `_` 才绕过 |

---

## 已知阻断项

**3 参 `print` 会调用 x86 Win32 DLL**，共 **288 处**：

```
breakup.dll/urb (171)  lngtwave.dll/vwi (73)  breakup.dll/lrB (32)
trvswave.dll/h  (11)   NSFont.dll         (1)
```

这些是 x86 原生 DLL，**Android 无法加载**，必须用 Kotlin 重写等效特效
（画面碎裂、水波纹、字形变换）。当前这些调用会被记录并跳过——不会崩，但特效缺失。

另有 19 处 `mpegplay`、1 处 `avi`（视频播放）需要原生替代。

---

## 目录结构

```
.
├── README.md
├── LICENSE                       MIT（仅覆盖本仓库代码与文档）
├── core/                         引擎核心（纯 Kotlin，零 Android 依赖）
│   ├── build.gradle.kts
│   └── src/main/kotlin/mgq/core/
│       ├── script/               解码器 + 解析器 + 表达式求值
│       ├── vm/                   VM + 变量存储
│       ├── archive/              .nsa 读取 + 资源解析
│       ├── platform/             平台抽象契约
│       └── cli/                  桌面验证工具
├── android/                      Android 应用壳
│   └── src/main/kotlin/io/github/qwwqeras1145/mgq/
│       ├── MainActivity.kt       目录选择
│       ├── GameActivity.kt       游戏画面 + 引擎线程
│       ├── EngineSession.kt      VM 装配 + Canvas 渲染 + 音频
│       └── UriGameStorage.kt     SAF → 本地镜像
└── docs/
    ├── 07-移植过程全记录.md        ★ 完整移植过程（含弯路与失败）
    ├── ref/                      格式规范与指令语义
    └── history/                  旧方案记录（已被推翻，保留对照）
```

---

## 许可与合规

- 本仓库代码与文档：**MIT**（见 [LICENSE](LICENSE)）
- **不包含**任何游戏本体内容，也不分发游戏文件
- 引擎只读取使用者自己拥有的游戏数据，不联网、不上传

---

## 参考

- [Winlator](https://github.com/brunodev85/winlator) —— 旧方案使用的兼容层
- [ONScripter](https://en.wikipedia.org/wiki/NScripter) —— NScripter 的开源复刻（本项目的语义参考来源之一）
