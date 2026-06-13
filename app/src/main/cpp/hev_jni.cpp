#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include <android/log.h>

#define TAG "HevJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#if defined(__arm__) || defined(__i386__)

extern "C" {
    int  hev_socks5_tunnel_main_from_str(const char* config_str, int tun_fd);
    void hev_socks5_tunnel_quit(void);
}

struct TunnelArgs { char* config; int fd; };

static void* tunnel_thread(void* arg) {
    TunnelArgs* a = (TunnelArgs*)arg;
    LOGI("hev tunnel start fd=%d", a->fd);
    int ret = hev_socks5_tunnel_main_from_str(a->config, a->fd);
    LOGI("hev tunnel exit ret=%d", ret);
    free(a->config);
    free(a);
    return nullptr;
}

static pthread_t g_thread = 0;
static int       g_running = 0;

extern "C"
JNIEXPORT void JNICALL
Java_hev_htproxy_TProxyService_TProxyStartService(
        JNIEnv* env, jclass, jstring configPath, jint fd) {

    if (g_running) {
        LOGI("déjà en cours, arrêt préalable");
        hev_socks5_tunnel_quit();
        if (g_thread) { pthread_join(g_thread, nullptr); g_thread = 0; }
        g_running = 0;
    }

    const char* path = env->GetStringUTFChars(configPath, nullptr);
    FILE* f = fopen(path, "r");
    env->ReleaseStringUTFChars(configPath, path);

    if (!f) { LOGE("impossible d'ouvrir la config"); return; }

    fseek(f, 0, SEEK_END);
    long sz = ftell(f);
    rewind(f);
    char* buf = (char*)malloc(sz + 1);
    fread(buf, 1, sz, f);
    buf[sz] = '\0';
    fclose(f);

    TunnelArgs* args = (TunnelArgs*)malloc(sizeof(TunnelArgs));
    args->config = buf;
    args->fd     = (int)fd;

    g_running = 1;
    pthread_create(&g_thread, nullptr, tunnel_thread, args);
    LOGI("thread lancé fd=%d", (int)fd);
}

extern "C"
JNIEXPORT void JNICALL
Java_hev_htproxy_TProxyService_TProxyStopService(JNIEnv*, jclass) {
    LOGI("TProxyStopService");
    if (g_running) {
        hev_socks5_tunnel_quit();
        if (g_thread) { pthread_join(g_thread, nullptr); g_thread = 0; }
        g_running = 0;
    }
}

#else

extern "C"
JNIEXPORT void JNICALL
Java_hev_htproxy_TProxyService_TProxyStartService(
        JNIEnv*, jclass, jstring, jint) {
    LOGE("TProxyStartService: non supporté sur arm64 (lib epro)");
}

extern "C"
JNIEXPORT void JNICALL
Java_hev_htproxy_TProxyService_TProxyStopService(JNIEnv*, jclass) {
    LOGE("TProxyStopService: non supporté sur arm64");
}

#endif

extern "C"
JNIEXPORT jlongArray JNICALL
Java_hev_htproxy_TProxyService_TProxyGetStats(JNIEnv* env, jclass) {
    jlongArray arr = env->NewLongArray(2);
    jlong vals[2] = {0, 0};
    env->SetLongArrayRegion(arr, 0, 2, vals);
    return arr;
}
