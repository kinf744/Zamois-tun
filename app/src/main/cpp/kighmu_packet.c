/**
 * kighmu_packet.c
 * Packet processing optimizations — checksum, NAT table, zero-copy helpers.
 */

#include <jni.h>
#include <android/log.h>
#include <string.h>
#include <stdlib.h>
#include <stdint.h>

#define LOG_TAG "KighmuPacket"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// ─── Fast checksum (optimized for ARM) ───────────────────────────────────────

static uint32_t checksum_accumulate(const uint8_t *data, size_t len, uint32_t init) {
    uint32_t sum = init;
    // Process 4 bytes at a time on aligned data
    const uint32_t *p = (const uint32_t *)data;
    while (len >= 4) {
        sum += *p++;
        len -= 4;
    }
    const uint8_t *q = (const uint8_t *)p;
    while (len >= 2) {
        sum += *(uint16_t *)q;
        q += 2;
        len -= 2;
    }
    if (len) sum += *q;
    return sum;
}

static uint16_t checksum_finalize(uint32_t sum) {
    while (sum >> 16) sum = (sum & 0xFFFF) + (sum >> 16);
    return (uint16_t)~sum;
}

// ─── JNI: Compute IP checksum ────────────────────────────────────────────────

JNIEXPORT jint JNICALL
Java_com_kighmu_vpn_vpn_KighmuVpnService_nativeIpChecksum(
    JNIEnv *env, jobject thiz, jbyteArray header) {

    jsize len = (*env)->GetArrayLength(env, header);
    jbyte *data = (*env)->GetByteArrayElements(env, header, NULL);

    uint32_t sum = checksum_accumulate((uint8_t *)data, (size_t)len, 0);
    uint16_t result = checksum_finalize(sum);

    (*env)->ReleaseByteArrayElements(env, header, data, JNI_ABORT);
    return (jint)(result & 0xFFFF);
}

// ─── JNI: Buffer copy (zero-alloc path) ──────────────────────────────────────

JNIEXPORT jint JNICALL
Java_com_kighmu_vpn_vpn_KighmuVpnService_nativeCopyBuffer(
    JNIEnv *env, jobject thiz,
    jbyteArray src, jint srcOffset,
    jbyteArray dst, jint dstOffset,
    jint length) {

    jbyte *s = (*env)->GetByteArrayElements(env, src, NULL);
    jbyte *d = (*env)->GetByteArrayElements(env, dst, NULL);

    if (s && d) {
        memcpy(d + dstOffset, s + srcOffset, (size_t)length);
    }

    (*env)->ReleaseByteArrayElements(env, src, s, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, dst, d, 0);
    return length;
}

// ─── JNI: Detect IP version ──────────────────────────────────────────────────

JNIEXPORT jint JNICALL
Java_com_kighmu_vpn_vpn_KighmuVpnService_nativeGetIpVersion(
    JNIEnv *env, jobject thiz, jbyteArray packet) {

    jsize len = (*env)->GetArrayLength(env, packet);
    if (len < 1) return -1;

    jbyte first;
    (*env)->GetByteArrayRegion(env, packet, 0, 1, &first);
    return (first >> 4) & 0x0F;  // IP version from first nibble
}
