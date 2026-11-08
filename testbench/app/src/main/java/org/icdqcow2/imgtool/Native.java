package org.icdqcow2.imgtool;

/**
 * imgtool 的 JNI 入口（薄层）。
 *
 * <p>调用约定与 CLI 完全一致：命令名 + 字符串参数 → 一行 JSON 信封。
 * 库不发用户文案；界面文字请查 {@code app/i18n/strings.json}。
 *
 * <p>线程：{@link #exec} 是阻塞调用，请放到后台线程；{@link #cancel()}
 * 可以在任意线程调（协作式取消，下一个检查点生效）。
 *
 * <p>加载的 so 名：libimgapp.so（包含 imgtool 静态库或链接 libimgtool.so）。
 */
public final class Native {
    static {
        System.loadLibrary("imgapp");
    }

    private Native() {}

    /** 事件回调（在工作线程上调用）。jsonLine 是一行 JSON，字段见 app/jni/README.md。 */
    public interface EventSink {
        void onEvent(String jsonLine);
    }

    /** imgtool 版本（如 "imgtool 2026.10.15"）。 */
    public static native String version();

    /** 命令表 JSON（命令/参数规格/帮助/features）。App 用它自动生成界面并提前灰掉不可用功能。 */
    public static native String list();

    /**
     * 执行一个命令。
     *
     * @param cmd        命令名，如 "boot.repair"（见 {@link #list()}）
     * @param keys       参数名数组（不含 --），可为 null
     * @param vals       参数值数组（全部字符串），可为 null
     * @param imagePath  镜像路径；传 null 时用 imageFd
     * @param imageFd    Android SAF 的 ParcelFileDescriptor.getFd()；没有传 -1
     * @param writable   写操作传 true
     * @param checkSize  写前校验分区空间（可选）
     * @param logPath    日志路径；null=不写日志（建议放 App 的 cacheDir）
     * @param logLevel   0=ERROR 1=WARN 2=INFO 3=DEBUG 4=TRACE
     * @return 一行 JSON 信封（成功 {"ok":true,"data":{...}}；失败 {"ok":false,"err":{...}}）
     */
    public static native String exec(String cmd, String[] keys, String[] vals,
                                     String imagePath, int imageFd,
                                     boolean writable, boolean checkSize,
                                     String logPath, int logLevel);

    /** 上一次失败的结构化快照（cmd/code/rc/at/image/log/status/events）。没有失败时 code 为空。 */
    public static native String lastError();

    /** 请求取消正在执行的操作。 */
    public static native void cancel();

    /** 设置/清除事件回调（传 null 清除）。建议在第一次 exec 前设置。 */
    public static native void setEventSink(EventSink sink);

    /** 事件级别（默认 2=INFO）。调 3/4 会收到很细的过程事件。 */
    public static native void setEventLevel(int level);

    /**
     * 设临时目录（★ Android 没有 /tmp，写操作前必须调）。
     * 传 context.getCacheDir().getAbsolutePath()；BCD 读写、恢复分区填充都要用它。
     * @return true=设置成功；false=路径为空或不可写
     */
    public static native boolean setTmpdir(String dir);

    /** I/O 无进展超时（毫秒；0=关闭，默认 30000）。 */
    public static native void setIoTimeoutMs(int ms);
}