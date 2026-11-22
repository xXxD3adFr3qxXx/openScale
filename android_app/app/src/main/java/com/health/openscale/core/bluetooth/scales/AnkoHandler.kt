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
import com.health.openscale.core.utils.LogManager
import java.util.Date
import java.util.UUID

/**
 * Anko Bluetooth Body Analysis Scale (Kmart 43468829, model iF1012D, advertises as "IF_B7",
 * vendor app MovingLife).
 *
 * Broadcast-only: every advertisement is ADV_NONCONN_IND. Manufacturer data, company id
 * 0x0100 (raw `00 01`), then 17 bytes as `ScanRecord` hands them over:
 *   [0..2]   02 03 11      constant header
 *   [3..8]   scale MAC
 *   [9]      02            constant
 *   [10..11] weight, u16 BE, 0.01 kg
 *   [12..13] 0 while weighing, a value on the final frame (impedance or body fat, not decoded)
 *   [14]     0x01 live, 0xA1 final
 *   [15]     packet counter
 *   [16]     checksum = sum([9..15]) & 0xFF
 *
 * Decoded from an HCI snoop: final frames 48.95 kg and 43.10 kg matched the scale display.
 * Operated by [BroadcastScaleAdapter]; GATT hooks are intentional no-ops.
 */
class AnkoHandler : ScaleDeviceHandler() {

    companion object {
        const val COMPANY_ID = 0x0100
        private const val FRAME_LEN = 17
        private const val FLAG_FINAL = 0xA1

        /** Weight in kg and whether it is the final frame, or null if [d] is not a valid frame. */
        fun parseFrame(d: ByteArray): Pair<Float, Boolean>? {
            if (d.size != FRAME_LEN) return null
            val u = { i: Int -> d[i].toInt() and 0xFF }
            if (u(0) != 0x02 || u(1) != 0x03 || u(2) != 0x11) return null
            if (((9..15).sumOf(u) and 0xFF) != u(16)) return null
            return ((u(10) shl 8) or u(11)) / 100f to (u(14) == FLAG_FINAL)
        }
    }

    private var hasPublished = false

    override fun supportFor(device: ScannedDeviceInfo): DeviceSupport? {
        val data = device.manufacturerData?.get(COMPANY_ID) ?: return null
        return if (parseFrame(data) != null) deviceSupport else null
    }

    override fun onConnected(user: ScaleUser) = Unit

    override fun onNotification(characteristic: UUID, data: ByteArray, user: ScaleUser) = Unit

    override fun onDisconnected() {
        hasPublished = false
    }

    override fun onAdvertisement(result: ScanResult, user: ScaleUser): BroadcastAction {
        val data = result.scanRecord?.getManufacturerSpecificData(COMPANY_ID)
            ?: return BroadcastAction.IGNORED
        val (kg, final) = parseFrame(data) ?: return BroadcastAction.IGNORED
        if (kg <= 0f) return BroadcastAction.IGNORED

        if (!final) {
            hasPublished = false // someone is on the scale: a new weigh-in
            return BroadcastAction.CONSUMED_KEEP_SCANNING
        }
        // The final frame repeats for ~10 s; store it once.
        if (hasPublished) return BroadcastAction.CONSUMED_STOP

        LogManager.i(TAG, "final ${"%.2f".format(kg)} kg → publish")
        publish(ScaleMeasurement().apply {
            userId = user.id
            this[MeasurementType.WEIGHT] = Kg(kg)
            dateTime = Date()
        })
        hasPublished = true
        return BroadcastAction.CONSUMED_STOP
    }

    private val deviceSupport = DeviceSupport(
        displayName = "Anko Body Analysis Scale",
        capabilities = setOf(DeviceCapability.LIVE_WEIGHT_STREAM),
        implemented = setOf(DeviceCapability.LIVE_WEIGHT_STREAM),
        linkMode = LinkMode.BROADCAST_ONLY
    )
}
