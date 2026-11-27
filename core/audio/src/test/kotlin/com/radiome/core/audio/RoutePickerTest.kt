package com.radiome.core.audio

import android.media.AudioDeviceInfo
import com.radiome.core.audio.RoutePicker.BUILTIN_MIC
import com.radiome.core.audio.RoutePicker.BUILTIN_SPEAKER
import com.radiome.core.audio.RoutePicker.USB_DEVICE
import com.radiome.core.audio.RoutePicker.USB_HEADSET
import com.radiome.core.audio.RoutePicker.WIRED_HEADPHONES
import com.radiome.core.audio.RoutePicker.WIRED_HEADSET
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutePickerTest {
    private val mic = DeviceDesc(1, BUILTIN_MIC, "Pixel")
    private val speaker = DeviceDesc(2, BUILTIN_SPEAKER, "Pixel")
    private val phoneOnlyIn = listOf(mic)
    private val phoneOnlyOut = listOf(speaker)

    @Test
    fun typeConstantsMatchAndroid() {
        // Compile-time constants, so this checks the inlined values against the SDK without a device.
        assertEquals(AudioDeviceInfo.TYPE_BUILTIN_MIC, BUILTIN_MIC)
        assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, BUILTIN_SPEAKER)
        assertEquals(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, RoutePicker.BUILTIN_EARPIECE)
        assertEquals(AudioDeviceInfo.TYPE_WIRED_HEADSET, WIRED_HEADSET)
        assertEquals(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, WIRED_HEADPHONES)
        assertEquals(AudioDeviceInfo.TYPE_LINE_ANALOG, RoutePicker.LINE_ANALOG)
        assertEquals(AudioDeviceInfo.TYPE_USB_DEVICE, USB_DEVICE)
        assertEquals(AudioDeviceInfo.TYPE_USB_ACCESSORY, RoutePicker.USB_ACCESSORY)
        assertEquals(AudioDeviceInfo.TYPE_USB_HEADSET, USB_HEADSET)
    }

    @Test
    fun autoWithoutCableIsAcoustic() {
        val c = RoutePicker.pick(RoutePreference.AUTO, phoneOnlyIn, phoneOnlyOut)
        assertEquals(RouteMode.ACOUSTIC, c.mode)
        assertEquals(mic, c.input)
        assertEquals(speaker, c.output)
        assertFalse(c.cableMissing)
    }

    @Test
    fun autoPicksTrrsHeadsetCable() {
        val jackIn = DeviceDesc(3, WIRED_HEADSET, "Headset")
        val jackOut = DeviceDesc(4, WIRED_HEADSET, "Headset")
        val c = RoutePicker.pick(RoutePreference.AUTO, phoneOnlyIn + jackIn, phoneOnlyOut + jackOut)
        assertEquals(RouteMode.CABLE, c.mode)
        assertEquals(jackIn, c.input)
        assertEquals(jackOut, c.output)
    }

    @Test
    fun headphonesWithoutMicStayAcousticInAuto() {
        val c = RoutePicker.pick(RoutePreference.AUTO, phoneOnlyIn, phoneOnlyOut + DeviceDesc(4, WIRED_HEADPHONES, "Jack"))
        assertEquals(RouteMode.ACOUSTIC, c.mode)
        assertEquals(speaker, c.output)
    }

    @Test
    fun digirigUsbAudioWinsOverTheJackAndIsPairedWithItself() {
        // Jack listed first, as some phones do; Digirig (CM108) is a USB device with both directions.
        val jackIn = DeviceDesc(3, WIRED_HEADSET, "Headset")
        val jackOut = DeviceDesc(4, WIRED_HEADPHONES, "Headset")
        val usbIn = DeviceDesc(5, USB_DEVICE, "USB Audio Device", "card=1;device=0")
        val usbOut = DeviceDesc(6, USB_DEVICE, "USB Audio Device", "card=1;device=0")
        val c = RoutePicker.pick(RoutePreference.AUTO, listOf(mic, jackIn, usbIn), listOf(speaker, jackOut, usbOut))
        assertEquals(RouteMode.CABLE, c.mode)
        assertEquals(usbIn, c.input)
        assertEquals(usbOut, c.output)
    }

    @Test
    fun twoUsbCardsPairByAddress() {
        val aIn = DeviceDesc(5, USB_HEADSET, "Dongle", "card=1;device=0")
        val bIn = DeviceDesc(7, USB_DEVICE, "Digirig", "card=2;device=0")
        val bOut = DeviceDesc(8, USB_DEVICE, "Digirig", "card=2;device=0")
        val c = RoutePicker.pick(RoutePreference.AUTO, listOf(aIn, bIn), listOf(bOut))
        assertEquals(bIn, c.input)
        assertEquals(bOut, c.output)
    }

    @Test
    fun forcedCableWithNothingPluggedInIsFlagged() {
        val c = RoutePicker.pick(RoutePreference.CABLE, phoneOnlyIn, phoneOnlyOut)
        assertEquals(RouteMode.CABLE, c.mode)
        assertTrue(c.cableMissing)
        assertNull(c.input)
        assertNull(c.output)
    }

    @Test
    fun forcedCableUsesWhatIsThere() {
        val out = DeviceDesc(4, WIRED_HEADPHONES, "Jack")
        val c = RoutePicker.pick(RoutePreference.CABLE, phoneOnlyIn, phoneOnlyOut + out)
        assertEquals(out, c.output)
        assertTrue(c.cableMissing)
    }

    @Test
    fun forcedAcousticIgnoresTheCable() {
        val usbIn = DeviceDesc(5, USB_DEVICE, "Digirig", "card=1;device=0")
        val usbOut = DeviceDesc(6, USB_DEVICE, "Digirig", "card=1;device=0")
        val c = RoutePicker.pick(RoutePreference.ACOUSTIC, listOf(mic, usbIn), listOf(speaker, usbOut))
        assertEquals(RouteMode.ACOUSTIC, c.mode)
        assertEquals(mic, c.input)
        assertEquals(speaker, c.output)
    }

    @Test
    fun unpluggingTheCableFallsBackToAcousticInAuto() {
        val usbIn = DeviceDesc(5, USB_DEVICE, "Digirig", "card=1;device=0")
        val usbOut = DeviceDesc(6, USB_DEVICE, "Digirig", "card=1;device=0")
        assertEquals(RouteMode.CABLE, RoutePicker.pick(RoutePreference.AUTO, listOf(mic, usbIn), listOf(speaker, usbOut)).mode)
        assertEquals(RouteMode.ACOUSTIC, RoutePicker.pick(RoutePreference.AUTO, listOf(mic), listOf(speaker)).mode)
    }
}
