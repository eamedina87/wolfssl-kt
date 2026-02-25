package tech.medina.wolfssl_kt.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

data class DiscoveredBleDevice(
    val name: String?,
    val address: String,
    val rssi: Int
)

sealed class BleConnectionEvent {
    data object ScanStarted : BleConnectionEvent()
    data object ScanStopped : BleConnectionEvent()
    data class DeviceDiscovered(val device: DiscoveredBleDevice) : BleConnectionEvent()
    data class Connected(val address: String) : BleConnectionEvent()
    data class Disconnected(val address: String) : BleConnectionEvent()
    data class ServicesReady(val address: String) : BleConnectionEvent()
    data class CharacteristicWriteFailed(val status: Int) : BleConnectionEvent()
    data class Error(val message: String) : BleConnectionEvent()
}

class BluetoothLeConnectionManager(
    context: Context,
    private val bluetoothProvider: GattBluetoothProvider
) {
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val scanner: BluetoothLeScanner?
        get() = bluetoothAdapter?.bluetoothLeScanner

    private val scanResults = linkedMapOf<String, DiscoveredBleDevice>()

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredBleDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredBleDevice>> = _discoveredDevices.asStateFlow()

    private val _events = MutableSharedFlow<BleConnectionEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<BleConnectionEvent> = _events.asSharedFlow()

    private var currentGatt: BluetoothGatt? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null

    @SuppressLint("MissingPermission")
    fun startScan() {
        val bleScanner = scanner
        if (bleScanner == null) {
            emitEvent(BleConnectionEvent.Error("BLE scanner not available"))
            return
        }

        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(BluetoothGattProfile.SERVICE_UUID))
            .build()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanResults.clear()
        _discoveredDevices.value = emptyList()
        bleScanner.startScan(listOf(filter), settings, scanCallback)
        emitEvent(BleConnectionEvent.ScanStarted)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanner?.stopScan(scanCallback)
        emitEvent(BleConnectionEvent.ScanStopped)
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        currentGatt?.close()
        bluetoothProvider.unbind()
        notifyCharacteristic = null
        currentGatt = device.connectGatt(
            null,
            false,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE
        )
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        currentGatt?.disconnect()
    }

    fun close() {
        currentGatt?.close()
        currentGatt = null
        notifyCharacteristic = null
        bluetoothProvider.unbind()
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        val device = bluetoothAdapter?.getRemoteDevice(address)
        if (device == null) {
            emitEvent(BleConnectionEvent.Error("Device not found for address $address"))
            return
        }
        connect(device)
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val address = result.device.address ?: return
            val discovered = DiscoveredBleDevice(
                name = result.device.name ?: result.scanRecord?.deviceName,
                address = address,
                rssi = result.rssi
            )
            scanResults[address] = discovered
            _discoveredDevices.value = scanResults.values.toList()
            emitEvent(BleConnectionEvent.DeviceDiscovered(discovered))
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            emitEvent(BleConnectionEvent.Error("Scan failed with code=$errorCode"))
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                emitEvent(BleConnectionEvent.Error("Connection state error status=$status"))
                gatt.close()
                bluetoothProvider.unbind()
                return
            }

            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    emitEvent(BleConnectionEvent.Connected(gatt.device.address))
                    gatt.discoverServices()
                }
                BluetoothGatt.STATE_DISCONNECTED -> {
                    emitEvent(BleConnectionEvent.Disconnected(gatt.device.address))
                    bluetoothProvider.unbind()
                    notifyCharacteristic = null
                    gatt.close()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                emitEvent(BleConnectionEvent.Error("Service discovery failed status=$status"))
                return
            }

            val service: BluetoothGattService = gatt.getService(BluetoothGattProfile.SERVICE_UUID)
                ?: run {
                    emitEvent(BleConnectionEvent.Error("Required BLE service not found"))
                    return
                }

            val writeCharacteristic = service.getCharacteristic(BluetoothGattProfile.WRITE_CHARACTERISTIC_UUID)
                ?: run {
                    emitEvent(BleConnectionEvent.Error("Write characteristic not found"))
                    return
                }

            notifyCharacteristic = service.getCharacteristic(BluetoothGattProfile.NOTIFY_CHARACTERISTIC_UUID)
                ?: run {
                    emitEvent(BleConnectionEvent.Error("Notify characteristic not found"))
                    return
                }

            bluetoothProvider.bind(gatt, writeCharacteristic)
            enableNotifications(gatt, notifyCharacteristic!!)
            emitEvent(BleConnectionEvent.ServicesReady(gatt.device.address))
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == BluetoothGattProfile.NOTIFY_CHARACTERISTIC_UUID) {
                bluetoothProvider.onCharacteristicReceived(value)
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return
            }
            if (characteristic.uuid == BluetoothGattProfile.NOTIFY_CHARACTERISTIC_UUID) {
                val value = characteristic.value ?: return
                bluetoothProvider.onCharacteristicReceived(value)
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                emitEvent(BleConnectionEvent.CharacteristicWriteFailed(status))
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        val notifySet = gatt.setCharacteristicNotification(characteristic, true)
        if (!notifySet) {
            emitEvent(BleConnectionEvent.Error("Failed to enable local notifications"))
            return
        }

        val descriptor = characteristic.getDescriptor(BluetoothGattProfile.CCC_DESCRIPTOR_UUID)
        if (descriptor == null) {
            emitEvent(BleConnectionEvent.Error("CCC descriptor not found"))
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                emitEvent(BleConnectionEvent.Error("Failed to write CCC descriptor, status=$status"))
            }
            return
        }

        @Suppress("DEPRECATION")
        run {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            val started = gatt.writeDescriptor(descriptor)
            if (!started) {
                emitEvent(BleConnectionEvent.Error("Failed to start CCC descriptor write"))
            }
        }
    }

    private fun emitEvent(event: BleConnectionEvent) {
        _events.tryEmit(event)
        Log.d(TAG, event.toString())
    }

    private companion object {
        const val TAG = "BleConnectionManager"
    }
}
