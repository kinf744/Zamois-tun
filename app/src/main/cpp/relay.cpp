#include <jni.h>
#include <unistd.h>
#include <sys/socket.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>

struct PipeArgs {
    int src, dst;
};

static void* pipe_thread(void* arg) {
    PipeArgs* a = (PipeArgs*)arg;
    char buf[65536];
    ssize_t n;
    while ((n = recv(a->src, buf, sizeof(buf), 0)) > 0) {
        ssize_t sent = 0;
        while (sent < n) {
            ssize_t s = send(a->dst, buf + sent, n - sent, MSG_NOSIGNAL);
            if (s <= 0) goto done;
            sent += s;
        }
    }
done:
    shutdown(a->src, SHUT_RD);
    shutdown(a->dst, SHUT_WR);
    free(a);
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_kighmu_vpn_engines_NativeRelay_relay(
        JNIEnv*, jclass, jint fd1, jint fd2) {
    PipeArgs* a1 = (PipeArgs*)malloc(sizeof(PipeArgs));
    a1->src = fd1; a1->dst = fd2;
    PipeArgs* a2 = (PipeArgs*)malloc(sizeof(PipeArgs));
    a2->src = fd2; a2->dst = fd1;
    pthread_t t1, t2;
    pthread_create(&t1, nullptr, pipe_thread, a1);
    pthread_create(&t2, nullptr, pipe_thread, a2);
    pthread_join(t1, nullptr);
    pthread_join(t2, nullptr);
}
