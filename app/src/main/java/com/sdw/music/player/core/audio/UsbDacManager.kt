package com.sdw.music.player.core.audio

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.sdw.music.player.Song
import java.util.concurrent.ConcurrentHashMap

data class UsbDacDevice(val name: String, val vid: Int, val pid: Int)
data class UsbEndpointInfo(val address: Int, val maxPacketSize: Int, val interval: Int, val isUac2: Boolean)

object UsbDacManager {
    private const val TAG = "UsbDacManager"
    private const val USB_CLASS_AUDIO = 1
    private const val SUBCLASS_AUDIOCONTROL = 1
    private const val SUBCLASS_AUDIOSTREAMING = 2
    private const val PROTOCOL_UAC2 = 0x20
    private const val ACTION_USB_PERMISSION = "com.sdw.music.player.USB_PERMISSION"
    private const val FIND_DACS_COOLDOWN_MS = 5000L

    @Volatile private var isNativeLoaded = false
    @Volatile private var contextRef: Context? = null
    @Volatile private var permissionReceiver: BroadcastReceiver? = null
    @Volatile private var streaming = false
    @Volatile private var initAttempted = false
    @Volatile private var cachedUsbDevice: UsbDevice? = null
    private var lastFindDacsTime = 0L

    private data class PendingClaim(
        val device: UsbDevice,
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val ifaceNum: Int
    )
    @Volatile private var pendingClaim: PendingClaim? = null

    // ============================================================
    // Public API
    // ============================================================

    fun init(context: Context) {
        if (isNativeLoaded || initAttempted) return
        initAttempted = true
        val appCtx = context.applicationContext
        contextRef = appCtx

        DebugLog.add(TAG, "init: loading native lib oboe_bridge...")
        try {
            System.loadLibrary("oboe_bridge")
            isNativeLoaded = true
            DebugLog.add(TAG, "init: oboe_bridge loaded OK")
        } catch (e: UnsatisfiedLinkError) {
            isNativeLoaded = false
            DebugLog.add(TAG, "init: oboe_bridge FAIL ${e.message}")
        }

        registerPermissionReceiver(appCtx)
        DebugLog.add(TAG, "init done, nativeLoaded=$isNativeLoaded")
    }

    fun findDacs(): List<UsbDacDevice> {
        val now = System.currentTimeMillis()
        if (cachedUsbDevice != null && (now - lastFindDacsTime) < FIND_DACS_COOLDOWN_MS) {
            DebugLog.add(TAG, "findDacs: cached → ${cachedUsbDevice!!.productName}")
            return listOf(UsbDacDevice(
                name = cachedUsbDevice!!.productName ?: "USB DAC",
                vid = cachedUsbDevice!!.vendorId,
                pid = cachedUsbDevice!!.productId
            ))
        }

        val ctx = contextRef
        if (ctx == null) { DebugLog.add(TAG, "findDacs: contextRef=null"); return emptyList() }
        val usbMgr = ctx.getSystemService(Context.USB_SERVICE) as? UsbManager
        if (usbMgr == null) { DebugLog.add(TAG, "findDacs: usbManager=null"); return emptyList() }

        val dacs = mutableListOf<UsbDacDevice>()
        DebugLog.add(TAG, "findDacs: scanning ${usbMgr.deviceList.size} USB devices...")
        for ((_, dev) in usbMgr.deviceList) {
            val audio = isAudioDevice(dev)
            DebugLog.add(TAG, "findDacs: vid=${dev.vendorId.toString(16)} pid=${dev.productId.toString(16)} name=${dev.productName} ifaceCount=${dev.interfaceCount} audio=$audio")
            if (audio) {
                cachedUsbDevice = dev
                dacs.add(UsbDacDevice(
                    name = dev.productName ?: "USB DAC (${dev.vendorId}:${dev.productId})",
                    vid = dev.vendorId, pid = dev.productId
                ))
            }
        }
        lastFindDacsTime = now
        DebugLog.add(TAG, "findDacs: result=${dacs.size} DAC(s) found")
        return dacs
    }

    fun getDacDevice(): UsbDevice? = cachedUsbDevice

    fun requestPermission(device: UsbDevice, context: Context) {
        val ctx = context.applicationContext
        val usbMgr = ctx.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
        if (usbMgr.hasPermission(device)) {
            DebugLog.add(TAG, "perm: already have for ${device.productName}")
            return
        }
        val pi = PendingIntent.getBroadcast(ctx, 0, Intent(ACTION_USB_PERMISSION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        usbMgr.requestPermission(device, pi)
        DebugLog.add(TAG, "perm: requesting for ${device.productName}")
    }

    fun claimAndStart(
        device: UsbDevice, sampleRate: Int, channels: Int, bitsPerSample: Int, ifaceNum: Int = -1
    ): Boolean {
        val ctx = contextRef
        if (ctx == null) { DebugLog.add(TAG, "claim: contextRef=null"); return false }
        val usbMgr = ctx.getSystemService(Context.USB_SERVICE) as? UsbManager
        if (usbMgr == null) { DebugLog.add(TAG, "claim: usbManager=null"); return false }

        if (!usbMgr.hasPermission(device)) {
            pendingClaim = PendingClaim(device, sampleRate, channels, bitsPerSample, ifaceNum)
            DebugLog.add(TAG, "claim: no permission, queued")
            requestPermission(device, ctx)
            return false
        }

        if (!isNativeLoaded) { DebugLog.add(TAG, "claim: native not loaded"); return false }

        val conn = usbMgr.openDevice(device)
        if (conn == null) { DebugLog.add(TAG, "claim: openDevice FAIL"); return false }
        val fd = conn.fileDescriptor

        // Dump all interfaces for debug
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            DebugLog.add(TAG, "claim: iface[$i] id=${iface.id} class=${iface.interfaceClass} subclass=${iface.interfaceSubclass} proto=${iface.interfaceProtocol} eps=${iface.endpointCount}")
        }

        // Find and claim audio streaming interfaces
        var targetIfaceNum = ifaceNum
        var claimed = false
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == USB_CLASS_AUDIO && iface.interfaceSubclass == SUBCLASS_AUDIOSTREAMING) {
                // Skip alt=0 (idle, no endpoints)
                val hasEp = iface.endpointCount > 0
                DebugLog.add(TAG, "claim: iface[$i] id=${iface.id} endpointCount=${iface.endpointCount} hasEp=$hasEp")
                if (!hasEp && ifaceNum < 0) continue
                if (targetIfaceNum < 0) targetIfaceNum = iface.id
                conn.claimInterface(iface, true)
                DebugLog.add(TAG, "claim: claimed iface id=${iface.id}")
                claimed = true
            }
        }

        if (!claimed) { DebugLog.add(TAG, "claim: no audio streaming iface"); conn.close(); return false }

        val epInfo = getEndpointInfo(device, targetIfaceNum)
        if (epInfo == null) { DebugLog.add(TAG, "claim: no ISO OUT endpoint"); conn.close(); return false }

        val result = nativeClaim(
            device.vendorId, device.productId, fd,
            epInfo.address, epInfo.maxPacketSize, epInfo.interval,
            epInfo.isUac2, targetIfaceNum
        )
        DebugLog.add(TAG, "claim: nativeClaim vid=${device.vendorId.toString(16)} pid=${device.productId.toString(16)} ep=0x${epInfo.address.toString(16)} mps=${epInfo.maxPacketSize} uac2=${epInfo.isUac2} iface=$targetIfaceNum → $result")
        if (!result) conn.close()
        return result
    }

    fun startStreaming(sampleRate: Int, channels: Int, bitsPerSample: Int): Boolean {
        if (!isNativeLoaded) { DebugLog.add(TAG, "start: native not loaded"); return false }
        if (!nativeIsClaimed()) { DebugLog.add(TAG, "start: not claimed"); return false }
        val result = nativeUsbStart(sampleRate, channels, bitsPerSample)
        streaming = result
        DebugLog.add(TAG, "start: sr=$sampleRate ch=$channels bits=$bitsPerSample → $result")
        return result
    }

    fun pushPcm(data: FloatArray, frameCount: Int): Int {
        if (!isNativeLoaded || !streaming) return -1
        return nativePushPcm(data, frameCount)
    }

    fun stopAndRelease() {
        streaming = false; pendingClaim = null
        if (isNativeLoaded) { nativeStop(); nativeRelease() }
        DebugLog.add(TAG, "stopAndRelease")
    }

    /** Pause DAC stream thread without releasing USB claim or ring buffer */
    fun pauseStream() {
        streaming = false
        if (isNativeLoaded) nativeStopThreadOnly()  // stop streamLoop, keep USB claim
        DebugLog.add(TAG, "pauseStream (threads stopped, USB claim kept)")
    }

    fun isStreaming(): Boolean = streaming && isNativeLoaded && nativeIsClaimed()
    fun getUnderrunCount(): Int = if (isNativeLoaded) nativeGetUnderrunCount() else 0
    fun getDacName(): String? = if (isNativeLoaded) nativeGetDacName() else null
    fun getDetailedDacInfo(): String? = if (isNativeLoaded) nativeGetDetailedInfo() else null
    fun getNativeDebugLog(): String? = if (isNativeLoaded) nativeGetDebugLog() else null

    fun setVolume(v: Float) {
        currentVolume = v.coerceIn(0f, 1f)
        if (isNativeLoaded) nativeSetVolume(currentVolume)
    }
    fun getVolume(): Float = currentVolume
    @Volatile private var currentVolume = 0.7f

    fun getSafeDacInfo(): String {
        if (!isNativeLoaded) return "Native driver not loaded"
        return nativeGetDetailedInfo()?.lines()?.filter {
            !it.contains("/dev/") && !it.contains("fd=")
        }?.joinToString("\n") ?: "No DAC connected"
    }

    fun getNativeLog(): String {
        return if (isNativeLoaded) nativeGetDebugLog() ?: "" else "Native not loaded"
    }

    /**
     * Read source file sample rate via MediaExtractor (no decode).
     */
    fun getSourceSampleRate(song: Song): Int {
        val path = song.filePath.ifEmpty { song.path }
        if (path.isBlank()) return 48000
        val ex = android.media.MediaExtractor()
        return try {
            ex.setDataSource(path)
            for (i in 0 until ex.trackCount) {
                val fmt = ex.getTrackFormat(i)
                if (fmt.getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    return fmt.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
                }
            }
            48000
        } finally { ex.release() }
    }

    // ============================================================
    // Private helpers
    // ============================================================

    private fun isAudioDevice(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == USB_CLASS_AUDIO) return true
            if (iface.interfaceClass == 0x10 &&
                (iface.interfaceSubclass == SUBCLASS_AUDIOCONTROL ||
                 iface.interfaceSubclass == SUBCLASS_AUDIOSTREAMING)) return true
        }
        return false
    }

    private fun getEndpointInfo(device: UsbDevice, targetIfaceId: Int): UsbEndpointInfo? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.id != targetIfaceId) continue
            if (iface.interfaceClass == USB_CLASS_AUDIO && iface.interfaceSubclass == SUBCLASS_AUDIOSTREAMING) {
                val uac2 = iface.interfaceProtocol == PROTOCOL_UAC2
                for (j in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(j)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC && ep.direction == UsbConstants.USB_DIR_OUT) {
                        return UsbEndpointInfo(ep.address, ep.maxPacketSize, ep.interval, uac2 || ep.maxPacketSize > 1024 || ep.interval > 1)
                    }
                }
            }
        }
        return null
    }

    private fun registerPermissionReceiver(context: Context) {
        if (permissionReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != ACTION_USB_PERMISSION) return
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) as? UsbDevice
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                DebugLog.add(TAG, "perm result: granted=$granted device=${device?.productName}")
                if (granted && device != null) {
                    val claim = pendingClaim
                    if (claim != null && claim.device.deviceId == device.deviceId) {
                        pendingClaim = null
                        claimAndStart(device, claim.sampleRate, claim.channels, claim.bitsPerSample, claim.ifaceNum)
                    }
                } else { pendingClaim = null }
            }
        }
        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("UnsafeRegisteredReceiver") context.registerReceiver(receiver, filter)
        permissionReceiver = receiver
    }

    // ============================================================
    // JNI externals
    // ============================================================

    private external fun nativeUsbAvailable(): Boolean
    private external fun nativeClaim(vid: Int, pid: Int, fd: Int, address: Int, maxPacketSize: Int, epInterval: Int, isUac2: Boolean, ifaceNum: Int): Boolean
    private external fun nativeUsbStart(sampleRate: Int, channels: Int, bitsPerSample: Int): Boolean
    private external fun nativePushPcm(data: FloatArray, frameCount: Int): Int
    private external fun nativeStop()
    private external fun nativeStopThreadOnly()
    private external fun nativeRelease()
    private external fun nativeIsClaimed(): Boolean
    private external fun nativeGetUnderrunCount(): Int
    private external fun nativeGetDacName(): String?
    private external fun nativeGetDetailedInfo(): String?
    private external fun nativeGetDebugLog(): String?
    private external fun nativeSetVolume(volume: Float)
}
