/**
 * kighmu_tun.c
 * Native TUN interface helpers for high-performance packet processing.
 * Used by KighmuVpnService via JNI for optimized packet routing.
 */

#include <jni.h>
#include <android/log.h>
#include <string.h>
#include <stdlib.h>
#include <unistd.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <netinet/ip.h>
#include <netinet/tcp.h>
#include <netinet/udp.h>
#include <arpa/inet.h>
#include <errno.h>

#define LOG_TAG "KighmuNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

#define MAX_PACKET_SIZE 65535

// ─── Checksum ──────────────────────────────────────────────────────────────

static uint16_t checksum(const uint8_t *data, size_t len) {
    uint32_t sum = 0;
    while (len > 1) {
        sum += *(uint16_t *)data;
        data += 2;
        len -= 2;
    }
    if (len > 0) sum += *data;
    while (sum >> 16) sum = (sum & 0xFFFF) + (sum >> 16);
    return (uint16_t)~sum;
}

// ─── JNI: Read packet from TUN fd ────────────────────────────────────────

JNIEXPORT jbyteArray JNICALL
Java_com_kighmu_vpn_vpn_KighmuVpnService_nativeReadPacket(
    JNIEnv *env, jobject thiz, jint tun_fd) {

    uint8_t buffer[MAX_PACKET_SIZE];
    ssize_t len = read(tun_fd, buffer, sizeof(buffer));

    if (len <= 0) {
        if (len < 0) LOGE("TUN read error: %s", strerror(errno));
        return NULL;
    }

    jbyteArray result = (*env)->NewByteArray(env, (jsize)len);
    if (result) {
        (*env)->SetByteArrayRegion(env, result, 0, (jsize)len, (jbyte *)buffer);
    }
    return result;
}

// ─── JNI: Write packet to TUN fd ─────────────────────────────────────────

JNIEXPORT jint JNICALL
Java_com_kighmu_vpn_vpn_KighmuVpnService_nativeWritePacket(
    JNIEnv *env, jobject thiz, jint tun_fd, jbyteArray packet) {

    jsize len = (*env)->GetArrayLength(env, packet);
    jbyte *data = (*env)->GetByteArrayElements(env, packet, NULL);

    ssize_t written = write(tun_fd, data, (size_t)len);
    (*env)->ReleaseByteArrayElements(env, packet, data, JNI_ABORT);

    if (written < 0) {
        LOGE("TUN write error: %s", strerror(errno));
        return -1;
    }
    return (jint)written;
}

// ─── JNI: Parse IP packet header info ────────────────────────────────────

JNIEXPORT jstring JNICALL
Java_com_kighmu_vpn_vpn_KighmuVpnService_nativeParsePacketInfo(
    JNIEnv *env, jobject thiz, jbyteArray packet) {

    jsize len = (*env)->GetArrayLength(env, packet);
    if (len < 20) return (*env)->NewStringUTF(env, "too_short");

    jbyte *data = (*env)->GetByteArrayElements(env, packet, NULL);
    struct iphdr *iph = (struct iphdr *)data;

    char src_ip[INET_ADDRSTRLEN];
    char dst_ip[INET_ADDRSTRLEN];
    inet_ntop(AF_INET, &iph->saddr, src_ip, sizeof(src_ip));
    inet_ntop(AF_INET, &iph->daddr, dst_ip, sizeof(dst_ip));

    char info[256];
    uint8_t proto = iph->protocol;
    uint16_t src_port = 0, dst_port = 0;

    if (proto == IPPROTO_TCP && len >= (int)(iph->ihl * 4 + 4)) {
        struct tcphdr *tcph = (struct tcphdr *)((uint8_t *)data + iph->ihl * 4);
        src_port = ntohs(tcph->source);
        dst_port = ntohs(tcph->dest);
        snprintf(info, sizeof(info), "TCP %s:%d->%s:%d", src_ip, src_port, dst_ip, dst_port);
    } else if (proto == IPPROTO_UDP && len >= (int)(iph->ihl * 4 + 4)) {
        struct udphdr *udph = (struct udphdr *)((uint8_t *)data + iph->ihl * 4);
        src_port = ntohs(udph->source);
        dst_port = ntohs(udph->dest);
        snprintf(info, sizeof(info), "UDP %s:%d->%s:%d", src_ip, src_port, dst_ip, dst_port);
    } else {
        snprintf(info, sizeof(info), "PROTO:%d %s->%s", proto, src_ip, dst_ip);
    }

    (*env)->ReleaseByteArrayElements(env, packet, data, JNI_ABORT);
    return (*env)->NewStringUTF(env, info);
}

// ─── JNI: Rewrite destination IP/port (for NAT) ──────────────────────────

JNIEXPORT jbyteArray JNICALL
Java_com_kighmu_vpn_vpn_KighmuVpnService_nativeRewriteDestination(
    JNIEnv *env, jobject thiz,
    jbyteArray packet,
    jstring new_dst_ip,
    jint new_dst_port) {

    jsize len = (*env)->GetArrayLength(env, packet);
    jbyte *data = (*env)->GetByteArrayElements(env, packet, NULL);

    const char *dst_str = (*env)->GetStringUTFChars(env, new_dst_ip, NULL);

    if (len >= 20) {
        struct iphdr *iph = (struct iphdr *)data;
        inet_pton(AF_INET, dst_str, &iph->daddr);
        iph->check = 0;
        iph->check = checksum((uint8_t *)iph, iph->ihl * 4);

        if (new_dst_port > 0 && iph->protocol == IPPROTO_TCP &&
            len >= (int)(iph->ihl * 4 + 4)) {
            struct tcphdr *tcph = (struct tcphdr *)((uint8_t *)data + iph->ihl * 4);
            tcph->dest = htons((uint16_t)new_dst_port);
            tcph->check = 0; // Recalculate in Kotlin for simplicity
        }
    }

    (*env)->ReleaseStringUTFChars(env, new_dst_ip, dst_str);

    jbyteArray result = (*env)->NewByteArray(env, len);
    (*env)->SetByteArrayRegion(env, result, 0, len, data);
    (*env)->ReleaseByteArrayElements(env, packet, data, 0);

    return result;
}

// ─── JNI: Get native library version ─────────────────────────────────────

JNIEXPORT jstring JNICALL
Java_com_kighmu_vpn_vpn_KighmuVpnService_nativeGetVersion(
    JNIEnv *env, jobject thiz) {
    return (*env)->NewStringUTF(env, "KIGHMU Native v1.0");
}
