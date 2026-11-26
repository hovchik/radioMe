package com.radiome.core.audio

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log

/**
 * Keys PTT with the RTS or DTR line of the first USB serial adapter it finds. If Android has not yet granted access
 * to the adapter, it asks (the system shows a dialog) and stays not [ready] until the next [refresh].
 */
class UsbSerialPtt(context: Context, private val useRts: Boolean) : PttKeyer {
    private val appContext = context.applicationContext
    private val usb = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private var connection: UsbDeviceConnection? = null
    private var chip: SerialChip? = null
    private var iface = 0

    /** Name of the adapter in use, or null. */
    var deviceName: String? = null
        private set

    override val ready: Boolean get() = connection != null

    /**
     * Finds and opens the adapter; call when starting the radio and when a device is attached. Without access it asks
     * for it when [askPermission] is set (the answer comes back as [ACTION_PERMISSION]).
     */
    @Synchronized
    fun refresh(askPermission: Boolean = true): Boolean {
        if (connection != null) return true
        val (device, found) = usb.deviceList.values.firstNotNullOfOrNull { d -> chipOf(d)?.let { d to it } } ?: return false
        if (!usb.hasPermission(device)) {
            if (!askPermission) return false
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val intent = Intent(ACTION_PERMISSION).setPackage(appContext.packageName)
            usb.requestPermission(device, PendingIntent.getBroadcast(appContext, 0, intent, flags))
            return false
        }
        val c = usb.openDevice(device) ?: return false
        iface = interfaceFor(device, found)
        device.getInterface(iface.coerceAtMost(device.interfaceCount - 1)).let { c.claimInterface(it, true) }
        for (t in SerialLines.open(found, iface)) transfer(c, t)
        connection = c
        chip = found
        deviceName = device.productName ?: "USB serial %04x:%04x".format(device.vendorId, device.productId)
        key(false)
        return true
    }

    @Synchronized
    override fun key(on: Boolean) {
        val c = connection ?: return
        val ch = chip ?: return
        for (t in SerialLines.set(ch, iface, rts = useRts && on, dtr = !useRts && on)) {
            if (transfer(c, t) < 0) Log.w(TAG, "PTT control transfer failed: $t")
        }
    }

    @Synchronized
    override fun close() {
        runCatching { key(false) }
        connection?.close()
        connection = null
        chip = null
        deviceName = null
    }

    private fun transfer(c: UsbDeviceConnection, t: ControlTransfer): Int =
        c.controlTransfer(t.requestType, t.request, t.value, t.index, null, 0, TIMEOUT_MS)

    private fun chipOf(d: UsbDevice): SerialChip? {
        val cdc = (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_COMM }
        return SerialLines.chipFor(d.vendorId, cdc)
    }

    private fun interfaceFor(d: UsbDevice, chip: SerialChip): Int = when (chip) {
        SerialChip.CDC_ACM -> (0 until d.interfaceCount).firstOrNull { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_COMM } ?: 0
        else -> 0
    }

    companion object {
        const val ACTION_PERMISSION = "com.radiome.USB_PERMISSION"
        private const val TAG = "UsbSerialPtt"
        private const val TIMEOUT_MS = 500
    }
}
