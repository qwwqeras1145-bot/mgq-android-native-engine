# 04 · 方案 B：ONScripter 路线评估

**结论先行：对你的测试目标，这条路走不通。**
但它是唯一"原生安卓"的希望，所以值得把为什么不行的理由记下来。

---

## ONScripter 是什么

[ONScripter](https://en.wikipedia.org/wiki/NScripter) 是 NScripter 的**开源重实现**（GPL）。

- 原始目标：让 NScripter 游戏跑在 Linux / macOS 上
- 后来移植到：Android（ONScripter-Plus 等）、PSP、iOS
- 运行方式：**直接读 `nscript.dat` 和 `*.nsa`，在 ARM 上原生执行**

如果它能用，优势非常明显：

| 对比项 | Winlator | ONScripter |
|---|---|---|
| 执行方式 | x86 指令动态翻译 | **原生 ARM** |
| 启动速度 | 慢（Wine prefix + 翻译） | 快 |
| 内存占用 | 高 | 低 |
| 兼容性 | 几乎全部 | **仅子集** |

问题就在最后一行。

---

## 为什么它跑不了本项目测试目标

### 致命伤：不支持 `nslua.dll`

`nslua` 是 NScripter 的 **Lua 脚本扩展**，让游戏可以用 Lua 写复杂逻辑。

ONScripter 实现的是 **NScripter 的指令集子集**，没有任何 Lua 解释器。

测试目标用 Lua 实现了：

| 系统 | 是否依赖 nslua |
|---|---|
| 对话/分支/存档 | ❌ 纯 NScripter 指令 |
| **战斗系统** | ✅ 伤害、属性、技能全在 Lua |
| **图鉴** | ✅ |
| **成就** | ✅ |
| **反省会** | ✅ |
| 开局全解锁 | ✅ |

**结果**：ONScripter 能进标题画面、能看对话，
**但一进战斗就崩**——而这游戏的主体就是战斗。

### 次要伤：私有 DLL 插件

游戏目录里有 8 个 DLL：

```
NSFont.dll     字体渲染
nslua.dll      Lua 扩展      ← 致命
nsogg2.dll     OGG 音频解码
nspng.dll      PNG 解码
breakup.dll   ?
whirl.dll     ?
trvswave.dll  ?
lngtwave.dll  ?
```

ONScripter 只实现了官方标准的那几个。后 4 个是游戏私有扩展，**完全没有对应实现**。

### 附带伤：非标准 `.nsa` 索引

本项目的 `analyze_port.py` 勘察中发现，这些 `.nsa` 的头部结构是：

```
arc3.nsa  00 0B 00 00 | 01 A5 | 63 68 61 72 61 5C ... ("chara\alice_st16bre.bmp")
arc4.nsa  00 02 00 00 | 00 44 | CF B5 CD B3 5C ... ("系统\m_title_wuyu.jpg")
```

6 字节头 + 紧随其后就是文件名 + NUL + 14 字节元数据。

这与公开的 NSA 规范不完全一致（**索引结构是私有的**）。
ONScripter 用的标准 `Sar` 读取器不一定能正确解析。

### 数据佐证

实际上，测试目标作者自己就在游戏目录里放了：

```
魔物娘系统所用的nscripter解包封包工具\
  ├── nsaarc.exe    解包/封包 .nsa
  ├── nscmake.exe   编译脚本
  ├── nsdec.exe     反编译 nscript.dat
  └── nsout.exe     导出
```

**需要自带一套解包工具**，本身就说明这游戏的资源组织是非标准的。

---

## 那 ONScripter 什么时候有用？

它并非没用，只是适用范围窄：

| 游戏类型 | ONScripter 可行性 |
|---|---|
| 纯文字 AVG（只有对话、选项、CG） | ✅ 很好 |
| 带简单小游戏（用标准 NScripter 指令实现） | ⚠️ 可能可以 |
| 用 `nslua` 扩展 | ❌ 不行 |
| 用自定义 DLL 插件 | ❌ 不行 |
| 有复杂战斗系统的 | ❌ 不行 |

**判断方法**：看游戏目录有没有 `nslua.dll`。

```powershell
# 有输出 = 用不了 ONScripter
Get-ChildItem -LiteralPath "D:\游戏\某游戏" -Filter "nslua*"
```

本项目测试目标有此文件，**因此排除方案 B**。

`analyze_port.py` 会自动做这个判断：

```
 [2] ONScripter-Plus（开源 NScripter 重实现）
     结论 : 仅在不使用扩展 DLL 时可行
     ⚠ 阻碍: 不支持 nslua.dll（Lua 扩展）——战斗系统/图鉴/成就等逻辑会失效
```

---

## 如果一定要试（仅供验证用途）

对于**没有** `nslua.dll` 的 NScripter 游戏：

1. 安装 ONScripter-Plus
2. 把游戏目录（`nscript.dat` + `*.nsa` + `system/`）放进应用指定目录
3. 应用会自动扫描并列出游戏

ONScripter 的目录约定：

```
<ONScripter 数据目录>/
└── <游戏名>/
    ├── nscript.dat
    ├── arc.nsa
    └── ...
```

常见问题：

| 现象 | 原因 |
|---|---|
| 游戏列表不出现 | 目录层级不对，需要一级子目录 |
| 黑屏 | `*.nsa` 索引非标准，读不出来 |
| 文字乱码 | 编码不是 Shift-JIS/GBK 预期值 |
| 中途崩溃 | 命中了未实现的 NScripter 指令 |

---

## 为什么不应自己写一个 ONScripter 分支

理论上可以 fork ONScripter，加上 Lua 支持，做出一个能跑这游戏的安卓原生版本。

**不要这么做：**

1. **工作量**：实现 Lua 绑定 + NScripter 完整指令集 + 私有 DLL 替代，以人年计
2. **结果不确定**：Lua 逻辑与宿主环境的耦合方式未知，需要大量逆向
3. **法律**：ONScripter 是 GPL，一旦分发衍生版就触发 GPL 义务；
   而目标程序的私有 DLL 接口逆向本身就涉及灰色地带
4. **收益为零**：Winlator 已经能跑，且零改动

> **工程判断：能跑就别重写。** 方案 A 已经解决了问题，方案 B 是纯粹的过度工程。

---

## 结论

| 问题 | 答案 |
|---|---|
| ONScripter 能跑这个游戏吗？ | ❌ 不能，`nslua` 是硬阻塞 |
| 值得为它开发一个分支吗？ | ❌ 不值得，工作量巨大且 Winlator 已解决 |
| 这方案记录下来的价值？ | ✅ 避免后人重走这条路 |

**回到 [方案 A](03-方案A-Winlator直接运行.md)。**
