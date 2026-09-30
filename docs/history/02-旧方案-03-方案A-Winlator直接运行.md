# 03 · 方案 A：Winlator 直接运行（推荐）

这是本项目**唯一推荐的方案**。核心思路：

> 不碰游戏文件。在安卓上搭一个 Windows 兼容层，直接跑原来的 `.exe`。

---

## 原理

[Winlator](https://github.com/brunodev85/winlator) 是一个安卓应用，把几套成熟的开源技术拼在一起：

| 组件 | 作用 |
|---|---|
| **Wine 9.2** | 把 Windows API 调用翻译成 Linux 调用 |
| **Box86 / Box64** | 把 x86 指令动态翻译成 ARM 指令 |
| **PRoot** | 无 root 运行完整 Linux 用户态 |
| **Mesa (Turnip/Zink/VirGL)** | GPU 驱动 |
| **CNC DDraw** | DirectDraw → OpenGL（老 2D 游戏的关键） |
| **ALSA** | 音频 |

对 NScripter 这种 **2D + DirectDraw + 无 3D 依赖**的程序，这是最理想的负载：
没有顶点着色器、没有 DX12、没有反作弊，兼容层只要把 GDI/DirectDraw 画对就行。

---

## 前置检查

先明确你的游戏有多大。

```bash
python tools/analyze_port.py "D:\游戏\你的游戏"
```

本项目测试目标的结果：

```
总体积   : 4.26 GB
文件总数 : 1,524
```

### 空间预算

| 项目 | 占用 |
|---|---|
| 游戏本体 | 4.26 GB |
| Winlator 系统镜像 (imagefs) | ~1.5 GB |
| 容器 (Wine prefix) | ~0.5 GB |
| **小计** | **~6.3 GB** |
| 建议预留（含余量） | **≥ 8.5 GB** |

### 设备要求

- ☐ Android 8.0 (API 26) 或更高
- ☐ ARM64 处理器（`arm64-v8a`）—— 2017 年后绝大多数手机满足
- ☐ OpenGL ES 2.0
- ☐ ≥ 8.5 GB 可用存储

> **检查架构**：`adb shell getprop ro.product.cpu.abi` 应输出 `arm64-v8a`。

---

## 第 1 步：在电脑上整理游戏目录

### 1.1 关于路径中的中文（**重要，但位置常常被搞错**）

**真正必须用纯 ASCII 的是「手机上」的路径**，也就是 Wine 最终看到的那个路径。

| 环节 | 中文的影响 |
|---|---|
| 游戏在电脑上的原始位置 | ❌ **无影响** —— Wine 根本看不到它 |
| 拷贝过程的源路径 | ⚠️ 仅影响命令行工具的编码，GUI 拖拽无影响 |
| **手机上游戏所在的目录** | ✅ **决定性** —— 这就是 Wine 里的 `D:\` |

因为容器把 `/sdcard/Download` 映射成 `D:`，所以：

```
手机:  /sdcard/Download/mgq/游戏.exe
Wine:  D:\mgq\游戏.exe          ← 只有这一层必须纯 ASCII
```

**电脑上的目录名可以保持中文，不用动。**

#### 如果要用命令行工具（adb / 脚本）

中文源路径在部分终端编码下会出问题。**不需要复制 4.26 GB**——
建一个目录联接就有纯 ASCII 入口，秒级完成、零额外空间：

```cmd
mklink /J D:\mgq "D:\游戏\勇者大战魔物娘三章剧情汉化整合版"
```

（`/J` 是目录联接，不是符号链接：不需要管理员权限，也不需要开发者模式。
建完 `D:\mgq` 和原目录就是同一份数据，改哪边都一样。）

#### 顺带一提：exe 文件名也是中文

`勇者大战魔物娘三章完全汉化版.exe` —— exe 文件名在 Wine 里**通常**没问题，
但如果桌面图标点开闪退，把 exe 改名成英文（如 `mgq.exe`）是最快的排查手段。
**改名不是改游戏内容**，随时能改回去。

#### ⚠️ 手机侧必须遵守的两条

1. 文件夹名用 **`mgq`**（小写英文），不能是中文
2. 不要放在需要特殊权限的位置，`内部存储/Download/mgq` 最稳

### 1.2 确认 exe 能否在本机跑通

在电脑上双击原 exe，确认：

- ☐ 能正常进标题画面
- ☐ 中文不乱码

如果电脑上都乱码，那是系统区域设置问题，先解决它（控制面板 → 区域 → 非 Unicode 程序语言 → 中文(简体)）。

### 1.3 记录关键文件

```
D:\mgq\
├── 勇者大战魔物娘三章完全汉化版.exe   ← 启动这个
├── nscript.dat
├── arc.nsa / arc1.nsa / arc2.nsa / ...
├── nslua.dll / nsogg2.dll / nspng.dll / NSFont.dll
├── envdata
└── system\ , bgm\ , se\ , effect\ , save\
```

---

## 第 2 步：在手机上装 Winlator

### 2.1 下载

**只从官方 GitHub Releases 下载**：

🔗 https://github.com/brunodev85/winlator/releases

下载最新的 `Winlator_x.y.apk`。

> ⚠️ 第三方站点分发的 APK 可能被篡改或捆绑恶意代码。Winlator 没有 Play 商店版本。

### 2.2 安装

1. 设置 → 应用 → 浏览器/文件管理器 → **允许安装未知应用**
2. 点击 APK 安装
3. 首次启动会请求权限：

| 权限 | 用途 | 必须 |
|---|---|---|
| `WRITE_EXTERNAL_STORAGE` | 保存容器数据和游戏文件 | ✅ |
| `READ_EXTERNAL_STORAGE` | 读取 `.exe` 和游戏资源 | ✅ |
| `MODIFY_AUDIO_SETTINGS` | 音频驱动 | ✅ |

### 2.3 等待初始化

首次启动会解压 `imagefs`（内部 Linux 文件系统），
**耗时 30–90 秒，不要关闭应用**。空间不足会失败。

---

## 第 3 步：把游戏传到手机

### 方式对比

| 方式 | 4.26 GB / 1524 文件 耗时 | 评价 |
|---|---|---|
| **adb push** | 最快，稳定 | ✅ 推荐 |
| USB MTP 拖拽 | 慢，大量小文件易中断 | ⚠️ 可用 |
| SD 卡直插 | 快 | ✅ 有读卡器就用 |
| 局域网 (SMB/FTP) | 中等 | ⚠️ |

### 3.1 推荐：adb push

```bash
# 手机开启 USB 调试后
adb devices

# 推送到 Winlator 的下载目录
adb push "D:\mgq" /sdcard/Download/mgq

# 验证
adb shell du -sh /sdcard/Download/mgq
```

> `.nsa` 单个文件最大 1.6 GB，需确保手机文件系统是 exFAT 或内部存储（FAT32 有 4 GB 单文件上限）。

### 3.2 目标位置

放到手机的 **`Download/mgq`**，后续在 Winlator 里映射为 `D:` 盘。

---

## 第 4 步：创建 Winlator 容器

打开 Winlator → 右上角 `+` → 新建容器。

### 推荐配置

| 设置项 | 推荐值 | 理由 |
|---|---|---|
| **Screen Size** | `800x600` | NScripter 游戏原生多为 640x480/800x600，避免拉伸模糊 |
| **Graphics Driver** | `Turnip`（Adreno）或 `VirGL`（Mali/其他） | 2D 游戏对驱动要求低 |
| **DX Wrapper** | `CNC DDraw` | ⭐ **关键**：NScripter 用 DirectDraw |
| **Box86/Box64 Preset** | `Compatibility` | 32 位程序优先兼容性 |
| **Windows Version** | `Windows XP` | 2013 年的程序，XP 兼容性最好 |
| **Audio Driver** | `ALSA` | 默认 |
| **CPU Cores** | 全部 | |

> **为什么 DX Wrapper 选 CNC DDraw？**
> NScripter 用 DirectDraw 做 2D 位块传输（Blt）。
> DXVK 是给 D3D9/10/11 的，对 DirectDraw 无能为力。
> CNC DDraw 专门把 DirectDraw 翻译成 OpenGL，正是这个场景需要的。

### 屏幕方向的细节

手机是 19.5:9 竖屏，游戏是 4:3。两种选择：

- **容器分辨率设 800x600**，横屏玩 → 左右留黑边，画面最清晰
- 开启 Winlator 的缩放让画面填满 → 会拉伸变形

**建议前者。** 视觉小说文字清晰度远比填满屏幕重要。

---

## 第 5 步：映射驱动器

容器设置 → **Drives** → 添加：

| Drive | 映射到 |
|---|---|
| `D:` | `/sdcard/Download` |

这样游戏路径就是 `D:\mgq\勇者大战魔物娘三章完全汉化版.exe`。

> 如果你按第 1 步改了 ASCII 路径，这里就是 `D:\mgq\...exe`。

---

## 第 6 步：运行

1. 保存容器，回到主界面
2. 进入容器（点击容器图标）
3. 会看到 Wine 的文件浏览器
4. 导航到 `D:\mgq\`，双击 `勇者大战魔物娘三章完全汉化版.exe`

### 首次启动可能较慢

Wine 首次运行要初始化 prefix，可能黑屏 10–30 秒。**不要急着杀进程。**

### 创建快捷方式

在容器里找到 exe，然后：

- **触屏**：双指同时点一下 exe
- **鼠标**：左键单击选中 → `Ctrl` + 左键单击

在弹出菜单里选 **Create Shortcut**。

之后可以在 Winlator 主界面单独配置这个快捷方式（覆盖容器默认值）：

- 屏幕尺寸（`screenSize`）
- 图形驱动（`graphicsDriver`）
- DX Wrapper（`dxwrapper`）
- Box86/Box64 preset
- 输入控制配置（`controlsProfile`）
- 环境变量（`envVars`）
- 启动参数（`execArgs`）

> 快捷方式本质是容器内的一个 `.desktop` 文件（XDG Desktop Entry 格式），
> 覆写项写在 `[Extra Data]` 段里。只写与容器默认值不同的字段。

### ⭐ 添加到安卓桌面

**这一步才是关键。** 在 Winlator 主界面：

**快捷方式 → 菜单（⋮）→ Add to Home Screen**

安卓桌面上就会出现一个图标。**点一下直接进游戏**——不需要先开 Winlator、
再进容器、再找 exe。

效果上等同于「给这个游戏装了一个 App」。

配合快捷方式的独立配置，你还可以：

- 自定义图标（替换容器 icons 目录下 16/32/48/64 px 的图标文件）
- 自定义显示名称（重命名 `.desktop` 文件的文件名，桌面显示的就是它）

> 参考：[Winlator Shortcuts 文档](https://mintlify.wiki/brunodev85/winlator/controls/shortcuts)、
> [Winlator101 快捷方式说明](https://github.com/K11MCH1/Winlator101/blob/main/docs/shortcuts.md)

### 顺带一提：为什么不是「打包成 APK」

这是最常见的追问，答案有两层。

**技术层（决定性）**：NScripter 是**闭源解释器**，不存在「编译到安卓」的产物。
所谓「做成 APK」，实际只能是：

```
APK = Winlator（数百 MB，含压缩的 imagefs）
    + 游戏全部文件（4.26 GB）
    ≈ 4.6 GB 的安装包
```

这在技术上勉强能做（fork Winlator，把资源塞进 `assets/`，首次启动解压），
但代价是：

| 问题 | 后果 |
|---|---|
| 安装包 4.6 GB | 安装极慢，低端机可能直接失败 |
| 首次启动解压 4.26 GB | 等几分钟，且失败要重来 |
| 任何小改动 | 重新打包、重装整个 4.6 GB |
| 更新游戏 | 不可能，只能重做整个包 |
| 复用 | 换手机要重传 4.6 GB |

**而两步做法（装 Winlator + 拷贝文件夹）得到完全相同的体验，
且每一步都可增量更新。** 后者严格优于前者。

**发行层**：那个 4.6 GB 的 APK 里装的就是游戏本体，
产出它等于做了一份游戏副本。这与本项目的边界冲突。

**结论**：Add to Home Screen 已经给到你想要的一切，不需要 APK。

---

## 第 7 步：输入控制

NScripter 游戏主要靠**鼠标左键点击**推进对话。

Winlator 的输入覆盖层（On-screen controls）：

1. 游戏中呼出 Winlator 菜单（侧边栏手势）
2. **Input Controls** → 添加一个 `Mouse` 区域
3. 建议布局：
   - 大区域 = 鼠标移动 + 左键（用于推进对话、点选项）
   - 一个按钮 = `Esc`（呼出游戏菜单）
   - 一个按钮 = `Ctrl`（快进，NScripter 常见快捷键）
   - 一个按钮 = `右键`

> 也可以直接蓝牙/USB 接鼠标，体验最好。

---

## 常见问题排查

### ❌ 双击 exe 无反应 / 闪退

| 可能原因 | 排查 |
|---|---|
| 路径含中文/日文 | 改成纯 ASCII 路径 |
| Windows 版本设太高 | 容器设为 Windows XP |
| 缺 DirectDraw 支持 | DX Wrapper 改 `CNC DDraw` |
| 文件没传完 | `adb shell du -sh` 对比大小 |

查看 Wine 日志：容器设置里开启 **Debug** 或看 `~/.wine/drive_c` 下的日志。

### ❌ 文字全是方框 / 乱码

- NScripter 通常自带 `NSFont.dll` 做字体渲染，理论上不依赖系统字体
- 若仍乱码，是 **Wine 的代码页**问题：
  - 容器设置 → Environment Variables → 加 `LANG=zh_CN.GBK`
  - 或 `LC_ALL=zh_CN.GBK`
- 也可以往 Wine 的 `C:\windows\Fonts\` 放一个中文字体（如 `simhei.ttf`）

### ❌ 没有声音

- 确认容器 Audio Driver = `ALSA`
- 检查手机是否静音/勿扰模式
- NScripter 用 `nsogg2.dll` 解 OGG，通常没问题

### ❌ 画面卡顿

- 2D 游戏本不该卡，问题通常在 Box64 翻译层
- 改 Box86/Box64 Preset 为 `Performance`
- 关掉其他后台应用
- 降低容器分辨率到 `640x480`

### ❌ 存档丢失

NScripter 存档写在游戏目录的 `save\` 下。
确认该目录**可写**（从 PC 拷贝过去的文件权限可能是只读）。

```bash
adb shell chmod -R 777 /sdcard/Download/mgq
```

### ❌ 提示缺少 DLL

某些 NScripter 扩展 DLL 需要 VC++ 运行库。
在 Winlator 容器里用 **Wine Components** 安装 `vcrun2010` / `vcrun6`。

---

## 备选方案：Termux + Box64

不想用 Winlator 的话，可以手工在 Termux 里搭：

```bash
pkg install x11-repo tur-repo
pkg install termux-x11-nightly
pkg install box64 wine
```

然后用 `termux-x11` 起 X 服务器，`box64 wine game.exe`。

**评价**：灵活但配置繁琐，要自己处理显示、音频、输入。
Winlator 本质上是把这些打包好了，**除非你有特殊需求，否则没必要**。

---

## 方案 A 小结

| 维度 | 评价 |
|---|---|
| 合法性 | ✅ 只在自己设备运行自己的副本，不涉及分发 |
| 技术可行性 | ✅ 2D + DirectDraw = 兼容层最擅长的负载 |
| 游戏改动 | ✅ **零改动**，`nslua` 等 DLL 原样加载 |
| 配置复杂度 | ⭐⭐⭐ 中等（一次配好，长期可用） |
| 性能 | ⭐⭐⭐ 够用（2D 无压力） |
| 主要坑 | 中文路径、DX Wrapper 选择、存储空间 |

---

## 下一步

- [04 · 方案 B：ONScripter 路线评估](04-方案B-ONScripter路线评估.md)
- [06 · 移植过程全记录](06-移植过程全记录.md)
