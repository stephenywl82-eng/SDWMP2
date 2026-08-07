package com.sdw.music.player

/**
 * Per-DAC quirk profile for USB exclusive mode.
 *
 * Every DAC has different firmware quirks. Instead of scattering PID checks across
 * MusicService and native code, define known devices here with their capabilities
 * and let the playback pipeline read from a single source of truth.
 *
 * Native code (usb_audio_driver.cpp) still has its own low-level checks for things
 * like SET_CUR skipping, but these profiles tell the Kotlin layer what to expect.
 *
 * Routing (3 paths, ordered by priority):
 *   Path 1 — USB Host Exclusive (Bit-Perfect): known-good DACs like TTGK 33C0
 *   Path 2 — Oboe System Route: plug in any USB DAC, Oboe opens without setDeviceId,
 *            Android auto-routes to USB_HEADSET via kernel USB driver (like Resonāda)
 *   Path 3 — ExoPlayer (SRC fallback): no DAC, Bluetooth, or broken 44.1k family
 */
data class DacProfile(
    val vid: Int,
    val pid: Int,
    val name: String,

    /** True = use Oboe system-route instead of hand-rolled USB Host Exclusive.
     *  This is the safe default for unknown DACs — let the kernel USB driver
     *  handle all the low-level quirks (SET_CUR, clock negotiation, format auto-detect).
     *  Set false only for DACs we've thoroughly verified with host-mode Bit-Perfect. */
    val useSystemRoute: Boolean = true,

    /** True if the DAC physically cannot generate 44.1 kHz family (44.1/88.2/176.4/352.8).
     *  Songs with these rates will be routed to ExoPlayer for SRC. */
    val lacks44k1Clock: Boolean = false,

    /** True if SET_CUR control transfers will break this DAC (e.g. EPIPE / dead endpoint).
     *  Native open()/start() already skips SET_CUR for devices where this matters;
     *  this flag is informational for the Kotlin layer. */
    val skipSetCur: Boolean = false,

    /** Wire format bits for USB ISO OUT. Use 32 for S32_LE sub-slot DACs
     *  (mps%8==0), 24 for S24_3LE (mps%6==0), default 16 otherwise.
     *  Only used when useSystemRoute=false. */
    val wireBits: Int = 16,

    /** True = try Android 14+ BIT_PERFECT API (setPreferredMixerAttributes) before
     *  falling back to Oboe system-route. Only meaningful when useSystemRoute=true.
     *  DACs with known firmware quirks (broken SET_CUR, clock issues) can benefit
     *  from the kernel USB driver's usb_quirks tolerance via this API. */
    val tryBitPerfectApi: Boolean = false
) {
    companion object {
        // ── Known DACs ─────────────────────────────────────────

        /** TTGK Audio (pid=33C0) — our reference DAC. Full UAC2, all rates, clean alt switch.
         *  Only DAC we trust with hand-rolled USB Host Exclusive for Bit-Perfect. */
        val TTGK_REFERENCE = DacProfile(0x3302, 0x33C0, "TTGK Audio (reference)",
            useSystemRoute = false)

        /** TTGK Note (pid=201D) — gimped UAC2. Only 48k-family, no Clock Source descriptor.
         *  SET_CUR returns success but chip crystal is 24.576 MHz only.
         *  Try BIT_PERFECT API first; fall back to Oboe system-route. */
        val TTGK_NOTE = DacProfile(0x3302, 0x201D, "TTGK Note",
            lacks44k1Clock = true, tryBitPerfectApi = true)

        /** vid=2972 pid=0047 — ALAC-capable DAC with broken Clock Entity.
         *  SET_CUR always returns Broken pipe (errno=32).
         *  Try BIT_PERFECT API first; fall back to Oboe system-route. */
        val VID2972_0047 = DacProfile(0x2972, 0x0047, "Unknown DAC (2972:0047)",
            lacks44k1Clock = true, skipSetCur = true, tryBitPerfectApi = true)

        /** 2D13:A001 "USB HiFi Audio" — S32_LE wire, buggy SET_CUR.
         *  Hardware supports 44.1k but SET_CUR locks clock at 384k.
         *  Try BIT_PERFECT API first; fall back to Oboe system-route. */
        val HIFI_A001 = DacProfile(0x2D13, 0xA001, "USB HiFi Audio (2D13:A001)",
            skipSetCur = true, tryBitPerfectApi = true)

        /** Realtek USB2.0 Audio (0BDA:4BA6) — standard UAC2 with Clock Source descriptor.
         *  SET_CUR works, both 44.1k and 48k families.
         *  Try BIT_PERFECT API first; fall back to Oboe system-route. */
        val REALTEK_4BA6 = DacProfile(0x0BDA, 0x4BA6, "Realtek USB2.0 Audio (0BDA:4BA6)",
            tryBitPerfectApi = true)

        // ── Lookup ─────────────────────────────────────────────

        private val byKey: Map<Pair<Int, Int>, DacProfile> = listOf(
            TTGK_REFERENCE, TTGK_NOTE, VID2972_0047, HIFI_A001, REALTEK_4BA6
        ).associateBy { it.vid to it.pid }

        /** Look up a known profile, or return a generic safe default (system-route). */
        fun find(vid: Int, pid: Int): DacProfile {
            return byKey[vid to pid] ?: DacProfile(vid, pid, "USB DAC (${vid.toString(16)}:${pid.toString(16)})")
        }

        /** True if this VID/PID is known to lack 44.1 kHz clock hardware. */
        fun lacks44k1(vid: Int, pid: Int): Boolean = find(vid, pid).lacks44k1Clock

        /** True if this VID/PID should skip SET_CUR in native layer. */
        fun shouldSkipSetCur(vid: Int, pid: Int): Boolean = find(vid, pid).skipSetCur

        /** Correct wire-format bits for this DAC. */
        fun wireBitsFor(vid: Int, pid: Int): Int = find(vid, pid).wireBits

        /** True if this DAC should use Oboe system-route instead of USB Host Exclusive.
         *  For known-good DACs (TTGK 33C0) we use hand-rolled Bit-Perfect path. */
        fun shouldUseSystemRoute(vid: Int, pid: Int): Boolean = find(vid, pid).useSystemRoute
    }
}
