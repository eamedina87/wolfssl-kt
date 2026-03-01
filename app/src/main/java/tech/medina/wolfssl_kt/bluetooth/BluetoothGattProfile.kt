package tech.medina.wolfssl_kt.bluetooth

import java.util.UUID

object BluetoothGattProfile {
    const val ADVERTISING_NAME_PREFIX = "WolfsslKt"
    val SERVICE_UUID: UUID = UUID.fromString("9b9f6f0b-c584-4e11-a432-1531d545ef62")
    const val DISCOVERY_MANUFACTURER_ID: Int = 0xFFFF
    val DISCOVERY_MANUFACTURER_DATA: ByteArray = byteArrayOf(0x57, 0x4B) // "WK"
    val WRITE_CHARACTERISTIC_UUID: UUID = UUID.fromString("c11f695f-1eff-43e7-89f7-291f0c6f7241")
    val NOTIFY_CHARACTERISTIC_UUID: UUID = UUID.fromString("c58f8076-e158-4baf-9e76-f30f4b4ec264")
    val CCC_DESCRIPTOR_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}
