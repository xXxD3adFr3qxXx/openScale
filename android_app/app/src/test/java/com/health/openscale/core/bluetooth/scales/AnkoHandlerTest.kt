/*
 * openScale
 * Copyright (C) 2026 openScale contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.health.openscale.core.bluetooth.scales

import com.google.common.truth.Truth.assertThat
import com.health.openscale.core.bluetooth.ScaleCatalog.hex
import org.junit.Test

// Frames from an HCI snoop log, MAC replaced:
// header 020311 | MAC | 02 | weight kg/100 | resistance | 01 or A1 | counter | checksum
class AnkoHandlerTest {

    @Test
    fun `reads weight and resistance from a locked frame`() {
        val frame = AnkoHandler.parseFrame(hex("020311 C0FFEE123456 02 131F 0086 A1 07 62"))!!
        assertThat(frame.weightKg).isWithin(0.001f).of(48.95f)
        assertThat(frame.stable).isTrue()
        assertThat(frame.resistance).isEqualTo(134)
    }

    @Test
    fun `marks a live frame as not stable`() {
        val frame = AnkoHandler.parseFrame(hex("020311 C0FFEE123456 02 123E 0000 01 70 C3"))!!
        assertThat(frame.weightKg).isWithin(0.001f).of(46.70f)
        assertThat(frame.stable).isFalse()
        assertThat(frame.resistance).isEqualTo(0)
    }

    @Test
    fun `rejects a bad checksum, header or length`() {
        assertThat(AnkoHandler.parseFrame(hex("020311 C0FFEE123456 02 131F 0086 A1 07 63"))).isNull()
        assertThat(AnkoHandler.parseFrame(hex("020312 C0FFEE123456 02 131F 0086 A1 07 62"))).isNull()
        assertThat(AnkoHandler.parseFrame(hex("020311 C0FFEE123456 02 131F 0086 A1 07"))).isNull()
    }

    @Test
    fun `uses resistance only for barefoot readings`() {
        assertThat(AnkoHandler.resistanceToOhms(134)).isEqualTo(134 * AnkoHandler.OHMS_PER_STEP)
        assertThat(AnkoHandler.resistanceToOhms(0)).isNull()    // shoes
        assertThat(AnkoHandler.resistanceToOhms(2042)).isNull() // socks
    }
}
