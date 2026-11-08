/* test_bridge.c — 主机侧回归（不需要 Android，直接跑真实 qcow2）
 * 用法: test_bridge <qcow2> [espLba srcFile]
 */
#include "imgapp_bridge.h"
#include "img_types.h"
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static int pass = 0, fail = 0;
static void ok(const char *m){ printf("  ✅ %s\n", m); pass++; }
static void ng(const char *m){ printf("  ❌ %s\n", m); fail++; }
static int events = 0;
static int ev_bad = 0;
/* ★ 事件行必须是【合法 JSON】。以前只数条数，桥里漏了逗号
 *   （{"level":"INFO""logger":...}）也照样"通过" —— 2026-10-20 实测才发现。
 *   这里做结构校验；严格 json.loads 由 run_host_test.sh 用 python 复核。*/
static void on_event(const char *json, void *ud){
    (void)ud;
    if(!json) return;
    if(strstr(json, "\"event\"")){
        events++;
        if(json[0] != '{' || json[strlen(json)-1] != '}') ev_bad++;
        if(strstr(json, "\"\"level\"") || strstr(json, "\"\"logger\"") ||
           strstr(json, "\"\"event\"")  || strstr(json, "\"\"rc\"") ||
           strstr(json, "0\"fields\"") || strstr(json, "\"\"fields\"")) ev_bad++;
    }
}

static int run(const char *cmd, const char **keys, const char **vals, int n,
               const char *path, int fd, int writable, imgapp_reply *rep){
    imgapp_req req;
    memset(&req, 0, sizeof req);
    req.cmd = cmd;
    req.keys = keys; req.vals = vals; req.nargs = n;
    req.image_path = path; req.image_fd = fd;
    req.writable = writable; req.check_size = 0;
    req.log_level = 2;
    char err[256] = "";
    int rc = imgapp_exec(&req, rep, err, sizeof err);
    if(rc != IMG_OK){
        fprintf(stderr, "bridge error: %s (rc=%d)\n", err, rc);
        return 0;
    }
    return 1;
}

int main(int argc, char **argv){
    if(argc < 2){ fprintf(stderr, "用法: %s <qcow2> [espLba srcFile]\n", argv[0]); return 2; }
    const char *img = argv[1];
    printf("════ C 桥冒烟测试 ════\n");
    imgapp_init();
    imgapp_set_event_cb(on_event, NULL);

    printf("版本: %s\n", imgapp_version());

    char *list = imgapp_list_json();
    if(list && strstr(list, "\"boot.repair\"")) ok("cmd.list 含 boot.repair");
    else ng("cmd.list 异常");
    imgapp_free(list);

    const char *k1[] = {"img"}, *v1[] = {img};
    imgapp_reply r = {0};
    if(run("img.info", k1, v1, 1, img, -1, 0, &r)){
        if(r.json && strstr(r.json, "\"ok\":true") && strstr(r.json, "\"table\":\"GPT\""))
            ok("img.info → ok=true table=GPT");
        else ng("img.info 结果不对");
    } else ng("img.info 桥层失败");
    imgapp_free(r.json);

    if(run("part.show", k1, v1, 1, img, -1, 0, &r)){
        if(r.json && strstr(r.json, "\"parts\"")) ok("part.show → 含 partitions");
        else ng("part.show 结果不对");
    } else ng("part.show 桥层失败");
    imgapp_free(r.json);

    if(run("nosuch.cmd", NULL, NULL, 0, NULL, -1, 0, &r)){
        if(r.json && strstr(r.json, "CMD-NOTFOUND")) ok("未知命令 → CMD-NOTFOUND");
        else ng("未知命令错误码不对");
    } else ng("未知命令桥层失败");
    imgapp_free(r.json);

    char *le = imgapp_last_error_json();
    if(le && strstr(le, "CMD-NOTFOUND")) ok("lastError 含 CMD-NOTFOUND");
    else ng("lastError 异常");
    imgapp_free(le);

    /* SAF fd 路径：只给 fd，不给 path */
    int fd = open(img, O_RDWR);
    if(fd < 0){ ng("open 失败，跳过 fd 用例"); }
    else {
        if(run("img.info", NULL, NULL, 0, NULL, fd, 0, &r)){
            if(r.json && strstr(r.json, "\"ok\":true")) ok("只给 SAF fd（/proc/self/fd）也能打开");
            else ng("fd 模式结果不对");
        } else ng("fd 模式桥层失败");
        imgapp_free(r.json);
        close(fd);
    }

    if(argc >= 4){
        const char *k[] = {"img", "lba", "src", "dst"};
        const char *v[] = {img, argv[2], argv[3], "/EFI/bridge-test.bin"};
        events = 0;
        if(run("fat.put", k, v, 4, img, -1, 1, &r)){
            if(r.json && strstr(r.json, "\"ok\":true")) ok("fat.put → 写入成功");
            else ng("fat.put 结果不对");
            if(events > 0) ok("事件回调收到 begin/done");
            else ng("事件回调没收到事件");
            if(events > 0 && ev_bad == 0) ok("事件行都是合法 JSON 结构（无缺逗号/缺括号）");
            else if(ev_bad) ng("事件行结构有问题（ev_bad 见上，桥的 JSON writer 缺分隔符？）");
        } else ng("fat.put 桥层失败");
        imgapp_free(r.json);
    } else {
        printf("  ⏭ 未给 espLba/src，跳过 fat.put 事件验证\n");
    }

    /* 取消标志：下一个命令开始时必须自动复位 */
    imgapp_cancel();
    if(run("img.info", k1, v1, 1, img, -1, 0, &r)){
        if(r.json && strstr(r.json, "\"ok\":true")) ok("取消后下一次命令自动复位");
        else ng("取消后仍失败（复位没生效）");
    } else ng("复位用例桥层失败");
    imgapp_free(r.json);

    imgapp_shutdown();
    printf("════ 结果：通过 %d · 失败 %d ════\n", pass, fail);
    return fail == 0 ? 0 : 1;
}