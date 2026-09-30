# NScripter 指令语义参考（MGQ / ONScripter 方言）

> 面向「用 Kotlin 在 Android 上重实现 NScripter 解释器」的精确技术参考。
> 本文只描述**引擎语义**，不含任何游戏代码实现。
> 目标游戏：`勇者大战魔物娘`（NScripter + `nslua.dll` / `nsogg2.dll` / `nspng.dll`，脚本 `nscript.dat`，解密后 382,325 行）。

---

## 0. 本文的证据级别

每一条结论后都标注来源等级，请按下列优先级采信：

| 标记 | 含义 |
|---|---|
| 【源码】 | 直接读 ONScripter C++ 源码（Ogapee 原作，`uyjulian/onscripter` 镜像）得到的行为，含行号级依据。**最高可信度** |
| 【实证】 | 对目标游戏脚本（已解密全文）做逐命令统计与上下文抽查得到的实际用法 |
| 【手册】 | NScripter 官方手册的日文/英文/中文译本（senzogawa 原始参考、insani 英译镜像、kaisernet 汇编版） |
| 【未确认】 | 未能从任何来源确认；**不要凭猜测实现** |

**冲突处理原则：源码 > 手册。** 本文已发现至少两处手册错误（见 §7.1 `skip`、§7.1 `delay`），均已注明。

---

## 1. 引擎与方言

| 项目 | 值 | 来源 |
|---|---|---|
| 目标引擎 | NScripter 2.x（含 Lua 扩展 DLL） | 【实证】 |
| 脚本文件 | `nscript.dat`，逐字节 XOR `0x84` 加密 | 【源码】`ScriptHandler::readScriptSub` |
| 其它加密模式 | mode2 = 按 `{0x79,0x57,0x0d,0x80,0x04}` 滚动 XOR；mode3 = 用 exe 生成的 key table | 【源码】同上 |
| 画布尺寸（MGQ） | 800×600 逻辑分辨率 | 【实证】脚本首行 `;$V10000G1500S800,600L10000` |
| 变量数 / 全局起始 | 10000 个变量，`%1500` 起为全局变量 | 【实证】同上 |
| 资源包 | 5 个 `.nsa`（自研压缩），引擎 `nsa` 指令启用 | 【实证】 |
| 文本编码 | 中文补丁为 GBK/CP936；引擎按 CP932/UTF-8/CP936 自适应 | 【实证】 |

**分辨率与缩放**：脚本使用逻辑分辨率（NScr 默认 640×480，MGQ 为 800×600）。引擎内部用 `screen_ratio1 / screen_ratio2` 把所有坐标**按比例映射**到实际窗口/设备分辨率；也就是说 `lsp x,y` 等坐标不是设备像素，而是「逻辑像素」。【源码】`AnimationInfo::scalePosXY`：

```cpp
pos.x = orig_pos.x * screen_ratio1 / screen_ratio2;
```

---

## 2. 脚本文件格式与解析总则

这一节是重实现的第一道关卡，错在这里后面全错。

### 2.1 行与字符

- 行分隔符 `0x0a`；`0x0d` 在读入时被**丢弃**（CRLF 与 LF 等价）。【源码】`readScriptSub`
- 脚本被一次性读入内存并**一次性建立标签索引**（`labelScript()`），运行期不再扫描全文。【源码】

### 2.2 行首 token 的判定（命令 vs 文本）

`ScriptHandler::readToken()` 是唯一的分词入口，判定规则如下（**按源码顺序**）：

| 行首字符 | 结果 |
|---|---|
| `;` | 整行注释，跳过 |
| `langjp` / `langen`（且与当前语言一致） | 整行注释 |
| 字节 ≥ `0x80`（日文/中文） | **文本行** |
| 数字 `0`–`9` | 文本行 |
| `@` `\` `/` `!` `#` `,` `"` `(` `[` `<` `` ` `` `>` `%` `?` `$` | 文本行 |
| `*` | 标签行，走 `readLabel()` |
| `~`、`:`、行尾 | 单独 token |
| 字母 / `_` | **命令 token**：`[A-Za-z_][A-Za-z0-9_]*`，**统一转小写** |
| 其它 | 打印警告，跳过该字符后重试 |

推论（很重要）：

1. **命令大小写不敏感**（token 被小写化）。【源码】
2. 以 `%`/`$`/`?` 开头的行是**文本**（用于在对话里插值），不是命令。
3. 文本行内出现的 `%var` / `$var` 会被**就地展开成字符串**写进文本缓冲区（`addIntVariable` / `addStrVariable`）。【源码】
4. 文本行内遇到 `@` 或 `\` 会记录「点击等待点」，遇到 `clickstr` 配置的字符也会记录。【源码】

### 2.3 命令行内部

- `:` 分隔同一行内的多条命令（顺序执行）。行首的 `:` 是空操作。【源码】`ONScripter::parseLine`
- `;` 之后到行尾全部是注释。
- **行首 `_` 前缀绕过 defsub**：`_texton` 调用内建 `texton` 而不调用游戏定义的同名用户命令。【源码】

```cpp
if (cmd[0] != '_') { ...在 user_func_hash 中查用户命令，命中则 gosubReal... }
else { cmd++; }        // 剥掉下划线，继续查内建表
```
- **未知命令不致命**：打印 `command [xxx] is not supported yet!!` 后 `skipToken()` 继续在同一行往后解析。【源码】

### 2.4 头部配置行 `;$V...G...S...L...`

【手册】语义：

```
;$V<变量总数>G<全局变量起始号>S<宽>,<高>L<标签数上限>
```

MGQ 实际值：`;$V10000G1500S800,600L10000`

| 字段 | 含义 | 引擎默认 |
|---|---|---|
| `V` | 数值/字符串变量槽总数 | 4096（`variable_range`） |
| `G` | 全局变量起始编号（小于它的算「局部变量」，可被 `reset` 清零） | 200（`global_variable_border`） |
| `S` | 逻辑分辨率 | 640×480 |
| `L` | 标签数上限 | 引擎内部推算 |

【源码】`ScriptHandler::readConfiguration()`：`variable_range = 4096; global_variable_border = 200;`

---

## 3. 标签解析规则（问题 1）

### 3.1 标签定义

- 标签 = 行首 `*` + 名字；名字字符集为字母/数字/`_`。
- 连续多个 `*` 会被折叠：`while (*(buf+1)=='*') buf++;`。【源码】`ScriptHandler::labelScript`
- **标签名在读取时统一转小写**（`readLabel` 内 `if (ch >= 'A' && ch <= 'Z') ch += 'a' - 'A';`），查询时也转小写 → **标签名大小写不敏感**。【源码】
- 标签名在索引里**不含前导 `*`**。
- 重复标签：`findLabel` **从后往前**线性查找（`for (i = num_of_labels - 1; i >= 0; --i)`）→ **最后出现的那一个生效**。【源码】

### 3.2 跳转与返回

| 指令 | 语法 | 精确语义 | 阻塞 |
|---|---|---|---|
| `goto` | `goto *label` | 读一个字符串，**丢掉首字符**（即 `*`）后 `setCurrentLabel()`，直接跳转 | 否 |
| `gosub` | `gosub *label` | 同上，但先压入 `NestInfo{nest_mode=LABEL, next_script=当前行下一位置}` | 否 |
| `return` | `return` 或 `return *label` | 必须在 gosub 上下文内，否则 **`errorAndExit("return: not in gosub")` 直接终止**。弹出栈帧回到调用点；若带参数且以 `*` 开头，则**回到调用点后再跳到该标签** | 否 |
| `jumpf` | `jumpf` | 向前找下一个 `~` 标记行 | 否 |
| `jumpb` | `jumpb` | 向后找上一个 `~` 标记行 | 否 |
| `tablegoto` | `tablegoto %v,*l0,*l1,...` | 按下标跳表，`%v=0` → `*l0` | 否 |
| `skip` | `skip N` | 见 §7.1 | 否 |
| `break *label` | `break *label` | 出 for 循环**并**跳到该标签（等价 goto，手册明确说不推荐） | 否 |

关键实现细节（【源码】`ScriptParser::returnCommand`）：

```cpp
if (!last_nest_info->previous || last_nest_info->nest_mode != NestInfo::LABEL)
    errorAndExit("return: not in gosub");
...
const char* label = script_h.readStr();
if (label[0] != '*') script_h.setCurrent(last_nest_info->next_script);
else                 setCurrentLabel(label + 1);
```

- `goto` 实现只有两行：`setCurrentLabel(script_h.readStr() + 1);` —— **`goto` 的参数必须带 `*`**，因为它无条件丢弃第一个字符。`goto "xxx"`（无 `*`）会丢掉 `x`，得到错误标签名。
- **`return` 支持动态标签表达式**：MGQ 大量使用 `return "*zukan_"+$zlavel`（399 处）做「按变量返回不同标签」的分派。这是**字符串表达式作为标签**的正规用法，不是 hack。
- 标签不存在时 `findLabel` 会 `errorAndExit("Label \"xxx\" is not found.")` —— **致命错误，不是软失败**。

### 3.3 gosub 深度

`NestInfo` 是链表，无硬上限；`for` 与 `gosub` 共用同一条链（`nest_mode` 区分 `LABEL` / `FOR`）。深层递归会耗尽内存。【源码】

---

## 4. 变量系统（问题 2）

### 4.1 三类变量

| 语法 | 类型 | 初值 | 编号范围 |
|---|---|---|---|
| `%N` / `%name` | 数值变量 | `0` | 0 – 4095（默认 4096 个槽位）；超出会**动态扩展**（`extended_variable_data`），实际上不封顶【源码】 |
| `$N` / `$name` | 字符串变量 | `""` | 同上 |
| `?N` | 数组变量 | 0 | `dim` 声明，二维及以上支持 |

**重要**：`%N` 与 `$N` **共用同一个编号空间**（`variable_data[N]` 同时含 `num` 与 `str` 字段）。写 `%5` 和 `$5` 是同一个槽的两个面。【源码】`ScriptHandler::VariableData`

【实证】MGQ 用 `;$V10000G1500...` 把槽位扩到 10000，`numalias` 用到的编号最高超过 1600；`lsp` 精灵号用到 710。

### 4.2 间接寻址（Kotlin 实现必须支持）

- `%%N`：以 `%N` 的**值**作为变量号，再取该数值变量。`%%0` = 「变量号 = %0 的那个数值变量」。
- `$%N`：以 `%N` 的值为变量号，取字符串变量。
- 【实证】MGQ：`split $%sub1,",",$savemonth,...` —— 第一参数是**动态编号的字符串变量**。
- 【手册】这是 NScripter 传「变量引用」给 `defsub` 的标准手法：把变量号传进去，被调用方用 `%%0` / `$%1` 反向解引用。

### 4.3 数组与 `dim`

```
dim ?10[4]          ; 一维，长度 5（下标 0..4）——方括号内是「最大下标」，不是长度
dim ?20[2][3]       ; 二维 3×4
mov  ?0[2][5],20    ; 赋值
movl ?10,0,1,2,3,4  ; 整行批量赋值
movl ?20[1],0,2,4,6 ; 二维按行赋值
```

- `dim` 只能出现在**定义块**（`DEFINE_MODE`），否则 `errorAndExit("dim: not in the define section")`。【源码】
- 【实证】MGQ：`dim ?mon_labo_var[9]` + `movl ?mon_labo_var,0,0,...`。
- 数组下标从 0 开始，`[n]` 表示 n 个间隔 → n+1 个元素。【手册】

### 4.4 `numalias`（问题 6）

```
numalias <名字>,<数值>
```

- 只能是定义块指令（否则 `errorAndExit("numalias: numalias: not in the define section")`）。【源码】
- 作用：把**名字**登记进「数字别名表」，之后脚本里所有出现该名字的地方（数值参数位置）都被替换为那个数字。【源码】`ScriptHandler::addNumAlias`
- **它的本质是「给编号起名」，不是「声明变量」**。`numalias exp,5` 之后，`mov %exp,0` ≡ `mov %5,0`；`if %exp>3` ≡ `if %5>3`。【手册】【实证】
- 别名的值可以是**任意整数**，包括 0、负数、超过变量槽数的值（那样的 `%name` 会走扩展槽）。
- **别名会遮蔽同名命令的「变量名」用法**：MGQ 里 `numalias click,1515`、`numalias skip,1516`、`numalias textspeed,1511`、`numalias savetime,207`、`numalias bgmvol,1513`、`numalias sevol,1514`。这意味着脚本里的 `%click` 是变量 1515。**注意这些名字同时是内建命令名**，所以解析器必须：在**命令位置**按命令处理，在**变量位置**（`%`/`$` 之后）按别名处理。【实证】
- MGQ 有 1200 条 `numalias`。【实证】
- 别名表是**字符串 → int** 的映射，大小写：`readLabel()` 会把别名名小写化 → 别名也大小写不敏感。

### 4.5 `stralias`

```
stralias <名字>,"<字符串>"
```

- 定义块指令。【源码】`stralias: not in the define section`
- 用途：给「文件名 + sprite 处理串」起短名。【手册】示例：
  ```
  stralias man_a0,":a/10,20,2;man_alpha.bmp"
  ld c,man_a0,1          ; 等价于 ld c,":a/10,20,2;man_alpha.bmp",1
  ```
- 【实证】MGQ **完全没有使用** `stralias`（0 次）。

### 4.6 `intlimit`

```
intlimit <变量号>,<最小值>,<最大值>
```
- 越界即被钳制到边界值。【手册】
- MGQ 未使用。

### 4.7 表达式语法（【源码】`parseIntExpression` / `readNextOp` / `parseStr`）

- 运算符：`+` `-` `*` `/` 以及**关键字** `mod`（写作 `%1 mod 2`）。
- **有优先级**：`*` `/` `mod` 的 op 值带 `0x04` 位（`OP_MULT=4, OP_DIV=5, OP_MOD=6`），高于 `+`(2) `-`(3)；`readNextOp` 用这个位做标准优先级归约。【源码】
- 支持圆括号 `( )` 与一元负号。【源码】
- 字符串表达式：`"a"+$b` 之类的 `+` 拼接（`parseStr`）。【源码】
- `#RRGGBB` 可以作为字符串字面量出现在参数位（颜色）。【源码】
- 字面量支持 `0x` 十六进制。【手册】

---

## 5. `if` / `notif` / `for` / `next` / `break`（问题 3）

### 5.1 `if` 的完整语法

```
if <条件> <命令>
if <条件> <命令1>:<命令2>:...
if <条件1> & <条件2> <命令>
if <条件1> | <条件2> <命令>
notif <条件> <命令>
```

**条件形式（仅此三类）**：【源码】`ScriptParser::ifCommand`

1. `fchk "<文件>"` —— 该资源是否被加载过（文件日志）
2. `lchk *label` / `lchk "*label"` / `lchk $var` —— 该标签是否被访问过（标签日志）
3. `<值> <运算符> <值>`
   - 左边是 `%var` 或数组 → **整数比较**
   - 左边是 `$var` 或字符串字面量 → **`strcmp` 字符串比较**

**运算符全集**（源码中逐字符判定）：

| 写法 | 含义 | 备注 |
|---|---|---|
| `=` | 等于 | 单等号即等于 |
| `==` | 等于 | |
| `!=` | 不等于 | |
| `<>` | 不等于 | |
| `>` `<` | 大小 | 字符串时按 `strcmp` 符号 |
| `>=` `<=` | 大于等于 / 小于等于 | 两字符串比较时必须用两字符形式 |

**逻辑组合**：

- `&`（可重复写 `&&`）表示 **AND**；`|`（`||`）表示 **OR**。
- **`&` 与 `|` 不能混用**，混用直接 `errorAndExit("if: using & and | at the same time is not supported.")`。【源码】
- **没有 `and` / `or` / `not` 关键字**；否定请用 `notif`。（用户问题中的 `and`/`or`/`not` 在此方言中**不存在**，标为【未确认/不存在】。）

**条件不成立时的行为**：`return RET_SKIP_LINE;` —— **跳过本行剩余全部内容**（包括 `:` 后面的所有命令）。条件成立时 `RET_CONTINUE`，主循环继续解析本行后续 → 也就是「执行尾随命令」。【源码】

**尾随命令与 `:` 链**：

```
if %x=1 mov %y,2                 ; 条件成立才执行 mov
if %x=1 mov %y,2:add %y,3        ; 条件成立才执行两条
if %zukanon=2 mov %zukanon,1:goto *zukan_commonkaisou0   ; 【实证】MGQ 原样用法
```
注意：**不成立时整行都跳过**，所以 `if ... a:b` 里的 `b` 也受同一条件约束；如果需要「无条件执行 b」，必须另起一行。

**`if` 的单行多条件实例**（【实证】MGQ）：`if %0>=500 & %0<=504 saveon:sub %0,500:cselgoto %0`

**不支持的写法**：`if %x`（无运算符）不会被当作「非零为真」——源码必然要读到运算符；没有运算符时行为【未确认】，重实现时建议报错而不是猜测。

### 5.2 `for` / `next` / `break`

```
for %v=<初值> to <终值> [step <步长>]
    ...
next
break
break *label
```

【源码】`forCommand` / `nextCommand` / `breakCommand` 的精确语义：

- 循环变量**必须是 `%` 数值变量**，否则 `errorAndExit("for: no integer variable.")`。
- 必须写 `=`，必须写 `to`；`step` 缺省为 `1`。
- 初值**立即**赋给循环变量。
- 若循环体一次都不该执行（`step>0 && from>to`，或 `step<0 && from<to`），置 `break_flag = true`，跳过一次。
- `next`：先 `循环变量 += step`，再判断是否越界；越界则弹栈结束循环，否则跳回循环体首。
- `next` 的边界是**闭区间**（`val > to` 才结束，`step` 为正时）。
- **`break_flag` 是全局单标志**：`break` 在 `for` 外使用会 `errorAndExit("break: not in for loop")`；`next` 在 `for` 外同理。
- `break *label`：出循环**并**跳转到该标签（手册明说会留下栈碎片，不推荐）。
- 【实证】MGQ：`for %move=2201 to 2499 step 1` … `next`（双层/多层嵌套均有）。

---

## 6. `defsub` / `getparam` / `getret`（问题 4）

### 6.1 `defsub` 机制

```
*define
defsub mycommand
...
game

*mycommand
getparam %a,%b
...
return
```

**注册**（【源码】`ScriptParser::defsubCommand`）：

```cpp
const char* cmd = script_h.readLabel();
if (cmd[0] >= 'a' && cmd[0] <= 'z') {     // 只接受小写字母开头
    UserFuncHash& ufh = user_func_hash[cmd[0] - 'a'];
    ...
}
```

- **只有首字母为小写 `a`–`z` 的名字会被注册**；其它名字（数字开头、大写开头）被**静默忽略**，不报错。
- 名字是小写化后比较的 → 大小写不敏感。
- 注册表按下发首字母分成 26 条链表，查找是 `strcmp` 精确匹配（不是前缀匹配）。

**分派**（【源码】`ONScripter::parseLine`）：**defsub 优先于内建指令**。

```
行首 token（小写）
 ├─ 若 == 某个已注册 defsub 名 → gosubReal(名字, 下一位置)   ← 跳转到同名 *label
 └─ 否则查内建指令表
行首带 '_' → 剥掉 '_'，跳过 defsub 查找，直接查内建表（用于调用被覆盖的原命令）
```

**参数传递**：`defsub` 的调用是**一次 gosub**，实参并不预先求值；被调用方用 `getparam` 从**调用点的 token 流**里按顺序解析参数。【源码】

**典型范式**（【实证】MGQ 的 `cspl`）：
```
*cspl
getparam %teigi2,%teigi3        ; 接收两个数值参数
for %sub1=%teigi2 to %teigi3
csp %sub1
next
return
```

**为什么游戏要定义 `name` / `damage` / `skillname`**：
`defsub` 是 NScripter 唯一的「用户自定义指令」机制，等价于宏 + 子程序。商业脚本用它把领域概念封装成可读的指令，例如 MGQ：

| 用户命令 | MGQ 中的实现要点 | 用途 |
|---|---|---|
| `name` | `getparam $platename` → `csp 599:print 1` / `lsp 599,":s/...;"+$platename,...:print 1` | 显示角色名牌 |
| `damage` / `damage_nobr` / `damage_nobr2` | `getparam` + 文字/演出 | 战斗伤害数字演出 |
| `skillname` | 技能名演出 | 战斗 UI |
| `count` | `saveoff` + `getparam %finish1,%finish2` + 大量 `if %finish1=N mov %count_xxx,%count_xxx+1` | 统计各种结局次数 |
| `sean_change` | `saveoff` + `getparam $haikei` + `cspl 3,7` + `csp 611` + `bg "bg\"+$haikei+".bmp",7,1500` | 换场景背景 |
| `cspl` / `vspl` | 批量 `csp` / `vsp` | 批量清/显精灵 |
| `screen_clear` / `screen_clear2` / `screen_vanish` / `screen_appear` | `btndef clear` + `barclear` + `cspl`/`vspl` | 清屏/隐藏/显示 UI 层 |
| `hseanwave` / `hseanvoice` | `dwaveloop` 播放音效 | 语音控制 |
| `skillcount1..3` | 统计 | 战斗 |

MGQ 的 `defsub` 全集（共 18 条，脚本第 305118–305135 行）：
`name, vspl, cspl, damage, damage_nobr, damage_nobr2, skillname, sean_change, count, screen_clear, screen_clear2, screen_vanish, screen_appear, hseanwave, hseanvoice, skillcount1, skillcount2, skillcount3`

> ⚠️ **对重实现的直接影响**：`cspl`、`vspl`、`screen_clear`、`screen_clear2`、`screen_vanish`、`screen_appear`、`name` **不是引擎内建指令**，而是上面的 `defsub`。任何把这些名字硬编码成内建命令的实现，都是在「猜游戏的宏展开」，只要换游戏就会错。正确做法：先查 defsub 表，再查内建表。

### 6.2 `getparam`

```
getparam <变量>[,<变量>...]
getparam2 <变量>[,<变量>...]
```

- **必须处于 gosub 上下文**，否则 `errorAndExit("getparam: not in a subroutine")`。【源码】
- 按**目标变量类型**决定从调用点读取什么：
  - 数值/数组变量 → 读一个整数表达式
  - 字符串变量 → 读一个字符串表达式
  - 指针型变量（`i%n` / `s%n` 语法）→ 读一个变量号，实现「按引用传递」
- `getparam2`：额外把**剩余未读取的实参**读掉（用于丢弃多余参数）。【源码】
- 参数个数由脚本自己决定，没有上限检查。

**按引用传递的范式**（【手册】）：
```
defsub myinc
...
*myinc
getparam %0          ; %0 得到「传入变量的编号」
inc %%0              ; 对那个变量 +1
return
```

### 6.3 `getret`

```
getret %var
getret $var
```

【源码】`ONScripter::getretCommand` 只是把内部寄存器 `getret_int` / `getret_str` 拷出来。它的写入者有：

1. `exec_dll` 调用插件后的返回值（NScr 官方语义）。【手册】
2. `textfield` / `layermessage` 的返回值。【手册】
3. **`lrclick` 之后 = 最后一次点击类型**：【源码】`clickCommand`

```cpp
if (lrclick_flag) getret_int = (current_button_state.button == -1) ? 0 : 1;
```

→ **`lrclick` + `getret` 的约定：右键 = 0，左键 = 1。**

【实证】MGQ 的 399 处用法完全符合该约定：
```
print 10,500:lrclick
getret %hanyo1:if %hanyo1=0 return "*zukan_"+$zlavel
```
（左键继续看下一张，右键退回上一层）

> 这是与「DLL 返回值」**同名但不同用途**的用法，重实现时必须两者都支持：`getret` 是一个「上一条命令的返回值」寄存器。

---

## 7. 指令级参考

标注约定：**阻塞**列写「是（原因）/ 否」。所有时间单位均为**毫秒**，所有坐标均为**逻辑像素**（见 §1）。

### 7.1 流程、算术与计时（核心）

#### `mov`
```
mov %var,<数值表达式>
mov $var,"<字符串>"
mov3..mov10 %var,<v1>,...,<vN>
movl ?array,<v1>,<v2>,...        ; 无限个（写满整行）
```
- `%`/数组目标：写入数值（`mov3` 等把值写入**连续的 N 个变量**，从给定变量号开始）。【源码】`movCommand`
- `$` 目标：写入字符串。
- `movl`：源码中 `count = -1`（无限），逐个写入数组，**带 `[i]` 偏移累加**。
- 阻塞：否。

#### `add` / `sub` / `inc` / `dec` / `mul` / `div` / `mod`
```
add %var,<值>          ; %var = %var + 值
add $var,"<字符串>"     ; 字符串拼接（strcat）
sub %var,<值>          ; %var = %var - 值
mul %var,<值>          ; %var = %var * 值
div %var,<值>          ; %var = %var / 值   ← C 的整数除法，向零截断，不是四舍五入
mod %var,<值>          ; %var = %var % 值   ← C 的 %
inc %var               ; %var = %var + 1
dec %var               ; %var = %var - 1
```
【源码】逐个函数体确认：`subCommand` = `val1 - val2`，`mulCommand` = `val1 * val2`，`divCommand` = `val1 / val2`，`modCommand` = `val1 % val2`（C 语义，负数取余结果取决于 C 实现，Android/Kotlin 需与 C 一致）。
- `mod` 也可作表达式运算符：`mov %0,%1 mod 2`。【手册】
- 除数为 0 时是**未定义行为（C 的 SIGFPE）**，源码没有任何保护 →【未确认】，重实现建议返回 0 并记录警告。
- 阻塞：全部否。

#### `itoa` / `itoa2` / `atoi` / `len` / `mid`
```
itoa  $var,<数值>      ; 半角十进制字符串
itoa2 $var,<数值>      ; 全角数字字符串（日文显示用）
atoi  %var,"<字符串>"   ; C 的 atoi，遇非数字停止；不认全角数字
len   %var,"<字符串>"   ; 长度，单位是**字节**（strlen），一个汉字 = 2
mid   $var,<源串>,<起始位置>,<长度>   ; 位置从 0 开始；单位同样是字节
```
【源码】
- `lenCommand`: `script_h.setInt(&pushed, strlen(buf));` → **字节长度**。
- `itoaCommand`: 若名称为 `itoa2` **且当前编码不是 UTF-8**，走 `getStringFromInteger(..., -1)`（全角数字）；否则与 `itoa` 完全相同（`sprintf("%d")`）。
  → **在 UTF-8 模式下 `itoa2` 退化为 `itoa`**，这一条对 Android 端非常关键。
- `atoiCommand`: `atoi(buf)`，**不处理全角数字**。
- 【实证】MGQ：`itoa2` 321 次、`itoa` 50 次、`atoi` 50 次、`len` 6 次、`mid` 2 次。

#### `rnd` / `rnd2`
```
rnd  %var,<上限N>       ; 0 .. N-1
rnd2 %var,<下限>,<上限>  ; 下限 .. 上限（闭区间）
```
- 【手册】`rnd` 的第二参数为 0 时**不改变变量**。
- 【实证】MGQ 高频使用 `rnd2`（2302 次），`rnd` 未使用。
- 阻塞：否。

#### `split`
```
split "<字符串>","<单字符分隔符>",<目标变量1>[,<目标变量2>...]
```
【源码】`ONScripter::splitCommand` 精确语义：
- 分隔符只取字符串的**第一个字符**。
- 逐段切分，段计入后续变量；目标若是数值变量则用 `atoi(段)`，若是字符串变量则整段赋入。
- **按编码的字节宽度推进**（`enc.getBytes()`），不会把双字节字符切断。
- 分隔符本身被吞掉；源串指针推进 `c+1`。
- 【实证】MGQ：
  ```
  split $%sub1,",",$savemonth,$saveday,$savehour,$saveminute,$savetext   ; 存档元数据
  split $savetextget,"(",$sub1,$sub2,$sub4,$sub6,$sub8                   ; 去括号
  ```
  （注意第一参数用了动态变量 `$%sub1`，见 §4.2）
- 阻塞：否。

#### `skip` / `skipoff` / `isskip`
```
skip <行数>
skipoff
isskip %var
```
【源码】`skipCommand`：
```cpp
int val = script_h.readInt();
if (val == 0) val = 1;
int line = current_label_info.start_line + current_line + val;   // 相对「当前标签起始行 + 当前行」
```
- **正数 = 向后（向文件尾）跳**，负数 = 向前跳；`0` 被当作 `1`。
- 跳转基准是**当前标签的起始行**加上标签内偏移，所以 `skip` 只能在本标签范围内安全使用。
- ⚠️ **手册错误**：insani 英文手册写「Can skip forward (-) or backward (+)」，与源码相反。**以源码为准：正数向前（文件尾方向）**。
- `skipoff`：清掉 `SKIP_NORMAL` 位，关闭「跳到下一个选项」的快速略过模式。【源码】
- `isskip %var`：写回当前进度模式 —— **0 = 普通，1 = skip 模式，2 = auto 模式**；ONScripter-EN 在「整页显示模式」下还会返回 **4**。【源码】【手册】
  - 【手册补充】在 kidoku（未读停）模式下，遇到未读句子时返回 0。
- 【实证】MGQ 的典型组合：
  ```
  isskip %sinkouonoff:if %sinkouonoff=1 skip 11
  if %2801=0 skip 16
  ```
- 阻塞：否。

#### `wait` / `delay` / `resettimer` / `waittimer` / `gettimer`
```
wait      <毫秒>     ; 强制等待，点击无效
delay     <毫秒>     ; 等待，点击可跳过；skip 模式下直接略过
resettimer           ; 内部计时器归零
waittimer <毫秒>     ; 等到内部计时器达到该值
gettimer  %var       ; 读取内部计时器（自 resettimer 起的毫秒数）
```
【源码】精确差异：

```cpp
// wait：只有计时器，不接受输入
event_mode = WAIT_TIMER_MODE;                 waitEvent(script_h.readInt());
// delay：计时器 + 输入，可点击跳过，且 skip 模式直接返回
if (skip_mode & SKIP_NORMAL || ctrl_pressed_status) return RET_CONTINUE;
event_mode = WAIT_TIMER_MODE | WAIT_INPUT_MODE; waitEvent(val);
// waittimer：把「绝对时刻」换算成剩余毫秒
int count = script_h.readInt() + internal_timer - SDL_GetTicks(); if (count<0) count=0;
// resettimer：internal_timer = SDL_GetTicks();
// gettimer：SDL_GetTicks() - internal_timer
```
- ⚠️ **手册错误**：insani 英文手册说 `delay` 不可点击跳过 —— 与源码相反。**`delay` 可点击跳过，`wait` 不可**。
- 【手册】`waittimer` 是 NScr 中**最精确**的计时手段；图像效果时长精度远低于它。
- 特例：`!w` / `!d` 是文本内联版本，**参数必须是字面量，不能是变量**；`wait` / `delay` 可以用变量/表达式。【手册】
- 阻塞：`wait` 是（计时）、`delay` 是（计时，可点击/可跳略）、`waittimer` 是（计时）、`resettimer` 否、`gettimer` 否。

#### `game` / `reset` / `end`（问题 8）
```
game        ; 定义块专用：结束定义块，进入游戏
reset       ; 复位游戏
definereset ; 强制整体重读脚本
end         ; 结束游戏并关闭窗口
```

**`game` 的精确语义**（【源码】`gameCommand` + `resetCommand`）：

1. 若当前不在 `DEFINE_MODE` → `errorAndExit("game: not in the define section")`。
2. `current_mode = NORMAL_MODE`（此后 `numalias`/`dim`/`defsub` 等定义块指令会报错）。
3. 补齐 lookback（回想）用的默认图片资源。
4. 初始化文本分页缓冲 `page_list`（环形链表）。
5. **调用 `resetCommand()`**：
   ```cpp
   resetSub();                                  // 复位引擎状态（精灵/按钮/效果等）
   start_page = current_page = &page_list[0];
   clearCurrentPage();                          // 清空当前文本页
   for (i = 0; i < script_h.global_variable_border; i++)
       script_h.getVariableData(i).reset(false);   // ← 只清「局部变量」
   setCurrentLabel("start");                    // ← 跳到 *start
   ```
6. 载入光标资源；若启用 Lua，执行 `LUA_RESET` 回调。

**因此：`game` = 「切到程序模式 → 清空文本页 → 把编号 < global_variable_border 的变量全部清零（数值 0，字符串 ""）→ 跳到 `*start`」。**
**全局变量（编号 ≥ global_variable_border，MGQ 中 ≥1500）不会被清**——这是 NScr 跨会话保存进度的机制。

**MGQ 的实际结构**（【实证】，可作为重实现的验收用例）：
```
第 3 行:  goto *define
第 4 行:  *game_start          ← 程序块其实在文件最前面
...
第 305082 行: *define          ← 定义块在文件末尾
   ... defsub / numalias / setwindow / effect ...
   第 306110 行: game          ← 定义块结束
   第 306117 行: *start        ← game 跳到这里
```
> 也就是说：**定义块可以出现在文件的任何位置，`game` 之后必须能靠 `*start` 标签找到入口**，不能假设「程序块紧跟在 game 之后」。

**`reset` 的语义**：`resetSub()` + 文本页归零 + 清空**局部变量**（`< global_variable_border`）+ 跳到 `*start`。玩家点「重新开始」走的就是它。

**`definereset`**：让引擎重新读取脚本（连定义块一起重来），用于改分辨率等场景。
**`end`**：直接退出进程（`quit(); exit(0);`）。【源码】

#### `caption`
```
caption "<窗口标题>"
```
- 设置窗口标题（引擎内部按当前编码转换）。【源码】`captionCommand`
- 定义块与程序块均可使用。【手册】
- 【实证】MGQ 在定义块用了 1 次。阻塞：否。

#### `getini`（问题 10）
```
getini $var,"<ini文件名>","<section>","<key>"
```
- 【手册】打开 ini 文件读取一项，写入字符串变量。四个参数依次为：结果变量、ini 文件名、section 名、key 名。
- ⚠️ **`getini` 在 ONScripter 中不存在**：ONScripter 的指令表里没有它（已逐条核对 `ONScripter_lut.cpp`，见 §18）。它只在 NScripter 本体中实现。
- 【实证】MGQ 使用 336 次，用作**怪物资料数据库**：
  ```
  getini $name,$mon_labo_mon_ini,"data","name"
  getini $sub1,$mon_labo_mon_ini,"data","monster_y":atoi %monster_y,$sub1
  ```
  可见：ini 文件名本身可以是字符串变量；读回的是字符串；随后用 `atoi` 转数字；`getini` 支持 `:` 链式后续命令。
- 语义细节（找不到 section/key 时返回什么、是否支持注释/引号）→【未确认】，需要 NScripter 本体实测。
- 阻塞：否。

#### `flushout`（NScr 2.48+）
```
flushout <毫秒>
```
- 【手册】一种特殊效果：**以白屏收尾**，因此随后必须立刻载入背景（`bg`）。
- ⚠️ **ONScripter 的指令表中没有 `flushout`**（见 §18）。它只在 NScripter 本体中实现。
- 【实证】MGQ 用了 22 次，典型是 `flushout 1000`（战斗/场景切换的白闪）。
- 阻塞：**是**（它本身就是一个按时长播放的效果）。

#### `checkpage`（Log Mode 相关）
```
checkpage %var,<往前第几页>
```
【源码】`checkpageCommand` 精确语义：
```cpp
int page_no = script_h.readInt();
Page* page = current_page;
while (page != start_page && page_no > 0) { page_no--; page = page->previous; }
if (page_no > 0) setInt(0);   // 走不到那么远 → 不存在
else             setInt(1);   // 能退 N 页 → 存在
```
- 结果：**1 = 该页存在，0 = 不存在**。
- 参数 `N` 是「往前数几页」；**0 表示当前页**（永远存在，手册建议传 ≥1）。【手册】
- 用途：判断还能不能往前翻页（回想模式）。
- 【实证】MGQ 的用法：
  ```
  checkpage %sub1,1
  if %sub1=0 screen_clear : return     ; 已经是第一页 → 清屏返回
  checkpage %sub1,%backlogtotal        ; 这组页数是否都还有
  ```
  （注意 `if ... screen_clear : return` 里空格的存在；`:` 之后的 `return` 同样受条件约束）
- 阻塞：否。

### 7.2 文本与窗口

#### `print`（问题 7）
```
print                      ; 等价于 print 1？——见下
print <效果号>
print <效果号>,<时长毫秒>
print <效果号>,<时长毫秒>,"<遮片图>"
print <自定义效果号>
```
【源码】`printCommand` 全文只有 5 行：

```cpp
EffectLink *el = parseEffect(true);
if (setEffect(el)) return RET_CONTINUE;
while (doEffect(el));
```

**精确语义**：

1. `print` 把**当前累积的屏幕状态**（背景 + 精灵 + 站立图 + 文本窗 + 文本 + bar）**一次性合成并刷新到屏幕**，同时播放指定的切换效果。
2. 效果参数解析（`readEffect`）：
   - `print N` → 效果号 N，时长取该效果注册时的值。
   - `print N,M` → 效果号 N，时长 M 毫秒。
   - `print N,M,"mask.bmp"` → 再指定遮片图。
   - 若只给了效果号且 N 不在 0..255 内 → 打印警告并把 N 改为 0。
3. **效果号 0**：`setEffect()` 立即 `return true` → `print` **立刻返回，不做任何屏幕刷新**（只更新内部缓冲）。【源码】+【手册】「0 = 只画到内存，不画到屏幕」。
4. **效果号 1**：瞬时显示（一帧完成）。
5. **效果号 ≥2**：若是 `effect` 指令注册过的自定义号，用注册的参数；否则视为内建效果号并用后面的时长/遮片。**找不到的效果号会让引擎直接 `exit(-1)`**。【源码】`parseEffect`
6. **阻塞**：`print` 会**一直循环 `doEffect()` 直到效果播放完毕**（每帧刷新 + 处理输入）。所以 `print 10,3000` 会**阻塞约 3 秒**（除非点击/跳过使其提前结束）。
7. 跳过行为：`effect_cut_flag`（`effectcut`）且处于 skip / 按住 Ctrl 时，效果被强制当作 1（瞬时）；否则 skip 模式下时长会被压到 1/10（>100ms 时）。【源码】`setEffect`

**所以 `print 10,3000` 的准确解释是：「用 10 号效果（像素级交叉淡入淡出），耗时 3000 毫秒，把当前画面显示出来」——它与「翻页/等待点击」无关：`print` 不等待点击。真正等待点击的是 `click` / `lrclick` / `@` / `\`。**

【实证】MGQ 的 `print` 用法统计（3124 次）：
| 写法 | 次数 | 含义 |
|---|---|---|
| `print 10,500:lrclick` | 2090 | 500ms 交叉淡入 + 等左右键 |
| `print 10,500` | 659 | 500ms 交叉淡入 |
| `print 3`（瞬时）等单参数 | 182 | 用注册效果 |
| `print 10,1500` | 91 | 1.5s 淡入 |
| `print 3:wait N` | 89 | 瞬时 + 显式等待 |
| `print 10,3000` | 少量 | 3s 淡入 |
| `print 15,2000,"system\\breakup.dll/urb"` | 3 | 遮片/DLL 效果 |

**内建效果号对照表**（【手册】，效果号即列表序号）：

| 号 | 效果 | 号 | 效果 |
|---|---|---|---|
| 0 | 只写内存，不刷新屏幕 | 10 | 像素级交叉淡入（crossfade） |
| 1 | 瞬时显示 | 11–14 | 左/右/上/下 卷动 |
| 2–5 | 左/右/上/下 快门（shutter） | 15 | 遮片淡入（需 `"mask.bmp"`） |
| 6–9 | 左/右/上/下 窗帘（curtain） | 16 / 17 | 马赛克 out / in |
|  |  | 18 | 遮片交叉淡入（很耗 CPU） |

- 自定义效果号从 **2** 开始可用（0/1 有固定含义），范围 2–255。【手册】
- `effect N,<内建号>[,<时长>][,"<遮片>"]` 注册自定义效果号。【手册】

#### `texton` / `textoff` / `textclear` / `br`
```
texton       ; 显示文本窗
textoff      ; 隐藏文本窗
textclear    ; 清空文本内容（== 换新页）
br           ; 在文本中插入换行（不换页）
```
【源码】
- `texton`：`enterTextDisplayMode(); text_on_flag = true;` 若设置了 `windowchip` 精灵则同时显示该精灵。
- `textoff`：`leaveTextDisplayMode(true); text_on_flag = false;`（并按需隐藏 windowchip 精灵）。
- `textclear`：**实现就是 `newPage()`** → 它等同于「翻页」，会重置当前页的文字与坐标，而不只是擦掉字。【源码】
- `br`：`sentence_font.newLine(); current_page->add(0x0a);` → **只换行**，不影响页。【源码】
- 阻塞：全部否。
- 【实证】MGQ：`textoff` 186 次、`texton` 62 次、`textclear` 51 次、`br` 0 次（该游戏用独立精灵排字，不用 `br`）。

#### `setwindow` / `setwindow2` / `setwindow3`
```
setwindow  <文本左上X>,<文本左上Y>,<每行列数>,<行数>,<字宽X>,<字高Y>,<字间距X>,<行间距Y>,
           <默认显示速度>,<粗体0/1>,<阴影0/1>,<窗体颜色#rrggbb>,<窗体左上X>,<窗体左上Y>,<窗体右下X>,<窗体右下Y>
setwindow  <前11个同上>,<窗体图片文件名>,<窗体左上X>,<窗体左上Y>,<窗体右下X>,<窗体右下Y>
setwindow2 #rrggbb | setwindow2 "<图片文件名>"
setwindow3 <与 setwindow 相同的 16 个参数>     ; 区别：不清空 Log/回想 缓冲
```
【手册】要点：
- 前 11 个数值依次是：文本区左上 X/Y、**文本列数**、**文本行数**、字宽 X、字高 Y、字间距 X、字间距 Y、默认单字显示速度（ms）、粗体开关、阴影开关。
- 之后二选一：**窗体颜色** `#rrggbb` 或**窗体背景图文件名**，再跟窗体矩形的 左上X,左上Y,右下X,右下Y。
- 默认值可手工写成：
  ```
  setwindow 8,16,20,23,26,26,0,2,20,1,1,#999999,0,0,639,479
  ```
  （引擎默认字号 26×26，`DEFAULT_FONT_SIZE 26`【源码】，窗口半透明 `#999999`）
- **`setwindow2` 只能改「窗体颜色或图片」，其它参数一律不能改**，且不会清除文本内容或 log。【手册】
- **`setwindow3` 与 `setwindow` 完全相同，唯一区别是「不清空 Log 缓冲」**。【手册】
  - 手册额外警告：如果用 `setwindow3` 改了窗体尺寸而没同步 log 模式尺寸，回想模式可能出错。
- 【实证】MGQ：`setwindow3` 5 次，`setwindow`/`setwindow2` 0 次（它用自定义窗口图片/精灵）。
- 阻塞：否。

#### `windoweffect`
```
windoweffect <效果号>[,<时长毫秒>][,"<遮片>"]
```
- 文本框显示/隐藏时使用的效果，参数与 `effect` 相同但**没有「自定义效果号」那一位**。【手册】
- 例：`windoweffect 10,1000` = 1 秒交叉淡入。【手册】
- 引擎默认 `window_effect.effect = 1`（瞬时），`duration = 0`。【源码】`ScriptParser::reset`
- 阻塞：显示/隐藏文本窗时会等效果播放（`texton`/`textoff` 内部走效果流程）。

#### `shadedistance`
```
shadedistance <X偏移像素>,<Y偏移像素>
```
- 设置**文字阴影**的偏移量（像素）。【手册】
- 【源码】`shadedistanceCommand`：值会按 `screen_ratio1/screen_ratio2` 缩放；**若缩放后为 0 则强制为 1**（所以 `shadedistance 0,0` 实际得到 1,1）。
- 默认值 1,1。【源码】`ScriptParser::reset`
- **NScr 2.62 起也可在程序块使用**（原来是定义块专用）。【手册】
- 【手册】注意：`reset` **不会**把它恢复默认。
- 【实证】MGQ 在定义块设 `shadedistance 1,2`，并高频复用 518 次（多在程序块内切换）。
- 阻塞：否。

#### `name`（游戏自定义，非引擎指令）
- 【实证】MGQ 用 `defsub name` 定义，实现为「接收一个字符串参数，显示角色名牌」：
  ```
  *name
  getparam $platename
  if $platename="" csp 599:print 1:return
  len %name_count,$platename
  lsp 599,":s/20,20,0;#FFFFFF"+$platename,155-%name_count*5,408:print 1
  return
  ```
  即：名字为空则清除 599 号精灵并刷新；否则用**字符串精灵**（`:s/...`）以 20×20 字号、白色、水平居中（`155 - 字数*5`）绘制在 (408) 行。
- ⚠️ 这不是引擎命令。任何把 `name` 当内建命令的实现都是错的（除非只针对 MGQ 特化）。

#### `click` / `lrclick`
```
click      ; 等一次左键点击
lrclick    ; 等一次左键或右键点击
```
【源码】二者共用 `clickCommand`：
```cpp
skip_mode &= ~SKIP_NORMAL;               // 进入点击等待会退出 skip 模式
event_mode = WAIT_TIMER_MODE | WAIT_INPUT_MODE;
if (lrclick_flag) event_mode |= WAIT_RCLICK_MODE;
waitEvent(-1);                           // 无限等待
if (lrclick_flag) getret_int = (button == -1) ? 0 : 1;
```
- 阻塞：**是**（等点击，无超时）。
- `lrclick` 后必须用 `getret %v` 取值（0 = 右键，1 = 左键）。【源码】【实证】
- 文本内的等价物是 `@`（点击等待）与 `\`（换页等待）。【手册】

#### `yesnobox` / `okcancelbox` / `mesbox`
```
yesnobox   %var,"<消息>","<标题>"
okcancelbox %var,"<消息>","<标题>"
mesbox "<消息>","<标题>"
```
- 【源码】`yesnoboxCommand`：读取变量与两个字符串 → 构造对话框 → `show_dialog_flag = true` → 刷新 → 进入 `WAIT_BUTTON_MODE` 循环 `waitEvent(-1)` 直到按键。**阻塞：是。**
- 【实证】MGQ：
  ```
  yesnobox %sub1,"是否返回标题画面？","标题画面确认"
  if ... 判断 %sub1
  ```
- `mesbox`：⚠️ **ONScripter 没有实现 `mesbox`**（指令表里没有，源码里没有 `mesboxCommand`）。它只在 NScripter 本体可用：弹一个消息框（消息 + 标题），确认后继续。**阻塞：是（NScr 中）。** 对 Android 重实现而言，如果游戏用到，需要自己弹系统对话框。MGQ 未使用（0 次）。

#### `monocro` / `monocrooff`
```
monocro #rrggbb     ; 进入单色调
monocro off         ; 恢复彩色
```
【源码】`monocroCommand`：
- 若参数是 `off`（按标签匹配）→ `monocro_flag = false`。
- 否则 → `monocro_flag = true`，颜色写入 `monocro_color`，并**重建 256 项查找表**：
  ```cpp
  monocro_color_lut[i][c] = (monocro_color[c] * i) >> 8;
  ```
- 最后 `dirty_rect.fill(screen_width, screen_height)` → **标记全屏为脏**，即**下一次屏幕刷新（`print`/`repaint`）才看得到效果**。
- 【手册】文本窗与鼠标光标**不受** monochrome 影响（在 `refreshSurface` 的绘制顺序中也得到了印证，见 §14）。
- ⚠️ **`monocrooff` 不是命令**。恢复彩色只能写 `monocro off`。【源码】指令表核对 +【实证】MGQ 0 次。
- 【实证】MGQ：`monocro #cc0000` / `monocro #000000` 共 25 次，`monocro off` 亦有使用。
- 阻塞：否（只置状态 + 标脏）。

#### `nega`
```
nega <0|1|2>
```
- 0 = 取消底片；1 = 底片与 monocro 组合时「先 nega 再 monocro」；2 = 反之。【手册】
- 【手册】与 `monocro` 一样**不立即刷新屏幕**，需要后面调用 `print`（或 `repaint`）。
- 【实证】MGQ 未使用（0 次）。阻塞：否。

#### `bar` / `barclear`
```
bar <bar号>,<当前值>,<左上X>,<左上Y>,<宽>,<高>,<最大值>,<颜色#rrggbb>
barclear
```
- 【源码】`barCommand` 依次读取：编号、当前值、X、Y、宽、高、最大值、颜色；比例缩放坐标；**不主动刷新屏幕**。
- **必须随后调用 `print`（或 `repaint`）才会显示**。【手册】
- 宽/高是「达到最大值时」的尺寸；棒条左对齐、从左往右填充。【手册】
- `barclear` 删除**全部** bar（`bar_info[0..99]`）。【源码】——注意它没有参数，是清空而不是按号清除。
- 【实证】MGQ：
  ```
  bar 0,1,767,%bgmbary/100,16,%bgmbarone2/100,1,#DDDDDD
  barclear
  ```
  （bar 号 0，当前值 1，X=767，Y=变量/100，宽 16，高=变量/100，最大 1，颜色 `#DDDDDD`）
  MGQ 用 `bar` 做设置界面的音量条。`bar` 3 次、`barclear` 7 次。
- 阻塞：否。

### 7.3 按钮与选项

#### `btndef` / `btnwait` / `btnwait2`
```
btndef "<图片>"     ; 把图片读入按钮缓冲
btndef clear        ; 清空按钮定义与缓冲
btn  <按钮号>,<X>,<Y>,<宽>,<高>,<切图X>,<切图Y>
btnwait  %var       ; 等待按钮点击（返回后清空按钮定义）
btnwait2 %var       ; 等待按钮点击（返回后**保留**按钮定义）
```
- `btndef`：把图片读入按钮内存缓冲；**先前定义的按钮会被清掉**。【手册】
  - 【实证】MGQ 里 `btndef clear`（514 次）是标准的「先清空」写法。
- `btnwait` 返回值（【手册】完整表，极重要）：

| 返回值 | 含义 |
|---|---|
| ≥1 | 被点击的按钮号 |
| 0 | 左键点在按钮之外 |
| −1 | 右键 |
| −2 | `btntime` 超时（未启用 `usewheel`）；或鼠标滚轮上滚（启用 `usewheel` 时） |
| −3 | 滚轮下滚（`usewheel`） |
| −4 | `btnarea` 悬停 |
| −5 | `btntime` 超时（启用 `usewheel` 时） |
| −10 / −11 | Esc / 空格（`useescspc`） |
| −12 / −13 | PageUp / PageDown（`getpage`） |
| −19 / −20 | Enter（`getenter`）/ Tab（`gettab`） |
| −21…−32 | F1…F12（`getfunction`） |
| −40…−43 | 方向键（`getcursor`） |
| −50 | Insert（`getinsert`） |
| −51…−53 | Z / X / C（`getzxc`） |
| −60 / −61 | Skip 关闭 / Auto 关闭（`getskipoff`） |
| −70 | 鼠标中键（`getmclick`） |

- `btnwait2` 与 `btnwait` 的唯一区别：**返回后不清空按钮定义**（按钮图仍在内存里）。用完应当 `btndef ""` 释放内存。【手册】
- 【实证】MGQ：`btnwait2` 100 次、`btnwait` 31 次、`btndef` 514 次。
- 阻塞：**是**（`WAIT_BUTTON_MODE` + `waitEvent(-1)`）。

#### `btndown`
```
btndown <0|1>
```
- 【源码】`btndown_flag = (script->readInt() == 1) ? true : false;` → 这是一个**模式开关**，不是取值命令。
- 作用：打开后按钮在**按住**时即上报（连续输入），而不是等抬手。【实证】MGQ 注释「マウスの連続入力取得」。
- 阻塞：否。

#### `spbtn` / `exbtn` / `exbtn_d` / `cellcheckspbtn` / `cellcheckexbtn`
```
spbtn <精灵号>,<按钮号>
exbtn  <精灵号>,<按钮号>,"<控制串>"
exbtn_d "<控制串>"
cellcheckspbtn <精灵号>,<按钮号>
cellcheckexbtn <精灵号>,<按钮号>,"<控制串>"
```
- `spbtn`：把一个**精灵**当按钮用；**cell 0 = 未悬停图，cell 1 = 悬停/按下图**（即精灵图要竖着切两格）。按下后返回该按钮号。【手册】
- `exbtn`：复合按钮，用控制串描述悬停时要执行的一系列 `sp` 操作（类似 `spstr` 的语法）。【手册】
- `exbtn_d`：设置「鼠标不在任何复合按钮上」时的默认显示控制串。【手册】
- `cellcheckspbtn` / `cellcheckexbtn`：仅当精灵**至少 2 个 cell** 时才建立按钮（防止单格图被当成按钮）。【手册】
- 【实证】MGQ：`spbtn` 55 次、`exbtn` 96 次（如 `exbtn 401,401,"P301,1"`），未用 `exbtn_d`。
- 阻塞：否（只是定义）。

#### `btntime` / `btntime2` / `getbtntimer`
```
btntime  <毫秒>     ; 给下一次 btnwait 设超时（不等待语音）
btntime2 <毫秒>     ; 同上，但会等语音播完
getbtntimer %var    ; 取得在 btnwait 中停留的时间
```
- 【手册】`btntime` 要写在 `btnwait` **前一行**；超时后 `btnwait` 返回 −2（或 −5）。中途发生右键/空白点击而跳回 btnwait 上方时会**重新计时**。
- 【实证】MGQ 未使用 `btntime`/`btntime2`/`getbtntimer`。
- 阻塞：它们本身不阻塞。

#### `csel` / `cselbtn` / `cselgoto` / `getcselnum` / `getcselstr` / `selectbtnwait`
```
csel "<选项1>",*label1[,"<选项2>",*label2,...]
```
- 【源码】`selectCommand` 中 `csel` 走 `SELECT_CSEL_MODE`：
  1. 解析成对的（文本, 标签）；
  2. **不创建任何按钮**（与 `select` 不同）；
  3. 关闭 saveon（`saveon_flag = false`，等同临时 `saveoff`）；
  4. **跳转到 `*customsel` 标签**，把控制权交给游戏的自定义选项逻辑。
- 游戏必须在脚本里定义 `*customsel`，用下列命令自行绘制与处理选项：

| 命令 | 语法 | 语义 |
|---|---|---|
| `getcselnum` | `getcselnum %var` | 取本次 `csel` 的选项个数 |
| `getcselstr` | `getcselstr $var,<下标>` | 取第 N 个选项文本（下标从 0 开始） |
| `cselbtn` | `cselbtn <选项下标>,<按钮号>,<X>,<Y>` | 把某选项注册成一个按钮 |
| `cselgoto` | `cselgoto <选项下标>` | 跳到该下标对应的标签 |
| `selectbtnwait` | `selectbtnwait %var` | 在 `*customsel` 中等按钮，返回值同 `btnwait`（含 −1 右键、−2 lookback 等） |

- 【手册】标准写法（来自官方示例）：
  ```
  *customsel
  btndef clear
  getcselnum %0
  getcursorpos %1,%2
  cselbtn 0,500,%1,%2
  add %2,21
  cselbtn 1,501,%1,%2
  if %0>2 add %2,21:cselbtn 2,502,%1,%2
  *csel_loop
  selectbtnwait %0
  if %0=-2 systemcall lookback:goto *csel_loop
  if %0=-1 gosub *rclk:goto *csel_st
  if %0>=500 & %0<=504 saveon:sub %0,500:cselgoto %0
  goto *csel_loop
  ```
- 【实证】MGQ：`csel` 111 次、`getcselnum` 1 次、`getcselstr` 4 次、`selectbtnwait` 0 次（MGQ 用自己的按钮系统）。
- **`cselstr` 不是命令**：只有 `getcselstr`。【源码】指令表核对 +【实证】MGQ 0 次。
- 阻塞：`csel` 本身不阻塞（它只是一次跳转）；`selectbtnwait` **阻塞**（等按钮）。
- 对比 `select` / `selgosub` / `selnum`（引擎自带选项，会**自动排版成按钮并阻塞等待**）：`select` 选后 goto，`selgosub` 选后 gosub，`selnum` 把选择结果写入变量。【手册】【源码】MGQ 未使用这三个。

### 7.4 图形、精灵与图层

#### `bg`
```
bg "<图片文件>",<效果>[,<时长>][,"<遮片>"]
bg #rrggbb,<效果>[,<时长>][,"<遮片>"]
bg {black|white},<效果>[,<时长>][,"<遮片>"]
```
【源码】`bgCommand`：
- 特判 `white` / `black` 关键字（`compareString`），其余读字符串（文件路径或 `#rrggbb`）。
- **载入背景时会先把 3 个站立图（tachi）全部删除**（`tachi_info[i].remove()`）。
- 设置背景 → `createBackground()` → 标脏全屏 → 播放指定效果（阻塞到效果结束）。
- 【实证】MGQ 用法：`bg black,1`（瞬时黑屏）、`bg black,7,1500`（右窗帘 1.5 秒）、`bg "bg\"+$haikei+".bmp",7,1500`（**字符串拼接出的路径 + 效果 7 + 1500ms**）。
- 阻塞：**是**（效果时长）。

#### `lsp` / `lsph` / `csp` / `vsp`
```
lsp  <精灵号>,"<sprite处理串>",<左上X>,<左上Y>[,<不透明度>]
lsph <精灵号>,"<sprite处理串>",<左上X>,<左上Y>[,<不透明度>]
csp  <精灵号>          ; 删除精灵；-1 = 全部删除
vsp  <精灵号>,<0|1>    ; 显示开关
```
- 精灵号范围 **0–999**（`MAX_SPRITE_NUM 1000`）。【源码】
- **`lsp` 的 x,y 是「精灵左上角」**（`orig_pos.x/y` 直接就是绘制原点，没有居中处理）。【源码】`lspCommand` + `refreshSurface`
- 第 5 参数是**不透明度**（0–255）；省略时用图片自身 alpha / `default_alpha`。【源码】【手册】
- `lsph` = 与 `lsp` 完全相同，只是初始 `visible = false`，之后可用 `vsp` 显示。【手册】
- `csp N`：删除该精灵；`csp -1`：**遍历 0..999 全部删除**（含动画与图片资源）。【源码】
- `vsp N,1` 显示 / `vsp N,0` 隐藏；只改可见标志 + 标脏。【源码】
- 修改位置/可见性**不会立刻上屏**，需要 `print` / `repaint`。【手册】
- 【实证】MGQ：`lsp` 21835 次、`csp` 2959 次、`vsp` 555 次、`lsph` 2 次、`csp -1` 常见。

**sprite 处理串（`lsp` 第二参数）完整语法**（【源码】`ONScripter::parseTaggedString`）：

```
"[:[b][f]<透明方式>][/<格数>[,<间隔ms>[,<播放模式>]]][;]<文件名>"
"[:s[/字宽,字高[,字距[,行距[,抗锯齿]]]];#颜色[#颜色...]]<文本>"        ← 字符串精灵
">宽,高,#rrggbb[ #rrggbb...]"                                          ← 直接生成矩形色块
"*<层号>"                                                             ← 视频层（wcmpg.dll）
```

| 片段 | 取值 | 含义 |
|---|---|---|
| `:` | | 处理串起始标记（没有 `:` 就是纯文件名） |
| `b` | | 放大 2 倍（`is_2x`） |
| `f` | | 水平翻转（`is_flipped`） |
| `a` | | alpha 透明 |
| `l` | | 以**左上角**像素颜色为透明色 |
| `r` | | 以**右上角**像素颜色为透明色 |
| `c` | | 不透明（copy） |
| `s` | | 字符串精灵模式（见下） |
| `m<文件>;` | | 遮片透明 |
| `#rrggbb` | | 指定透明色（direct color key） |
| `!<n>` | | 索引色图片的调色板号 |
| `/格数` | | 动画格数（`num_of_cells`） |
| `,间隔` | ms | 每格显示时长；也可写 `<d0,d1,d2,...>` 为**每格单独指定** |
| `,模式` | 数字字符 | `1`=播放一次，`2`=循环，`3`=不播放（按钮/手动 `cell` 用）；源码中 `loop_mode = 字符 - '0'` |
| `;` | | 处理串与文件名分隔 |

- **字符串精灵**：`:s/字宽,字高[,字距X[,字距Y[,抗锯齿]]];#色1[#色2]<文本>`
  - 颜色最多两类：第一个是正常色，第二个是**作为按钮时的悬停色**。
  - `/` 之后的四个数是字号与字距（后两个可省）；MGQ 大量使用 `:s/20,20,1;`、`:s/20,20,0,0,0;`、`:s/24,24,1;`。
  - 文本可以直接写中文，也可以是 `$var` 形式参与拼接。
- **动画模式的 `0`**：MGQ 的鼠标光标用了 `:l/2,160,0;`。按源码，`loop_mode = '0'-'0' = 0`，而 `loop_mode != 3` 即视为可动画 → 0 与 1/2 一样「会动」。1/2 的具体差别在 `proceedAnimation` 的循环包装【未确认（未逐行核对动画推进函数）】。
- 【实证】MGQ 的典型 sprite 串：
  ```
  lsp 700,":s/20,20,1;#FFFFFF文本",152,83          ; 文字精灵
  lsp 255,":a;chara\alice_st01b.bmp",400,300,100,100,0   ; ← 这是 lsp2
  lsph 0,":l/2,160,0;system\cursor0.bmp",0,0        ; 2 格、160ms 的光标动画
  lsp 704,":c;system\ef_black.jpg",0,0              ; 不透明全屏图
  ```

#### `lsp2` / `lsph2` / `lsp2add` / `lsp2sub` / `msp2` / `amsp2` / `csp2` / `vsp2`（问题 5）

这是「伪 3D / 仿射变换精灵」——**扩展精灵（extended sprite）**。精灵号范围 **0–255**（`MAX_SPRITE2_NUM 256`）。【源码】

```
lsp2   <号>,"<处理串>",<中心X>,<中心Y>,<X缩放%>,<Y缩放%>,<旋转角度>[,<不透明度0-255>]
lsph2  <同 lsp2>                      ; 初始隐藏
lsp2add <同 lsp2>                     ; 加法混合
lsp2sub <同 lsp2>                     ; 减法混合
lsph2add / lsph2sub                   ; 上述两种的隐藏版
msp2   <号>,<ΔX>,<ΔY>,<Δ缩放X>,<Δ缩放Y>,<Δ旋转>[,<Δ不透明度>]
amsp2  <号>,<中心X>,<中心Y>,<缩放X%>,<缩放Y%>,<旋转角度>[,<不透明度>]
csp2   <号>                           ; -1 = 全部删除
vsp2   <号>,<0|1>
```
【源码】逐字段确认（`lsp2Command` / `mspCommand` / `amspCommand`）：

| 参数 | 含义 | 备注 |
|---|---|---|
| 中心 X / Y | **图片中心**坐标（不是左上角！） | `calcAffineMatrix` 用 `±affine_pos.w/2` 计算四角 |
| 缩放 X / Y | **百分比**，`100` = 原尺寸 | 源码 `mat[0][0] = cos * scale_x / 100` |
| 旋转角度 | **度**，**逆时针为正** | `cos(-π·rot/180)`，屏幕坐标 y 向下，取负即逆时针 |
| 不透明度 | 0–255，可省略 | 省略时 `trans = -1`（用图片自身 alpha） |
| 负缩放 | 翻转图片 | 源码对 `scale_x<0` 特殊处理四角顺序 |

- `msp2` 的每个字段都是**相对增量**（`orig_pos.x += dx`、`scale_x += dscale_x`、`rot += drot`）。
- `amsp2` 是**绝对赋值**（直接覆盖位置/缩放/旋转/不透明度）。
- **`msp2` 的不透明度增量有一个必须照抄的怪癖**：
  ```cpp
  if (ai->trans == -1) ai->trans = 255 + delta;   // 首次调节时以 255 为基准
  else                 ai->trans += delta;
  clamp(0, 255);
  ```
  也就是说 `msp2 0,0,0,0,0,0,-255` 得到完全不透明→透明，而「相对当前的 -10」在首次调用时等价于「-10 相对 255」。
- **`msp`（普通精灵）的第 4 参数同样是「增量」**，且走同一套 `trans == -1` 逻辑；`amsp` 才是绝对赋值：
  ```cpp
  // mspCommand / amspCommand
  if (amsp)  ai->trans = readInt();               // 绝对，clamp 0..255
  else { if (ai->trans == -1) ai->trans = 255 + readInt(); else ai->trans += readInt(); }
  ```
- `msp  <号>,<ΔX>,<ΔY>[,<Δ不透明度>]`：位置相对移动。【手册】
- `amsp <号>,<X>,<Y>[,<不透明度>]`：绝对移动。【手册】
- `lsp` / `lsp2` 的可选最后参数则是**绝对**不透明度：`ai->trans = readInt()`（缺省 `-1`，表示沿用图片自身 alpha）。【源码】
- 【实证】MGQ 用法：
  ```
  lsp2 255,":a;chara\alice_st01b.bmp",400,300,100,100,0     ; 中心(400,300)，100%，不旋转
  lsp2 255,":a;chara\alice_st03b.bmp",600,900,500,500,0:print 10,500   ; 放大到 500%
  msp2 255,1,3,2,2,0                                        ; 相对移动+缩放
  msp2 0,10,-10,0,0,15,0                                    ; 7 个参数：含不透明度增量
  amsp 700,172,0+%move                                      ; 绝对定位（参数可含表达式）
  csp2 255:print 10,1000
  csp2 -1
  ```
  MGQ：`lsp2` 155、`msp2` 50、`amsp` 49、`csp2` 50、`amsp2`/`vsp2` 0 次。
- 混合模式：`lsp2add` = 加法混合，`lsp2sub` = 减法混合，普通 `lsp2` = 正常 alpha 混合。【源码】`BLEND_ADD/BLEND_SUB/BLEND_NORMAL`
- 阻塞：全部否（改状态 + 标脏，需 `print` 上屏）。
- 普通精灵与扩展精灵是**两套独立数组**（`sprite_info[1000]` 与 `sprite2_info[256]`），编号空间互不影响。

#### `cspl` / `vspl`（MGQ 用户命令，非引擎指令）
```
cspl <起始号>,<结束号>          ; = for %i = 起 to 止 : csp %i : next
vspl <起始号>,<结束号>,<0|1>    ; = for %i = 起 to 止 : vsp %i,标志 : next
```
- 【实证】MGQ 的 `defsub` 实现见 §6.1；`cspl 3,7`、`cspl 0,550`、`cspl 551,710`、`vspl 551,710,0/1` 是常见调用。
- ⚠️ **不是引擎内建指令**，ONScripter 指令表中没有这两个名字（已核对）。任何重实现都应当通过 defsub 展开执行，而不是硬编码。
- 若游戏想「批量删精灵」，NScr 的原生写法是 `csp -1`（全清）。

#### `screen_clear` / `screen_clear2` / `screen_vanish` / `screen_appear`（MGQ 用户命令）
- 全部是 MGQ 的 `defsub`（见 §6.1），实现为「`btndef clear` + `barclear` + 批量 `cspl`/`vspl`」：
  ```
  *screen_clear   btndef clear : barclear : cspl 0,550      : return
  *screen_clear2  btndef clear : barclear : cspl 551,710    : return
  *screen_vanish  btndef clear : barclear : vspl 551,710,0  : return
  *screen_appear  btndef clear : barclear : vspl 551,710,1  : return
  ```
- ⚠️ **不是引擎内建指令**（ONScripter 中不存在）。`screen_appear` 在 MGQ 脚本中出现 0 次（只注册未使用）。
- 语义含义：MGQ 把精灵号 0–550 当作「背景/立绘层」、551–710 当作「UI/状态层」，于是这两个宏就是「清背景层」「清 UI 层」「隐藏 UI 层」「显示 UI 层」。

#### `quake` / `quakex` / `quakey`
```
quake  <振幅像素>,<时长毫秒>
quakex <振幅>,<时长>      ; 仅水平
quakey <振幅>,<时长>      ; 仅垂直
```
【源码】`quakeCommand`：
- 参数分别是振幅与时长；效果号用 `MAX_EFFECT_NUM + 类型`（0=纵向、1=横向、2=随机）合成。
- **时长会被强制放大**：`if (duration < amplitude * 4) duration = amplitude * 4;`
- 标脏全屏后播放效果 → **阻塞**。
- 【实证】MGQ：`quake 10,1500`（104 次），常与 `print` 搭配做受击演出。

#### `effect` / `effectblank` / `effectcut` / `effectskip` / `seteffectspeed`
```
effect <自定义效果号>,<内建效果号>[,<时长毫秒>][,"<遮片图>"]
effectblank <毫秒>
effectcut
effectskip <0|1>
seteffectspeed <0|1|2>
```
- `effect`：给内建效果绑定一个自定义号（编号从 2 开始），之后 `print 2` 之类即可复用。【手册】
- `effectblank N`：**效果结束后再等待 N 毫秒**才执行下一条命令。**若效果是内建 1 号（瞬时），此等待不生效**。【手册】
  - 引擎默认 `effect_blank = 10`。【源码】`ScriptParser::reset`
- `effectcut`：跳略模式下把效果时长压到 0（瞬时）。【源码】`effect_cut_flag`
- `effectskip 1/0`：效果播放中点击能否跳过（默认 1 可跳过）。【手册】
- `seteffectspeed 0/1/2`：默认 / 半速 / 瞬时。【手册】
- 【实证】MGQ 未使用 `effect` / `effectblank` / `effectskip`（0 次），但用了 `effectcut`（定义块 1 次）。

#### `mpegplay` / `avi` / `movie`
```
mpegplay "<文件>",<点击可否中断 0|1>
avi      "<文件>",<点击可否中断 0|1>
```
【源码】`mpegplayCommand`：读文件名与中断标志 → `stopBGM(false)` → 播放（`playMPEG`）→ 失败则 `endCommand()` → `repaint()`。
- 第二参数：`1` = 点击可跳过，`0` = 必须看完。【手册】
- **阻塞：是**（播放期间占据事件循环）。
- 【实证】MGQ：`mpegplay "effect\movie01.mpg",1`，共 18 次。

#### `glass` / `dwaveon` / `dwaveoff` / `monocrooff` / `jmp` / `cselstr`
- 【源码】+【实证】这些名字**在 ONScripter 指令表中不存在**，在 MGQ 382,325 行脚本中出现 **0 次**。详见 §18。

### 7.5 音频（问题 9）

#### `bgm` / `bgmonce` / `bgmstop` / 淡入淡出
```
bgm     "<文件>"     ; 循环播放压缩音乐（等价 mp3loop）
bgmonce "<文件>"     ; 只播一次
bgmstop              ; 停止
mp3     "<文件>"     ; 只播一次
mp3loop "<文件>"     ; 循环
mp3stop              ; 停止
bgmfadeout <毫秒>    ; 定义/程序块：设定 BGM 淡出时长
bgmfadein  <毫秒>
mp3fadeout <毫秒>
mp3fadein  <毫秒>
mp3vol / bgmvol / sevol / voicevol <0-100>
stop                 ; 停止全部音乐
```
- 【手册】`bgm` = `mp3loop` 的别名；两者都走「压缩音乐（BGM）通道」。
- 【手册】`mp3fadeout` 会同时作用于单次播放的头尾、循环曲的头尾、以及切歌时的淡出。
- 【源码】`mp3stopCommand` 里有一段**会阻塞**的逻辑：若已设置 `mp3fadeout_duration` 且音乐正在播放——
  - 若文件是 **OGG**：不等待淡出结束（`event_mode = IDLE_EVENT_MODE; waitEvent(0)`）后立即继续；
  - 否则：`event_mode = WAIT_TIMER_MODE; waitEvent(-1);` **阻塞到淡出结束**。
  → 也就是说 **`mp3stop`/`bgmstop` 在设置了淡出且曲目非 OGG 时会阻塞**。
- 音量范围 0–100。【手册】MGQ 用 `defbgmvol`/`bgmvol`/`sevol`（`defsevol 80`、`defbgmvol 50` 之类经变量间接设置）。
- 【实证】MGQ：`bgm` 1 次、`bgmstop` 508 次、`bgmonce` 0 次；**音乐主要也走 `dwave`**。

#### `wave` / `waveloop` / `wavestop`
```
wave     "<WAV文件>"     ; 播一次
waveloop "<WAV文件>"     ; 循环
wavestop                 ; 停止
```
【源码】`waveCommand`：
```cpp
wave_play_loop_flag = <是否 waveloop>;
wavestopCommand();                       // 先停掉上一个 wave
setStr(&wave_file_name, script_h.readStr());
playSound(wave_file_name, SOUND_CHUNK, wave_play_loop_flag, MIX_WAVE_CHANNEL);
```
- **`wave` 使用「单一固定通道」`MIX_WAVE_CHANNEL`**：新 `wave` 会先停掉旧的，天然不能混音。
- 阻塞：否。

#### `dwave` / `dwaveloop` / `dwavestop` / `dwaveload` / `dwaveplay` / `dwaveplayloop` / `chvol`
```
dwave          <通道号>,"<WAV文件>"   ; 在该通道播放一次
dwaveloop      <通道号>,"<WAV文件>"   ; 循环
dwavestop      <通道号>               ; 停止该通道
dwaveload      <通道号>,"<WAV文件>"   ; 预载入内存
dwaveplay      <通道号>               ; 播放已预载的（一次）
dwaveplayloop  <通道号>               ; 播放已预载的（循环）
chvol          <通道号>,<0-100>       ; 该通道音量
```
【源码】`dwaveCommand`：
- 通道号会被 **clamp 到 `[0, ONS_MIX_CHANNELS-1]`**（ONScripter 中 `ONS_MIX_CHANNELS = 50`；NScr 2.82+ 支持 0–199）。负数当 0，超上限当上限。
- `dwaveload/dwaveplay/dwaveplayloop` 使用 `wave_sample[ch]` 数组；**未预载就 `dwaveplay` 会出错**（源码里 `Mix_PlayChannel(ch, wave_sample[ch], ...)` 传 NULL）。【手册补充】
- 【手册】只能播放 **PCM WAV**；`dwaveload` **不支持 OGG**。
- 【手册】`dwave` 与 `wave` 的关键差异：**`dwave` 是多通道混音**（不同通道可同时发声），`wave` 是单通道独占。因此「BGM 用 mp3/bgm，语音与音效用 dwave」是标准做法。

**`dwave` 与「语音」的关系（对 MGQ 尤其重要）**：
- 【手册】约定：**`dwave` 通道 0 = 语音通道**，其它通道 = 音效。`defvoicevol` 设通道 0 的音量，`defsevol` 设其它通道的音量，`voicevol`/`sevol` 同义。
- 【实证】MGQ 完全依赖这套约定：`dwave` 6000 次、`dwavestop` 754 次、`dwaveloop`（`hseanwave` 宏里）若干；音频文件是 **.ogg**（`dwaveloop 5,"se\hsean01_innerworks_a1.ogg"`），靠游戏自带的 `nsogg2.dll` 解码。
- 【手册】自 Ver 2.54 起：若同一 WAV 曾用 `wave` 从 NSA 归档播放，未先 `wavestop`/`stop` 就改用 `dwave` 播放会**报错**。
- 阻塞：全部否（`dwavestop` 也不阻塞）。

#### `dwaveon` / `dwaveoff`（用户问题中的命令）
- ⚠️ **在两个权威来源中都找不到**：
  - ONScripter 指令表逐条核对：无 `dwaveon` / `dwaveoff`（§18）。
  - MGQ 382,325 行脚本：出现 **0 次**。
- 结论：**它们不是 NScripter / ONScripter 的标准指令**。可能来自某个 ONScripter 分支（例如第三方 ONScripter-Plus 系）。本文**无法确认其语义**，标为【未确认】。重实现时若遇到，建议按「忽略」处理并记录到未实现清单，而不是猜测行为。
- 注意不要与 `saveon`/`saveoff`、`menu_waveon`/`menu_waveoff`（ONScripter-EN 的全局静音开关）混淆。

#### `se`
- ⚠️ 【源码】ONScripter 指令表中**没有 `se`**；【实证】MGQ 0 次。
- NScripter 官方的音效播放指令是 `wave` / `dwave`，音量指令是 `sevol`。**`se` 不是标准命令**，标为【未确认/不存在】。

#### `mode_wave_demo` / `mp3save`
- `mode_wave_demo`：让 WAV 在 skip 模式下也播放（默认不播）。【手册】
  - 【手册】ONScripter 原本不识别该命令且总是播放 skip 中的 WAV；ONScripter-EN 自 20090816 起支持它。
  - 【实证】MGQ 在定义块用了 1 次。
- `mp3save`：播放中存档后，读档时继续播放该曲。【手册】

#### `v` / `dv` / `mv` 简写
```
v<NNNN>:    ; == wave  "voice\<NNNN>.wav" 之前先输出文字
dv<NNNN>:   ; == dwave 0,"voice\<NNNN>.wav"
mv<NNNN>:   ; == mp3   "voice\<NNNN>.mp3"
```
- 【源码】`ONScripter::parseLine` 在末尾特判：`v` + 数字 → `vCommand()`，`dv` + 数字 → `dvCommand()`（**数字前不能有空格**，冒号是行分隔符）。
- 【实证】MGQ 未使用这三种简写（它直接写 `dwave`）。

### 7.6 存档（问题 12）

#### 脚本级指令
```
savegame  <存档号>              ; 直接存档，无确认
savegame2 <存档号>,"<附加字符串>" ; 存档并附带一段字符串
loadgame  <存档号>              ; 直接读档，无确认
saveon                          ; 打开存档点记录
saveoff                         ; 关闭存档点记录
savenumber <数量>               ; 存档位数量上限（默认 9，最大 20）
savefileexist %var,<存档号>      ; 1 = 存在
getsavestr $var,<存档号>         ; 取 savegame2 存的字符串
savetime <存档号>,%月,%日,%时,%分 ; 取存档时间
savepoint                       ; 手动更新存档点
autosaveoff                     ; 关闭自动存档点
loadgosub *label                ; 读档后自动调用的子程序
savedir "<目录>"                ; 存档目录
```
【源码】精确语义：

- **`saveoff` 的真正含义**（强烈建议照抄）：
  ```cpp
  int ONScripter::saveoffCommand() {
      if (!autosaveoff_flag){
          if (saveon_flag && internal_saveon_flag) storeSaveFile();  // 快照当前状态
          saveon_flag = false;
      }
  }
  ```
  `storeSaveFile()` 把**当前状态快照进内存**，`saveon_flag = false` 之后：
  - 玩家此时存档，写出的**不是当前状态**，而是 `saveoff` 那一刻的快照；
  - 换句话说：**读档会回到「最后一次 saveon / savepoint 的位置」，而不是存档时脚本执行到的位置。**
  - 手册的表述一致：「它不是禁止存档，而是让读档回到最后 `saveon` 定义的位置」。
- `saveoff` 的**性能意义**：saveon 打开时引擎要记录「载入过的每个精灵」等日志（用于恢复画面），快速动画时会明显变慢，所以演出前 `saveoff`、演出后 `saveon` 是标准套路。【手册】【实证】MGQ 的 `sean_change`、`count`、`hseanwave` 等宏内部都以 `saveoff` 开头（132 次）。
- `savenumber`：默认 9，最大 20；**设得过大会因为菜单溢出而崩**。【手册】
- `loadgame`：读失败时**不报错**，直接继续执行下一条。【手册】
- `savegame`/`loadgame` 都**不弹确认框**。【手册】
- `loadgosub *label`：读档完成后立刻 gosub 到该标签（MGQ 用它做读档后的配置恢复）。【手册】
- `savepoint`：手动刷新存档点（例如点击地图后）。【手册】
- **自动存档点的触发时机**（【源码】`ScriptParser::enterTextDisplayMode`）：每次「进入文本显示模式」时（即显示新一句话时），若 `saveon_flag` 与 `internal_saveon_flag` 都允许，会调用 `storeSaveFile()` 更新存档点，并把 `internal_saveon_flag` 置回 false（保证一句只记一次）。所以**读档总是回到某一句话的开头**，这正是 NScr 的存档手感来源。
- 注意：**`save` / `load` 不是脚本命令**。它们是右键菜单（`rmenu`）的功能项名字：
  ```
  rmenu "保存到文件",save,"从文件读取",load
  ```
  在脚本里存档/读档要用 `savegame` / `loadgame`。ONScripter 指令表中没有裸的 `save`/`load`（§18 已核对）。

#### `getspmode`
```
getspmode %var,<精灵号>
```
- 【源码】`getspmodeCommand`：`setInt(&pushed, sprite_info[no].visible ? 1 : 0);`
- 语义：该**普通精灵**当前是否显示（1/0）。注意它读的是 `sprite_info`，**不适用于 `lsp2` 的扩展精灵**。
- 【实证】MGQ 未使用。阻塞：否。

#### 存档文件格式
【源码】`ONScripter_file.cpp` + `ONScripter_file2.cpp`：

- 文件名：`<savedir>save<N>.dat`（默认 `save0.dat`…；`savedir "save"` 时在 `save\` 目录下）。存档号 ≥10 时编号补零到 2 位。
- 文件头魔数：**ASCII `"ONS"`**（`#define SAVEFILE_MAGIC_NUMBER "ONS"`），随后是版本 `2.8`。
- 整数按**主机字节序**原样写入（`writeInt` 直接写 `int`），字符串为「长度 + 原始字节」形式（`writeStr`），**没有任何跨平台归一化** → 重实现时必须自己定义字节序并在 Android 上固定为小端。
- 写入顺序（`saveSaveFile2`，字段级）：

| 顺序 | 字段 |
|---|---|
| 1 | `2`（版本标记：1 = <2.96，2 = ≥2.96） |
| 2–3 | 文本粗体开关、阴影开关 |
| 4 | `0`（保留） |
| 5 | `rmode_flag`（右键菜单可用性） |
| 6–8 | 文本颜色 RGB |
| 9–10 | 两个光标图片名 |
| 11–13 | 窗口效果号、时长、效果图名 |
| 14–21 | 文本窗口参数：文本左上 X/Y、列数、行数、字宽、字高、字距 X、字距 Y |
| 22–24 | 窗口色 RGB |
| 25–26 | 透明标志、默认文字速度 |
| 27–30 | 文本框矩形（x, y, 右下x, 右下y）+ 文本框背景图名 |
| 31–36 | 两个光标是否为绝对定位 + 两个光标的 x/y |
| 37 | 背景图名（`bg_info.file_name`） |
| 38–40 | 三张站立图（tachi l/c/r）图名 |
| 41–43 | 三张站立图的 x |
| 44–46 | 三张站立图的 y |
| 47–52 | 三个 `0`、三个 `-1`（保留/占位） |
| 53… | **每个普通精灵（0–999）**：图片名、x、y、可见标志、当前 cell、alpha —— 共 6 个字段 × 1000 |
| 之后 | **数值/字符串变量 `0 .. global_variable_border-1`**（默认 0–199；MGQ 为 0–1499）—— `writeVariables(0, border)` |
| 之后 | **栈信息（nest info）**：gosub 帧数与每帧的脚本偏移；for 帧记 4 个 int（变量号、终值、步长、取负的偏移） |
| 之后 | `monocro_flag`、monocro 颜色 RGB、`nega_mode` |
| 之后 | MIDI 文件名、wave 文件名、CD 音轨号、各循环标志（midi/wave/cd/music）、`mp3save_flag` 与音乐文件名 |
| 之后 | `erase_text_window_mode` 标志、常量 `1` |
| 之后 | **100 个 bar**（每个 6 个 int + 4 字节颜色；未使用的写占位值） |
| 之后 | **100 个 prnum**（数字标签，每个 5 个 int + 4 字节颜色） |
| 之后 | 常量 `1, 0, 1`、按钮缓冲图名（`btndef_info.image_name`） |
| 之后 | **数组变量**（`writeArrayVariable`） |
| 之后 | 若干标志字节、`loopbgm` 的两个文件名 |

- **全局变量（编号 ≥ `global_variable_border`）不写在存档里**，而是单独保存（`saveGlovalData()`）——这就是「跨存档继承的进度（CG 收集率、已通关次数）」的实现方式。
- **不保存的内容**：`lsp2`/扩展精灵（`sprite2_info`）不在上面的精灵循环中；bar/prnum 以外的手绘内容；`dwave` 各通道的播放位置；`btndown` 等瞬时标志；`shadedistance`（`reset` 也不会复位它）。
- 【实证】MGQ 的做法更复杂：它**不用 `savegame`**（脚本中 0 次），而是通过 `exec_dll` + 自绘存档界面 + `getscreenshot` 实现自己的存档系统；但 `savefileexist`（2 次）、`savetime`、`saveoff`（132 次）仍在用。**重实现时应当把标准 `savegame`/`loadgame` 做对，同时允许游戏覆盖存档 UI。**

---

## 8. 图层顺序与 Z 序（问题 11）

【源码】`ONScripter::refreshSurface()` —— 这是背景/立绘/精灵/窗口/文字的**唯一权威顺序**（从后往前画）：

```
1. bg_info                                  背景
2. 精灵号 > z_order 的普通精灵（从 999 递减到 z_order+1 绘制）  ← 号大的先画（更靠后）
3. 站立图 tachi（按 human_order[2-i] 顺序）
4. 【仅当 windowback】nega/monocro → 扩展精灵(sprite2) → 文本窗与文字
5. 精灵号 <= z_order 的普通精灵（从 z_order 递减到 0 绘制）      ← 号小的后画（更靠前）
6. 【仅当未设 windowback】扩展精灵(sprite2) → nega/monocro
7. bar[]（0..99）
8. prnum[]（0..99）
9. 【仅当未设 windowback】文本窗与文字
10. 点击等待光标（cursor_info）
11. 对话框（dialog_info）
12. 按钮（button_link 链）
```

**由此得到的规则**：

| 规则 | 说明 |
|---|---|
| **精灵号越小越靠前** | 两组循环都是**递减绘制**，后画的覆盖先画的。所以 `lsp 0` 在 `lsp 700` 之上 |
| **`humanz N` 设定分界** | `N` 写入 `z_order`（引擎默认 **499**）。号 > N 的精灵在**立绘后面**，号 ≤ N 的在**立绘前面（也在文字窗前面）** |
| `windowback` | 把**文本窗**插到「站立图那一层」：文字窗随之被号 ≤ N 的精灵遮住；同时扩展精灵（`lsp2`）被画到文字窗**下面**。用于「字幕被角色遮住」的演出 |
| `bar` / `prnum` 与文本窗的相对位置**取决于 `windowback`** | 未设 `windowback`（默认）时：bar → prnum → **文本窗/文字最后画** ⇒ 棒条与数字在文字**下面**。设了 `windowback` 时：文本窗先画（见第 4 步），bar/prnum 在第 7/8 步才画 ⇒ 棒条与数字在文字**上面** |
| `monocro` / `nega` 的范围 | 应用点在**文字窗与文字之前**（两个分支都是），所以**文本窗和文字永远不会被单色/底片影响**——与手册「文本窗和光标不跟着变色」一致 |
| `humanorder "rcl",<效果>` | 设定三张站立图的互相遮挡顺序（例如 `"rcl"` = 右在中最上、中在左上） |
| `underline <Y>` | 设定站立图的「地平线」Y 坐标（立绘对齐用） |
| `bgalia x,y,w,h` | 声明非标准尺寸的背景 |

【实证】MGQ 的图层规划与其吻合：
```
humanz 600      ; 分界 = 600
windowback      ; 文字窗放到立绘层
精灵 0–550      ; 背景/立绘/场景层（在文字窗之下、立绘附近）
精灵 551–710    ; UI/状态层（在文字窗之上）→ 所以 *screen_clear2 单独清 551–710
```
MGQ 的 `humanz 600` 注释就是「ウインドウ表示レイヤー（文本窗显示层）」。

**`shadedistance` 与精灵无关**：它只影响文本阴影偏移（`shade_distance[0/1]`，见 §7.2），不参与 Z 序。用户问题里把它与「精灵 priority/z-order」并列，这里明确澄清：**没有任何证据表明 `shadedistance` 影响精灵或图层顺序**。

---

## 9. 会阻塞的命令

按阻塞原因分类（依据：源码中所有 `waitEvent(...)` 调用点 + 各命令实现）。

### 9.1 等待点击
| 命令 | 说明 |
|---|---|
| `click` | 无限等左键（`WAIT_INPUT_MODE`，无超时） |
| `lrclick` | 无限等左键或右键；结果写入 `getret` |
| 文本中的 `@` | 点击等待（由 `clickWait()` 处理） |
| 文本中的 `\` | 换页等待（由 `clickNewPage()` 处理） |
| `clickstr` 配置的字符 | 同上，按行数决定是点击还是换页 |
| `btnwait` / `btnwait2` | 等按钮；可用 `btntime` 设置超时 |
| `select` / `selgosub` / `selnum` | 自带选项按钮并等待 |
| `selectbtnwait` | `*customsel` 内的按钮等待 |
| `textbtnwait` | `textgosub` 自定义等待标签内的按钮等待 |

### 9.2 等待计时
| 命令 | 说明 |
|---|---|
| `wait N` | 等 N 毫秒，**点击无效** |
| `delay N` | 等 N 毫秒，**点击可跳过**；skip 模式下直接略过 |
| `waittimer N` | 等到内部计时器达到 N（最精确的计时手段） |
| `!w N` | 文本内联版 `wait N`（参数必须字面量） |
| `!d N` | 文本内联版 `delay N`（参数必须字面量） |
| `autoclick N` | 设置点击等待的超时（不是自身阻塞，而是给 `@`/`btnwait` 加超时） |
| `spwait N` | 等某精灵的动画播完或被点击打断 |

### 9.3 等待效果播放结束
| 命令 | 说明 |
|---|---|
| `print` | 效果播放期间一直循环 `doEffect`；效果号 0 时**不阻塞且不刷新屏幕** |
| `bg` / `ld` / `cl` / `tal` | 带效果的切换会等效果播完 |
| `effect`（配合上面的显示命令） | 同上 |
| `windoweffect` | 文本框显示/隐藏的效果；**`texton`/`textoff` 内部就是 `while (doEffect(&window_effect))`，所以会按效果时长阻塞**（源码已核对） |
| `quake` / `quakex` / `quakey` | 震动效果按时长阻塞（时长会被抬到 ≥ 振幅×4） |
| `flushout N` | NScr 专有效果，按 N 毫秒阻塞 |
| `texton` / `textoff` | 若设置了窗口效果，会等效果播完 |

### 9.4 等待对话框
| 命令 | 说明 |
|---|---|
| `yesnobox` / `okcancelbox` | 模态对话框，`WAIT_BUTTON_MODE` 循环 | 
| `mesbox` | 消息框（**ONScripter 未实现**，仅 NScr） |
| `input` / `inputstr` / `inputnum` | 文本/数字输入对话框 |
| `textfield` | 屏幕内文本输入框 |

### 9.5 等待外部/媒体
| 命令 | 说明 |
|---|---|
| `mpegplay` / `avi` / `movie` | 影片播放期间占据事件循环 |
| `play` / `playonce` | CD 音轨播放（同步等待到开始播放） |
| `mp3stop` / `bgmstop` | **仅当设置了淡出且曲目不是 OGG 时**，会阻塞到淡出结束 |
| `shell` / `winexec` | `winexec` 的同步标志非 0 时会等外部程序结束 |

### 9.6 明确**不阻塞**的常被误认为阻塞的命令
`wait`/`delay` 之外的几乎所有状态设置类命令都不阻塞：`lsp`、`lsp2`、`msp`、`msp2`、`amsp`、`amsp2`、`csp`、`csp2`、`vsp`、`vsp2`、`cell`、`bar`、`barclear`、`prnum`、`monocro`、`nega`、`shadedistance`、`transmode`、`humanz`、`windowback`、`mov/add/...`、`bgm/wave/dwave/mp3/sevol/...`、`savegame`、`loadgame`、`saveoff`、`savenumber`、`getspmode`、`skip`、`skipoff`、`isskip`、`getini`、`checkpage`、`resettimer`、`caption`、`game`、`reset`。

> 注意：**`msp`/`amsp`/`lsp` 等改了也不会立刻上屏**，必须后面有 `print`（或 `repaint`）。这不叫「阻塞」，而是「需要显式刷新」。

---

## 10. MGQ 实证附录

### 10.1 命令使用频次（全文 382,325 行，前 40 名）

| 命令 | 次数 | 命令 | 次数 |
|---|---|---|---|
| `name`（defsub） | 66703 | `screen_clear`（defsub） | 280 |
| `if` | 48949 | `textoff` | 186 |
| `mov` | 32715 | `lsp2` | 155 |
| `lsp` | 21835 | `for` | 134 |
| `gosub` | 13903 | `saveoff` | 132 |
| `dwave` | 6000 | `next` | 131 |
| `skillname`（defsub） | 5182 | `csel` | 111 |
| `goto` | 4871 | `quake` | 104 |
| `print` | 3124 | `btnwait2` | 100 |
| `csp` | 2959 | `exbtn` | 96 |
| `return` | 2760 | `split` | 65 |
| `rnd2` | 2302 | `notif` | 63 |
| `cspl`（defsub） | 2291 | `texton` | 62 |
| `bg` | 1603 | `spbtn` | 55 |
| `skip` | 1521 | `textclear` | 51 |
| `damage`（defsub） | 1414 | `atoi` / `itoa` / `csp2` / `msp2` | 50 |
| `count`（defsub） | 1288 | `amsp` | 49 |
| `numalias` | 1200 | `vspl`（defsub） | 44 |
| `skillcount3/2/1`（defsub） | 781/637/385 | `btnwait` | 31 |
| `dwavestop` | 754 | `add` | 27 |
| `vsp` | 555 | `monocro` | 25 |
| `shadedistance` | 518 | `flushout` | 22 |
| `btndef` | 514 | `mpegplay` | 18 |
| `bgmstop` | 508 | `screen_clear2`（defsub） | 14 |
| `sean_change`（defsub） | 436 | `getparam` / `isskip` | 10 |
| `getret` | 399 | `barclear` | 7 |
| `getini` | 336 | `len` | 6 |
| `wait` | 329 | `checkpage` / `setwindow3` | 5 |
| `itoa2` | 321 | `getcselstr` / `yesnobox` / `getpage` | 4 |

**出现 0 次（在本文核对范围内）**：`dwaveon`、`dwaveoff`、`glass`、`monocrooff`、`cselstr`、`vsp2`、`amsp2`、`screen_appear`、`se`、`save`、`load`、`savenumber`、`bgmonce`、`wave`、`setwindow`、`setwindow2`、`br`、`mesbox`、`stralias`、`effect`、`nega`、`jmp`、`mul`、`div`、`mod`、`dec`、`break`、`dim`（1 次）、`movl`（1 次）等。

### 10.2 关键用法示例（可直接作为回归测试用例）

```
; 头部配置
;$V10000G1500S800,600L10000

; 标题/定义块
*define
    caption "勇者大战魔物娘！"
    nsa
    filelog
    labellog
    textgosub *text_btn
    globalon
    usewheel
    defaultspeed 20,10,2
    maxkaisoupage 200
    rubyon 10,10
    shadedistance 1,2
    automode
    deletemenu
    savedir "save"
    humanz 600
    windowback
    autosaveoff
    kidokuskip
    bgmfadeout 1000
    loadgosub *load_config
    effectcut
    mode_wave_demo
    exec_dll "NSFont.dll/gaiji,…"
    spi "nscrpng.spi|png"
    defsub name / vspl / cspl / … (18 条)
    numalias textkai,0 / textlastx,1 / … (1200 条)
game                       ; ← 结束定义块，跳到 *start

*start
    if %gamefirst=0 mov %textspeed,15
    if %gamefirst=0 mov %bgmvol,50
    …
```

```
; 精灵与仿射
lsp 704,":c;system\ef_black.jpg",0,0
lsp 700,":s/20,20,1;#FFFFFF文本内容",152,83
lsp2 255,":a;chara\alice_st01b.bmp",400,300,100,100,0
msp2 0,10,-10,0,0,15,0
amsp 700,172,0+%move
csp2 255:print 10,1000

; 效果与演出
bg black,7,1500
print 10,500:lrclick
getret %hanyo1:if %hanyo1=0 return "*zukan_"+$zlavel
quake 10,1500
monocro #cc0000
print 10,3000
monocro off

; 语音与音效
dwave 0,"voice\0001.ogg"
dwavestop 0
dwaveloop 5,"se\hsean01_innerworks_a1.ogg"

; 自定义选项
csel "・不需要",*sean0000b,"・需要",*sean0000b
csel "・不跳过",*sean0000x,"・跳过",*sean0000x

; 存档界面用的 ini 数据库
getini $name,$mon_labo_mon_ini,"data","name"
getini $sub1,$mon_labo_mon_ini,"data","monster_y":atoi %monster_y,$sub1

; 存档元数据解析
split $%sub1,",",$savemonth,$saveday,$savehour,$saveminute,$savetext
```

---

## 11. 未确认 / 存疑

本节列出**无法从来源确认**的点。实现时请勿臆测；建议对每条做「保守实现 + 运行期记录」。

### 11.1 完全无法确认语义的命令（源码与脚本中都不存在）

| 命令 | 已知信息 | 建议 |
|---|---|---|
| `dwaveon` | ONScripter 指令表无、MGQ 脚本 0 次 | 不实现；遇到则忽略并记录 |
| `dwaveoff` | 同上 | 同上 |
| `glass` | 同上 | 同上 |
| `monocrooff` | 不是命令；恢复彩色应写 `monocro off` | 若遇到，等价处理为 `monocro off` |
| `cselstr` | 不是命令；只有 `getcselstr` | 不实现 |
| `se` | 不是命令；音效请用 `wave`/`dwave` | 不实现（可映射到 `dwave`） |
| `jmp` | NScripter 没有 `jmp`；只有 `jumpf`/`jumpb`/`goto` | 不实现 |
| `cspl` / `vspl` | **MGQ 的 defsub**，不是内建 | 通过 defsub 展开；若通用化需另立扩展 |
| `screen_clear` / `screen_clear2` / `screen_vanish` / `screen_appear` | **MGQ 的 defsub** | 同上 |
| `getini` | **仅 NScr 本体**，ONScripter 无实现 | 需自行实现 ini 解析；find 不到 key 时的返回值未确认 |
| `flushout` | **仅 NScr 2.48+**，ONScripter 无实现 | 需自行实现「白屏收尾」的转场 |

> 说明：ONScripter-Plus（第三方 Android 分支）**可能**定义了上面部分名字，但本次调研**无法访问其源码或 wiki**（github.com 与 raw.githubusercontent.com 在本机网络下不可达，jsdelivr 对该仓库亦报错）。因此这些命令的确切语义在本文件中**属于未确认**，而不是「不存在于所有方言」。

### 11.2 语义细节存疑

1. **`getini` 的边界行为**：section/key 不存在时返回什么（空串？保持原值？）、ini 是否允许注释与前后空格、是否区分大小写 —— 全部【未确认】，需要 NScr 本体实测。
2. **`itoa2` 在 UTF-8 模式下的行为**：源码显示此时它与 `itoa` 完全相同（不做全角转换）。Android 端若统一走 UTF-8，则**全角数字会丢失**，这是与 NScr 原生表现的**已知差异**，需要产品层确认是否要手工补全角映射。
3. **`lsp` 的动画 `loop_mode` 0 的确切含义**：源码只判定 `!=3` 即「可动画」，0/1/2 的差别在动画推进函数里（未逐行核对）。MGQ 的 `:l/2,160,0;` 光标可正常闪烁，说明 0 是「循环」或等价行为。
4. **除数为 0**（`div` / `mod` / `/` 表达式）：源码无保护，属 C 未定义行为 ——【未确认】NScr 实际表现（崩溃 or 0）。
5. **`skip` 负数跨标签**：源码用「当前标签起始行 + 当前行 + N」定位，跨出标签边界的实际行为未验证；MGQ 未出现负数用法。
6. **`select` / `selgosub` / `selnum` 的自动排版细节**（列数、行距、溢出行为）未逐行核对；MGQ 未使用。
7. **`btntime` 的超时值与 `usewheel` 组合**（返回 −2 还是 −5）来自手册表格，源码未逐行验证。
8. **`mesbox` 在 Android 上的等价物**：NScr 是 Win32 消息框；Android 端需自选实现（阻塞到用户确认），具体外观不影响语义。
9. **`quake` 的随机种子与逐帧位移公式**未核对（只确认了「时长 ≥ 振幅×4」与阻塞特性）。
10. **存档文件的字节序**：ONScripter 直接写主机字节序，未做归一化。若要与既有 PC 存档互通，需要确定原游戏存档是**小端**（Windows），本文按小端建议，但**未用真实存档文件验证**。
11. **`effectblank` 的默认值 10ms** 来自 ONScripter 源码；NScr 本体的默认值【未确认】。
12. **`skipoff` 与 `kidokuskip` 的交互**（未读停模式下 `isskip` 返回 0 的时机）来自手册注记，未做实测。

---

## 12. 来源

以下为本次调研**实际访问并使用**的来源（按使用权重排序）：

### 一级：ONScripter 源码（语义判定的最终依据）

- [ONScripter_lut.cpp（指令名表）](https://cdn.jsdelivr.net/gh/uyjulian/onscripter@master/ONScripter_lut.cpp)
- [ONScripter_command.cpp（各指令实现）](https://cdn.jsdelivr.net/gh/uyjulian/onscripter@master/ONScripter_command.cpp)
- [ScriptParser_command.cpp（定义块指令/跳转实现）](https://cdn.jsdelivr.net/gh/uyjulian/onscripter@master/ScriptParser_command.cpp)
- [ScriptParser.cpp（默认值、存档字段顺序、效果解析）](https://cdn.jsdelivr.net/gh/uyjulian/onscripter@master/ScriptParser.cpp)
- [ScriptHandler.cpp（分词、变量、标签索引）](https://cdn.jsdelivr.net/gh/uyjulian/onscripter@master/ScriptHandler.cpp)
- [ONScripter.cpp（parseLine / flush）](https://cdn.jsdelivr.net/gh/uyjulian/onscripter@master/ONScripter.cpp)
- [ONScripter_image.cpp（refreshSurface 图层顺序）](https://cdn.jsdelivr.net/gh/uyjulian/onscripter@master/ONScripter_image.cpp)
- [ONScripter_animation.cpp（parseTaggedString）](https://cdn.jsdelivr.net/gh/uyjulian/onscripter@master/ONScripter_animation.cpp)
- [AnimationInfo.cpp / AnimationInfo.h（仿射矩阵、混合）](https://cdn.jsdelivr.net/gh/uyjulian/onscripter@master/AnimationInfo.cpp)
- [ONScripter_effect.cpp（效果 0/1 与内建效果实现）](https://cdn.jsdelivr.net/gh/uyjulian/onscripter@master/ONScripter_effect.cpp)
- [ONScripter_file.cpp / ONScripter_file2.cpp（存档格式）](https://cdn.jsdelivr.net/gh/uyjulian/onscripter@master/ONScripter_file2.cpp)
- [ONScripter.h / ScriptHandler.h / ScriptParser.h（常量与枚举）](https://cdn.jsdelivr.net/gh/uyjulian/onscripter@master/ONScripter.h)
- [uyjulian/onscripter（镜像仓库首页）](https://github.com/uyjulian/onscripter)

### 二级：NScripter 指令参考（手册层语义、参数含义）

- [*NScripter API Reference（senzogawa 编纂, kaisernet 英译汇编版）](https://kaisernet.org/onscripter/api/NScrAPI.html)
- [senzogawa's NScripter Scripting Factory（日文原始参考入口）](http://senzogawa.s90.xrea.com/)
- [senzogawa NScripter 命令リファレンス（日文原始页）](http://senzogawa.s90.xrea.com/reference/NScrAPI.html)
- [insani.org NScripter Reference（官方手册英译镜像，逐命令页）](http://nscripter.insani.org/reference/meirei/lsp.htm)
- [insani 参考的命令分类目录](http://nscripter.insani.org/reference/group/cont24.htm)
- [insani NScripter 参考字母索引](http://nscripter.insani.org/reference/alphabet/alphabet.htm)
- [NScripter 指令手册中文不完全版（转载，用于中文术语对照）](https://www.cnblogs.com/1288blog/p/18921604)

### 三级：ONScripter / 生态说明

- [chaoskaiser72's ONScripter Corner（ONScripter-EN 版本与 ons.cfg 说明）](https://kaisernet.org/onscripter/)
- [ONScripter 官方站点（本次访问返回 404 / 证书错误，未能取得内容）](https://onscripter.osdn.jp/onscripter.html)
- [okamstudio/onscripter-plus（目标源，本机网络不可达，未能取得内容）](https://github.com/okamstudio/onscripter-plus)
- [matthewn4444/onscripter-plus-android wiki（经镜像站访问，仅含功能说明，无指令表）](https://github-wiki-see.page/m/matthewn4444/onscripter-plus-android/wiki/Features)

### 四级：目标游戏本体（实证来源）

- `D:\游戏\勇者大战魔物娘三章剧情汉化整合版\nscript.dat`（9,736,822 字节；逐字节 XOR `0x84` 解密后 382,325 行）——用于全部【实证】标注

### 访问失败的来源（已尝试，供后续补充）

- `https://onscripter.osdn.jp/onscripter.html`：TLS 信任失败 / 404（OSDN 侧返回「無効なURLです」）
- `https://github.com/okamstudio/onscripter-plus` 及其 wiki：本机网络不可达（`fetch failed`）
- `https://raw.githubusercontent.com/...`：同上
- `https://data.jsdelivr.com/v1/packages/gh/okamstudio/onscripter-plus`：`Couldn't fetch versions`（仓库不可达）
- `https://deepwiki.com/umineko-project/onscripter-ru/...`：Vercel 人机校验拦截
- `https://www.umic.jp/shokubataiken/nscripter-text1.pdf`：站点已闭馆，跳转到公告页

---

## 附录 A：被请求命令的最终状态表

| 用户列出的名字 | 归类 | 状态 |
|---|---|---|
| `goto` `gosub` `return` `if` `for` `next` `break` `mov` `add` `sub` `inc` `dec` `mul` `div` `mod` `itoa` `itoa2` `atoi` `len` `rnd` `rnd2` `split` `getparam` `getret` `defsub` `numalias` `stralias` `game` `caption` `skip` `skipoff` `isskip` `wait` `delay` `resettimer` `checkpage` | 核心流程 | ✅ 引擎内建（已详述） |
| `jmp` | 核心流程 | ❌ 不存在（只有 `jumpf`/`jumpb`） |
| `getini` | 核心流程 | ⚠️ 仅 NScr 本体；ONScripter 无实现 |
| `flushout` | 核心流程 | ⚠️ 仅 NScr 2.48+；ONScripter 无实现 |
| `print` `texton` `textoff` `textclear` `br` `setwindow` `setwindow2` `setwindow3` `windoweffect` `click` `yesnobox` `btndef` `btnwait` `btnwait2` `btndown` `spbtn` `exbtn` `csel` `getcselstr` `lrclick` `monocro` | 文本/按钮 | ✅ 引擎内建 |
| `mesbox` | 文本 | ⚠️ 仅 NScr 本体 |
| `name` | 文本 | ❌ 非内建；MGQ 的 defsub |
| `cselstr` | 按钮 | ❌ 不存在（应为 `getcselstr`） |
| `monocrooff` | 效果 | ❌ 不存在（写作 `monocro off`） |
| `bg` `lsp` `lsp2` `csp` `csp2` `msp2` `vsp` `amsp` `quake` `shadedistance` `bar` `barclear` `effect` `mpegplay` `nega` | 图形 | ✅ 引擎内建 |
| `bg black` | 图形 | ✅ `bg` 的颜色参数写法（`bg black,<效果>`） |
| `cspl` `vspl` `screen_clear` `screen_clear2` `screen_vanish` `screen_appear` | 图形 | ❌ 非内建；MGQ 的 defsub |
| `glass` | 图形 | ❌ 未确认/未找到 |
| `bgm` `bgmstop` `bgmonce` `wave` `dwave` `dwavestop` `mp3` `mp3stop` | 音频 | ✅ 引擎内建 |
| `dwaveon` `dwaveoff` `se` | 音频 | ❌ 未确认/未找到 |
| `save` `load` | 存档 | ❌ 非脚本命令（是 `rmenu` 的功能项名）；脚本用 `savegame`/`loadgame` |
| `saveoff` `savenumber` `getspmode` | 存档 | ✅ 引擎内建 |

## 附录 B：重实现检查清单（易错点）

1. 命令 token **小写化**、**大小写不敏感**；标签名同样小写化。
2. **defsub 优先于内建**；行首 `_` 绕过 defsub。
3. 行首 `%`/`$`/`?`/数字/双字节字符 = **文本行**，不是命令。
4. `:` 链式命令；`if` 条件不成立时**整行剩余（含 `:` 后续）全部跳过**。
5. `&` 与 `|` 不可混用；**没有 `and`/`or`/`not`**。
6. `len`/`mid` 以**字节**计（汉字 2 字节）；`atoi` 不认全角；`itoa2` 在 UTF-8 下退化为 `itoa`。
7. `skip` 正数向文件尾方向；`0` 视作 `1`；基准是当前标签行 + 当前行。
8. `return` 在非 gosub 上下文是**致命错误**；`return *label` = 先返回再跳转（动态标签）。
9. `lsp` 坐标 = **左上角**；`lsp2` 坐标 = **中心**；旋转**逆时针**为正；缩放为**百分比**。
10. 精灵号**越小越靠前**；`humanz` 设分界（默认 499）；`windowback` 会改变文字窗与扩展精灵的层级。
11. `msp2`/`msp` 的不透明度是**增量**，且首次调节以 255 为基准（`trans == -1` 分支）。
12. 改动精灵/bar/monocro 后**必须 `print` 或 `repaint` 才上屏**。
13. `print 0` = 不刷新屏幕；`print 1` = 瞬时；`print N,M` = 效果 N 持续 M 毫秒并**阻塞**。
14. `csp -1` 清全部普通精灵；`csp2 -1` 清全部扩展精灵；`barclear` 清全部 bar（无参数）。
15. `saveoff` 是「锁定存档点」而不是「禁止存档」；跨 `saveoff` 区间的存档会回到最后一次 `saveon`/`savepoint` 的状态。
16. `checkpage %v,N`：**1 = 存在，0 = 不存在**；N=0 是当前页。
17. `lrclick` + `getret`：**右键 0，左键 1**。
18. `wait` 不可点击跳过，`delay` 可以（手册写反了，以源码为准）。
19. 全局变量（编号 ≥ `G`）不随 `reset` 清零、单独持久化。
20. 存档魔数 `"ONS"`，版本 2.8，字段顺序见 §7.6；**扩展精灵（lsp2）与 dwave 播放位置不存档**。
