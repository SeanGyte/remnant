package com.remnant.dreams.tts

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Google's Android key restriction compares the `X-Android-Cert` header against the
 * certificate fingerprint typed into the console, as a plain uppercase hex string.
 * Get the formatting wrong -- lowercase, colons, a dropped leading zero -- and every
 * text-to-speech call starts failing once the restriction is switched on, so the
 * formatting is pinned here rather than trusted.
 *
 * Reading the certificate itself needs a PackageManager and is left to the device.
 */
class GoogleApiIdentityTest {

    @Test
    fun `bytes become uppercase hex with no separators`() {
        assertEquals(
            "00010F10FF",
            GoogleApiIdentity.toHex(byteArrayOf(0x00, 0x01, 0x0F, 0x10, 0xFF.toByte()))
        )
    }

    @Test
    fun `every byte contributes two characters, leading zeros included`() {
        // 0x0A must be "0A", not "A" -- a fingerprint short one character matches nothing.
        val hex = GoogleApiIdentity.toHex(ByteArray(20) { 0x0A })
        assertEquals(40, hex.length)
        assertEquals("0A".repeat(20), hex)
    }

    @Test
    fun `a real SHA-1 digest comes out 40 characters of uppercase hex`() {
        val digest = MessageDigest.getInstance("SHA-1").digest("remnant".toByteArray())
        val hex = GoogleApiIdentity.toHex(digest)

        assertEquals(40, hex.length)
        assertEquals(hex.uppercase(), hex)
        assertEquals(true, hex.all { it in '0'..'9' || it in 'A'..'F' })
    }

    @Test
    fun `an empty digest formats to an empty string rather than throwing`() {
        assertEquals("", GoogleApiIdentity.toHex(ByteArray(0)))
    }
}
