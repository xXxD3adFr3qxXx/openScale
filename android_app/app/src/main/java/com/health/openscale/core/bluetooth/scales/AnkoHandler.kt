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

import android.bluetooth.le.ScanResult
import com.health.openscale.core.bluetooth.data.ScaleMeasurement
import com.health.openscale.core.bluetooth.data.ScaleUser
import com.health.openscale.core.data.Kg
import com.health.openscale.core.data.MeasurementType
import com.health.openscale.core.service.ScannedDeviceInfo
import java.util.Date

/**
 * Anko Bluetooth Body Analysis Scale (Kmart 43468829, model iF1012D), advertising as "IF_B7".
 *
 * The scale never accepts a connection; it sends everything in non-connectable advertisements
 * under manufacturer id 0x0100. Frame after the company id:
 *
 *   0-2    02 03 11 (fixed)
 *   3-8    scale MAC
 *   9      02 (resolution, 0.01 kg)
 *   10-11  weight, big endian, kg/100
 *   12-13  resistance, raw (0 while weighing); not used until its scale to ohms is known
 *   14     01 while weighing, A1 once the weight is locked
 *   15     counter
 *   16     checksum, sum of bytes 9-15
 */
class AnkoHandler : ScaleDeviceHandler() {

    data class Frame(val weightKg: Float, val stable: Boolean)

    companion object {
        const val MANUFACTURER_ID = 0x0100
        private const val FRAME_LENGTH = 17
        private const val STABLE = 0xA1

        fun parseFrame(data: ByteArray): Frame? {
            if (data.size != FRAME_LENGTH) return null
            val b = IntArray(FRAME_LENGTH) { data[it].toInt() and 0xFF }
            if (b[0] != 0x02 || b[1] != 0x03 || b[2] != 0x11) return null

            var sum = 0
            for (i in 9..15) sum += b[i]
            if ((sum and 0xFF) != b[16]) return null

            return Frame(
                weightKg = ((b[10] shl 8) or b[11]) / 100f,
                stable = b[14] == STABLE
            )
        }
    }

    private var published = false

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val data = device.manufacturerData?.get(MANUFACTURER_ID) ?: return null
        parseFrame(data) ?: return null

        return DeviceSupport(
            displayName = "Anko Body Analysis Scale",
            capabilities = setOf(DeviceCapability.LIVE_WEIGHT_STREAM),
            implemented = setOf(DeviceCapability.LIVE_WEIGHT_STREAM),
            linkMode = LinkMode.BROADCAST_ONLY
        )
    }

    override fun onAdvertisement(result: ScanResult, user: ScaleUser): BroadcastAction {
        val data = result.scanRecord?.getManufacturerSpecificData(MANUFACTURER_ID)
            ?: return BroadcastAction.IGNORED
        val frame = parseFrame(data) ?: return BroadcastAction.IGNORED
        if (frame.weightKg <= 0f) return BroadcastAction.IGNORED

        if (!frame.stable) {
            published = false
            return BroadcastAction.CONSUMED_KEEP_SCANNING
        }

        // The locked frame keeps repeating for about 10 seconds
        if (published) return BroadcastAction.CONSUMED_STOP

        val measurement = ScaleMeasurement().apply {
            userId = user.id
            dateTime = Date()
            this[MeasurementType.WEIGHT] = Kg(frame.weightKg)
        }

        logI("weight ${frame.weightKg} kg")
        publish(measurement)
        published = true
        return BroadcastAction.CONSUMED_STOP
    }

    override fun onDisconnected() {
        published = false
    }
}
