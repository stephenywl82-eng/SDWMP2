#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <unistd.h>

#include "usb_audio_driver.h"

#define TAG "UsbDacJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static UsbAudioDriver* gUsbDriver = nullptr;

static UsbAudioDriver* getDriver() {
    if (!gUsbDriver) {
        gUsbDriver = new UsbAudioDriver();
    }
    return gUsbDriver;
}

extern "C" {

// ── nativeUsbAvailable ───────────────────────────────────────────────────

JNIEXPORT jboolean JNICALL
Java_com_sdw_music_player_core_audio_UsbDacManager_nativeUsbAvailable(JNIEnv*, jobject) {
    return JNI_TRUE;
}

// ── nativeClaim ──────────────────────────────────────────────────────────

JNIEXPORT jboolean JNICALL
Java_com_sdw_music_player_core_audio_UsbDacManager_nativeClaim(
    JNIEnv* env, jobject /* thiz */,
    jint vid, jint pid, jint fd, jint address, jint maxPacketSize,
    jint interval, jboolean isUac2, jint ifaceNum) {

    auto* driver = getDriver();
    if (!driver) return JNI_FALSE;

    int dupFd = dup(fd);
    if (dupFd < 0) {
        LOGE("nativeClaim: dup(fd=%d) failed", fd);
        return JNI_FALSE;
    }

    bool ok = driver->open(dupFd, address, maxPacketSize, interval,
                           isUac2 == JNI_TRUE, vid, pid, ifaceNum);
    LOGI("nativeClaim: vid=%04X pid=%04X fd=%d ep=0x%02X iface=%d -> %s",
         vid, pid, dupFd, address, ifaceNum, ok ? "OK" : "FAIL");
    return ok ? JNI_TRUE : JNI_FALSE;
}

// ── nativeUsbStart ───────────────────────────────────────────────────────

JNIEXPORT jboolean JNICALL
Java_com_sdw_music_player_core_audio_UsbDacManager_nativeUsbStart(
    JNIEnv*, jobject, jint sampleRate, jint channels, jint bitsPerSample) {

    auto* driver = getDriver();
    if (!driver) return JNI_FALSE;

    bool ok = driver->start(sampleRate, channels, bitsPerSample);
    LOGI("nativeUsbStart: sr=%d ch=%d bits=%d -> %s",
         sampleRate, channels, bitsPerSample, ok ? "OK" : "FAIL");
    return ok ? JNI_TRUE : JNI_FALSE;
}

// ── nativePushPcm ────────────────────────────────────────────────────────

JNIEXPORT jint JNICALL
Java_com_sdw_music_player_core_audio_UsbDacManager_nativePushPcm(
    JNIEnv* env, jobject, jfloatArray data, jint frameCount) {

    auto* driver = getDriver();
    if (!driver) return -1;

    jfloat* elements = env->GetFloatArrayElements(data, nullptr);
    if (!elements) return -1;

    int pushed = driver->pushPcm(elements, frameCount);
    env->ReleaseFloatArrayElements(data, elements, JNI_ABORT);
    return pushed;
}

// ── nativeStop ───────────────────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_com_sdw_music_player_core_audio_UsbDacManager_nativeStop(JNIEnv*, jobject) {
    auto* driver = getDriver();
    if (driver) driver->stop();
    LOGI("nativeStop");
}

// ── nativeStopThreadOnly ─────────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_com_sdw_music_player_core_audio_UsbDacManager_nativeStopThreadOnly(JNIEnv*, jobject) {
    auto* driver = getDriver();
    if (driver) driver->stopThreadOnly();
    LOGI("nativeStopThreadOnly");
}

// ── nativeRelease ────────────────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_com_sdw_music_player_core_audio_UsbDacManager_nativeRelease(JNIEnv*, jobject) {
    if (gUsbDriver) {
        delete gUsbDriver;
        gUsbDriver = nullptr;
    }
    LOGI("nativeRelease");
}

// ── nativeIsClaimed ──────────────────────────────────────────────────────

JNIEXPORT jboolean JNICALL
Java_com_sdw_music_player_core_audio_UsbDacManager_nativeIsClaimed(JNIEnv*, jobject) {
    auto* driver = getDriver();
    if (!driver) return JNI_FALSE;
    return driver->isClaimed() ? JNI_TRUE : JNI_FALSE;
}

// ── nativeGetUnderrunCount ───────────────────────────────────────────────

JNIEXPORT jint JNICALL
Java_com_sdw_music_player_core_audio_UsbDacManager_nativeGetUnderrunCount(JNIEnv*, jobject) {
    auto* driver = getDriver();
    if (!driver) return 0;
    return driver->getUnderrunCount();
}

// ── nativeGetCurrentSampleRate ───────────────────────────────────────────

JNIEXPORT jint JNICALL
Java_com_sdw_music_player_core_audio_UsbDacManager_nativeGetCurrentSampleRate(JNIEnv*, jobject) {
    auto* driver = getDriver();
    if (!driver) return 0;
    return driver->getSampleRate();
}

// ── nativeGetDacName ─────────────────────────────────────────────────────

JNIEXPORT jstring JNICALL
Java_com_sdw_music_player_core_audio_UsbDacManager_nativeGetDacName(JNIEnv* env, jobject) {
    auto* driver = getDriver();
    if (!driver) return env->NewStringUTF("No DAC");
    return env->NewStringUTF(driver->getDacName());
}

// ── nativeGetDetailedInfo ────────────────────────────────────────────────

JNIEXPORT jstring JNICALL
Java_com_sdw_music_player_core_audio_UsbDacManager_nativeGetDetailedInfo(JNIEnv* env, jobject) {
    auto* driver = getDriver();
    if (!driver) return env->NewStringUTF("No DAC connected");
    return env->NewStringUTF(driver->getDetailedInfo());
}

// ── nativeGetSupportedRates ──────────────────────────────────────────────

JNIEXPORT jstring JNICALL
Java_com_sdw_music_player_core_audio_UsbDacManager_nativeGetSupportedRates(JNIEnv* env, jobject) {
    auto* driver = getDriver();
    if (!driver) return env->NewStringUTF("48000");
    return env->NewStringUTF(driver->getSupportedRates());
}

// ── nativeGetStats ───────────────────────────────────────────────────────

JNIEXPORT jstring JNICALL
Java_com_sdw_music_player_core_audio_UsbDacManager_nativeGetStats(JNIEnv* env, jobject) {
    auto* driver = getDriver();
    if (!driver) return env->NewStringUTF("No DAC");

    UsbDacStats stats;
    driver->getStats(stats);

    char buf[512];
    snprintf(buf, sizeof(buf),
        "URB submitted=%llu completed=%llu errors=%llu | Ring avail=%d frames | Watermark=%d/%dms | Health=%s",
        (unsigned long long)stats.urbSubmitted,
        (unsigned long long)stats.urbCompleted,
        (unsigned long long)stats.urbErrors,
        stats.ringAvailFrames,
        stats.bufferWatermarkMs,
        stats.targetWatermarkMs,
        stats.healthState);
    return env->NewStringUTF(buf);
}

// ── nativeSetVolume ──────────────────────────────────────────────────────

JNIEXPORT void JNICALL
Java_com_sdw_music_player_core_audio_UsbDacManager_nativeSetVolume(JNIEnv*, jobject, jfloat volume) {
    auto* driver = getDriver();
    if (driver) driver->setVolume(volume);
}

// ── nativeGetDebugLog ────────────────────────────────────────────────────

JNIEXPORT jstring JNICALL
Java_com_sdw_music_player_core_audio_UsbDacManager_nativeGetDebugLog(JNIEnv* env, jobject) {
    auto* driver = getDriver();
    if (!driver) return env->NewStringUTF("Driver not initialized");
    return env->NewStringUTF(driver->getNativeDebugLog());
}

} // extern "C"
