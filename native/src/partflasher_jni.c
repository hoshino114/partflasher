#include <jni.h>
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "sha256.h"

#define IO_BUFFER_SIZE (256 * 1024)

static const char *HEX = "0123456789abcdef";

static void digest_to_hex(const uint8_t digest[SHA256_DIGEST_SIZE], char out[65]) {
    int i;
    for (i = 0; i < SHA256_DIGEST_SIZE; i++) {
        out[i * 2] = HEX[digest[i] >> 4];
        out[i * 2 + 1] = HEX[digest[i] & 0x0f];
    }
    out[64] = '\0';
}

JNIEXPORT jstring JNICALL
Java_com_partflasher_app_core_util_NativeSha256_nativeSha256File(
        JNIEnv *env, jobject thiz, jstring java_path) {
    (void) thiz;
    if (java_path == NULL) return NULL;

    const char *path = (*env)->GetStringUTFChars(env, java_path, NULL);
    if (path == NULL) return NULL;

    FILE *file = fopen(path, "rb");
    (*env)->ReleaseStringUTFChars(env, java_path, path);
    if (file == NULL) return NULL;

    uint8_t *buffer = (uint8_t *) malloc(IO_BUFFER_SIZE);
    if (buffer == NULL) {
        fclose(file);
        return NULL;
    }

    sha256_ctx ctx;
    sha256_init(&ctx);
    size_t read_total;
    int ok = 1;
    while ((read_total = fread(buffer, 1, IO_BUFFER_SIZE, file)) > 0) {
        sha256_update(&ctx, buffer, read_total);
    }
    if (ferror(file)) ok = 0;
    fclose(file);
    free(buffer);
    if (!ok) return NULL;

    uint8_t digest[SHA256_DIGEST_SIZE];
    char hex[65];
    sha256_final(&ctx, digest);
    digest_to_hex(digest, hex);
    return (*env)->NewStringUTF(env, hex);
}

JNIEXPORT jstring JNICALL
Java_com_partflasher_app_core_util_NativeSha256_nativeVersion(
        JNIEnv *env, jobject thiz) {
    (void) thiz;
    return (*env)->NewStringUTF(env, "native-sha256/1.0 (arm64)");
}
