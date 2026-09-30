# nscript.dat 编码格式（NScripter 编译脚本）

> 逆向对象：`勇者大战魔物娘三章剧情汉化整合版\nscript.dat`
> 逆向方式：**已知明文攻击**（原厂工具产出的 `result.txt` 作为明文）
> 结论：**逐字节 XOR `0x84`，常数密钥、无状态、无密钥流。** 另有一层与编码无关的换行展开，来自 `NSDEC.EXE` 的文本模式输出。

---

## 0. 结论速览

| 问题 | 答案 |
|---|---|
| 加密强度 | **零**。单字节 XOR，密钥就是常数 `0x84` |
| 是否分组/流密码 | 都不是。没有反馈、没有状态机、没有计数器 |
| 密钥 / 种子 | 常数 `0x84`，**无从派生**，全文件唯一定值 |
| 密钥流周期 | 1（等价于"没有密钥流"） |
| 是否状态相关 | **否**。第 i 个输出字节只由第 i 个输入字节决定 |
| 是否改变长度 | 加密层**不改变长度**（9,736,822 → 9,736,822） |
| 为什么 `result.txt` 更大 | `NSDEC.EXE` 写文件时把每个 `LF` 展开成 `CRLF`，多出 382,324 字节 |
| 编码方向是否可逆 | 完全可逆。XOR 自逆；实测 `encode(decode(x)) == x` 逐字节成立 |

一句话：**`nscript.dat` = `nscript.txt` 的每个字节 XOR `0x84`**（NScripter 的既定打包约定）。

> 外部旁证：社区已有对原厂 `nsdec.exe` / `nscmake.exe` 的公开逆向实现（[danmig06/nscript.dat-tool](https://github.com/danmig06/nscript.dat-tool)、[UlyssesWu/NtDec](https://github.com/UlyssesWu/NtDec)），同样指向 `0x84` 这一常数。
> 但本文件的结论**不依赖**这些外部资料：全部证据来自本文件的已知明文的逐字节求解（见 §1、§7）。

---

## 1. 数据与逆向方法

| 角色 | 文件 | 大小 | sha256 |
|---|---|---|---|
| 密文 | `nscript.dat` | 9,736,822 | `baa76f867061ed02dfea6d6388bda540b2fde6443282c5ff584c4b09bc699717` |
| 明文 | `result.txt` | 10,119,146 | `43ea4b72dd19fafa52afc5022f8a238a60da7d9ab1d930f0d3219ac9d2fcfb39` |

明文由原厂工具生成，工具自报身份：

```
nscript.dat を コンバートしました      ← Shift-JIS 进度日志（222 MB）
NSDEC Ver.0.7 2001/2/27
(c) LIQUID SYSTEMS
```

即 NScripter 作者 **LIQUID SYSTEMS** 官方的 `NSDEC.EXE`（解包工具），非第三方魔改，可信度高。

**求解步骤**（脚本 `recon/x4_solve.py`）：

1. 先试 `c ^ 0x84`，发现前 29 字节完全吻合，第 70 字节起出现差异。
2. 观察差异点是 `0A 0A` vs `0D 0A 0D 0A` —— 不是密码学差异，而是换行风格差异。
3. 把 `result.txt` 的 `CRLF` 折回 `LF`，长度立刻变成 9,736,822，与密文等长。
4. 逐位求 `key[i] = c[i] XOR p[i]`，得到 `Counter({132: 9736822})` —— **9,736,822 个字节的密钥全是同一个值**。
5. 用该常数做全量解码，与 `result.txt` **逐字节相同**。

> 关键判断：因为"密钥流"的取值集合只有 `{0x84}` 一个元素，所以**排除了**流密码、位置相关密钥、周期密钥、密钥调度等一切可能；又因为全量 9.7 MB 无一处例外，排除了"部分加密/明文夹杂"。

---

## 2. 精确算法

整条链路是**两层叠加**，必须分开理解，否则无法与 `result.txt` 对齐。

### 第 1 层：真正的编码层（NScripter 引擎关心的就是这一层）

```
raw[i] = dat[i] XOR 0x84          for i in 0 .. len(dat)-1
```

- 逐字节、**等长**、**位置无关**、**无状态**
- `raw` 即 NScripter / ONScripter 引擎实际读到的脚本字节流，**换行是纯 `LF`（0x0A）**
- 本游戏中 `raw` 不含任何 `0x0D` 字节（已全量统计：`raw.count(0x0D) == 0`）
- 文本编码是 **GBK/CP936**（不是原始日文 Shift-JIS）：`raw` 可被 CP936 完整解码，Shift-JIS 解码在中途报错

### 第 2 层：`NSDEC.EXE` 的输出副作用（**不是编码的一部分**）

```
result.txt = raw.replace("\n", "\r\n")
```

- 原因是 Windows CRT 以文本模式 `"w"` 打开输出文件，`fputc('\n')` 被翻译成 `CR LF`
- 证据：`raw` 中 `LF` 出现 382,324 次，`result.txt` 恰好比 `raw` 大 382,324 字节
- 证据：`result.txt` 中 `CR` 与 `LF` 各 382,324 个，且**全部成对**（`result.count("\r\n") == 382324`，无孤立 `CR`）

**因此："把 nscript.dat 解出来" 的标准答案是第 1 层的 `raw`；`result.txt` 是被工具额外做了换行归一化的版本。**

---

## 3. 是否状态相关？密钥如何派生？

| 检验项 | 方法 | 结果 |
|---|---|---|
| 输出是否只依赖输入字节值 | 逐位置求 `c XOR p` 并取集合 | 集合 = `{0x84}`，**单元素** |
| 是否依赖位置 | 按 `i mod 256` 分组统计密钥 | 每组的密钥值都唯一，**无位置依赖** |
| 是否有周期 >1 的密钥 | 密钥流全等 | 周期 = 1 |
| 是否依赖上下文/状态 | 若依赖，`c XOR p` 会随上下文漂移 | 全量无漂移 |
| 密钥来源 | — | **常数，无种子，无派生过程** |

**结论：无状态（stateless）、无密钥调度。** 用任何语言实现都只需要一个 `for` 循环。

---

## 4. 伪代码

### 解码（`nscript.dat` → 脚本）

```
function decodeNscript(dat: ByteArray) -> ByteArray:
    out = ByteArray(dat.size)
    for i in 0 .. dat.size-1:
        out[i] = dat[i] XOR 0x84
    return out                      # 纯 LF 的脚本（引擎真正消费的字节）

# 若要与 NSDEC.EXE 的 result.txt 逐字节一致，再加一步：
function toResultTxt(raw: ByteArray) -> ByteArray:
    return raw.replaceAll([0x0A], [0x0D, 0x0A])
```

### 编码（脚本 → `nscript.dat`）

```
function encodeNscript(script: ByteArray) -> ByteArray:
    # 本游戏的 nscript.dat 是由"纯 LF"脚本文本加密而来；
    # 若手上是 CRLF 文本，先归一化再加密，才能得到同样的字节
    script = script.replaceAll([0x0D, 0x0A], [0x0A])
    out = ByteArray(script.size)
    for i in 0 .. script.size-1:
        out[i] = script[i] XOR 0x84
    return out
```

> **XOR 是自逆运算**：解码函数与编码函数是同一个运算。所谓"逆方向"只体现在换行归一化那一步的选择上。
> 注：NScripter 引擎本身对 `LF` / `CRLF` 都能解析，所以"加密一份带 CRLF 的脚本"同样能跑，只是产生的字节流与本文件不同。

---

## 5. 前 32 字节逐字节对照

输入 `nscript.dat[0:32]`：

```
BF A0 D2 B5 B4 B4 B4 B4 C3 B5 B1 B4 B4 D7 BC B4 B4 A8 B2 B4 B4 C8 B5 B4 B4 B4 B4 8D 8D BF 3F 29
```

输出 `result.txt[0:32]`（本节范围内第 2 层尚未介入，因为前 32 字节没有 `LF`）：

```
3B 24 56 31 30 30 30 30 47 31 35 30 30 53 38 30 30 2C 36 30 30 4C 31 30 30 30 30 09 09 3B BB AD
```

| # | `nscript.dat` | `⊕0x84` | `result.txt` | 字符 |
|---|---|---|---|---|
| 0 | `BF` | `3B` | `3B` | `;` |
| 1 | `A0` | `24` | `24` | `$` |
| 2 | `D2` | `56` | `56` | `V` |
| 3 | `B5` | `31` | `31` | `1` |
| 4 | `B4` | `30` | `30` | `0` |
| 5 | `B4` | `30` | `30` | `0` |
| 6 | `B4` | `30` | `30` | `0` |
| 7 | `B4` | `30` | `30` | `0` |
| 8 | `C3` | `47` | `47` | `G` |
| 9 | `B5` | `31` | `31` | `1` |
| 10 | `B1` | `35` | `35` | `5` |
| 11 | `B4` | `30` | `30` | `0` |
| 12 | `B4` | `30` | `30` | `0` |
| 13 | `D7` | `53` | `53` | `S` |
| 14 | `BC` | `38` | `38` | `8` |
| 15 | `B4` | `30` | `30` | `0` |
| 16 | `B4` | `30` | `30` | `0` |
| 17 | `A8` | `2C` | `2C` | `,` |
| 18 | `B2` | `36` | `36` | `6` |
| 19 | `B4` | `30` | `30` | `0` |
| 20 | `B4` | `30` | `30` | `0` |
| 21 | `C8` | `4C` | `4C` | `L` |
| 22 | `B5` | `31` | `31` | `1` |
| 23 | `B4` | `30` | `30` | `0` |
| 24 | `B4` | `30` | `30` | `0` |
| 25 | `B4` | `30` | `30` | `0` |
| 26 | `B4` | `30` | `30` | `0` |
| 27 | `8D` | `09` | `09` | TAB |
| 28 | `8D` | `09` | `09` | TAB |
| 29 | `BF` | `3B` | `3B` | `;` |
| 30 | `3F` | `BB` | `BB` | GBK 首字节 |
| 31 | `29` | `AD` | `AD` | GBK 次字节 |

解码后的开头（CP936 呈现）：

```
;$V10000G1500S800,600L10000<TAB><TAB>;<中文注释>\r\n
\r\n
goto *define\r\n
\r\n
*game_start\r\n
```

**第 2 层第一次显形的位置是偏移 70**：

| 偏移 | `nscript.dat` | 仅 `⊕0x84` | `result.txt` |
|---|---|---|---|
| 70 | `8E` | `0A` | `0D` ← 新增的 CR |
| 71 | `8E` | `0A` | `0A` |
| 72 | `67` | `67` | `0D` ← 新增的 CR |
| 73 | `6F` | `6F` | `0A` |

即 `raw` 的 `0A 0A` 在 `result.txt` 里变成 `0D 0A 0D 0A`。

---

## 6. Kotlin 实现要点

```kotlin
object NscriptCodec {
    const val XOR_KEY = 0x84

    /** nscript.dat -> 脚本原始字节（纯 LF），引擎消费的就是它 */
    fun decode(dat: ByteArray): ByteArray =
        ByteArray(dat.size) { i -> ((dat[i].toInt() and 0xFF) xor XOR_KEY).toByte() }

    /** 复现 NSDEC.EXE 的 result.txt：LF -> CRLF */
    fun toResultTxt(raw: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(raw.size + raw.size / 16)
        for (b in raw) {
            if (b == 0x0A.toByte()) out.write(0x0D); out.write(b.toInt())
        }
        return out.toByteArray()
    }

    /** 脚本 -> nscript.dat（CRLF 先归一化为 LF） */
    fun encode(text: ByteArray): ByteArray {
        val lf = normalizeEol(text)                  // CRLF -> LF
        return ByteArray(lf.size) { i -> ((lf[i].toInt() and 0xFF) xor XOR_KEY).toByte() }
    }
}
```

注意事项：

1. **务必 `and 0xFF`**。Kotlin 的 `Byte.toInt()` 是符号扩展，负数 XOR 后直接 `toByte()` 虽然低位正确，但中间值语义会误导后续维护者。
2. **字节层面是编码无关的**。XOR 只管字节，不关心 GBK/Shift-JIS。文本解码（GBK）是下一层的事。
3. **解码结果是纯 LF**。若移植层要按行切分，按 `\n` 切即可；不要假设 `\r` 存在。
4. **不要用 Java/Kotlin 的文本模式 Reader 直接读 `nscript.dat`**。先用二进制读完再 XOR；用文本 Reader 会把 `0x8E`/`0x8D` 之类的字节当字符处理，在 GBK 下产生替换字符甚至丢字节。
5. 全文件约 9.7 MB，一次性读入内存完全没问题（Android 上也只需 ~20 MB 峰值），无需流式。

---

## 7. 验证记录

| 项 | 值 |
|---|---|
| 密文 `nscript.dat` sha256 | `baa76f867061ed02dfea6d6388bda540b2fde6443282c5ff584c4b09bc699717` |
| 明文 `result.txt` sha256 | `43ea4b72dd19fafa52afc5022f8a238a60da7d9ab1d930f0d3219ac9d2fcfb39` |
| 本实现解码输出 sha256 | `43ea4b72dd19fafa52afc5022f8a238a60da7d9ab1d930f0d3219ac9d2fcfb39` |
| 结论 | **byte-identical，10,119,146 / 10,119,146 字节全部相同，无分歧偏移** |

三重独立验证：

1. **全量比对**：本实现 decode 结果与 `result.txt` 长度同为 10,119,146，sha256 相同，`==` 比较为 `True`。
2. **反向闭环**：`encode(decode(nscript.dat))` 产出的字节流 sha256 = `baa76f86...9717`，与原始 `nscript.dat` **完全相同**，证明编解码互为严格逆运算（长度 9,736,822 不变，无换行漂移）。
3. **工具复现**：在独立 scratch 目录放置 `nscript.dat` 的副本，重新运行原厂 `NSDEC.EXE`（stdout 重定向到文件，未走管道），再次生成的 `result.txt` 大小 10,119,146、sha256 `43ea4b72...fb39`，与既有 `result.txt` 完全一致 —— 确认已知明文来源可靠、且该过程完全确定性。

参考实现：`D:\deep工作区\recon\nsdec_ref.py`（`python nsdec_ref.py --verify <nscript.dat> result.txt`）。

---

## 8. 歧义与边界（诚实声明）

1. **32 个密文字节值在本文件中从未出现**，因此它们的映射不被已知明文约束：

   ```
   7B  80 81 82 83 84 85 86 87 88 89 8A 8B 8C 8F
   90 91 92 93 94 95 96 97 98 99 9A 9B 9C 9D 9E 9F  FB
   ```

   换算到明文侧，缺的正好是 `0x00–0x08`、`0x0B–0x0F`、`0x14–0x1B`、`0x7F` —— 即除 `TAB(09)`、`LF(0A)` 之外的全部 C0 控制字符与 `DEL`，非常符合"这是一个纯文本脚本文件"的预期。也就是说：**纯文本里本来就不会出现这些字节**。
   把这 32 个值的映射补成 `c XOR 0x84` 依据的是算法通用性（XOR 是无条件逐字节规则），而非本文件的直接证据。对本游戏的全部数据无任何影响。

2. **`CRLF` 的归属存在两种不可区分的解释**：
   (a) `nscript.dat` 里存的就是纯 LF 脚本，`NSDEC.EXE` 写文件时展开成 CRLF；
   (b) `NSDEC.EXE` 以文本模式**读**文件（CRLF→LF）后 XOR 再以文本模式写回（LF→CRLF），净效果相同。

   两种解释对"编码算法"的结论完全一致（都是 XOR 0x84），只影响对 `NSDEC.EXE` 内部实现的描述。若要严格复现 `result.txt`，两种建模都给出同一答案。

3. **不要把 `result.txt` 当成"引擎读到的字节"**。引擎读的是 XOR 后的纯 LF 字节流；`result.txt` 多了 382,324 个 `CR`。移植时若拿 `result.txt` 去和引擎内存里的脚本比对，会在这 382,324 个位置不匹配。

4. **`魔物娘终章中文版(去乱码).exe` 未做分析**，按任务要求忽略。

---

## 9. 被排除的候选算法族（含否定证据）

| 候选家族 | 判定 | 证据 |
|---|---|---|
| 固定常数 **XOR** | ✅ **成立** | 全量 9,736,822 字节无例外 |
| 固定常数 **加减** | ❌ | `c - 0x84`、`c + 0x84` 全量检验均为 False；XOR 成立即排除加减（除 0x00 外三者互斥） |
| 位置相关密钥 / 流密码 | ❌ | 按 `i mod 256` 分组的密钥值全部唯一，密钥流恒为 `0x84`，周期 = 1 |
| 重复密钥 XOR | ❌ | 密钥流无周期结构，退化为常数 |
| 半字节对解码（两输入字节拼一输出字节） | ❌ | 输入输出**等长**（换行层之外无长度变化），无法是 2→1 或 1→2 的拼装 |
| 表置换 / S-Box 替换 | ❌ | 关系确实"是字节值的函数"，但该函数恰为 `c XOR 0x84`；224 个观测值全部精确吻合 XOR，S-Box 解释是冗余的 |
| 位反转 / 按位旋转 | ❌ | `bitreverse(c) == p` 与半字节交换 `c` 均在 20 万字节内即失败 |
| 长度前缀 / 标志位前缀编码 | ❌ | 无任何长度字段；明文结构（`;$V10000G1500...`）从偏移 0 直接开始，XOR 后即为合法脚本 |
| 状态机 / 上下文自适应 | ❌ | 密钥流不随上下文漂移 |
| 压缩（RLE/LZ） | ❌ | 第 1 层严格等长；看似"膨胀 383 KB"完全是 CRLF 展开所致，与压缩无关 |

---

## 10. 对移植工作的影响

- **脚本解密不构成任何障碍**：三行代码即可，9.7 MB 全量解码在 Android 上是毫秒级。
- 解密后拿到的是 **GBK 编码的 NScripter 中文脚本**（约 10 MB / 38 万行）。真正的移植难点不在编码层，而在 NScripter 指令集与 `nslua.dll` 的实现（见 `docs/04-方案B-ONScripter路线评估.md`）。
- 若将来需要**回写**（例如把修改后的脚本重新打包），直接用 `encode()`：CRLF 归一化为 LF 后 XOR 0x84，实测可完美还原原始 `nscript.dat` 的字节。
