/* imgapp_bridge.h — App 侧 C 桥（JNI 与平台无关的核心部分）
 *
 * 为什么分两层：
 *   · 这一层是纯 C，可以在这台 Linux 上用普通 gcc 编译、跑真实镜像做回归；
 *   · imgapp_jni.c 只做「Java 类型 ↔ C 字符串/数组」的转换，逻辑很薄。
 *   Android 上（NDK）和主机上跑的是同一份桥代码，问题不会只出现在手机上。
 *
 * 契约（与 CLI 完全一致）：
 *   输入  = 命令名 + 参数键值对（全部字符串）+ 镜像路径或 SAF fd
 *   输出  = 一行 JSON 信封（stdout 那份），App 自己解析 + 用自己的文案表渲染
 *   错误  = 语义码（FAT-ENOSPC 等）+ rc + at；桥不返回任何面向用户的文案
 *
 * 线程：
 *   imgapp_exec() 内部串行（同一进程同一时刻只跑一个操作，和 CLI 一样）；
 *   imgapp_cancel() 可以从任意线程调用（协作式取消，下一个检查点生效）。
 */
#ifndef IMGAPP_BRIDGE_H
#define IMGAPP_BRIDGE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct imgapp_req {
    const char *cmd;            /* 命令名，如 "boot.repair"（必填） */
    const char *const *keys;    /* 参数名数组（不含 --） */
    const char *const *vals;    /* 参数值数组（全部字符串，与 keys 一一对应） */
    int         nargs;

    /* 镜像来源二选一：
     *   image_path != NULL  → 普通路径（桌面/Android 私有目录）
     *   image_fd   >= 0     → Android SAF 的 ParcelFileDescriptor.getFd()
     * 两者都给时以 image_path 为准。命令本身不需要镜像（cmd.list/ctl.cancel）时可都不给。*/
    const char *image_path;
    int         image_fd;
    int         writable;       /* 0=只读打开，1=可写（写命令必须 1） */
    int         check_size;     /* 1=写前校验分区空间（可选） */

    const char *log_path;       /* NULL=不写日志；建议 App 传 cacheDir 下的路径 */
    int         log_level;      /* 0=ERROR 1=WARN 2=INFO(默认) 3=DEBUG 4=TRACE */
} imgapp_req;

typedef struct imgapp_reply {
    char *json;                 /* malloc 的 JSON 信封；用 imgapp_free() 释放 */
    int   rc;                   /* 0 或 img_err_t（负值）；json 里也有 rc */
} imgapp_reply;

/* 事件回调：json_line 一行 JSON，见 docs/JNI.md 的字段表。
 * ★ 在【工作线程】上调用；JNI 层会负责 AttachCurrentThread。
 * ★ 回调里不要调用 imgapp_exec()（会等自己）。*/
typedef void (*imgapp_event_cb)(const char *json_line, void *ud);

int  imgapp_init(void);                 /* 进程启动调一次；默认开 30s I/O 看门狗 */
void imgapp_shutdown(void);
int  imgapp_exec(const imgapp_req *req, imgapp_reply *out,
                 char *err, size_t errcap);   /* 0=桥层成功（命令可能失败，看 out->rc） */
char *imgapp_list_json(void);           /* 命令表 JSON（App 自动生成界面） */
char *imgapp_last_error_json(void);     /* 上次失败的结构化快照（含事件尾部） */
void imgapp_cancel(void);               /* 请求取消（另一线程调用） */
void imgapp_set_event_cb(imgapp_event_cb cb, void *ud);
void imgapp_set_event_level(int lv);    /* 默认 2=INFO；3/4 会很多，除非要做细进度 */
void imgapp_set_io_timeout_ms(int ms);  /* 0=关闭；默认 30000 */
/* ★ 安卓没有 /tmp：App 必须在任何写操作之前把临时目录指到自己的 cache 目录，
 *   否则 bcd.create / BCD 读写会报 TMPDIR-UNAVAILABLE。
 *   dir=NULL/空/不可写 → IMG_EINVAL。可在 imgapp_init() 前后调用。 */
int  imgapp_set_tmpdir(const char *dir);
void imgapp_set_lang(const char *code);   /* 库内人话（note/summary/检查项名）的语言："zh"/"en" */
const char *imgapp_version(void);
void imgapp_free(void *p);

#ifdef __cplusplus
}
#endif
#endif