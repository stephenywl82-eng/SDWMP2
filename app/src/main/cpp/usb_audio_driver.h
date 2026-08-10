#pragma once

#include <atomic>
#include <cstdint>
#include <string>
#include <thread>
#include <vector>
#include <linux/usbdevice_fs.h>
#include <linux/usb/ch9.h>

/**
 * Native USB Audio Class driver for direct DAC streaming.
 *
 * Uses Linux USB device file ioctls (USBDEVFS_SUBMITURB) over the fd
 * obtained from Android's UsbDeviceConnection.getFileDescriptor().
 *
 * Flow:
 *   Java UsbDacManager 锟?JNI (usb_audio_jni.cpp) 锟?UsbAudioDriver
 *
 * Ring buffer: lock-free SPSC, 32768 frames (~740ms buffer at 44.1kHz stereo float).
 * Streaming: dedicated thread with isochronous URB submission (64 packets per URB).
 * Sample rate: synchronous mode 锟?clock derived from USB SOF, no control transfer needed.
 *
 * Logging: All operations are logged to both Android logcat and an in-memory ring buffer
 * accessible via getNativeDebugLog() / JNI nativeGetDebugLog() for in-app display.
 * Log format: HH:MM:SS.mmm TAG message (Salt Player style).
 */

/**
 * An audio-streaming alternate setting candidate discovered from USB descriptor.
 */
struct DacAltCandidate {
    int ifaceNum = 0;    // bInterfaceNumber (for SETINTERFACE)
    int alt = 0;         // bAlternateSetting
    int epAddr = 0;      // endpoint address (with direction bit)
    int maxPkt = 0;      // wMaxPacketSize
    int bInterval = 1;   // bInterval (1 = HS 125us, 4 = FS 1ms)
    int channels = 2;    // number of channels
    int subslot = 2;     // bytes-per-sample: 2=S16, 3=S24_3LE, 4=S32_LE
    int res = 16;        // actual bit resolution
    int clockId = -1;    // associated clock source entity ID
    int sampleRate = 0;  // from format descriptor tSamFreq (0=multi-rate)
};

/**
 * A clock-source frequency sub-range read from GET_RANGE control transfer.
 */
struct ClockRange {
    int min = 0;         // minimum sample rate
    int max = 0;         // maximum sample rate
    int res = 0;         // 0 = fully continuous within [min,max]
    int clockId = -1;    // clock entity ID
};

/**
 * Streaming transmission statistics.
 */
struct UsbDacStats {
    uint64_t urbSubmitted   = 0;
    uint64_t urbCompleted   = 0;
    uint64_t urbErrors      = 0;
    uint64_t totalSamplesOut = 0;
    int32_t  ringReadPos    = 0;
    int32_t  ringWritePos   = 0;
    int32_t  ringAvailFrames = 0;
    int32_t  bufferWatermarkMs = 0;
    int32_t  targetWatermarkMs  = 200;
    const char* healthState = "idle";
};

class UsbAudioDriver {
public:
    static constexpr int kRingFrames    = 32768;       // ~740ms buffer at 44.1kHz (was 131072/3s, cut gap between songs)
    static constexpr int kMaxUrbCount   = 16;          // in-flight URBs
    static constexpr int kPacketsPerUrb = 64;          // Salt-style: 64 ISO packets per URB
    static constexpr int kMaxPacketSize = 576;         // max bytes per ISO packet (384 mps + headroom)
    static constexpr int kMaxUrbBuffer  = kPacketsPerUrb * kMaxPacketSize;
    static constexpr int kFeedbackIntervalUs = 200000;

    UsbAudioDriver();
    ~UsbAudioDriver();

    // 鈹€鈹€ Lifecycle 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€
    bool open(int fd, int epAddress, int maxPacketSize, int interval,
              bool isUac2, int vid, int pid, int ifaceNum);
    bool start(int sampleRate, int channels, int bitsPerSample);
    void stop();
    void stopThreadOnly();
    void resetRingBuffer();  // 銆怴3.2.7銆戞殏鍋滃悗鎭㈠鏃舵竻绌?ring buffer锛岄伩鍏嶆棫鏁版嵁韪╄笍瀵艰嚧鍣煶
    int pushPcm(const float* data, int frameCount);
    int getRingFillFrames();  // 銆怴3.2.7銆慐OS 鎺掔┖鐢?
    int clockRate_ = 0;       // 銆怴3.2.7銆慏AC 鏃堕挓褰撳墠 SET_CUR 閫熺巼
    int currentAlt_ = 0;      // 銆怴3.2.7銆戝綋鍓?alt 璁剧疆锛?=16bit 2=24bit 3=32bit锛?

    // 鈹€鈹€ USB control 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€
    bool claimInterface(int desiredAlt);
    void releaseInterface();
    bool setInterfaceAlt(int alt);
    int setSampleRate(int rate);  // public: safe only when stream inactive
    int trySetSampleRate(int rate); // internal: unconditionally sends SET_CUR
    const char* getSupportedRates();

    // 鈹€鈹€ State 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€
    bool isClaimed() const      { return claimed_.load(std::memory_order_acquire); }
    bool isStreaming() const    { return streaming_.load(std::memory_order_acquire); }
    int  getSampleRate() const  { return sampleRate_; }
    int  getBitsPerSample() const { return bitsPerSample_; }
    int  getUnderrunCount() const { return underrunCount_.load(std::memory_order_acquire); }
    void setVolume(float v)      { volume_ = v; }
    float getVolume() const      { return volume_; }
    const char* getDacName() const   { return dacName_.c_str(); }
    const char* getDetailedInfo() const { return detailedInfo_.c_str(); }
    void getStats(UsbDacStats& out) const;

    // 鈹€鈹€ Debug log ring buffer (Salt-style in-app log) 鈹€鈹€鈹€鈹€鈹€
    const char* getNativeDebugLog() const { return nativeLogBuf_; }

    // 鈹€鈹€ Conversion helpers 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€
    int framesToBytes(int frames) const { return frames * bytesPerFrame_; }
    int packetSizeFrames() const;
    int packetSizeBytes() const;

private:
    void streamLoop();
    void feedbackLoop();
    static void* streamThreadEntry(void* arg);
    static void* feedbackThreadEntry(void* arg);

    int  findClockSourceId();
    int  findFeedbackEndpoint();
    void parseSupportedRates();
    void parseAltCandidates();       // scan all AS alt settings 鈫?altCandidates_
    void parseClockRanges();         // read GET_RANGE clock sub-ranges 鈫?clockRanges_
    int  selectAltForRate(int targetRate, int targetBits, int targetChannels); // pick best alt from candidates
    int readConfigDescriptor(uint8_t interfaceNum, uint8_t* buf, size_t maxLen);
    int controlTransfer(uint8_t bmRequestType, uint8_t bRequest,
                        uint16_t wValue, uint16_t wIndex,
                        void* data, uint16_t wLength, unsigned timeoutMs = 100);

    struct UrbSlot {
        usbdevfs_urb* urb = nullptr;
        uint8_t buffer[kMaxUrbBuffer];
    };
    UrbSlot urbSlots_[kMaxUrbCount];
    bool submitUrb(int slot, int numBytes);
    bool submitUrbRaw(int slot);
    void reapCompletedUrbs();

    // 鈹€鈹€ Native log ring (written by C++, read by Kotlin via JNI) 鈹€鈹€
    static constexpr int kNativeLogSize = 32768;
    mutable char nativeLogBuf_[kNativeLogSize] = {};
    mutable int nativeLogWrite_ = 0;
    void nativeLog(const char* tag, const char* fmt, ...)
        __attribute__((format(printf, 3, 4)));

    // 鈹€鈹€ State 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€
    int fd_ = -1;
    int epAddress_ = 0;
    int maxPacketSize_ = 0;
    int interval_ = 1;
    bool isUac2_ = false;
    int vid_ = 0;
    int pid_ = 0;
    int ifaceNum_ = 0;

    int sampleRate_  = 48000;
    int channels_    = 2;
    int bitsPerSample_ = 24;
    int bytesPerFrame_ = 6;

    std::atomic<bool> streaming_{false};
    std::atomic<bool> claimed_{false};
    std::atomic<int>  underrunCount_{0};
    mutable std::atomic<uint64_t> urbSubmitted_{0};
    mutable std::atomic<uint64_t> urbCompleted_{0};
    mutable std::atomic<uint64_t> urbErrors_{0};
    mutable std::atomic<uint64_t> totalSamplesOut_{0};
    mutable std::atomic<int32_t>  bufferWatermarkMs_{0};

    float* ringBuffer_ = nullptr;
    float volume_ = 1.0f;
    std::atomic<int> writePos_{0};
    std::atomic<int> readPos_{0};

    std::thread streamThread_;
    std::thread feedbackThread_;

    std::string dacName_;
    std::string detailedInfo_;
    std::string supportedRates_;

    // 鈹€鈹€ Descriptor-driven DAC adaptation 鈹€鈹€
    std::vector<DacAltCandidate> altCandidates_;
    std::vector<ClockRange> clockRanges_;
    int  acIface_ = 0;     // AudioControl interface number (for clock control transfers)
};
