// proxy_engine.cpp
//
// Native half of the Footage Import module. Kept deliberately small and
// single-purpose for Phase 1:
//   1) nativeProbeImage    - read just the header of an image (width/height/
//                             channels) without decoding pixel data. Used to
//                             validate every frame of an imported sequence
//                             quickly, even on a 4000-frame sequence.
//   2) nativeGenerateProxy - decode a full frame, box-downscale it, and
//                             write a JPEG proxy straight to disk.
//
// Everything here works from an in-memory byte buffer rather than a file
// path, because footage frames are usually selected through Android's
// Storage Access Framework (content:// Uris). Kotlin already has to open an
// InputStream to read those, so we pass the bytes straight into native code
// once instead of writing a temp copy to disk first just to hand C++ a
// path. One copy across the JNI boundary, no extra I/O.
//
// No exceptions/RTTI (see CMakeLists -fno-exceptions/-fno-rtti equivalent in
// build.gradle.kts) - all failure paths are plain return codes.

#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cstdio>
#include <cstring>

#define STB_IMAGE_IMPLEMENTATION
#include "stb_image.h"

#define STB_IMAGE_RESIZE_IMPLEMENTATION
#include "stb_image_resize2.h"

#define STB_IMAGE_WRITE_IMPLEMENTATION
#include "stb_image_write.h"

#define LOG_TAG "TrackStudioNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

extern "C" {

// Returns {width, height, channels} or an empty array if the buffer isn't a
// decodable image. stbi_info_from_memory only parses the header, so this is
// cheap enough to call once per frame during import validation.
JNIEXPORT jintArray JNICALL
Java_com_trackstudio_app_data_native_ProxyEngine_nativeProbeImage(
        JNIEnv *env, jobject /* this */, jbyteArray srcBytes) {

    jsize len = env->GetArrayLength(srcBytes);
    jbyte *bytes = env->GetByteArrayElements(srcBytes, nullptr);

    int width = 0, height = 0, channels = 0;
    int ok = stbi_info_from_memory(
            reinterpret_cast<const stbi_uc *>(bytes), static_cast<int>(len),
            &width, &height, &channels);

    env->ReleaseByteArrayElements(srcBytes, bytes, JNI_ABORT);

    if (!ok) {
        return env->NewIntArray(0);
    }

    jintArray result = env->NewIntArray(3);
    jint values[3] = {width, height, channels};
    env->SetIntArrayRegion(result, 0, 3, values);
    return result;
}

// Decodes srcBytes, downscales by `scale` (0 < scale <= 1.0) using a
// high-quality box/Mitchell filter (stb_image_resize2 picks a sane default
// per data type), and writes a baseline JPEG proxy to dstPath.
// Returns true on success.
JNIEXPORT jboolean JNICALL
Java_com_trackstudio_app_data_native_ProxyEngine_nativeGenerateProxy(
        JNIEnv *env, jobject /* this */,
        jbyteArray srcBytes, jstring dstPath, jfloat scale, jint jpegQuality) {

    if (scale <= 0.0f || scale > 1.0f) {
        LOGE("Invalid proxy scale: %f", scale);
        return JNI_FALSE;
    }

    jsize len = env->GetArrayLength(srcBytes);
    jbyte *bytes = env->GetByteArrayElements(srcBytes, nullptr);

    int srcW, srcH, srcChannels;
    // Force 3-channel (RGB) output - proxies are for on-screen preview /
    // tracking only, never for final composite, so we drop alpha up front
    // and halve the memory + JPEG-encode cost versus keeping 4 channels.
    unsigned char *pixels = stbi_load_from_memory(
            reinterpret_cast<const stbi_uc *>(bytes), static_cast<int>(len),
            &srcW, &srcH, &srcChannels, 3);

    env->ReleaseByteArrayElements(srcBytes, bytes, JNI_ABORT);

    if (pixels == nullptr) {
        LOGE("stbi_load_from_memory failed: %s", stbi_failure_reason());
        return JNI_FALSE;
    }

    int dstW = static_cast<int>(srcW * scale);
    int dstH = static_cast<int>(srcH * scale);
    if (dstW < 1) dstW = 1;
    if (dstH < 1) dstH = 1;

    unsigned char *resized = nullptr;

    if (dstW == srcW && dstH == srcH) {
        // scale == 1.0 (full-res "proxy" is really just a re-encode, e.g.
        // for a project that disabled proxies but still wants a lighter
        // cache copy) - skip the resize pass entirely.
        resized = pixels;
    } else {
        resized = static_cast<unsigned char *>(
                malloc(static_cast<size_t>(dstW) * dstH * 3));
        if (resized == nullptr) {
            LOGE("OOM allocating resize buffer (%dx%d)", dstW, dstH);
            stbi_image_free(pixels);
            return JNI_FALSE;
        }

        stbir_resize_uint8_srgb(
                pixels, srcW, srcH, 0,
                resized, dstW, dstH, 0,
                STBIR_RGB);
    }

    const char *dstPathChars = env->GetStringUTFChars(dstPath, nullptr);

    // Quality is clamped defensively - a bad value from a corrupted project
    // settings file should degrade to "reasonable JPEG", never crash.
    int quality = jpegQuality;
    if (quality < 1) quality = 1;
    if (quality > 100) quality = 100;

    int writeOk = stbi_write_jpg(dstPathChars, dstW, dstH, 3, resized, quality);

    env->ReleaseStringUTFChars(dstPath, dstPathChars);

    if (resized != pixels) {
        free(resized);
    }
    stbi_image_free(pixels);

    if (!writeOk) {
        LOGE("stbi_write_jpg failed for proxy output");
        return JNI_FALSE;
    }

    return JNI_TRUE;
}

} // extern "C"
