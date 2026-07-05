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
import java.util.concurrent.ConcurrentHashMap

data class UsbDacDevice(val name: String, val vid: Int, val pid: Int)
data class UsbEndpointInfo(val address: Int, val maxPacketSize: Int, val interval: Int, val isUac2: Boolean)

/**
 * Singleton manager for USB DAC exclusive-mode control.
 *
 * Finds USB Audio Class devices via Android UsbManager, requests permission,
 * claims the audio streaming interface, and bridges to native UsbAudioDriver via JNI.
 *
 * Native library: libusb_audio_driver.so (JNI)
 *
 * UAC version detection:
 * - USB_CLASS_AUDIO = 1
 * - Interface subclass: 1 = AudioControl, 2 = AudioStreaming, 3 = MIDIStreaming
 * - bInterfaceProtocol == 0x20 → UAC 2.0
 * - Fallback heuristic: maxPacketSize > 1024 or epInterval > 1 → UAC 2.0
 */
object UsbDacManager {

    private const val TAG = "UsbDacManager"
    private const val USB_CLASS_AUDIO = 1
    private const val SUBCLASS_AUDIOCONTROL = 1
    private const val SUBCLASS_AUDIOSTREAMING = 2
    private const val PROTOCOL_UAC2 = 0x20

    private const val ACTION_USB_PERMISSION = "com.sdw.music.player.USB_PERMISSION"

    @Volatile private var isNativeLoaded = false
    @Volatile private var contextRef: Context? = null
    @Volatile private var permissionReceiver: BroadcastReceiver? = null
    @Volatile private var streaming = false

    // Pending permission request state
    private data class PendingClaim(
        val device: UsbDevice,
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int
    )
    @Volatile private var pendingClaim: PendingClaim? = null

    // ============================================================
    // Public API
    // ============================================================

    /**
     * Initialize the manager. Must be called once from Application or Service.
     * Registers the USB permission broadcast receiver.
     */
    fun init(context: Context) {
        if (isNativeLoaded) return // already initialized
        val appContext = context.applicationContext
        contextRef = appContext

        // Attempt to load native library
        try {
            System.loadLibrary("usb_audio_driver")
            isNativeLoaded = true
            Log.i(TAG, "Native library usb_audio_driver loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            isNativeLoaded = false
            Log.w(TAG, "Native library usb_audio_driver not available: ${e.message}. " +
                    "USB DAC exclusive mode will not function until the native .so is provided.")
        }

        registerPermissionReceiver(appContext)
        Log.d(TAG, "UsbDacManager initialized (native=${isNativeLoaded})")
    }

    /**
     * Scan for connected USB Audio Class devices.
     * Returns a list of [UsbDacDevice] instances.
     */
    fun findDacs(): List<UsbDacDevice> {
        val ctx = contextRef ?: return emptyList()
        val usbManager = ctx.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return emptyList()

        val dacs = mutableListOf<UsbDacDevice>()
        for ((_, device) in usbManager.deviceList) {
            if (isAudioDevice(device)) {
                dacs.add(UsbDacDevice(
                    name = device.productName ?: "USB DAC (${device.vendorId}:${device.productId})",
                    vid = device.vendorId,
                    pid = device.productId
                ))
            }
        }
        Log.d(TAG, "findDacs: found ${dacs.size} USB DAC(s)")
        return dacs
    }

    /**
     * Request USB permission for the given device.
     * On permission granted, the internal receiver will automatically call claimAndStart.
     */
    fun requestPermission(device: UsbDevice, context: Context) {
        val ctx = context.applicationContext
        val usbManager = ctx.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return

        if (usbManager.hasPermission(device)) {
            Log.d(TAG, "Already have permission for ${device.productName}")
            return
        }

        val permissionIntent = PendingIntent.getBroadcast(
            ctx, 0,
            Intent(ACTION_USB_PERMISSION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        usbManager.requestPermission(device, permissionIntent)
        Log.d(TAG, "Requesting USB permission for ${device.productName}")
    }

    /**
     * Claim the USB device and prepare for streaming.
     * If permission is needed, this will request it and return false;
     * the actual claim will happen in the permission callback.
     *
     * @return true if claimed successfully, false if permission is needed or claim failed
     */
    fun claimAndStart(device: UsbDevice, sampleRate: Int, channels: Int, bitsPerSample: Int): Boolean {
        val ctx = contextRef ?: return false
        val usbManager = ctx.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false

        if (!usbManager.hasPermission(device)) {
            pendingClaim = PendingClaim(device, sampleRate, channels, bitsPerSample)
            requestPermission(device, ctx)
            return false
        }

        if (!isNativeLoaded) {
            Log.e(TAG, "Cannot claim — native library not loaded")
            return false
        }

        val endpointInfo = getEndpointInfo(device)
        if (endpointInfo == null) {
            Log.e(TAG, "No audio streaming endpoint found on ${device.productName}")
            return false
        }

        val conn = usbManager.openDevice(device) ?: return false
        val fd = conn.fileDescriptor
        val nativeFd = fd  // FileDescriptor, JNI receives as jobject

        // Find the audio streaming interface and claim it
        var claimed = false
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == USB_CLASS_AUDIO && iface.interfaceSubclass == SUBCLASS_AUDIOSTREAMING) {
                conn.claimInterface(iface, true)
                Log.d(TAG, "Claimed audio streaming interface ${iface.id}")
                claimed = true
            }
        }

        if (claimed) {
            val result = nativeClaim(
                device.vendorId, device.productId,
                nativeFd,
                endpointInfo.address,
                endpointInfo.maxPacketSize,
                endpointInfo.interval,
                endpointInfo.isUac2
            )
            if (result) {
                Log.i(TAG, "nativeClaim succeeded for ${device.productName}")
                return true
            } else {
                Log.e(TAG, "nativeClaim failed for ${device.productName}")
                conn.close()
            }
        } else {
            Log.e(TAG, "Failed to claim audio streaming interface on ${device.productName}")
            conn.close()
        }

        return false
    }

    /**
     * Start the USB audio stream with the given parameters.
     */
    fun startStreaming(sampleRate: Int, channels: Int, bitsPerSample: Int): Boolean {
        if (!isNativeLoaded) {
            Log.e(TAG, "Cannot start streaming — native library not loaded")
            return false
        }
        if (!nativeIsClaimed()) {
            Log.e(TAG, "Cannot start streaming — no device claimed")
            return false
        }
        val result = nativeUsbStart(sampleRate, channels, bitsPerSample)
        streaming = result
        Log.i(TAG, "startStreaming(sr=$sampleRate, ch=$channels, bits=$bitsPerSample): $result")
        return result
    }

    /**
     * Push a buffer of float PCM samples to the DAC.
     * @param data interleaved float samples normalized to [-1.0, 1.0]
     * @param frameCount number of frames (samples per channel)
     * @return number of frames successfully pushed, or -1 on error
     */
    fun pushPcm(data: FloatArray, frameCount: Int): Int {
        if (!isNativeLoaded || !streaming) return -1
        return nativePushPcm(data, frameCount)
    }

    /**
     * Stop streaming and release the USB device.
     */
    fun stopAndRelease() {
        streaming = false
        pendingClaim = null
        if (isNativeLoaded) {
            nativeStop()
            nativeRelease()
        }
        Log.d(TAG, "stopAndRelease")
    }

    /**
     * @return true if the device is claimed and streaming
     */
    fun isStreaming(): Boolean = streaming && isNativeLoaded && nativeIsClaimed()

    /**
     * @return the number of buffer underruns since last reset
     */
    fun getUnderrunCount(): Int {
        if (!isNativeLoaded) return 0
        return nativeGetUnderrunCount()
    }

    /**
     * @return the name reported by the native driver
     */
    fun getDacName(): String? {
        if (!isNativeLoaded) return null
        return nativeGetDacName()
    }

    /**
     * @return detailed DAC info from the native driver
     */
    fun getDetailedDacInfo(): String? {
        if (!isNativeLoaded) return null
        return nativeGetDetailedInfo()
    }

    /**
     * @return a safe, non-private summary of the DAC info
     */
    fun getSafeDacInfo(): String {
        if (!isNativeLoaded) return "Native driver not loaded"
        val info = nativeGetDetailedInfo() ?: return "No DAC connected"
        // Strip any potentially sensitive path info
        return info.lines().filter { !it.contains("/dev/") && !it.contains("fd=") }.joinToString("\n")
    }

    // ============================================================
    // Private helpers
    // ============================================================

    private fun isAudioDevice(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == USB_CLASS_AUDIO) return true
            // Some devices report 0x10 for audio+video combined, check subclass
            if (iface.interfaceClass == 0x10 &&
                (iface.interfaceSubclass == SUBCLASS_AUDIOCONTROL ||
                 iface.interfaceSubclass == SUBCLASS_AUDIOSTREAMING)) return true
        }
        return false
    }

    private fun getEndpointInfo(device: UsbDevice): UsbEndpointInfo? {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == USB_CLASS_AUDIO && iface.interfaceSubclass == SUBCLASS_AUDIOSTREAMING) {
                // Check protocol for UAC version
                val isUac2 = iface.interfaceProtocol == PROTOCOL_UAC2

                for (j in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(j)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC &&
                        ep.direction == UsbConstants.USB_DIR_OUT) {
                        val epIsUac2 = isUac2 || ep.maxPacketSize > 1024 || ep.interval > 1
                        return UsbEndpointInfo(
                            address = ep.address,
                            maxPacketSize = ep.maxPacketSize,
                            interval = ep.interval,
                            isUac2 = epIsUac2
                        )
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

                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE) as? UsbDevice
                }

                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                Log.d(TAG, "USB permission result: granted=$granted, device=${device?.productName}")

                if (granted && device != null) {
                    val claim = pendingClaim
                    if (claim != null && claim.device.deviceId == device.deviceId) {
                        pendingClaim = null
                        Log.i(TAG, "Permission granted, auto-claiming ${device.productName}")
                        claimAndStart(device, claim.sampleRate, claim.channels, claim.bitsPerSample)
                    }
                } else {
                    pendingClaim = null
                    Log.w(TAG, "USB permission denied for ${device?.productName}")
                }
            }
        }

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnsafeRegisteredReceiver")
            context.registerReceiver(receiver, filter)
        }
        permissionReceiver = receiver
        Log.d(TAG, "USB permission receiver registered")
    }

    // ============================================================
    // JNI externals (private)
    // ============================================================

    private external fun nativeUsbAvailable(): Boolean
    private external fun nativeClaim(vid: Int, pid: Int, fd: Int, address: Int, maxPacketSize: Int, epInterval: Int, isUac2: Boolean): Boolean
    private external fun nativeUsbStart(sampleRate: Int, channels: Int, bitsPerSample: Int): Boolean
    private external fun nativePushPcm(data: FloatArray, frameCount: Int): Int
    private external fun nativeStop()
    private external fun nativeRelease()
    private external fun nativeIsClaimed(): Boolean
    private external fun nativeGetUnderrunCount(): Int
    private external fun nativeGetDacName(): String?
    private external fun nativeGetDetailedInfo(): String?
}
