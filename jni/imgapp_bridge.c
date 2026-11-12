/* imgapp_bridge.c — App 侧 C 桥（平台无关；JNI 只是薄薄一层）
 *
 * 设计要点：
 *   1. 输入输出全部是【字符串 / 一行 JSON】，App 不需要理解 C 结构体；
 *   2. 库只发 error_code + 数值，桥不翻译文案（文案在 i18n/strings.json）；
 *   3. 事件回调推 JSON 行，默认只推 INFO 及以上（begin/done/cancel/alive），
 *      需要细进度时 App 调 imgapp_set_event_level(3|4)；
 *   4. 同一进程串行执行（与 CLI 一致）；cancel 可以从另一线程随时调。
 */
#include "imgapp_bridge.h"
#include "img_cmd.h"
#include "img_text.h"
#include "img_event.h"
#include "img_types.h"

#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>   /* access() / setenv */
#include <string.h>

/* ---------------- 极简 JSON writer（只写，不解析） ---------------- */
typedef struct { char *p, *end; } jw;
static void jw_ch(jw *w, char c){ if(w->p < w->end) *w->p++ = c; }
static void jw_raw(jw *w, const char *s){ if(s) while(*s) jw_ch(w, *s++); }
static void jw_str(jw *w, const char *s){
    jw_ch(w, '"');
    for(; s && *s; s++){
        unsigned char c = (unsigned char)*s;
        switch(c){
            case '"':  jw_raw(w, "\\\""); break;
            case '\\': jw_raw(w, "\\\\"); break;
            case '\n': jw_raw(w, "\\n");  break;
            case '\r': jw_raw(w, "\\r");  break;
            case '\t': jw_raw(w, "\\t");  break;
            default:
                if(c < 0x20){ char b[8]; snprintf(b, sizeof b, "\\u%04x", c); jw_raw(w, b); }
                else jw_ch(w, (char)c);
        }
    }
    jw_ch(w, '"');
}
static void jw_key(jw *w, const char *k){ jw_str(w, k); jw_ch(w, ':'); }
static void jw_num(jw *w, long long n){ char b[32]; snprintf(b, sizeof b, "%lld", n); jw_raw(w, b); }

/* ---------------- 事件 → 一行 JSON ---------------- */
static const char *lv_name(img_lv_t lv){
    switch(lv){
        case IMG_LV_ERROR: return "ERROR";
        case IMG_LV_WARN:  return "WARN";
        case IMG_LV_INFO:  return "INFO";
        case IMG_LV_DEBUG: return "DEBUG";
        default:           return "TRACE";
    }
}
static void ev_to_json(const img_ev *e, char *buf, size_t cap){
    if(!buf || cap < 2){ return; }
    jw w = { buf, buf + cap - 1 };
    jw_ch(&w, '{');
    /* ★ 键之间必须写逗号：以前漏了，产出的是 {"level":"INFO""logger":...} —— 非法 JSON，
     *   App 只要 json.loads 就会整条丢掉（2026-10-20 实测发现）。*/
    jw_key(&w, "level");  jw_str(&w, lv_name(e->lv)); jw_ch(&w, ',');
    jw_key(&w, "logger"); jw_str(&w, e->mod ? e->mod : ""); jw_ch(&w, ',');
    jw_key(&w, "event");  jw_str(&w, e->ev ? e->ev : ""); jw_ch(&w, ',');
    jw_key(&w, "rc");     jw_num(&w, e->rc);
    if(e->msg && e->msg[0]){ jw_ch(&w, ','); jw_key(&w, "message"); jw_str(&w, e->msg); }
    jw_ch(&w, ','); jw_key(&w, "fields"); jw_ch(&w, '{');
    for(int i = 0; i < e->nf; i++){
        if(i) jw_ch(&w, ',');
        jw_str(&w, e->f[i].k);
        jw_ch(&w, ':');
        if(e->f[i].kind == 1)      jw_str(&w, e->f[i].s ? e->f[i].s : "");
        else if(e->f[i].kind == 2) jw_num(&w, e->f[i].n);
        else { char b[32]; snprintf(b, sizeof b, "0x%llx", e->f[i].n); jw_raw(&w, b); }
    }
    jw_ch(&w, '}');
    jw_ch(&w, '}');
    *w.p = 0;
}

/* ---------------- 全局状态 ---------------- */
static pthread_mutex_t g_exec_mu = PTHREAD_MUTEX_INITIALIZER;
static imgapp_event_cb g_ev_cb = NULL;
static void *g_ev_ud = NULL;
static volatile int g_ev_lv = IMG_LV_INFO;
static int g_inited = 0;

static void on_text(const img_ev *e, void *ud){
    (void)ud;
    if(!e) return;
    imgapp_event_cb cb = g_ev_cb;
    if(!cb) return;
    if((int)e->lv > g_ev_lv) return;
    char line[4096];
    ev_to_json(e, line, sizeof line);
    cb(line, g_ev_ud);
}

/* ---------------- 进度 → 事件 JSON（P2） ----------------
 * 事件名固定 "progress"，字段全部语言中立（phase/op 是枚举名，单位是原始字节）。
 * App 拿 pct 画进度条、拿 eta_s 显示剩余时间；item 是被处理对象的路径。
 * 注意：进度是 INFO 级 —— App 调 setEventLevel(0|1) 时会被过滤掉。*/
static void prog_to_json(void *ud, const img_progress_t *p){
    (void)ud;
    imgapp_event_cb cb = g_ev_cb;
    if(!cb || !p) return;
    if((int)IMG_LV_INFO > g_ev_lv) return;
    char line[4096];   /* REQ-69 §5：补了 elapsed/overall/msg；REQ-77 又加 dst_item，留够余量 */
    jw w = { line, line + sizeof line - 1 };
    jw_ch(&w,'{');
    jw_key(&w,"level");  jw_str(&w,"INFO");  jw_ch(&w,',');
    jw_key(&w,"logger"); jw_str(&w,"prog");  jw_ch(&w,',');
    jw_key(&w,"event");  jw_str(&w,"progress"); jw_ch(&w,',');
    jw_key(&w,"rc");     jw_num(&w,0);       jw_ch(&w,',');
    jw_key(&w,"fields"); jw_ch(&w,'{');
    jw_key(&w,"phase");  jw_str(&w, img_phase_name(p->phase)); jw_ch(&w,',');
    jw_key(&w,"op");     jw_str(&w, img_op_name(p->op));       jw_ch(&w,',');
    jw_key(&w,"cur");    jw_num(&w,(long long)p->cur);         jw_ch(&w,',');
    jw_key(&w,"total");  jw_num(&w,(long long)p->total);       jw_ch(&w,',');
    jw_key(&w,"cur_bytes");   jw_num(&w,(long long)p->cur_bytes);   jw_ch(&w,',');
    jw_key(&w,"total_bytes"); jw_num(&w,(long long)p->total_bytes); jw_ch(&w,',');
    jw_key(&w,"pct");    jw_num(&w,p->pct);   jw_ch(&w,',');
    jw_key(&w,"eta_s");  jw_num(&w,p->eta_s); jw_ch(&w,',');
    /* ★ REQ-69 §5：App 要"已用"和"阶段说明"。
     *   elapsed 是**秒**（老字段，别改含义）；elapsed_ms 是毫秒，页面用这个。*/
    jw_key(&w,"elapsed");    jw_num(&w,p->elapsed_s); jw_ch(&w,',');
    jw_key(&w,"elapsed_ms"); jw_num(&w,(long long)p->elapsed_s*1000LL); jw_ch(&w,',');
    jw_key(&w,"overall_pct"); jw_num(&w,p->overall_pct); jw_ch(&w,',');
    jw_key(&w,"overall_cur_bytes");   jw_num(&w,(long long)p->overall_cur_bytes);   jw_ch(&w,',');
    jw_key(&w,"overall_total_bytes"); jw_num(&w,(long long)p->overall_total_bytes); jw_ch(&w,',');
    jw_key(&w,"msg");    jw_str(&w, p->msg ? p->msg : ""); jw_ch(&w,',');
    jw_key(&w,"item");   jw_str(&w, p->item ? p->item : ""); jw_ch(&w,',');
    /* ★ REQ-77 Q1：文件计数（0 = 该任务没有文件概念，或总数未知）。*/
    jw_key(&w,"files_cur");   jw_num(&w,(long long)p->files_cur);   jw_ch(&w,',');
    jw_key(&w,"files_total"); jw_num(&w,(long long)p->files_total); jw_ch(&w,',');
    /* ★ REQ-77 Q2：正在写到哪里（item 是【源】路径，这个是【目标】）。*/
    jw_key(&w,"dst_item"); jw_str(&w, p->dst_item ? p->dst_item : "");
    jw_ch(&w,'}'); jw_ch(&w,'}');
    *w.p = 0;
    cb(line, g_ev_ud);
}

int imgapp_set_tmpdir(const char *dir){
    if(!dir || !*dir) return IMG_EINVAL;
    if(access(dir, W_OK | X_OK) != 0) return IMG_EINVAL;   /* 不可写就别设，免得后面才炸 */
    return setenv("IMGTOOL_TMPDIR", dir, 1) == 0 ? IMG_OK : IMG_EIO;
}

int imgapp_init(void){
    if(g_inited) return IMG_OK;
    img_out_set_text(on_text, NULL);
    img_cmd_set_progress(prog_to_json, NULL);   /* ★ P2：命令内部进度 → progress 事件 */
    img_io_watchdog_start(30000);      /* App 默认：30s 无 I/O = 卡死 */
    { const char *e = getenv("IMGTOOL_LANG"); img_lang_set(e); }   /* NULL → 读环境变量，默认中文 */
    g_inited = 1;
    return IMG_OK;
}
void imgapp_shutdown(void){
    img_io_watchdog_stop();
    img_log_close();
    img_out_set_text(NULL, NULL);
    g_ev_cb = NULL; g_ev_ud = NULL;
    g_inited = 0;
}

/* ---------------- 执行 ---------------- */
static char *render_result(const img_result *r){
    int cap = 256 * 1024;
    char *buf = malloc((size_t)cap);
    if(!buf) return NULL;
    int n = img_result_json(r, buf, cap);
    if(n < 0){ free(buf); return NULL; }
    if(n >= cap - 1){                       /* 保险：超大结果换大缓冲重渲 */
        free(buf); cap = 4 * 1024 * 1024;
        buf = malloc((size_t)cap);
        if(!buf) return NULL;
        n = img_result_json(r, buf, cap);
        if(n < 0){ free(buf); return NULL; }
    }
    return buf;
}

int imgapp_exec(const imgapp_req *req, imgapp_reply *out, char *err, size_t errcap){
    if(err && errcap) err[0] = 0;
    if(!out) return IMG_EINVAL;
    out->json = NULL; out->rc = IMG_EINVAL;
    if(!req || !req->cmd || !req->cmd[0]){
        if(err) snprintf(err, errcap, "cmd 为空");
        return IMG_EINVAL;
    }
    if(req->nargs < 0 || (req->nargs > 0 && (!req->keys || !req->vals))){
        if(err) snprintf(err, errcap, "参数数组不完整");
        return IMG_EINVAL;
    }
    imgapp_init();

    pthread_mutex_lock(&g_exec_mu);
    img_cancel_reset();

    img_lv_t lv = (img_lv_t)(req->log_level >= 0 && req->log_level <= 4 ? req->log_level : IMG_LV_INFO);
    if(req->log_path && req->log_path[0])
        img_log_open(req->log_path, lv, 0, 1);   /* 失败不阻塞命令（App 可看返回值） */

    img_args *args = img_args_new();
    if(!args){
        if(err) snprintf(err, errcap, "内存不足");
        img_log_close(); pthread_mutex_unlock(&g_exec_mu);
        return IMG_ENOMEM;
    }
    for(int i = 0; i < req->nargs; i++){
        if(!req->keys[i] || !req->vals[i]) continue;
        if(img_args_set(args, req->keys[i], req->vals[i]) != IMG_OK){
            /* 绝不静默丢参数：丢掉 --dst 会让命令按默认值"成功"，比报错危险。*/
            if(err) snprintf(err, errcap, "参数 %s 过长或非法（单个值上限 2047 字节）", req->keys[i]);
            img_args_free(args);
            img_log_close(); pthread_mutex_unlock(&g_exec_mu);
            return IMG_EINVAL;
        }
    }

    /* 镜像来源：路径优先；否则用 SAF fd（/proc/self/fd/N 在 Android 同样有效） */
    char fdpath[64];
    const char *path = (req->image_path && req->image_path[0]) ? req->image_path : NULL;
    if(!path && req->image_fd >= 0){
        snprintf(fdpath, sizeof fdpath, "/proc/self/fd/%d", req->image_fd);
        path = fdpath;
    }
    if(!path){
        const char *a = img_args_get(args, "img");   /* 兼容 App 直接把 img 放参数里 */
        if(a && a[0]) path = a;
    }

    /* 失败卡片头部（App 取 lastError 时能看到命令与参数） */
    { char ctxline[512]; int n = snprintf(ctxline, sizeof ctxline, "%s", req->cmd);
      for(int i = 0; i < req->nargs && n > 0 && n < (int)sizeof ctxline; i++)
          n += snprintf(ctxline + n, sizeof ctxline - (size_t)n, " %s=%s",
                        req->keys[i] ? req->keys[i] : "?", req->vals[i] ? req->vals[i] : "");
      img_card_set_context(ctxline); }

    const img_cmd *c = img_cmd_find(req->cmd);
    int needs_img = 0;
    if(c) for(int i = 0; i < c->nargs; i++)
        if(!strcmp(c->args[i].name, "img")){ needs_img = 1; break; }

    img_result r;
    int rc;
    img_ctx *ctx = NULL;
    if(needs_img && path){
        char e2[256] = "";
        ctx = img_ctx_open(path, req->writable != 0, req->check_size != 0, e2, sizeof e2);
        if(!ctx){
            img_result_begin(&r, req->cmd);
            img_result_fail(&r, IMG_ENOENT, "IMG-OPEN-FAIL", path,
                            e2[0] ? e2 : NULL, NULL);
            rc = IMG_ENOENT;
        } else {
            rc = img_cmd_exec(req->cmd, ctx, args, &r);
        }
    } else {
        /* 不需要镜像的命令（cmd.list/ctl.cancel），或缺 img 参数 → 交给命令表报 CMD-ARG-BAD */
        rc = img_cmd_exec(req->cmd, NULL, args, &r);
    }

    out->json = render_result(&r);
    out->rc = rc;

    if(ctx) img_ctx_close(ctx);
    img_args_free(args);
    img_log_close();
    pthread_mutex_unlock(&g_exec_mu);
    return out->json ? IMG_OK : IMG_ENOMEM;
}

char *imgapp_list_json(void){
    int cap = 64 * 1024;
    char *buf = malloc((size_t)cap);
    if(!buf) return NULL;
    int n = img_cmd_list_json(buf, cap);
    if(n < 0){ free(buf); return NULL; }
    if(n >= cap - 1){
        free(buf); cap = 1024 * 1024;
        buf = malloc((size_t)cap);
        if(!buf) return NULL;
        n = img_cmd_list_json(buf, cap);
        if(n < 0){ free(buf); return NULL; }
    }
    return buf;
}

char *imgapp_last_error_json(void){
    img_error_info_t ei;
    img_error_info(&ei);
    int cap = (int)sizeof ei + 1024;
    char *buf = malloc((size_t)cap);
    if(!buf) return NULL;
    jw w = { buf, buf + cap - 1 };
    jw_ch(&w, '{');
    jw_key(&w, "cmd");    jw_str(&w, ei.cmd);
    jw_key(&w, "code");   jw_str(&w, ei.code);
    jw_key(&w, "rc");     jw_num(&w, ei.rc);
    jw_key(&w, "at");     jw_str(&w, ei.at);
    jw_key(&w, "image");  jw_str(&w, ei.img);
    jw_key(&w, "log");    jw_str(&w, ei.log);
    jw_key(&w, "status"); jw_str(&w, ei.status);   /* 中文备注：仅日志/调试，App 按 code 渲染 */
    jw_key(&w, "events"); jw_str(&w, ei.events);
    jw_ch(&w, '}');
    *w.p = 0;
    return buf;
}

void imgapp_cancel(void){ img_cancel(); }
void imgapp_set_event_cb(imgapp_event_cb cb, void *ud){ g_ev_cb = cb; g_ev_ud = ud; }
void imgapp_set_event_level(int lv){
    if(lv < 0) lv = 0;
    if(lv > 4) lv = 4;
    g_ev_lv = lv;
}
void imgapp_set_lang(const char *code){ img_lang_set(code); }   /* "zh"/"en"；NULL=读环境变量 */
void imgapp_set_io_timeout_ms(int ms){
    if(ms <= 0) img_io_watchdog_stop();
    else        img_io_watchdog_start(ms);
}
const char *imgapp_version(void){ return IMGTOOL_VERSION; }
void imgapp_free(void *p){ free(p); }