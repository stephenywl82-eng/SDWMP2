#include "usb_audio_driver.h"
#include <android/log.h>
#include <cmath>
#include <cstring>
#include <algorithm>
#include <unistd.h>
#include <sys/ioctl.h>
#include <sys/resource.h>
#include <sched.h>
#include <pthread.h>
#include <linux/usbdevice_fs.h>
#include <linux/usb/ch9.h>
#include <errno.h>
#include <cstdarg>
#include <cstdlib>

#define TAG "UsbAudioDriver"

// Salt-style: log to both Android logcat AND in-memory ring buffer for in-app display
#define LOGI(fmt, ...)  do { \
    __android_log_print(ANDROID_LOG_INFO, TAG, fmt, ##__VA_ARGS__); \
    nativeLog(TAG, fmt, ##__VA_ARGS__); \
} while(0)
#define LOGW(fmt, ...)  do { \
    __android_log_print(ANDROID_LOG_WARN, TAG, fmt, ##__VA_ARGS__); \
    nativeLog(TAG "!WARN", fmt, ##__VA_ARGS__); \
} while(0)
#define LOGE(fmt, ...)  do { \
    __android_log_print(ANDROID_LOG_ERROR, TAG, fmt, ##__VA_ARGS__); \
    nativeLog(TAG "!ERR", fmt, ##__VA_ARGS__); \
} while(0)

// 鈹€鈹€ nativeLog ring buffer implementation 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

void UsbAudioDriver::nativeLog(const char* tag, const char* fmt, ...) {
    if (!fmt) return;
    char line[384];
    // Timestamp: since we can't use clock_gettime easily, use a monotonic counter
    static std::atomic<int64_t> seq{0};
    int64_t s = seq.fetch_add(1);
    int sec = (int)(s / 1000);
    int ms  = (int)(s % 1000);
    int off = snprintf(line, 32, "[%d.%03d] ", sec, ms);

    va_list ap;
    va_start(ap, fmt);
    vsnprintf(line + off, sizeof(line) - off - 1, fmt, ap);
    va_end(ap);
    size_t len = strlen(line);
    if (len > 0 && line[len-1] != '\n') { line[len] = '\n'; line[len+1] = 0; }

    size_t remain = kNativeLogSize - nativeLogWrite_ - 1;
    if (strlen(line) < remain) {
        strcpy(nativeLogBuf_ + nativeLogWrite_, line);
        nativeLogWrite_ += strlen(line);
    } else {
        // Buffer full: wrap around
        nativeLogWrite_ = 0;
        strcpy(nativeLogBuf_, line);
        nativeLogWrite_ = strlen(line);
    }
}

// ============================================================================
// USB control request constants
// ============================================================================
static constexpr uint8_t REQ_GET_CUR = 0x81;  // device-to-host, class, interface
static constexpr uint8_t REQ_GET_MIN = 0x81;
static constexpr uint8_t REQ_GET_MAX = 0x81;
static constexpr uint8_t REQ_SET_CUR = 0x21;  // host-to-device, class, interface

static constexpr uint8_t UAC_SET_CUR = 0x01;
static constexpr uint8_t UAC_GET_CUR = 0x81;

static constexpr uint8_t CS_SAMPLING_FREQ_CONTROL = 0x01;

// UAC 1.0 standard request codes for class-specific interface
static constexpr uint8_t CUR_ATTR   = 0x01; // SET_CUR / GET_CUR
static constexpr uint16_t SAMPLING_FREQ_CONTROL = 0x0100; // CS = 1 (sampling freq), CN = 0

// UAC 2.0: Clock Source descriptor subtype = 0x0A, Clock Selector = 0x0B
static constexpr uint8_t UAC2_CS_CLOCK_SOURCE   = 0x0A;
static constexpr uint8_t UAC2_CS_CLOCK_SELECTOR = 0x0B;
static constexpr uint8_t UAC2_CS_INTERFACE       = 0x24;
static constexpr uint8_t UAC2_CS_ENDPOINT        = 0x25;

// ============================================================================
// UsbAudioDriver implementation
// ============================================================================

UsbAudioDriver::UsbAudioDriver() {
    ringBuffer_ = static_cast<float*>(calloc(kRingFrames * 2, sizeof(float))); // stereo interleaved
    if (!ringBuffer_) {
        LOGE("Failed to allocate ring buffer");
    }
    // Allocate variable-length usbdevfs_urb for each slot
    for (int i = 0; i < kMaxUrbCount; i++) {
        size_t urbSize = sizeof(usbdevfs_urb) + kPacketsPerUrb * sizeof(usbdevfs_iso_packet_desc);
        urbSlots_[i].urb = static_cast<usbdevfs_urb*>(calloc(1, urbSize));
        if (!urbSlots_[i].urb) {
            LOGE("Failed to allocate URB slot %d", i);
        }
    }
    LOGI("UsbAudioDriver created, ring=%p, %d frames", ringBuffer_, kRingFrames);
}

UsbAudioDriver::~UsbAudioDriver() {
    stop();
    if (ringBuffer_) {
        free(ringBuffer_);
        ringBuffer_ = nullptr;
    }
    for (int i = 0; i < kMaxUrbCount; i++) {
        if (urbSlots_[i].urb) {
            free(urbSlots_[i].urb);
            urbSlots_[i].urb = nullptr;
        }
    }
    LOGI("UsbAudioDriver destroyed");
}

// 鈹€鈹€ open 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

bool UsbAudioDriver::open(int fd, int epAddress, int maxPacketSize, int interval,
                          bool isUac2, int vid, int pid, int ifaceNum) {
    fd_ = fd;
    epAddress_ = epAddress;
    maxPacketSize_ = maxPacketSize;
    interval_ = (interval > 0) ? interval : 1;
    isUac2_ = isUac2;
    vid_ = vid;
    pid_ = pid;
    ifaceNum_ = ifaceNum;

    char buf[256];
    snprintf(buf, sizeof(buf), "Open: fd=%d ep=0x%02X mps=%d int=%d uac2=%d vid=%04X pid=%04X",
             fd_, epAddress_, maxPacketSize_, interval_, isUac2_, vid_, pid_);
    detailedInfo_ = buf;
    LOGI("%s", buf);

    // 鈺愨晲锟?DISCONNECT kernel audio driver FIRST 鈺愨晲锟?
    // Android kernel already has the USB Audio Class driver (snd_usb_audio)
    // attached to this device. That driver claims the interfaces and owns the
    // clock, causing EBUSY on any SET_CUR control transfer.
    // USBDEVFS_DISCONNECT tells the kernel driver to release its claim so we
    // can talk directly to the hardware. This is what AAudio/HAL exclusive mode
    // implicitly does via AudioFlinger.
    // Interface number 0 = the AudioControl interface; we must disconnect
    // the kernel driver before claiming our own.
    // USBDEVFS_DISCONNECT (0x5502): force kernel audio driver off the device.
    // May not be available on all Android kernels. We continue regardless.
    struct usbdevfs_ioctl disconnect = {};
    disconnect.ifno = 0;
    disconnect.ioctl_code = 0x5502;  // USBDEVFS_DISCONNECT = _IO('U', 2)
    disconnect.data = nullptr;
    int discRet = ioctl(fd_, USBDEVFS_IOCTL, &disconnect);
    LOGI("DISCONNECT kernel driver iface=0: ret=%d errno=%d (%s)",
         discRet, errno, strerror(errno));

    // [v6.x adaptive] REMOVED hardcoded SET_CUR(44100) from open().
    // Problem: on 48kHz-only DACs (Realtek 4BA6), setting 44100 before knowing the actual
    // song rate can put the DAC into a broken state if the DAC firmware fakes success.
    // The rate switch now happens exclusively in start(), where we know the actual sample rate.
    // We still need a sensible default so clockRate_ isn't garbage — assume 0 (unknown).
    // TTGK 33C0 is the only DAC that matters here and it worked with SET_CUR anyway.
    clockRate_ = 0;  // unknown until first start()
    LOGI("open: clock deferred to start() — no hardcoded SET_CUR");

    // 鈹€鈹€ DEBUG: 璇婃柇鏃堕挓鏄惁鐪熸鍒囨崲 鈹€鈹€
    {
        // 鏂瑰紡1: GET_CUR CS_SAM_FREQ_CONTROL via AC iface (鏍囧噯璇绘硶)
        for (int tryCid = 1; tryCid <= 10; tryCid++) {
            uint32_t curRate = 0;
            struct usbdevfs_ctrltransfer ct = {};
            ct.bRequestType = 0xA1; ct.bRequest = 0x01; // GET_CUR
            ct.wValue = 0x0100;  // CS_SAM_FREQ_CONTROL << 8
            ct.wIndex = (uint16_t)((tryCid & 0xFF) | 0x0000); // clockId + AC iface=0
            ct.wLength = 4; ct.timeout = 500; ct.data = &curRate;
            int r = ioctl(fd_, USBDEVFS_CONTROL, &ct);
            if (r == 4 && curRate > 0) {
                LOGI("DEBUG GET_CUR OK: clockId=%d rate=%u (ret=%d)", tryCid, curRate, r);
                clockRate_ = (int)curRate;
            }
        }
        // 鏂瑰紡2: GET_CUR via streaming iface (ifaceNum_)
        {
            uint32_t curRate = 0;
            struct usbdevfs_ctrltransfer ct = {};
            ct.bRequestType = 0xA1; ct.bRequest = 0x01;
            ct.wValue = 0x0100;
            ct.wIndex = (uint16_t)ifaceNum_;
            ct.wLength = 4; ct.timeout = 500; ct.data = &curRate;
            int r = ioctl(fd_, USBDEVFS_CONTROL, &ct);
            LOGI("DEBUG GET_CUR via stream iface=%d: rate=%u (ret=%d)", ifaceNum_, curRate, r);
        }
    }
    // 鈹€鈹€ END DEBUG 鈹€鈹€

    // Claim the audio streaming interface (alt=1 activates ISO OUT)
    if (!claimInterface(1)) {
        LOGE("Failed to claim interface");
        return false;
    }
    currentAlt_ = 1;

    // 銆愯嚜閫傚簲DAC銆戣В鏋愬叏閮ˋudioStreaming alt setting鍊欓€夎〃
    parseAltCandidates();
    LOGI("Alt candidates: %zu found", altCandidates_.size());
    for (size_t i = 0; i < altCandidates_.size(); ++i) {
        auto& c = altCandidates_[i];
        LOGI("  #%zu: iface=%d alt=%d ep=0x%02X mps=%d ch=%d subslot=%d res=%d clkId=%d rate=%d",
             i, c.ifaceNum, c.alt, c.epAddr, c.maxPkt, c.channels, c.subslot, c.res, c.clockId, c.sampleRate);
    }

    // 銆愯嚜閫傚簲DAC銆戣clock sub-range (GET_RANGE) 锟?楠岃瘉姣忎釜rate鏄惁琚獶AC纭欢鏀寔
    parseClockRanges();
    LOGI("Clock ranges: %zu found", clockRanges_.size());
    for (size_t i = 0; i < clockRanges_.size(); ++i) {
        auto& cr = clockRanges_[i];
        LOGI("  #%zu: clkId=%d min=%d max=%d res=%d", i, cr.clockId, cr.min, cr.max, cr.res);
    }

    // Parse supported sample rates from AudioControl descriptors
    parseSupportedRates();
    LOGI("Supported sample rates: %s", supportedRates_.c_str());

    // Detect UAC 1.0 / 2.0 clock source
    int clockId = findClockSourceId();
    LOGI("Clock source ID: %d", clockId);

    // Find feedback endpoint for async DACs
    int fbEp = findFeedbackEndpoint();
    if (fbEp > 0) {
        char tmp[64];
        snprintf(tmp, sizeof(tmp), ", fbEp=0x%02X", fbEp);
        detailedInfo_ += tmp;
        LOGI("Feedback endpoint: 0x%02X", fbEp);
    }

    return true;
}

// 鈹€鈹€ start 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

bool UsbAudioDriver::start(int sampleRate, int channels, int bitsPerSample) {
    if (fd_ < 0) {
        LOGE("start: device not open");
        return false;
    }

    // If already streaming, stop old thread cleanly and restart
    // 銆怴3.2.7 淇銆戝繀椤昏蛋 stopThreadOnly()锛氬畠锟?DISCARD 鍦ㄩ URB锟?
    // 涔嬪墠鍙疆 flag+join锟?2 涓湪锟?URB 娌″洖鏀讹紝鍚庣画 setInterfaceAlt(0) EBUSY 澶辫触锟?
    // DAC 鐣欏湪 alt=1(16bit/44.1k)锛宎pp 锟?24bit@48k 锟?宸﹀０閬撳櫔锟?鍙冲０閬撳揩杩涳拷?
    if (streaming_.load(std::memory_order_acquire)) {
        LOGW("start: restarting stream (old sr=%d ch=%d bits=%d) -> (sr=%d ch=%d bits=%d)",
             sampleRate_, channels_, bitsPerSample_, sampleRate, channels, bitsPerSample);
        stopThreadOnly();
    }

    sampleRate_ = sampleRate;
    channels_ = channels;
    bitsPerSample_ = bitsPerSample;
    bytesPerFrame_ = channels_ * (bitsPerSample_ / 8);

    LOGI("start: sr=%d ch=%d bits=%d bytesPerFrame=%d",
         sampleRate_, channels_, bitsPerSample_, bytesPerFrame_);

    // 銆愯嚜閫傚簲DAC銆戜粠descriptor鍊欓€夎〃閫夋渶浣砤lt + 楠岃瘉clock range
    int targetAlt = selectAltForRate(sampleRate_, bitsPerSample_, channels_);
    LOGI("selectAltForRate(r=%d bits=%d ch=%d) 锟斤拷 alt=%d (candidates=%zu, ranges=%zu)",
         sampleRate_, bitsPerSample_, channels_, targetAlt, altCandidates_.size(), clockRanges_.size());

    // Fallback: if adaptive match failed, use old hardcoded logic
    // BUT only if the sample rate is within a supported clock range
    if (targetAlt < 0) {
        bool rateSupported = false;
        for (auto& r : clockRanges_) {
            if (sampleRate_ >= r.min && sampleRate_ <= r.max) { rateSupported = true; break; }
        }
        if (!rateSupported) {
            LOGE("start: %d Hz outside all clock ranges — refuse to force. Use ExoPlayer/Oboe instead.", sampleRate_);
            return false;
        }
        LOGW("start: adaptive match failed, falling back to hardcoded alt");
        targetAlt = (bitsPerSample_ == 24) ? 2 : (bitsPerSample_ == 32) ? 3 : 1;
        if (sampleRate_ != clockRate_) {
            int scRet = trySetSampleRate(sampleRate_);
            if (scRet >= 0) clockRate_ = sampleRate_;
            else LOGW("start: fallback SET_CUR(%d) failed", sampleRate_);
        }
        // Fallback: update maxPacketSize_/bytesPerFrame_ from candidates if available
        if (!altCandidates_.empty()) {
            for (auto& c : altCandidates_) {
                if (c.alt == targetAlt) {
                    maxPacketSize_ = c.maxPkt;
                    interval_ = c.bInterval;
                    // Prefer actual bit depth over candidate subslot (which may default to 2)
                    int subslotFromBits = (bitsPerSample_ + 7) / 8;
                    bytesPerFrame_ = c.channels * subslotFromBits;
                    LOGI("start: fallback: mps=%d bpf=%d (alt=%d, bits=%d)", maxPacketSize_, bytesPerFrame_, targetAlt, bitsPerSample_);
                    break;
                }
            }
        }
    }

    if (sampleRate_ != clockRate_ || targetAlt != currentAlt_) {
        setInterfaceAlt(0);
        int scRet = clockRate_;
        if (sampleRate_ != clockRate_) {
            // [v6.0.14] Skip SET_CUR for known buggy DACs; rely on alt-switch implicit lock
            bool buggyDac = (vid_ == 0x2D13 && pid_ == 0xA001)
                || (vid_ == 0x2972 && pid_ == 0x0047)
                || (vid_ == 0x3302 && pid_ == 0x201D)  // TTGK Note: no clock source descriptor
                || (vid_ == 0x0BDA && pid_ == 0x4BA6); // Realtek: 48kHz-only, SET_CUR(44.1k) fails
            scRet = buggyDac ? sampleRate_ : trySetSampleRate(sampleRate_);
            if (buggyDac) { LOGI("start: skipping SET_CUR for pid=%04X (implicit alt-switch lock)", pid_); clockRate_ = sampleRate_; }
            else if (scRet >= 0) clockRate_ = sampleRate_;
            else LOGW("start: SET_CUR(%d) failed, clock stays at %d", sampleRate_, clockRate_);
        }
        bool altOk = setInterfaceAlt(targetAlt);
        if (!altOk) {
            // EBUSY 閲嶈瘯涓€娆★細缁欏唴鏍稿洖锟?URB 鐨勬椂闂寸獥锟?
            usleep(20000);
            altOk = setInterfaceAlt(targetAlt);
        }
        if (altOk) {
            currentAlt_ = targetAlt;
            // 浠庨€変腑鐨勫€欓€夋洿鏂板疄闄呭弬锟?
            for (auto& c : altCandidates_) {
                if (c.alt == targetAlt) {
                    maxPacketSize_ = c.maxPkt;
                    bytesPerFrame_ = c.channels * ((bitsPerSample_ + 7) / 8);  // Use actual stream bits, not DAC max subslot
                    interval_ = c.bInterval;
                    break;
                }
            }
        }
        else LOGE("start: setInterfaceAlt(%d) FAILED twice - DAC stuck at alt=%d, ABORT", targetAlt, currentAlt_);
        LOGI("start: switch clock->%d (ret=%d) alt->%d ok=%d (bits=%d mps=%d bpf=%d)",
             clockRate_, scRet, targetAlt, altOk ? 1 : 0, bitsPerSample_, maxPacketSize_, bytesPerFrame_);
        if (!altOk) return false;
    }

    // 銆愯瘖鏂€戣锟?DAC 鐪熷疄鐘舵€侊細SETINTERFACE 杩斿洖鎴愬姛 锟?璁惧鐪熷垏杩囧幓
    {
        uint8_t curAlt = 255;
        struct usbdevfs_ctrltransfer ct = {};
        ct.bRequestType = 0x81; ct.bRequest = 0x0A; // GET_INTERFACE
        ct.wValue = 0; ct.wIndex = (uint16_t)ifaceNum_;
        ct.wLength = 1; ct.timeout = 1000; ct.data = &curAlt;
        int r1 = ioctl(fd_, USBDEVFS_CONTROL, &ct);
        uint32_t curRate = 0;
        struct usbdevfs_ctrltransfer ct2 = {};
        ct2.bRequestType = 0xA1; ct2.bRequest = 0x01; // CUR
        ct2.wValue = 0x0100;  // CS_SAM_FREQ_CONTROL
        ct2.wIndex = 0x0900;  // clockId=9, AC iface=0
        ct2.wLength = 4; ct2.timeout = 1000; ct2.data = &curRate;
        int r2 = ioctl(fd_, USBDEVFS_CONTROL, &ct2);
        LOGI("start: VERIFY GET_INTERFACE=%d (ret=%d) GET_CUR rate=%u (ret=%d) [want alt=%d rate=%d]",
             curAlt, r1, curRate, r2, currentAlt_, sampleRate_);
    }

    // [V3.3.4] Do NOT reset ring positions here: Kotlin prebuffers ~500ms into the
    // ring BEFORE calling start(); zeroing writePos_/readPos_ discarded that prebuffer
    // (silent gap + first ~500ms of every song lost). Callers (play/resume/seek paths)
    // call resetRingBuffer() explicitly before pushing fresh data.
    underrunCount_.store(0, std::memory_order_release);

    // Start stream thread
    streaming_.store(true, std::memory_order_release);
    streamThread_ = std::thread(streamThreadEntry, this);
    return true;
}

// 鈹€鈹€ stop 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

void UsbAudioDriver::stop() {
    stopThreadOnly();
    releaseInterface();
    LOGI("stop: done (interface released)");
}

void UsbAudioDriver::stopThreadOnly() {
    streaming_.store(false, std::memory_order_release);

    if (fd_ >= 0) {
        for (int i = 0; i < kMaxUrbCount; ++i) {
            struct usbdevfs_urb* u = urbSlots_[i].urb;
            if (u && u->status == 0) {
                u->status = -ENOENT;
                if (ioctl(fd_, USBDEVFS_DISCARDURB, u) < 0) {
                    LOGW("DISCARDURB slot %d: %s", i, strerror(errno));
                }
            }
        }
    }

    if (streamThread_.joinable()) {
        // Detach a waiter so the caller (often the main/UI thread) never blocks
        // if REAPURB is stuck on a misconfigured endpoint. The waiter joins the
        // real stream thread once it exits; this prevents an ANR/freeze while
        // accepting a bounded thread leak in the pathological stuck-REAPURB case.
        std::thread waiter([t = std::move(streamThread_)]() mutable {
            t.join();
        });
        waiter.detach();
    }
    if (feedbackThread_.joinable()) {
        feedbackThread_.join();
    }
    LOGI("stopThreadOnly: threads stopped, USB claim kept");
}

void UsbAudioDriver::resetRingBuffer() {
    // 銆怴3.2.7銆戞殏鍋滃悗鎭㈠鏃跺繀椤绘竻 ring buffer銆俿topThreadOnly 鍙仠浜嗙嚎绋嬶紝
    // ring 鏁版嵁杩樺湪锛寃ritePos_/readPos_ 涓嶅榻愩€傜洿鎺ュ紑娴佷細瀵艰嚧鏂版棫鏁版嵁浜掔浉韪╄笍锟?
    // 宸﹀０閬撳櫔锟?/ 閿欎贡锟?
    // 銆怴3.3.21 淇銆戜笉浠呰閲嶇疆鎸囬拡锛岃繕瑕佹竻闆跺疄闄呮暟鎹€傚惁鍒欐棫姝屾畫锟?PCM 琚€佸幓 DAC锟?
    if (ringBuffer_) {
        memset(ringBuffer_, 0, kRingFrames * 2 * sizeof(float));
    }
    writePos_.store(0, std::memory_order_release);
    readPos_.store(0, std::memory_order_release);
    LOGI("resetRingBuffer: cleared (memset %d frames)", kRingFrames);
}

// 鈹€鈹€ pushPcm 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

int UsbAudioDriver::pushPcm(const float* data, int frameCount) {
    if (!ringBuffer_ || frameCount <= 0) return -1;

    // 銆怴3.2.7銆戣儗鍘嬶細娴佽繍琛屾椂闃诲绛夊緟绌洪棿锛岃В鐮佺嚎绋嬭闄愬埗鍒板疄鏃堕€熷害锟?
    // 鏈紑娴侊紙棰勭紦鍐查樁娈碉級鍐欏灏戠畻澶氬皯锛屼笉闃诲锟?
    int totalWritten = 0;
    const float* src = data;
    int remaining = frameCount;

    while (remaining > 0) {
        int wp = writePos_.load(std::memory_order_acquire);
        int rp = readPos_.load(std::memory_order_acquire);
        // 锟?1 甯ч棿闅欏尯鍒嗘弧/锟?
        int avail = kRingFrames - 1 - ((wp - rp + kRingFrames) % kRingFrames);

        if (avail <= 0) {
            if (!streaming_.load(std::memory_order_acquire)) break;  // 棰勭紦鍐叉弧浜嗙洿鎺ヨ繑锟?
            usleep(2000);  // 绛夋秷璐圭嚎绋嬭吘绌洪棿锛垀88锟?2ms @44.1k锟?
            continue;
        }

        int chunk = remaining < avail ? remaining : avail;
        int samples = chunk * 2; // stereo
        const int mask = kRingFrames * 2 - 1; // power-of-2 assumption

        for (int i = 0; i < samples; ++i) {
            int idx = ((wp * 2) + i) & mask;
            ringBuffer_[idx] = src[i];
        }

        writePos_.store((wp + chunk) % kRingFrames, std::memory_order_release);
        src += samples;
        remaining -= chunk;
        totalWritten += chunk;
    }

    return totalWritten;
}

int UsbAudioDriver::getRingFillFrames() {
    int wp = writePos_.load(std::memory_order_acquire);
    int rp = readPos_.load(std::memory_order_acquire);
    return (wp - rp + kRingFrames) % kRingFrames;
}

// claimInterface 锟?set the exact alternate setting chosen by Kotlin

bool UsbAudioDriver::claimInterface(int desiredAlt) {
    if (fd_ < 0) return false;

    // This mirrors the Java-side conn.claimInterface() 锟?the fd already has
    // the interface claimed. We just set our claimed flag.
    claimed_.store(true, std::memory_order_release);

    // Set the exact alt chosen by getEndpointInfo (Salt Player approach: no looping)
    // NOTE: applies to BOTH UAC1 (full-speed, e.g. TTGK) and UAC2. The alt
    // setting is what activates the ISO OUT endpoint; skipping it for UAC1
    // leaves the endpoint inactive -> submitUrbRaw returns ENOENT.
    if (desiredAlt > 0) {
        if (setInterfaceAlt(desiredAlt)) {
            LOGI("Set interface alt setting %d", desiredAlt);
        } else {
            LOGW("Failed to set interface alt %d, continuing anyway", desiredAlt);
        }
    }

    return claimed_.load(std::memory_order_acquire);
}

void UsbAudioDriver::releaseInterface() {
    claimed_.store(false, std::memory_order_release);
    if (fd_ >= 0) {
        close(fd_);
        fd_ = -1;
    }
}

bool UsbAudioDriver::setInterfaceAlt(int alt) {
    if (fd_ < 0) return false;

    struct usbdevfs_setinterface setif = {};
    setif.interface = ifaceNum_;  // streaming interface number from descriptor
    setif.altsetting = alt;

    int ret = ioctl(fd_, USBDEVFS_SETINTERFACE, &setif);
    if (ret < 0) {
        LOGW("setInterfaceAlt(%d) failed: %s (errno=%d)", alt, strerror(errno), errno);
        return false;
    }
    return true;
}

// 鈹€鈹€ setSampleRate 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

// 鈹€鈹€ Internal helpers 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

// Unconditionally send the sample rate control transfer.
// Caller guarantees: no ISO URB in flight (call from open() before alt=1).
// Uses the first UAC2 Clock Source entity found in the descriptor.
int UsbAudioDriver::trySetSampleRate(int rate) {
    uint8_t data[4];
    struct usbdevfs_ctrltransfer ctrl = {};
    int ret = -1;

    if (isUac2_) {
        // Find the first UAC2 Clock Source (not Clock Selector).
        // Parse descriptor: CS_CLOCK_SOURCE=0x0A at pos+2, id at pos+3.
        uint8_t desc[4096];
        int len = readConfigDescriptor(0, desc, sizeof(desc));
        int clockId = 3;  // default from OT descriptor (wClockSourceCluster=0x000A 锟?id=3)
        int pos = 0;
        while (pos < len && pos + 3 < (int)sizeof(desc)) {
            uint8_t dLen  = desc[pos];
            uint8_t dType = desc[pos + 1];
            uint8_t dSub  = desc[pos + 2];
            if (dType == UAC2_CS_INTERFACE && dSub == UAC2_CS_CLOCK_SOURCE && dLen >= 8) {
                clockId = desc[pos + 3];
                LOGI("Clock Source (not selector): id=%d", clockId);
                break;
            }
            pos += (dLen > 0) ? dLen : 1;
            if (dLen == 0) break;
        }

        // UAC2: SET_CUR on the clock entity via AC interface (ifaceNum_).
        data[0] = (uint8_t)(rate & 0xFF);
        data[1] = (uint8_t)((rate >> 8) & 0xFF);
        data[2] = (uint8_t)((rate >> 16) & 0xFF);
        data[3] = (uint8_t)((rate >> 24) & 0xFF);
        ctrl.bRequestType = 0x21;                          // host鈫抎evice | class | interface
        ctrl.bRequest     = 0x01;                          // SET_CUR
        ctrl.wValue       = 0x0100;                        // CS_SAM_FREQ_CONTROL << 8
        // wIndex: (clockId << 8) | AC interface number (0 for TTGK)
        ctrl.wIndex       = (uint16_t)((clockId << 8) | 0x00);
        ctrl.wLength      = 4;
        ctrl.timeout      = 200;
        ctrl.data         = data;
        ret = ioctl(fd_, USBDEVFS_CONTROL, &ctrl);
        LOGI("trySetSampleRate(UAC2): clockId=%d rate=%d wIndex=0x%04X ret=%d",
             clockId, rate, ctrl.wIndex, ret);
    } else {
        // UAC1: SET_CUR SAMPLING_FREQ_CONTROL on the ISO OUT endpoint.
        data[0] = (uint8_t)(rate & 0xFF);
        data[1] = (uint8_t)((rate >> 8) & 0xFF);
        data[2] = (uint8_t)((rate >> 16) & 0xFF);
        ctrl.bRequestType = 0x22;                          // host鈫抎evice | class | endpoint
        ctrl.bRequest     = 0x01;                          // SET_CUR
        ctrl.wValue       = 0x0100;                        // SAMPLING_FREQ_CONTROL
        ctrl.wIndex       = (uint16_t)epAddress_;          // ISO OUT endpoint address
        ctrl.wLength      = 3;
        ctrl.timeout      = 200;
        ctrl.data         = data;
        ret = ioctl(fd_, USBDEVFS_CONTROL, &ctrl);
        LOGI("trySetSampleRate(UAC1): ep=0x%02X rate=%d ret=%d", epAddress_, rate, ret);
    }

    if (ret < 0) {
        LOGW("trySetSampleRate(%d) failed: %s (errno=%d)", rate, strerror(errno), errno);
        return -1;
    }
    sampleRate_ = rate;
    return rate;
}

// Public setSampleRate: only safe when stream is NOT active.
// Since we now call trySetSampleRate from open() (before alt=1),
// this is mostly a no-op during normal start().
int UsbAudioDriver::setSampleRate(int rate) {
    if (fd_ < 0) return -1;
    if (streaming_.load(std::memory_order_acquire)) {
        LOGW("setSampleRate(%d): skipped 锟?stream active", rate);
        return sampleRate_;
    }
    return trySetSampleRate(rate);
}

// 鈹€鈹€ streamLoop 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€




void UsbAudioDriver::streamLoop() {
    // 銆怴3.2.7銆戦煶棰戝疄鏃朵紭鍏堢骇锟?19 = ANDROID URGENT_AUDIO锛夛紝闄嶄綆琚皟搴﹀櫒棰勫崰瀵艰嚧锟?DAC 鏂伯
    setpriority(PRIO_PROCESS, 0, -19);
    const int pktMaxFrames = packetSizeFrames();
    const int pktMaxBytes  = pktMaxFrames * bytesPerFrame_;
    const int urbBytes     = pktMaxBytes * kPacketsPerUrb;
    const double samplesPerMicroframe = sampleRate_ / (isUac2_ ? 8000.0 : 1000.0);

    LOGI("streamLoop: maxFrames=%d urbBytes=%d pkts/urb=%d hs=%d rate/mf=%.4f",
         pktMaxFrames, urbBytes, kPacketsPerUrb, isUac2_ ? 1 : 0, samplesPerMicroframe);

    // 銆怴3.2.7銆慞re-queue 12 URBs锛堝師4锛夛細纭欢鍦ㄩ闃熷垪 32ms锟?6ms锟?
    // 鎶楄皟搴︽姈鍔ㄢ€斺€旈暱鎾伓鍙戝崱椤挎牴鍥狅細stream 绾跨▼琚锟?>32ms 鍗虫柇锟?
    constexpr int kPreQueue = 12;
    for (int s = 0; s < kPreQueue; s++) {
        memset(urbSlots_[s].buffer, 0, urbBytes);
        urbSlots_[s].urb->number_of_packets = kPacketsPerUrb;
        urbSlots_[s].urb->buffer_length = urbBytes;
        for (int p = 0; p < kPacketsPerUrb; ++p) {
            urbSlots_[s].urb->iso_frame_desc[p].length = pktMaxBytes;
        }
        if (!submitUrbRaw(s)) {
            LOGE("streamLoop: pre-queue URB %d failed, aborting", s);
            streaming_.store(false, std::memory_order_release);
            return;
        }
    }
    int slot = kPreQueue;

    size_t reapUrbSize = sizeof(usbdevfs_urb) + kPacketsPerUrb * sizeof(usbdevfs_iso_packet_desc);
    auto* reapBuf = static_cast<uint8_t*>(calloc(1, reapUrbSize));
    if (!reapBuf) {
        LOGE("streamLoop: OOM for REAPURB buffer");
        streaming_.store(false, std::memory_order_release);
        return;
    }
    int urbCompletions = 0;
    double phaseAccum = 0.0;

    while (streaming_.load(std::memory_order_acquire)) {
        memset(reapBuf, 0, reapUrbSize);
        int ret = ioctl(fd_, USBDEVFS_REAPURB, reapBuf);
        if (ret < 0) {
            if (errno == EINTR || errno == EAGAIN) continue;
            LOGE("REAPURB #%d failed: %s (errno=%d)", urbCompletions + 1, strerror(errno), errno);
            break;
        }
        urbCompletions++;
        urbCompleted_.fetch_add(1, std::memory_order_relaxed);
        if (urbCompletions <= 4 || urbCompletions % 100 == 0) {
            LOGI("REAPURB #%d OK", urbCompletions);
        }

        int wp = writePos_.load(std::memory_order_acquire);
        int rp = readPos_.load(std::memory_order_acquire);
        int availFrames = (wp - rp + kRingFrames) % kRingFrames;

        uint8_t* buf = urbSlots_[slot].buffer;
        const int ringMask = kRingFrames * 2 - 1;
        int sampleOffset = 0;
        int totalFramesNeeded = 0;

        // Phase accumulator: alternate 5/6 frames per microframe for exact 44100 Hz
        for (int p = 0; p < kPacketsPerUrb; ++p) {
            phaseAccum += samplesPerMicroframe;
            int nFrames = (int)phaseAccum;
            phaseAccum -= nFrames;
            totalFramesNeeded += nFrames;
            int nBytes = nFrames * bytesPerFrame_;
            urbSlots_[slot].urb->iso_frame_desc[p].length = nBytes;

            if (availFrames >= totalFramesNeeded) {
                int nSamples = nFrames * channels_;
                float vol = volume_;
                switch (bitsPerSample_) {
                case 16: {
                    auto* out = reinterpret_cast<int16_t*>(buf + sampleOffset);
                    for (int j = 0; j < nSamples; ++j) {
                        int idx = ((rp * 2) + sampleOffset / 2 + j) & ringMask;
                        float s = ringBuffer_[idx] * vol;
                        if (s >  1.0f) s =  1.0f;
                        if (s < -1.0f) s = -1.0f;
                        out[j] = static_cast<int16_t>(s * 32767.0f);
                    }
                    break;
                }
                case 24: {
                    // 銆怴3.2.7锟? 瀛楄妭 LE 鎵撳寘锛坅lt2 subslot=3锟?
                    uint8_t* out = buf + sampleOffset;
                    for (int j = 0; j < nSamples; ++j) {
                        int idx = ((rp * 2) + sampleOffset / 3 + j) & ringMask;
                        float s = ringBuffer_[idx] * vol;
                        if (s >  1.0f) s =  1.0f;
                        if (s < -1.0f) s = -1.0f;
                        int32_t v = static_cast<int32_t>(s * 8388607.0f);
                        out[j * 3]     = static_cast<uint8_t>(v & 0xFF);
                        out[j * 3 + 1] = static_cast<uint8_t>((v >> 8) & 0xFF);
                        out[j * 3 + 2] = static_cast<uint8_t>((v >> 16) & 0xFF);
                    }
                    break;
                }
                case 32: {
                    auto* out = reinterpret_cast<int32_t*>(buf + sampleOffset);
                    for (int j = 0; j < nSamples; ++j) {
                        int idx = ((rp * 2) + sampleOffset / 4 + j) & ringMask;
                        double s = static_cast<double>(ringBuffer_[idx]) * vol;
                        if (s >  1.0) s =  1.0;
                        if (s < -1.0) s = -1.0;
                        out[j] = static_cast<int32_t>(s * 2147483647.0);
                    }
                    break;
                }
                default:
                    memset(buf + sampleOffset, 0, nBytes);
                    break;
                }
            } else {
                // underrun for this packet: fill silence, don't play stale buffer data
                memset(buf + sampleOffset, 0, nBytes);
            }
            sampleOffset += nBytes;
        }

        if (availFrames >= totalFramesNeeded) {
            readPos_.store((rp + totalFramesNeeded) % kRingFrames, std::memory_order_release);
            totalSamplesOut_.fetch_add(totalFramesNeeded, std::memory_order_relaxed);
        } else {
            underrunCount_.fetch_add(1, std::memory_order_relaxed);
        }

        urbSlots_[slot].urb->buffer_length = sampleOffset;
        bufferWatermarkMs_.store(availFrames * 1000 / sampleRate_, std::memory_order_relaxed);
        if (!submitUrbRaw(slot)) {
            LOGE("streamLoop: submitUrbRaw[%d] failed, breaking", slot);
            break;
        }
        slot = (slot + 1) % kMaxUrbCount;
    }

    free(reapBuf);
    streaming_.store(false, std::memory_order_release);
    LOGI("streamLoop: exit (%d URB completions)", urbCompletions);
}

void UsbAudioDriver::feedbackLoop() {
    // For async DACs with explicit feedback endpoint.
    // Reads the feedback value to adjust ring buffer pacing.
    // Simple implementation: poll feedback value, log deviation.
    LOGI("feedbackLoop: started");

    while (streaming_.load(std::memory_order_acquire)) {
        // If no feedback endpoint, just maintain timing
        std::this_thread::sleep_for(std::chrono::microseconds(kFeedbackIntervalUs));
    }

    LOGI("feedbackLoop: exit");
}

// 鈹€鈹€ findClockSourceId 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

int UsbAudioDriver::findClockSourceId() {
    if (!isUac2_) return -1;

    // Read the raw configuration descriptor
    uint8_t desc[4096];
    int len = readConfigDescriptor(0, desc, sizeof(desc));
    if (len < 0) return -1;

    // Scan for AudioControl interface descriptors
    int pos = 0;
    while (pos < len) {
        uint8_t dLen  = desc[pos];
        uint8_t dType = (pos + 1 < len) ? desc[pos + 1] : 0;
        uint8_t dSub  = (pos + 2 < len) ? desc[pos + 2] : 0;

        if (dType == UAC2_CS_INTERFACE && dSub == UAC2_CS_CLOCK_SOURCE && dLen >= 8) {
            int clockId = desc[pos + 3];
            LOGI("Found UAC2 Clock Source: id=%d, len=%d", clockId, dLen);
            return clockId;
        }

        pos += (dLen > 0) ? dLen : 1;
        if (dLen == 0) break;
    }

    LOGW("No UAC2 Clock Source descriptor found");
    return -1;
}

// 鈹€鈹€ findFeedbackEndpoint 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

int UsbAudioDriver::findFeedbackEndpoint() {
    if (fd_ < 0) return -1;

    uint8_t desc[4096];
    int len = readConfigDescriptor(0, desc, sizeof(desc));
    if (len < 0) return -1;

    int pos = 0;
    while (pos < len) {
        uint8_t dLen  = desc[pos];
        uint8_t dType = (pos + 1 < len) ? desc[pos + 1] : 0;
        uint8_t dSub  = (pos + 2 < len) ? desc[pos + 2] : 0;

        // UAC 2.0: AS Isochronous Data Endpoint descriptor -> bmAttributes can signal
        // explicit feedback. But simpler: look for an IN isochronous endpoint paired
        // with the streaming interface.
        if (dType == USB_DT_ENDPOINT && dLen >= 7) {
            uint8_t epAddr = desc[pos + 2];
            uint8_t epAttr = desc[pos + 3];
            // IN, isochronous, different from our OUT endpoint
            if ((epAddr & 0x80) && (epAttr & 0x03) == 0x01 && epAddr != epAddress_) {
                // Check if it's part of an audio streaming interface
                // Simple heuristic: any IN isochronous endpoint that isn't our OUT
                LOGI("Found potential feedback endpoint: 0x%02X", epAddr);
                return epAddr & 0x7F; // strip direction bit
            }
        }

        pos += (dLen > 0) ? dLen : 1;
        if (dLen == 0) break;
    }

    return -1;
}

// 鈹€鈹€ parseSupportedRates 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

void UsbAudioDriver::parseSupportedRates() {
    supportedRates_.clear();

    uint8_t desc[4096];
    int len = readConfigDescriptor(0, desc, sizeof(desc));
    if (len < 0) {
        supportedRates_ = "unknown (cannot read descriptor)";
        return;
    }

    // Scan for Type I Format descriptors in both UAC 1.0 and UAC 2.0
    int pos = 0;
    while (pos < len) {
        uint8_t dLen  = desc[pos];
        uint8_t dType = (pos + 1 < len) ? desc[pos + 1] : 0;
        uint8_t dSub  = (pos + 2 < len) ? desc[pos + 2] : 0;

        // UAC 1.0: Type I Format = CS_INTERFACE, subtype 0x02 (FORMAT_TYPE)
        // UAC 2.0: Type I Format = CS_INTERFACE, subtype 0x01 (FORMAT_TYPE_I)
        bool isFormatDesc = (dType == UAC2_CS_INTERFACE) &&
            ((dSub == 2) || (dSub == 0x01));

        if (isFormatDesc && dLen >= 8) {
            // Parse tSamFreq or the frequency table
            // UAC 1.0 Type I: 3-byte tSamFreq at offset 7+
            // UAC 2.0 Type I: 4-byte tSamFreq at offset 7+
            if (supportedRates_.empty()) {
                // Single fixed rate
                uint32_t rate = 0;
                if (dSub == 2) { // UAC 1.0
                    rate = desc[pos + 7] | (desc[pos + 8] << 8) | (desc[pos + 9] << 16);
                } else { // UAC 2.0
                    rate = desc[pos + 7] | (desc[pos + 8] << 8) |
                           (desc[pos + 9] << 16) | (desc[pos + 10] << 24);
                }

                if (rate > 0) {
                    char buf[32];
                    snprintf(buf, sizeof(buf), "%u", rate);
                    supportedRates_ = buf;
                }
            } else {
                // Already have a value, this might be an additional format
                char buf[32];
                uint32_t rate = 0;
                if (dSub == 2) {
                    rate = desc[pos + 7] | (desc[pos + 8] << 8) | (desc[pos + 9] << 16);
                } else {
                    rate = desc[pos + 7] | (desc[pos + 8] << 8) |
                           (desc[pos + 9] << 16) | (desc[pos + 10] << 24);
                }
                if (rate > 0) {
                    snprintf(buf, sizeof(buf), " %u", rate);
                    supportedRates_ += buf;
                }
            }
        }

        pos += (dLen > 0) ? dLen : 1;
        if (dLen == 0) break;
    }

    if (supportedRates_.empty()) {
        supportedRates_ = "only 48000"; // default assumption
    }

    LOGI("Supported sample rates: %s", supportedRates_.c_str());
}

// 鈹€鈹€ parseAltCandidates 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€
// Scan all AudioStreaming alternate settings and build a candidate table.
// Each candidate has ifaceNum (bInterfaceNumber for SETINTERFACE), alt,
// endpoint address, maxPacketSize, channels, subslot (bytes-per-sample),
// bit resolution, associated clock source ID, and sample rate.
//
// This replaces the old hardcoded alt1=16bit/alt2=24bit/alt3=32bit assumption
// with real descriptor-driven discovery that works with any UAC-compliant DAC.
// 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

void UsbAudioDriver::parseAltCandidates() {
    altCandidates_.clear();

    uint8_t desc[4096];
    // Two-step: read header (9 bytes) first to get wTotalLength, then full read
    int hdrLen = readConfigDescriptor(0, desc, 9);
    if (hdrLen < 9) {
        LOGW("parseAltCandidates: cannot read config header (got %d bytes)", hdrLen);
        return;
    }
    uint16_t wTotalLen = desc[2] | (desc[3] << 8);
    LOGI("parseAltCandidates: wTotalLength=%u", wTotalLen);
    int len = readConfigDescriptor(0, desc, wTotalLen > sizeof(desc) ? sizeof(desc) : wTotalLen);
    if (len < 0) {
        LOGW("parseAltCandidates: cannot read config descriptor");
        return;
    }

    // 鈹€鈹€ First pass: build map of UAC2 clock source entities 鈹€鈹€
    // clockId 锟?{ offset in desc for later sampling-frequency range read }
    struct ClkInfo { int id; int offset; };
    std::vector<ClkInfo> clocks;
    if (isUac2_) {
        int pos = 0;
        while (pos + 3 < len) {
            uint8_t dLen = desc[pos];
            uint8_t dType = (pos + 1 < len) ? desc[pos + 1] : 0;
            uint8_t dSub = (pos + 2 < len) ? desc[pos + 2] : 0;
            if (dType == UAC2_CS_INTERFACE && dSub == UAC2_CS_CLOCK_SOURCE && dLen >= 8) {
                clocks.push_back({desc[pos + 3], pos});
            }
            pos += (dLen > 0) ? dLen : 1;
            if (dLen == 0) break;
        }
    }

    // 鈹€鈹€ Second pass: walk to find AudioStreaming interface descriptors 鈹€鈹€
    // We look for USB_DT_INTERFACE with bInterfaceClass=AUDIO, bInterfaceSubclass=STREAMING,
    // bAlternateSetting > 0 (skip alt=0 idle). Then inside each AS interface we capture:
    //   - Clock Source linkage (bTerminalLink in input terminal, or CS_CLOCK_SOURCE in UAC2)
    //   - Endpoint descriptor with bmAttributes=ISOCHRONOUS, direction=OUT
    //   - Format descriptor (CS_INTERFACE, subtype FORMAT_TYPE / FORMAT_TYPE_I)
    int pos = 0;
    int curIfaceNum = -1, curIfaceClass = -1, curIfaceSubclass = -1, curAlt = -1;
    int curClockId = -1;
    int curEpAddr = -1, curMaxPkt = 0, curBInterval = 1;
    int curChannels = 2, curSubslot = 0, curRes = 0, curRate = 0;
    bool inStreamingIface = false, inEndpoint = false, inFormatDesc = false;

    auto resetAltState = [&]() {
        curEpAddr = -1; curMaxPkt = 0; curBInterval = 1;
        curChannels = 2; curSubslot = 0; curRes = 0; curRate = 0;
        inEndpoint = false; inFormatDesc = false;
    };

    auto flushCandidate = [&]() {
        if (inStreamingIface && curAlt > 0 && curEpAddr > 0 && curMaxPkt > 0) {
            // Don't require format descriptor 锟?some DACs only declare rate via clock source,
            // which we read in parseClockRanges(). Mark rate=0 as multi-rate.
            altCandidates_.push_back({
                curIfaceNum, curAlt, curEpAddr, curMaxPkt, curBInterval,
                curChannels, curSubslot, curRes, curClockId, curRate
            });
        }
        curEpAddr = -1; curMaxPkt = 0; curBInterval = 1;
        curChannels = 2; curSubslot = 2; curRes = 16; curRate = 0;
        inEndpoint = false; inFormatDesc = false;
    };

    LOGI("parseAlt: desc total len=%zu", (size_t)len);
    while (pos + 2 < len) {
        uint8_t dLen = desc[pos];
        uint8_t dType = (pos + 1 < len) ? desc[pos + 1] : 0;
        LOGI("parseAlt: pos=%d dType=0x%02x dLen=%d", (int)pos, dType, (int)dLen);
        if (dLen == 0) { LOGI("parseAlt: BREAK dLen=0 at pos=%d", (int)pos); break; }
        if (dLen < 2 || pos + dLen > len) { LOGI("parseAlt: BREAK dLen=%d pos+dLen=%zu > len=%zu", (int)dLen, (size_t)(pos+dLen), (size_t)len); break; }

        switch (dType) {
        case USB_DT_INTERFACE:  // 0x04
            if (dLen >= 9) {
                flushCandidate();
                curIfaceNum   = desc[pos + 2];  // bInterfaceNumber
                curAlt        = desc[pos + 3];  // bAlternateSetting
                curIfaceClass = desc[pos + 5];
                curIfaceSubclass = desc[pos + 6];
                // 1=IAD, 3=HID descriptor follow, skip non-audio
                LOGI("parseAlt: iface=%d alt=%d class=%d subclass=%d", curIfaceNum, curAlt, curIfaceClass, curIfaceSubclass);
                inStreamingIface = (curIfaceClass == 1/*AUDIO*/ && curIfaceSubclass == 2/*STREAMING*/ && curAlt > 0);
                if (!inStreamingIface) {
                    // Reset clockId on any interface boundary for safety
                    curClockId = -1;
                }
            }
            break;

        case USB_DT_ENDPOINT:  // 0x05
            if (inStreamingIface && dLen >= 7) {
                uint8_t epAddr = desc[pos + 2];
                uint8_t epAttr = desc[pos + 3];
                bool isIsoOut = ((epAttr & 0x03) == 0x01/*ISO*/) && ((epAddr & 0x80) == 0/*OUT*/);
                if (isIsoOut) {
                    curEpAddr   = epAddr;
                    curMaxPkt   = desc[pos + 4] | (desc[pos + 5] << 8);
                    curBInterval = desc[pos + 6];
                    inEndpoint  = true;
                }
            }
            break;

        case UAC2_CS_INTERFACE:  // 0x24 锟?class-specific interface descriptor
            if (inStreamingIface && dLen >= 3) {
                uint8_t dSub = desc[pos + 2];
                // UAC 1.0/2.0 both use subtype 0x02 for FORMAT_TYPE descriptors.
                // subtype 0x01 inside AudioStreaming is AS_GENERAL (different layout!).
                if (dSub == 0x02 && dLen >= 6) {
                    inFormatDesc = true;
                    if (dLen >= 6) {
                        // UAC2 FORMAT_TYPE (dLen=6): pos+3=bFormatType pos+4=bSubslotSize pos+5=bBitResolution
                        // Some DACs have dLen=6 with only subslot+bitRes at +4/+5
                        // Detect layout: if desc[pos+4] > 8 it's bBitResolution, shift by one
                        int subOff = 4, resOff = 5;
                        uint8_t val4 = desc[pos + 4];
                        uint8_t val5 = (dLen > 5) ? desc[pos + 5] : 0;
                        if (val4 > 8 && val4 <= 32 && (val5 == 0 || val5 > 32)) {
                            // val4 looks like bBitResolution, val5 is garbage → shift
                            subOff = 3; resOff = 4;
                        }
                        if (dLen > subOff) curSubslot = desc[pos + subOff];
                        if (dLen > resOff) curRes = desc[pos + resOff];
                        // If subslot looks like bit resolution (16/24/32), derive from it
                        if (curSubslot > 8 && curSubslot <= 32) {
                            curRes = curSubslot;
                            curSubslot = (curSubslot + 7) / 8;
                        }
                    }
                    // tSamFreq: 3 bytes UAC1 (offset 7-9), 4 bytes UAC2 (offset 7-10)
                    if (dLen >= 10) {
                        uint32_t sr = desc[pos + 7] | (desc[pos + 8] << 8);
                        if (isUac2_) {
                            sr |= (desc[pos + 9] << 16) | (desc[pos + 10] << 24);
                        } else {
                            sr |= (desc[pos + 9] << 16);
                        }
                        curRate = (int)sr;
                    }
                }
            }
            break;
        }

        pos += dLen;
    }
    flushCandidate();  // last one

    // 鈹€鈹€ Post-process: for UAC2, try to link candidates to clock sources 鈹€鈹€
    // via the CS interface descriptor chain (AS general has bTerminalLink).
    // We re-walk to find input terminal 锟?clock association.
    if (isUac2_ && !clocks.empty()) {
        pos = 0;
        curClockId = clocks[0].id;  // default: first clock
        while (pos + 2 < len) {
            uint8_t dLen = desc[pos];
            uint8_t dType = (pos + 1 < len) ? desc[pos + 1] : 0;
            uint8_t dSub = (pos + 2 < len) ? desc[pos + 2] : 0;
            if (dLen < 2 || pos + dLen > len) break;

            if (dType == USB_DT_INTERFACE && dLen >= 9) {
                // Flush previous alt candidate before switching
                flushCandidate();
                curIfaceNum = desc[pos + 2];
                curAlt = desc[pos + 3];
                curIfaceClass = desc[pos + 5];
                curIfaceSubclass = desc[pos + 6];
                inStreamingIface = (curIfaceClass == 1 && curIfaceSubclass == 2);
                resetAltState();
            }

            // UAC2 Input Terminal descriptor: subtype 0x02, has wTerminalType + bCSourceID
            if (dType == UAC2_CS_INTERFACE && dSub == 0x02 && dLen >= 8) {
                int terminalClockId = desc[pos + 7];  // bCSourceID (clock source ID)
                // Input Terminal is on AudioControl interface, its clock applies to ALL AudioStreaming candidates
                LOGI("parseAlt: InputTerminal clockId=%d on iface=%d", terminalClockId, curIfaceNum);
                for (auto& c : altCandidates_) {
                    if (c.ifaceNum == curIfaceNum && c.alt > 0 && c.clockId < 0) {
                        c.clockId = terminalClockId;
                    }
                }
                // If no candidate on this iface, set on ALL unassociated candidates
                bool any = false;
                for (auto& c : altCandidates_) { if (c.clockId == terminalClockId) any = true; }
                if (!any) {
                    for (auto& c : altCandidates_) {
                        if (c.clockId < 0) c.clockId = terminalClockId;
                    }
                }
            }

            pos += dLen;
        }
    }

    // 鈹€鈹€ Also capture acIface_ (AudioControl interface number) for clock GET_RANGE 鈹€鈹€
    acIface_ = 0;  // default
    pos = 0;
    while (pos + 9 < len) {
        uint8_t dLen = desc[pos];
        if (dLen < 9) { pos += (dLen > 0) ? dLen : 1; continue; }
        uint8_t dType = desc[pos + 1];
        if (dType == USB_DT_INTERFACE) {
            if (desc[pos + 5] == 1/*AUDIO*/ && desc[pos + 6] == 1/*AUDIOCONTROL*/) {
                acIface_ = desc[pos + 2];  // bInterfaceNumber
                break;
            }
        }
        pos += dLen;
    }

    LOGI("parseAltCandidates: %zu candidates, acIface=%d", altCandidates_.size(), acIface_);
}


// ===========================================================================
// parseClockRanges: read clock sub-ranges via UAC2 GET_RANGE
// ===========================================================================

void UsbAudioDriver::parseClockRanges() {
    clockRanges_.clear();
    if (!isUac2_ || fd_ < 0) return;

    // Gather unique clock IDs from candidates
    std::vector<int> clockIds;
    for (auto& c : altCandidates_) {
        if (c.clockId > 0) {
            bool found = false;
            for (int id : clockIds) if (id == c.clockId) { found = true; break; }
            if (!found) clockIds.push_back(c.clockId);
        }
    }

    if (clockIds.empty()) {
        int cid = findClockSourceId();
        if (cid > 0) clockIds.push_back(cid);
    }
    if (clockIds.empty()) {
        for (int cid = 3; cid <= 10; ++cid) clockIds.push_back(cid);
    }

    for (int cid : clockIds) {
        // [v6.x] UAC2 GET_MIN/GET_MAX/GET_RES — three separate 4-byte requests.
        // Old single GET_RANGE(12 bytes) returned garbage on TTGK/Realtek:
        // they return only 4 bytes, the rest is uninitialized stack data.
        uint8_t minData[4] = {}, maxData[4] = {}, resData[4] = {};
        auto read4 = [&](uint8_t req, uint8_t* buf) -> int {
            struct usbdevfs_ctrltransfer ct = {};
            ct.bRequestType = 0xA1;
            ct.bRequest     = req;
            ct.wValue       = 0x0100;  // CS_SAM_FREQ_CONTROL
            ct.wIndex       = (uint16_t)((cid << 8) | (acIface_ & 0xFF));
            ct.wLength      = 4;
            ct.timeout      = 200;
            ct.data         = buf;
            int r = ioctl(fd_, USBDEVFS_CONTROL, &ct);
            if (r < 4) {
                LOGW("getMin/Max/Res clkId=%d req=0x%02X failed ret=%d", cid, req, r);
                return -1;
            }
            return 0;
        };

        if (read4(0x82, minData) == 0 && read4(0x83, maxData) == 0) {
            uint32_t rMin = minData[0] | (minData[1]<<8) | (minData[2]<<16) | (minData[3]<<24);
            uint32_t rMax = maxData[0] | (maxData[1]<<8) | (maxData[2]<<16) | (maxData[3]<<24);
            int rRes = 0;
            if (read4(0x84, resData) == 0) {
                rRes = (int)(resData[0] | (resData[1]<<8) | (resData[2]<<16) | (resData[3]<<24));
            }
            clockRanges_.push_back({(int)rMin, (int)rMax, rRes, cid});
        }
    }

    if (clockRanges_.empty()) {
        LOGW("getRange empty, using tSamFreq fallback");
        std::string rates = supportedRates_;
        if (!rates.empty() && rates != "unknown (cannot read descriptor)") {
            int cid = clockIds.empty() ? 0 : clockIds[0];
            std::string token;
            for (size_t i = 0; i <= rates.size(); ++i) {
                if (i == rates.size() || rates[i] == ' ') {
                    if (!token.empty()) {
                        int rate = atoi(token.c_str());
                        if (rate > 0) clockRanges_.push_back({rate, rate, 0, cid});
                        token.clear();
                    }
                } else if (rates[i] >= '0' && rates[i] <= '9') {
                    token += rates[i];
                }
            }
        }
    }

    LOGI("parseClockRanges: %zu ranges", clockRanges_.size());
}

// ===========================================================================
// selectAltForRate: pick best alt setting from parsed candidates
// ===========================================================================

int UsbAudioDriver::selectAltForRate(int targetRate, int targetBits, int targetChannels) {
    // Step 1: clock range check
    if (!clockRanges_.empty()) {
        bool rateOk = false;
        for (auto& cr : clockRanges_) {
            if (targetRate >= cr.min && targetRate <= cr.max) {
                rateOk = true;
                break;
            }
        }
        if (!rateOk) {
            LOGE("selectAltForRate: %d Hz outside all clock ranges", targetRate);
            return -1;
        }
    }

    if (altCandidates_.empty()) {
        LOGE("selectAltForRate: no candidates");
        return -1;
    }

    // Step 2: filter + score
    struct Match { DacAltCandidate cand; int score = 0; };
    std::vector<Match> matches;
    int reqSubslot = (targetBits + 7) / 8;

    for (auto& c : altCandidates_) {
        if (c.channels != targetChannels && c.channels != 0) continue;
        if (c.sampleRate != 0 && c.sampleRate != targetRate) continue;
        if (c.subslot < reqSubslot) continue;

        int score = 0;
        if (c.sampleRate == targetRate) score += 1000;
        if (c.subslot == reqSubslot) score += 200;
        else if (c.subslot == reqSubslot + 1) score += 100;
        score += c.res;
        score += c.maxPkt / 16;

        matches.push_back({c, score});
    }

    if (matches.empty()) {
        for (auto& c : altCandidates_) {
            if ((c.channels == targetChannels || c.channels == 0) && c.subslot >= reqSubslot) {
                matches.push_back({c, c.res + c.maxPkt / 32});
            }
        }
    }

    if (matches.empty()) {
        LOGE("selectAltForRate: no candidate r=%d bits=%d ch=%d", targetRate, targetBits, targetChannels);
        return -1;
    }

    std::sort(matches.begin(), matches.end(),
              [](const Match& a, const Match& b) { return a.score > b.score; });

    auto& best = matches[0];
    LOGI("selectAltForRate: alt=%d r=%d sub=%d ch=%d res=%d score=%d / %zu",
         best.cand.alt, best.cand.sampleRate, best.cand.subslot,
         best.cand.channels, best.cand.res, best.score, matches.size());

    return best.cand.alt;
}
// 鈹€鈹€ readConfigDescriptor 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

int UsbAudioDriver::readConfigDescriptor(uint8_t interfaceNum, uint8_t* buf, size_t maxLen) {
    if (fd_ < 0) return 0;

    struct usbdevfs_ctrltransfer ctrl = {};
    ctrl.bRequestType = 0x80; // device-to-host, standard, device
    ctrl.bRequest     = USB_REQ_GET_DESCRIPTOR;
    ctrl.wValue       = (USB_DT_CONFIG << 8) | 0; // config descriptor, index 0
    ctrl.wIndex       = 0;
    ctrl.wLength      = static_cast<uint16_t>(maxLen);
    ctrl.timeout      = 500;
    ctrl.data         = buf;

    int ret = ioctl(fd_, USBDEVFS_CONTROL, &ctrl);
    if (ret < 0) {
        LOGE("readConfigDescriptor failed: %s (errno=%d)", strerror(errno), errno);
        return -1;
    }

    return ret;
}

// 鈹€鈹€ controlTransfer 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

int UsbAudioDriver::controlTransfer(uint8_t bmRequestType, uint8_t bRequest,
                                    uint16_t wValue, uint16_t wIndex,
                                    void* data, uint16_t wLength, unsigned timeoutMs) {
    if (fd_ < 0) return -1;

    // For class-specific requests targeting an interface, map wIndex to interface number
    struct usbdevfs_ctrltransfer ctrl = {};
    ctrl.bRequestType = bmRequestType;
    ctrl.bRequest     = bRequest;
    ctrl.wValue       = wValue;
    ctrl.wIndex       = wIndex;
    ctrl.wLength      = wLength;
    ctrl.timeout      = timeoutMs;
    ctrl.data         = data;

    int ret = ioctl(fd_, USBDEVFS_CONTROL, &ctrl);
    if (ret < 0) {
        return -1;
    }
    return ret; // bytes transferred
}

// 鈹€鈹€ submitUrb 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

bool UsbAudioDriver::submitUrb(int slot, int numBytes) {
    if (slot < 0 || slot >= kMaxUrbCount) return false;

    struct usbdevfs_urb* urb = urbSlots_[slot].urb;
    urb->type            = USBDEVFS_URB_TYPE_ISO;
    urb->endpoint        = epAddress_;
    urb->buffer          = urbSlots_[slot].buffer;
    urb->buffer_length   = numBytes;
    urb->number_of_packets = 1;
    urb->flags           = USBDEVFS_URB_ISO_ASAP;
    urb->iso_frame_desc[0].length = numBytes;

    int ret = ioctl(fd_, USBDEVFS_SUBMITURB, urb);
    if (ret < 0 && errno != EAGAIN) {
        LOGE("submitUrb[%d]: %s (errno=%d)", slot, strerror(errno), errno);
        return false;
    }
    return true;
}

// 鈹€鈹€ submitUrbRaw (multi-packet URB already set up) 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

bool UsbAudioDriver::submitUrbRaw(int slot) {
    if (slot < 0 || slot >= kMaxUrbCount) return false;

    struct usbdevfs_urb* urb = urbSlots_[slot].urb;
    urb->type            = USBDEVFS_URB_TYPE_ISO;
    urb->endpoint        = epAddress_;
    urb->buffer          = urbSlots_[slot].buffer;
    urb->status          = 0;
    urb->flags           = USBDEVFS_URB_ISO_ASAP;
    urb->number_of_packets = kPacketsPerUrb;

    int ret = ioctl(fd_, USBDEVFS_SUBMITURB, urb);
    if (ret < 0 && errno != EAGAIN) {
        LOGE("submitUrbRaw[%d]: %s (errno=%d)", slot, strerror(errno), errno);
        urbErrors_.fetch_add(1, std::memory_order_relaxed);
        return false;
    }
    urbSubmitted_.fetch_add(1, std::memory_order_relaxed);
    return true;
}

// 鈹€鈹€ packet size helpers 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

int UsbAudioDriver::packetSizeFrames() const {
    // UAC2 high-speed: 8000 microframes/sec
    // Full-speed: 1000 frames/sec
    int tps = isUac2_ ? 8000 : 1000;
    return (sampleRate_ + tps - 1) / tps;  // ceil(sampleRate / transfers_per_sec)
}

int UsbAudioDriver::packetSizeBytes() const {
    return packetSizeFrames() * bytesPerFrame_;
}

const char* UsbAudioDriver::getSupportedRates() {
    return supportedRates_.c_str();
}

// 鈹€鈹€ static thread wrappers 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

void* UsbAudioDriver::streamThreadEntry(void* arg) {
    auto* self = static_cast<UsbAudioDriver*>(arg);
    // 銆怴3.2.7銆慡CHED_FIFO 瀹炴椂璋冨害锛圓NDROID URGENT_AUDIO 鍚岀骇锛夛紝setpriority 涓嶄繚璇佸疄鏃讹拷?
    sched_param sp = { .sched_priority = 2 };
    pthread_setschedparam(pthread_self(), SCHED_FIFO, &sp);
    self->streamLoop();
    return nullptr;
}

void UsbAudioDriver::getStats(UsbDacStats& out) const {
    out.urbSubmitted = urbSubmitted_.load();
    out.urbCompleted = urbCompleted_.load();
    out.urbErrors    = urbErrors_.load();
    out.totalSamplesOut = totalSamplesOut_.load();
    out.ringReadPos  = readPos_.load();
    out.ringWritePos = writePos_.load();
    int wp = out.ringWritePos;
    int rp = out.ringReadPos;
    out.ringAvailFrames = (wp - rp + kRingFrames) % kRingFrames;
    out.bufferWatermarkMs = bufferWatermarkMs_.load();
    out.targetWatermarkMs = 200;
    int wm = out.bufferWatermarkMs;
    if (wm <= 0) out.healthState = "idle";
    else if (wm >= 160 && wm <= 240) out.healthState = "stable";
    else if (wm >= 80) out.healthState = "fluctuating";
    else out.healthState = "critical";
}

void* UsbAudioDriver::feedbackThreadEntry(void* arg) {
    auto* self = static_cast<UsbAudioDriver*>(arg);
    self->feedbackLoop();
    return nullptr;
}