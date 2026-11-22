package com.health.openscale.core.bluetooth.scales

import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.bluetooth.ScaleCatalog.hex
import org.junit.Test

/** Frames from an HCI snoop of a Kmart Anko scale (IF_B7), company id 0x0100 stripped. */
class AnkoHandlerTest {

    @Test
    fun `final frame gives 48_95 kg`() {
        val (kg, final) = AnkoHandler.parseFrame(hex("020311 C0FFEE123456 02 131F 0086 A1 07 62"))!!
        assertThat(kg).isWithin(1e-3f).of(48.95f)
        assertThat(final).isTrue()
    }

    @Test
    fun `live frame is not final`() {
        val (kg, final) = AnkoHandler.parseFrame(hex("020311 C0FFEE123456 02 123E 0000 01 70 C3"))!!
        assertThat(kg).isWithin(1e-3f).of(46.70f)
        assertThat(final).isFalse()
    }

    @Test
    fun `rejects a bad checksum, wrong header or wrong length`() {
        assertThat(AnkoHandler.parseFrame(hex("020311 C0FFEE123456 02 131F 0086 A1 07 63"))).isNull()
        assertThat(AnkoHandler.parseFrame(hex("020312 C0FFEE123456 02 131F 0086 A1 07 62"))).isNull()
        assertThat(AnkoHandler.parseFrame(hex("020311 C0FFEE123456 02 131F 0086 A1 07"))).isNull()
    }
}
