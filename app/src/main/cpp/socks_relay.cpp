#include <jni.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/sendfile.h>
#include <fcntl.h>
#include <errno.h>
#include <pthread.h>
#include <stdlib.h>

struct RelayArgs {
    int src;
    int dst;
};

static void* relay_thread(void* arg) {
    RelayArgs* args = (RelayArgs*)arg;
    int src = args->src;
    int dst = args->dst;
    free(args);

    char buf[65536];
    ssize_t n;
    while ((n = recv(src, buf, sizeof(buf), 0)) > 0) {
        ssize_t sent = 0;
        while (sent < n) {
            ssize_t s = send(dst, buf + sent, n - sent, MSG_NOSIGNAL);
            if (s <= 0) goto done;
            sent += s;
        }
    }
done:
    shutdown(src, SHUT_RD);
    shutdown(dst, SHUT_WR);
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_kighmu_vpn_engines_NativeRelay_relay(
        JNIEnv* env, jclass clazz, jint fd1, jint fd2) {

    // fd1 -> fd2
    RelayArgs* args1 = (RelayArgs*)malloc(sizeof(RelayArgs));
    args1->src = fd1;
    args1->dst = fd2;

    // fd2 -> fd1
    RelayArgs* args2 = (RelayArgs*)malloc(sizeof(RelayArgs));
    args2->src = fd2;
    args2->dst = fd1;

    pthread_t t1, t2;
    pthread_create(&t1, nullptr, relay_thread, args1);
    pthread_create(&t2, nullptr, relay_thread, args2);

    pthread_join(t1, nullptr);
    pthread_join(t2, nullptr);
}
