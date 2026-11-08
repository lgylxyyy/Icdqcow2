/* imgapp_jni.c — JNI 薄层（只做 Java 类型 ↔ C 字符串/数组转换）
 *
 * Java 侧类：org.icdqcow2.imgtool.Native（见 java/org/icdqcow2/imgtool/Native.java）
 * 逻辑全部在 imgapp_bridge.c；这一层保持"一眼能看完"，方便 Android 上排错。
 */
#include "imgapp_bridge.h"
#include <jni.h>
#include <stdlib.h>
#include <string.h>

static JavaVM *g_vm = NULL;
static jobject g_sink = NULL;             /* Native.EventSink 的全局引用 */
static jmethodID g_on_event = NULL;

/* UTF-8 → Java String（不用 NewStringUTF：它能处理 4 字节 emoji，不依赖 modified UTF-8） */
static jstring new_utf8(JNIEnv *env, const char *s){
    if(!s) return NULL;
    size_t n = strlen(s);
    jchar *u = malloc((n + 1) * sizeof(jchar));
    if(!u) return NULL;
    size_t j = 0;
    for(size_t i = 0; i < n; ){
        unsigned char c = (unsigned char)s[i];
        unsigned cp; int len;
        if(c < 0x80){ cp = c; len = 1; }
        else if((c & 0xE0) == 0xC0){ cp = c & 0x1F; len = 2; }
        else if((c & 0xF0) == 0xE0){ cp = c & 0x0F; len = 3; }
        else if((c & 0xF8) == 0xF0){ cp = c & 0x07; len = 4; }
        else { cp = 0xFFFD; len = 1; }
        for(int k = 1; k < len; k++){
            if(i + (size_t)k >= n || ((unsigned char)s[i + (size_t)k] & 0xC0) != 0x80){
                cp = 0xFFFD; len = k; break;
            }
            cp = (cp << 6) | ((unsigned char)s[i + (size_t)k] & 0x3F);
        }
        if(cp >= 0x10000){
            cp -= 0x10000;
            u[j++] = (jchar)(0xD800 + (cp >> 10));
            u[j++] = (jchar)(0xDC00 + (cp & 0x3FF));
        } else {
            u[j++] = (jchar)cp;
        }
        i += (size_t)len;
    }
    jstring out = (*env)->NewString(env, u, (jsize)j);
    free(u);
    return out;
}

static void event_trampoline(const char *json_line, void *ud){
    (void)ud;
    if(!g_vm || !g_sink || !g_on_event) return;
    JNIEnv *env = NULL;
    int attached = 0;
    jint st = (*g_vm)->GetEnv(g_vm, (void**)&env, JNI_VERSION_1_6);
    if(st == JNI_EDETACHED){
        if((*g_vm)->AttachCurrentThread(g_vm, (void**)&env, NULL) != JNI_OK) return;
        attached = 1;
    } else if(st != JNI_OK) return;

    jstring s = new_utf8(env, json_line);
    if(s){
        (*env)->CallVoidMethod(env, g_sink, g_on_event, s);
        (*env)->DeleteLocalRef(env, s);
    }
    if((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);  /* UI 异常不能拖死工作线程 */
    if(attached) (*g_vm)->DetachCurrentThread(g_vm);
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved){
    (void)reserved;
    g_vm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT jstring JNICALL
Java_org_icdqcow2_imgtool_Native_version(JNIEnv *env, jclass cls){
    (void)cls;
    return new_utf8(env, imgapp_version());
}

JNIEXPORT jstring JNICALL
Java_org_icdqcow2_imgtool_Native_list(JNIEnv *env, jclass cls){
    (void)cls;
    char *json = imgapp_list_json();
    jstring out = json ? new_utf8(env, json) : NULL;
    imgapp_free(json);
    return out;
}

JNIEXPORT jstring JNICALL
Java_org_icdqcow2_imgtool_Native_exec(JNIEnv *env, jclass cls,
        jstring jcmd, jobjectArray jkeys, jobjectArray jvals,
        jstring jimage_path, jint jimage_fd, jboolean jwritable,
        jboolean jcheck_size, jstring jlog_path, jint jlog_level){
    (void)cls;
    if(!jcmd) return NULL;

    const char *cmd = (*env)->GetStringUTFChars(env, jcmd, NULL);
    const char *image_path = jimage_path ? (*env)->GetStringUTFChars(env, jimage_path, NULL) : NULL;
    const char *log_path   = jlog_path   ? (*env)->GetStringUTFChars(env, jlog_path, NULL)   : NULL;

    jsize n = 0;
    if(jkeys && jvals){
        jsize nk = (*env)->GetArrayLength(env, jkeys);
        jsize nv = (*env)->GetArrayLength(env, jvals);
        if(nk != nv){
            if(cmd) (*env)->ReleaseStringUTFChars(env, jcmd, cmd);
            if(image_path) (*env)->ReleaseStringUTFChars(env, jimage_path, image_path);
            if(log_path) (*env)->ReleaseStringUTFChars(env, jlog_path, log_path);
            jclass iae = (*env)->FindClass(env, "java/lang/IllegalArgumentException");
            if(iae) (*env)->ThrowNew(env, iae, "keys/vals 长度不一致");
            return NULL;
        }
        n = nk;
    }

    const char **keys = calloc((size_t)(n ? n : 1), sizeof(char*));
    const char **vals = calloc((size_t)(n ? n : 1), sizeof(char*));
    jobject *jk = calloc((size_t)(n ? n : 1), sizeof(jobject));
    jobject *jv = calloc((size_t)(n ? n : 1), sizeof(jobject));
    jstring *sk = calloc((size_t)(n ? n : 1), sizeof(jstring));
    jstring *sv = calloc((size_t)(n ? n : 1), sizeof(jstring));
    if(!keys || !vals || !jk || !jv || !sk || !sv){
        free(keys); free(vals); free(jk); free(jv); free(sk); free(sv);
        if(cmd) (*env)->ReleaseStringUTFChars(env, jcmd, cmd);
        if(image_path) (*env)->ReleaseStringUTFChars(env, jimage_path, image_path);
        if(log_path) (*env)->ReleaseStringUTFChars(env, jlog_path, log_path);
        return NULL;
    }
    for(jsize i = 0; i < n; i++){
        jk[i] = (*env)->GetObjectArrayElement(env, jkeys, i);
        jv[i] = (*env)->GetObjectArrayElement(env, jvals, i);
        if(jk[i]){ sk[i] = (jstring)jk[i]; keys[i] = (*env)->GetStringUTFChars(env, sk[i], NULL); }
        if(jv[i]){ sv[i] = (jstring)jv[i]; vals[i] = (*env)->GetStringUTFChars(env, sv[i], NULL); }
    }

    imgapp_req req;
    memset(&req, 0, sizeof req);
    req.cmd = cmd;
    req.keys = keys;
    req.vals = vals;
    req.nargs = (int)n;
    req.image_path = image_path;
    req.image_fd = (int)jimage_fd;
    req.writable = jwritable ? 1 : 0;
    req.check_size = jcheck_size ? 1 : 0;
    req.log_path = log_path;
    req.log_level = (int)jlog_level;

    imgapp_reply rep = { NULL, 0 };
    char err[256] = "";
    imgapp_exec(&req, &rep, err, sizeof err);
    jstring out = rep.json ? new_utf8(env, rep.json) : NULL;
    imgapp_free(rep.json);

    for(jsize i = 0; i < n; i++){
        if(keys[i]) (*env)->ReleaseStringUTFChars(env, sk[i], keys[i]);
        if(vals[i]) (*env)->ReleaseStringUTFChars(env, sv[i], vals[i]);
        if(jk[i]) (*env)->DeleteLocalRef(env, jk[i]);
        if(jv[i]) (*env)->DeleteLocalRef(env, jv[i]);
    }
    free(keys); free(vals); free(jk); free(jv); free(sk); free(sv);
    if(cmd) (*env)->ReleaseStringUTFChars(env, jcmd, cmd);
    if(image_path) (*env)->ReleaseStringUTFChars(env, jimage_path, image_path);
    if(log_path) (*env)->ReleaseStringUTFChars(env, jlog_path, log_path);
    return out;
}

JNIEXPORT jstring JNICALL
Java_org_icdqcow2_imgtool_Native_lastError(JNIEnv *env, jclass cls){
    (void)cls;
    char *json = imgapp_last_error_json();
    jstring out = json ? new_utf8(env, json) : NULL;
    imgapp_free(json);
    return out;
}

JNIEXPORT void JNICALL
Java_org_icdqcow2_imgtool_Native_cancel(JNIEnv *env, jclass cls){
    (void)env; (void)cls;
    imgapp_cancel();
}

JNIEXPORT void JNICALL
Java_org_icdqcow2_imgtool_Native_setEventSink(JNIEnv *env, jclass cls, jobject sink){
    (void)cls;
    if(g_sink){ (*env)->DeleteGlobalRef(env, g_sink); g_sink = NULL; }
    g_on_event = NULL;
    if(sink){
        jclass c = (*env)->GetObjectClass(env, sink);
        if(c){
            g_on_event = (*env)->GetMethodID(env, c, "onEvent", "(Ljava/lang/String;)V");
            (*env)->DeleteLocalRef(env, c);
        }
        if(g_on_event) g_sink = (*env)->NewGlobalRef(env, sink);
    }
    imgapp_set_event_cb(g_sink ? event_trampoline : NULL, NULL);
}

JNIEXPORT void JNICALL
Java_org_icdqcow2_imgtool_Native_setEventLevel(JNIEnv *env, jclass cls, jint lv){
    (void)env; (void)cls;
    imgapp_set_event_level((int)lv);
}

JNIEXPORT jboolean JNICALL
Java_org_icdqcow2_imgtool_Native_setTmpdir(JNIEnv *env, jclass cls, jstring dir){
    (void)cls;
    if(!dir) return JNI_FALSE;
    const char *d = (*env)->GetStringUTFChars(env, dir, NULL);
    int rc = imgapp_set_tmpdir(d);
    if(d) (*env)->ReleaseStringUTFChars(env, dir, d);
    return rc == 0 ? JNI_TRUE : JNI_FALSE;   /* 桥接层约定：0=成功 */
}

JNIEXPORT void JNICALL
Java_org_icdqcow2_imgtool_Native_setIoTimeoutMs(JNIEnv *env, jclass cls, jint ms){
    (void)env; (void)cls;
    imgapp_set_io_timeout_ms((int)ms);
}