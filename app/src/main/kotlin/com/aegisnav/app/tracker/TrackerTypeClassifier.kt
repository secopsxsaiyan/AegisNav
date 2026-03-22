package com.aegisnav.app.tracker

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import com.aegisnav.app.util.AppLog
import com.aegisnav.app.util.SecureLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Phase 3 — Tracker Type Identification (3.1, 3.2, 3.3).
 *
 * Classifies BLE devices by manufacturer-specific advertisement data:
 *
 * | Tracker        | Company ID | Subtype byte |
 * |----------------|-----------|--------------|
 * | AirTag         | 0x004C    | 0x12         |
 * | Apple Find My  | 0x004C    | 0x07         |
 * | Samsung SmartTag| 0x0075   | —            |
 * | Tile           | 0x01DA    | —            |
 * | Chipolo        | 0x0278    | —            |
 * | PebbleBee      | 0x0177    | —            |
 * | Google Find My | 0x00E0    | —            |
 *
 * Classification uses two paths:
 *  1. [classify] — fast, pure-logic; accepts a map of companyId→data bytes.
 *  2. [classifyFromHex] — parses the manufacturerDataHex string stored in [ScanLog].
 *
 * When a device's persistence score is high, [readGattInfo] can be called to
 * attempt a 5-second GATT connection and read standard device-info characteristics:
 *  - 0x2A00 — Device Name
 *  - 0x2A24 — Model Number
 *  - 0x2A29 — Manufacturer Name
 */
@Singleton
class TrackerTypeClassifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secureLogger: SecureLogger
) {
    companion object {
        private const val TAG = "TrackerTypeClassifier"

        /** GATT read timeout in ms (Feature 3.3). */
        const val GATT_TIMEOUT_MS = 5_000L

        // Standard GATT characteristic UUIDs
        val UUID_DEVICE_NAME  = UUID.fromString("00002a00-0000-1000-8000-00805f9b34fb")
        val UUID_MODEL_NUMBER = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")
        val UUID_MANUFACTURER = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb")

        // Company IDs
        const val COMPANY_APPLE        = 0x004C
        const val COMPANY_SAMSUNG      = 0x0075
        const val COMPANY_TILE         = 0x01DA
        const val COMPANY_PEBBLE_BEE   = 0x0177
        const val COMPANY_GOOGLE_FMD   = 0x00E0
        const val COMPANY_CHIPOLO      = 0x0278

        // Apple subtypes
        const val APPLE_SUBTYPE_AIRTAG   = 0x12
        const val APPLE_SUBTYPE_FIND_MY  = 0x07

        // Chipolo Find service UUID fragment
        const val CHIPOLO_SERVICE_UUID_FRAGMENT = "0000fe24"
    }

    /**
     * Classify from a map of companyId → raw payload bytes.
     * This is the fast, non-allocating path for live scan results.
     *
     * @param mfgDataMap  Map of BLE company ID (int) → manufacturer-specific bytes
     * @param serviceUuids List of advertised service UUID strings (lowercase preferred)
     */
    fun classify(
        mfgDataMap: Map<Int, ByteArray>,
        serviceUuids: List<String> = emptyList()
    ): TrackerType {
        // Apple (AirTag or FindMy): company 0x004C, first byte = subtype
        mfgDataMap[COMPANY_APPLE]?.let { data ->
            if (data.isNotEmpty()) {
                return when (data[0].toInt() and 0xFF) {
                    APPLE_SUBTYPE_AIRTAG  -> TrackerType.AIR_TAG
                    APPLE_SUBTYPE_FIND_MY -> TrackerType.APPLE_FIND_MY
                    else                  -> TrackerType.UNKNOWN
                }
            }
        }
        if (mfgDataMap.containsKey(COMPANY_SAMSUNG))    return TrackerType.SAMSUNG_SMART_TAG
        if (mfgDataMap.containsKey(COMPANY_TILE))       return TrackerType.TILE
        if (mfgDataMap.containsKey(COMPANY_PEBBLE_BEE)) return TrackerType.PEBBLE_BEE
        if (mfgDataMap.containsKey(COMPANY_GOOGLE_FMD)) return TrackerType.GOOGLE_FIND_MY
        if (mfgDataMap.containsKey(COMPANY_CHIPOLO))    return TrackerType.CHIPOLO
        // Chipolo can also be identified by service UUID
        if (serviceUuids.any { it.contains(CHIPOLO_SERVICE_UUID_FRAGMENT, ignoreCase = true) }) {
            return TrackerType.CHIPOLO
        }
        return TrackerType.UNKNOWN
    }

    /**
     * Classify from the [ScanLog.manufacturerDataHex] string.
     *
     * The hex string is stored with the little-endian company ID prepended
     * (e.g. "4c00" for Apple 0x004C) followed by the payload bytes.
     */
    fun classifyFromHex(mfgHex: String?, serviceUuids: String? = null): TrackerType {
        if (mfgHex.isNullOrBlank()) {
            // Fall through to service UUID check
            return if (!serviceUuids.isNullOrBlank() &&
                serviceUuids.contains(CHIPOLO_SERVICE_UUID_FRAGMENT, ignoreCase = true)) {
                TrackerType.CHIPOLO
            } else {
                TrackerType.UNKNOWN
            }
        }
        val hex = mfgHex.lowercase().replace(" ", "")
        // Identify by first 4 hex chars (2 bytes = company ID, little-endian)
        val companyHexLE = hex.take(4)
        return when (companyHexLE) {
            "4c00" -> {
                // Apple — look at subtype byte (5th+6th chars = first payload byte)
                val subtypeHex = hex.drop(4).take(2)
                val subtype = subtypeHex.toIntOrNull(16) ?: 0
                when (subtype) {
                    APPLE_SUBTYPE_AIRTAG  -> TrackerType.AIR_TAG
                    APPLE_SUBTYPE_FIND_MY -> TrackerType.APPLE_FIND_MY
                    else                  -> TrackerType.UNKNOWN
                }
            }
            "7500" -> TrackerType.SAMSUNG_SMART_TAG
            "da01" -> TrackerType.TILE
            "7701" -> TrackerType.PEBBLE_BEE
            "e000" -> TrackerType.GOOGLE_FIND_MY
            "7802" -> TrackerType.CHIPOLO
            // Some implementations store company ID big-endian
            "004c" -> classifyApplePayload(hex.drop(4))
            "0075" -> TrackerType.SAMSUNG_SMART_TAG
            "01da" -> TrackerType.TILE
            "0177" -> TrackerType.PEBBLE_BEE
            "00e0" -> TrackerType.GOOGLE_FIND_MY
            "0278" -> TrackerType.CHIPOLO
            else   -> TrackerType.UNKNOWN
        }
    }

    private fun classifyApplePayload(payloadHex: String): TrackerType {
        val subtype = payloadHex.take(2).toIntOrNull(16) ?: 0
        return when (subtype) {
            APPLE_SUBTYPE_AIRTAG  -> TrackerType.AIR_TAG
            APPLE_SUBTYPE_FIND_MY -> TrackerType.APPLE_FIND_MY
            else                  -> TrackerType.UNKNOWN
        }
    }

    /**
     * Feature 3.3 — GATT connection for device identification.
     *
     * Attempts a 5-second GATT connection to [device] and reads the three
     * standard device-info characteristics: 0x2A00 (name), 0x2A24 (model),
     * 0x2A29 (manufacturer).
     *
     * Returns a [GattDeviceInfo] with whatever fields could be read, or null
     * if the connection timed out or failed.
     *
     * Should only be called when persistence score is high (e.g. 10+ sightings)
     * to avoid unnecessary radio use.
     *
     * **Requires BLUETOOTH_CONNECT permission (API 31+).**
     */
    @SuppressLint("MissingPermission")
    @SuppressWarnings("VARIABLE_WITH_REDUNDANT_INITIALIZER") // gatt=null needed for invokeOnCancellation lambda capture
    @Suppress("VARIABLE_WITH_REDUNDANT_INITIALIZER")
    suspend fun readGattInfo(device: BluetoothDevice): GattDeviceInfo? {
        return withTimeoutOrNull(GATT_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val readings = mutableMapOf<String, String>()
                var gatt: BluetoothGatt? = null

                val callback = object : BluetoothGattCallback() {
                    private val characteristicQueue = ArrayDeque(
                        listOf(UUID_DEVICE_NAME, UUID_MODEL_NUMBER, UUID_MANUFACTURER)
                    )

                    override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                        when (newState) {
                            BluetoothProfile.STATE_CONNECTED -> {
                                secureLogger.d(TAG, "GATT connected to ${device.address}, discovering services")
                                g.discoverServices()
                            }
                            BluetoothProfile.STATE_DISCONNECTED -> {
                                secureLogger.d(TAG, "GATT disconnected from ${device.address}")
                                // Always close the GATT handle after disconnect to free BT resources.
                                // invokeOnCancellation handles close on timeout; this covers normal flow.
                                try { g.close() } catch (_: Exception) {}
                                if (cont.isActive) cont.resume(buildResult(readings))
                            }
                        }
                    }

                    override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            AppLog.w(TAG, "Service discovery failed: status=$status")
                            g.disconnect()
                            return
                        }
                        readNext(g)
                    }

                    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION") // 3-param form deprecated in API 33; needed for API < 33
                    override fun onCharacteristicRead(
                        g: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                        status: Int
                    ) {
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            val value = characteristic.value
                            if (value != null) {
                                // [SEC-FIX] Limit string length to 256 chars to prevent
                                // a malicious BLE device from causing unbounded string allocation.
                                readings[characteristic.uuid.toString()] =
                                    String(value, Charsets.UTF_8).trim().take(256)
                            }
                        }
                        readNext(g)
                    }

                    // 4-param form introduced in API 33 — no suppression needed; kept for API 33+ devices
                    override fun onCharacteristicRead(
                        g: BluetoothGatt,
                        characteristic: BluetoothGattCharacteristic,
                        value: ByteArray,
                        status: Int
                    ) {
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            // [SEC-FIX] Limit string length to 256 chars.
                            readings[characteristic.uuid.toString()] =
                                String(value, Charsets.UTF_8).trim().take(256)
                        }
                        readNext(g)
                    }

                    private fun readNext(g: BluetoothGatt) {
                        val nextUuid = characteristicQueue.removeFirstOrNull()
                        if (nextUuid == null) {
                            g.disconnect()
                            return
                        }
                        val service = g.services.firstOrNull { svc ->
                            svc.characteristics.any { it.uuid == nextUuid }
                        }
                        val char = service?.getCharacteristic(nextUuid)
                        if (char == null) {
                            readNext(g)
                        } else {
                            val ok = g.readCharacteristic(char)
                            if (!ok) readNext(g)
                        }
                    }

                    private fun buildResult(map: Map<String, String>): GattDeviceInfo {
                        return GattDeviceInfo(
                            deviceName   = map[UUID_DEVICE_NAME.toString()],
                            modelNumber  = map[UUID_MODEL_NUMBER.toString()],
                            manufacturer = map[UUID_MANUFACTURER.toString()]
                        )
                    }
                }

                gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)

                cont.invokeOnCancellation {
                    try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
                }
            }
        }.also { result ->
            secureLogger.d(TAG, "GATT readInfo ${device.address}: $result")
        }
    }

    /** Result of a GATT device-info read (Feature 3.3). */
    data class GattDeviceInfo(
        val deviceName: String?,
        val modelNumber: String?,
        val manufacturer: String?
    )
}
