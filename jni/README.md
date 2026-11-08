# JNI 桥：字段与注意事项

## 1. Java 方法

```java
String list();                       // 命令表 JSON
String exec(cmd, keys[], vals[], imagePath, imageFd,
            writable, checkSize, logPath, logLevel);
String lastError();                  // 上次失败的结构化快照
void   cancel();
void   setEventSink(EventSink sink); // 工作线程回调
void   setEventLevel(int level);     // 默认 2=INFO
void   setIoTimeoutMs(int ms);       // 默认 30000，0=关闭
String version();
```

返回的 JSON 信封（与 CLI stdout 完全一致）：

```json
{"ok":true,"cmd":"img.info","version":1,"duration_ms":0,"data":{...}}
{"ok":false,"cmd":"boot.repair","version":1,"duration_ms":0,
 "err":{"rc":-2,"error_code":"BCD-REF-BAD","at":"/path/BCD"}}
```

**信封里没有中文文案**。`error_code` / `data` 的键是稳定契约；界面文字查
`../i18n/strings.json`（中英各一份，`check_i18n.py` 会对账）。

## 2. 事件 JSON（`setEventSink`）

工作线程上回调；JNI 层自动 `AttachCurrentThread`。默认只推 `INFO` 及以上
（`begin` / `done` / `cancel` / `alive`），需要细进度再 `setEventLevel(3|4)`。

```json
{"level":"INFO","logger":"fat32","event":"begin","rc":0,
 "message":"写入 FAT32",
 "fields":{"action":"put","path":"/EFI/a.bin","size_bytes":5}}
{"level":"INFO","logger":"fat32","event":"done","rc":0,
 "fields":{"action":"put","duration_ms":3,"written_bytes":5}}
```

字段说明：

| 字段 | 含义 |
|---|---|
| `level` | ERROR / WARN / INFO / DEBUG / TRACE |
| `logger` | 模块（fat32/ntfs/part/wim/winre/bcd…） |
| `event` | begin / done / cancel / alive / fail / 其它细节 |
| `rc` | 0 或负错误码（done/fail 必有） |
| `fields.action` | 动作名（put/format/apply…）→ 查 i18n `actions` |
| `fields.error_code` | 稳定错误码 → 查 i18n `errors` |
| `fields.*` | 数值（lba/path/size_bytes/duration_ms…），单位进键名 |

> `message` 是**开发期备注**（可能是中文），不是 App 契约；不要直接显示。

## 3. 镜像来源：path 或 SAF fd

- 普通路径：`imagePath` 传路径，`imageFd=-1`。
- Android SAF：`imagePath=null`，`imageFd=ParcelFileDescriptor.getFd()`。
  桥内部用 `/proc/self/fd/<fd>` 打开（桌面 Linux 与 Android 都有效），
  **自己另开一份文件描述符**，不会关掉 App 的 fd。
- 两者都给：以 `imagePath` 为准。

⚠ 已知边界：少数 ROM 的 SELinux 策略可能挡住 `/proc/self/fd`。
真机上若 `IMG-OPEN-FAIL`，在 Android 兼容工作区给核心加 `img_ctx_open_fd()`
（这是 Android 专属改动，放 `/workspace/imgtool-android`，不要污染基线）。

## 4. 日志与错误快照

- `logPath`：建议放 `context.getCacheDir()/imgtool-<timestamp>.log`；
  传 `null` 不落盘。日志是 logfmt（一行一个事件，E 级任何级别都不丢）。
- `lastError()`：失败后取结构化快照：
  ```json
  {"cmd":"boot.repair","code":"BCD-REF-BAD","rc":-2,"at":"...",
   "image":"...","log":"...","status":"（中文备注，仅调试）",
   "events":"（最近事件尾部，机器格式）"}
  ```
  App 用 `code` + i18n 渲染卡片；`events` 可折叠展示，供用户反馈。

## 5. 线程与取消

- `exec` 阻塞，放后台线程；同一进程内串行（内部有锁），不要并发调两个 `exec`。
- `cancel()` 任意线程可调；协作式，下一个 1MiB 检查点生效。
- 每个 `exec` 开始时自动 `img_cancel_reset()`，所以取消不会"污染"下一次操作。
- `setEventSink` 建议在第一次 `exec` 前设置；回调里不要调 `exec`（会等自己）。

## 6. 内存与字符串

- `reply.json` 在 C 侧 malloc，JNI 转成 Java String 后立即释放；Java 端无需手动释放。
- 输入用 `GetStringUTFChars`（modified UTF-8）；普通路径/中文可用。
  4 字节 emoji 路径在输入侧是 CESU-8，极罕见；输出侧已用真正的 UTF-16 转换，
  不会出现"问号"。
- 大结果（如 `part.show` 128 个分区）内部会扩到 4MiB 缓冲；仍然超出会截断成合法 JSON。