#include "usb_audio_driver.h"
#include <android/log.h>
#include <cmath>
#include <cstring>
#include <algorithm>
#include <unistd.h>
#include <sys/ioctl.h>
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

// ── nativeLog ring buffer implementation ───────────────────────────────

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

// ── open ─────────────────────────────────────────────────────────────────

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

    // Claim the audio streaming interface
    if (!claimInterface(1)) {
        LOGE("Failed to claim interface");
        return false;
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

// ── start ────────────────────────────────────────────────────────────────

bool UsbAudioDriver::start(int sampleRate, int channels, int bitsPerSample) {
    if (fd_ < 0) {
        LOGE("start: device not open");
        return false;
    }

    // If already streaming, stop old thread cleanly and restart
    if (streaming_.load(std::memory_order_acquire)) {
        LOGW("start: restarting stream (old sr=%d ch=%d bits=%d) �?(sr=%d ch=%d bits=%d)",
             sampleRate_, channels_, bitsPerSample_, sampleRate, channels, bitsPerSample);
        streaming_.store(false, std::memory_order_release);
        if (streamThread_.joinable()) {
            streamThread_.join();
        }
    }

    sampleRate_ = sampleRate;
    channels_ = channels;
    bitsPerSample_ = bitsPerSample;
    bytesPerFrame_ = channels_ * (bitsPerSample_ / 8);

    LOGI("start: sr=%d ch=%d bits=%d bytesPerFrame=%d",
         sampleRate_, channels_, bitsPerSample_, bytesPerFrame_);

    // Synchronous ISO mode: sample rate is determined by data rate, not control transfer.
    // The DAC derives its clock from USB SOF (1kHz). No setSampleRate needed.
    // (Salt Player also skips this for sync-mode DACs.)
    LOGI("Sync mode: sample rate implicit via data rate (no control transfer)");

    // Reset ring buffer positions for clean restart
    writePos_.store(0, std::memory_order_release);
    readPos_.store(0, std::memory_order_release);
    underrunCount_.store(0, std::memory_order_release);

    // Start stream thread
    streaming_.store(true, std::memory_order_release);
    streamThread_ = std::thread(streamThreadEntry, this);

    LOGI("start: stream thread launched");
    return true;
}

// ── stop ─────────────────────────────────────────────────────────────────

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
        streamThread_.join();
    }
    if (feedbackThread_.joinable()) {
        feedbackThread_.join();
    }
    LOGI("stopThreadOnly: threads stopped, USB claim kept");
}

// ── pushPcm ──────────────────────────────────────────────────────────────

int UsbAudioDriver::pushPcm(const float* data, int frameCount) {
    if (!ringBuffer_ || frameCount <= 0) return -1;

    int wp = writePos_.load(std::memory_order_acquire);
    int rp = readPos_.load(std::memory_order_acquire);

    // Available space (in frames)
    int avail = kRingFrames - ((wp - rp + kRingFrames) % kRingFrames);

    if (avail < frameCount) {
        underrunCount_.fetch_add(1, std::memory_order_release);
        // Write what we can
        frameCount = avail > 0 ? avail : 0;
    }

    if (frameCount <= 0) return 0;

    int samples = frameCount * 2; // stereo
    const int mask = kRingFrames * 2 - 1; // power-of-2 assumption

    for (int i = 0; i < samples; ++i) {
        int idx = ((wp * 2) + i) & mask;
        ringBuffer_[idx] = data[i];
    }

    writePos_.store((wp + frameCount) % kRingFrames, std::memory_order_release);
    return frameCount;
}

// claimInterface �?set the exact alternate setting chosen by Kotlin

bool UsbAudioDriver::claimInterface(int desiredAlt) {
    if (fd_ < 0) return false;

    // This mirrors the Java-side conn.claimInterface() �?the fd already has
    // the interface claimed. We just set our claimed flag.
    claimed_.store(true, std::memory_order_release);

    // Set the exact alt chosen by getEndpointInfo (Salt Player approach: no looping)
    if (isUac2_ && desiredAlt > 0) {
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

// ── setSampleRate ────────────────────────────────────────────────────────

int UsbAudioDriver::setSampleRate(int rate) {
    if (fd_ < 0) return 0;

    if (isUac2_) {
        // UAC 2.0: 4-byte LE SET_CUR on Clock Source (entity ID from findClockSourceId)
        int clockId = findClockSourceId();
        if (clockId < 0) {
            // Android USB Host fd may not support USBDEVFS_CONTROL for descriptor reads.
            // TTGK Audio DAC uses clock source entity 9 (confirmed via Salt Player).
            clockId = 9;
            LOGW("No UAC 2.0 clock source found via descriptor; fallback to clockId=9");
        }

        uint8_t data[4];
        data[0] = (rate >> 0)  & 0xFF;
        data[1] = (rate >> 8)  & 0xFF;
        data[2] = (rate >> 16) & 0xFF;
        data[3] = (rate >> 24) & 0xFF;

        // UAC 2.0: SET_CUR on Clock Source Sampling Frequency Control
        // wValue = (CS << 8) | CN = (SAMPLING_FREQ << 8) | 0 = 0x0100
        int ret = controlTransfer(0x21, 0x01,
                                  SAMPLING_FREQ_CONTROL,  // wValue: CS=1(sampling freq), CN=0
                                  clockId,                           // wIndex = clockSourceId
                                  data, 4, 200);
        if (ret >= 0) {
            LOGI("UAC2 setSampleRate: %d Hz -> OK", rate);
            sampleRate_ = rate;
            return rate;
        } else {
            LOGW("UAC2 setSampleRate(%d) failed: errno=%d", rate, errno);
            return 0;
        }
    } else {
        // UAC 1.0: SET_CUR on sampling frequency control endpoint
        // 3-byte LE value
        uint8_t data[3];
        data[0] = (rate >> 0)  & 0xFF;
        data[1] = (rate >> 8)  & 0xFF;
        data[2] = (rate >> 16) & 0xFF;

        // UAC 1.0: bmRequestType=0x21(class,interface,host2dev), bRequest=SET_CUR(0x01)
        // wValue = (CS << 8) | endpoint_addr
        int ret = controlTransfer(0x21, 0x01,
                                  SAMPLING_FREQ_CONTROL, // wValue
                                  epAddress_,            // wIndex
                                  data, 3, 200);
        if (ret >= 0) {
            LOGI("UAC1 setSampleRate: %d Hz -> OK", rate);
            sampleRate_ = rate;
            return rate;
        } else {
            LOGW("UAC1 setSampleRate(%d) failed: errno=%d", rate, errno);
            // If the DAC doesn't support the rate, try common fallbacks
            int fallbacks[] = {48000, 44100, 96000, 88200, 192000};
            for (int fb : fallbacks) {
                if (fb == rate) continue;
                data[0] = (fb >> 0)  & 0xFF;
                data[1] = (fb >> 8)  & 0xFF;
                data[2] = (fb >> 16) & 0xFF;
                ret = controlTransfer(0x21, 0x01, SAMPLING_FREQ_CONTROL, epAddress_, data, 3, 200);
                if (ret >= 0) {
                    LOGI("UAC1 setSampleRate fallback: %d Hz", fb);
                    sampleRate_ = fb;
                    return fb;
                }
            }
            return 0;
        }
    }
}

// ── streamLoop ───────────────────────────────────────────────────────────




void UsbAudioDriver::streamLoop() {
    const int pktMaxFrames = packetSizeFrames();
    const int pktMaxBytes  = pktMaxFrames * bytesPerFrame_;
    const int urbBytes     = pktMaxBytes * kPacketsPerUrb;
    const double samplesPerMicroframe = sampleRate_ / (isUac2_ ? 8000.0 : 1000.0);

    LOGI("streamLoop: maxFrames=%d urbBytes=%d pkts/urb=%d hs=%d rate/mf=%.4f",
         pktMaxFrames, urbBytes, kPacketsPerUrb, isUac2_ ? 1 : 0, samplesPerMicroframe);

    // Pre-queue 4 URBs with silence (max-size packets for simplicity)
    for (int s = 0; s < 4; s++) {
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
    int slot = 4;

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

// ── findClockSourceId ────────────────────────────────────────────────────

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

// ── findFeedbackEndpoint ─────────────────────────────────────────────────

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

// ── parseSupportedRates ─────────────────────────────────────────────────

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

// ── readConfigDescriptor ─────────────────────────────────────────────────

uint8_t UsbAudioDriver::readConfigDescriptor(uint8_t interfaceNum, uint8_t* buf, size_t maxLen) {
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

    return static_cast<uint8_t>(ret);
}

// ── controlTransfer ──────────────────────────────────────────────────────

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

// ── submitUrb ────────────────────────────────────────────────────────────

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

// ── submitUrbRaw (multi-packet URB already set up) ───────────────────────

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

// ── packet size helpers ──────────────────────────────────────────────────

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

// ── static thread wrappers ───────────────────────────────────────────────

void* UsbAudioDriver::streamThreadEntry(void* arg) {
    auto* self = static_cast<UsbAudioDriver*>(arg);
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
