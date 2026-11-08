/* img_vfd_real.c — 给工具端的 `--wrap` 把 `__real_*` 补上（2026-10-26）
 *
 * ★ 为什么 App 侧要管这个：
 *   imgtool 的 `img_vfd.c`（wimlib 直读 ISO 用的"虚拟 fd"）是用
 *   `-Wl,--wrap=open,open64,read,pread,lseek,fstat,close,realpath` 编的。
 *   `--wrap` 只把「**本模块内**对 open 的引用」改道到 `__wrap_open`；被包装的
 *   `__real_open` 在**动态库**里没人定义 → 载入时报
 *
 *       dlopen failed: cannot locate symbol "__real_open"
 *
 *   `imgcmd`（可执行文件）能跑，是因为 wrap 在**最终链接**时生效；App 这边
 *   libimgapp.so 是动态库，`libimgtool.so` 里的 `__real_*` 一直悬着。
 *   实测（2026-10-26，so `8335a9d8…`）：`ctypes.CDLL(libimgtool.so)` →
 *   `undefined symbol: __real_close`；装机后所有命令都变成 JNI 失败。
 *
 * 我们这一层的做法：在**先加载的** libimgapp.so 里把 `__real_*` 实现成
 * "直接调 libc 同名函数"。工具端将来自己补上也不冲突（先加载的定义优先）。
 * 等他们修好构建，这个文件可以删（删之前确认 libimgtool.so 里没有
 * `U __real_*`：`nm -D --undefined-only libimgtool.so | grep __real`）。
 */
#include <fcntl.h>
#include <stdarg.h>
#include <stdlib.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#define VFD_EXPORT __attribute__((visibility("default")))

static mode_t mode_of(int flags, va_list ap) {
    if (flags & (O_CREAT | O_TMPFILE)) return (mode_t)va_arg(ap, int);
    return 0;
}

VFD_EXPORT int __real_open(const char *path, int flags, ...) {
    va_list ap;
    va_start(ap, flags);
    mode_t m = mode_of(flags, ap);
    va_end(ap);
    return open(path, flags, m);
}

VFD_EXPORT int __real_open64(const char *path, int flags, ...) {
    va_list ap;
    va_start(ap, flags);
    mode_t m = mode_of(flags, ap);
    va_end(ap);
    return open(path, flags, m);
}

VFD_EXPORT ssize_t __real_read(int fd, void *buf, size_t n) {
    return read(fd, buf, n);
}

VFD_EXPORT ssize_t __real_pread(int fd, void *buf, size_t n, off_t off) {
    return pread(fd, buf, n, off);
}

VFD_EXPORT off_t __real_lseek(int fd, off_t off, int whence) {
    return lseek(fd, off, whence);
}

VFD_EXPORT int __real_fstat(int fd, struct stat *st) {
    return fstat(fd, st);
}

VFD_EXPORT int __real_close(int fd) {
    return close(fd);
}

VFD_EXPORT char *__real_realpath(const char *path, char *resolved) {
    return realpath(path, resolved);
}