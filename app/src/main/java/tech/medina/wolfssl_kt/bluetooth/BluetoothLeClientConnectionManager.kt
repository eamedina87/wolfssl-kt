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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DiscoveredBleDevice(
    val name: String?,
    val address: String,
    val rssi: Int
)

sealed class BleClientConnectionEvent {
    data object ScanStarted : BleClientConnectionEvent()
    data object ScanStopped : BleClientConnectionEvent()
    data class DeviceDiscovered(val device: DiscoveredBleDevice) : BleClientConnectionEvent()
    data class Connected(val address: String) : BleClientConnectionEvent()
    data class Disconnected(val address: String) : BleClientConnectionEvent()
    data class ServicesReady(val address: String) : BleClientConnectionEvent()
    data class CharacteristicWriteFailed(val status: Int) : BleClientConnectionEvent()
    data class Error(val message: String) : BleClientConnectionEvent()
}

class BluetoothLeClientConnectionManager(
    context: Context,
    private val bluetoothProvider: BluetoothProvider,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val appContext = context.applicationContext
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val scanner: BluetoothLeScanner?
        get() = bluetoothAdapter?.bluetoothLeScanner

    private val scanResults = linkedMapOf<String, DiscoveredBleDevice>()

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredBleDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredBleDevice>> = _discoveredDevices.asStateFlow()

    private val _events = MutableSharedFlow<BleClientConnectionEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<BleClientConnectionEvent> = _events.asSharedFlow()

    private var currentGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private var outgoingWriteJob: Job? = null

    @SuppressLint("MissingPermission")
    fun startScan() {
        val bleScanner = scanner
        if (bleScanner == null) {
            emitEvent(BleClientConnectionEvent.Error("BLE scanner not available"))
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
        emitEvent(BleClientConnectionEvent.ScanStarted)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanner?.stopScan(scanCallback)
        emitEvent(BleClientConnectionEvent.ScanStopped)
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        closeCurrentConnection()
        currentGatt = device.connectGatt(
            appContext,
            false,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE
        )
    }

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        val device = bluetoothAdapter?.getRemoteDevice(address)
        if (device == null) {
            emitEvent(BleClientConnectionEvent.Error("Device not found for address $address"))
            return
        }
        connect(device)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        currentGatt?.disconnect()
    }

    fun close() {
        closeCurrentConnection()
    }

    private fun startOutgoingWriter() {
        outgoingWriteJob?.cancel()
        outgoingWriteJob = scope.launch {
            for (data in bluetoothProvider.outgoingChannel) {
                writeToServerCharacteristic(data)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeToServerCharacteristic(data: ByteArray) {
        val gatt = currentGatt ?: return
        val characteristic = writeCharacteristic ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = gatt.writeCharacteristic(
                characteristic,
                data,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
            if (status != BluetoothGatt.GATT_SUCCESS) {
                emitEvent(BleClientConnectionEvent.CharacteristicWriteFailed(status))
            }
            return
        }

        @Suppress("DEPRECATION")
        run {
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            characteristic.value = data
            val started = gatt.writeCharacteristic(characteristic)
            if (!started) {
                emitEvent(BleClientConnectionEvent.Error("Failed to start characteristic write"))
            }
        }
    }

    private fun closeCurrentConnection() {
        outgoingWriteJob?.cancel()
        outgoingWriteJob = null
        writeCharacteristic = null
        notifyCharacteristic = null
        currentGatt?.close()
        currentGatt = null
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val discovered = DiscoveredBleDevice(
                name = result.device.name ?: result.scanRecord?.deviceName,
                address = result.device.address,
                rssi = result.rssi
            )
            scanResults[discovered.address] = discovered
            _discoveredDevices.value = scanResults.values.toList()
            emitEvent(BleClientConnectionEvent.DeviceDiscovered(discovered))
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            emitEvent(BleClientConnectionEvent.Error("Scan failed with code=$errorCode"))
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                emitEvent(BleClientConnectionEvent.Error("Connection state error status=$status"))
                gatt.close()
                return
            }

            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    emitEvent(BleClientConnectionEvent.Connected(gatt.device.address))
                    gatt.discoverServices()
                }
                BluetoothGatt.STATE_DISCONNECTED -> {
                    emitEvent(BleClientConnectionEvent.Disconnected(gatt.device.address))
                    closeCurrentConnection()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                emitEvent(BleClientConnectionEvent.Error("Service discovery failed status=$status"))
                return
            }

            val service: BluetoothGattService = gatt.getService(BluetoothGattProfile.SERVICE_UUID)
                ?: run {
                    emitEvent(BleClientConnectionEvent.Error("Required BLE service not found"))
                    return
                }

            writeCharacteristic = service.getCharacteristic(BluetoothGattProfile.WRITE_CHARACTERISTIC_UUID)
                ?: run {
                    emitEvent(BleClientConnectionEvent.Error("Write characteristic not found"))
                    return
                }

            notifyCharacteristic = service.getCharacteristic(BluetoothGattProfile.NOTIFY_CHARACTERISTIC_UUID)
                ?: run {
                    emitEvent(BleClientConnectionEvent.Error("Notify characteristic not found"))
                    return
                }

            enableNotifications(gatt, notifyCharacteristic!!)
            startOutgoingWriter()
            emitEvent(BleClientConnectionEvent.ServicesReady(gatt.device.address))
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == BluetoothGattProfile.NOTIFY_CHARACTERISTIC_UUID) {
                bluetoothProvider.publishIncoming(value)
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return
            }
            if (characteristic.uuid == BluetoothGattProfile.NOTIFY_CHARACTERISTIC_UUID) {
                val value = characteristic.value ?: return
                bluetoothProvider.publishIncoming(value)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        val notifySet = gatt.setCharacteristicNotification(characteristic, true)
        if (!notifySet) {
            emitEvent(BleClientConnectionEvent.Error("Failed to enable local notifications"))
            return
        }

        val descriptor = characteristic.getDescriptor(BluetoothGattProfile.CCC_DESCRIPTOR_UUID)
        if (descriptor == null) {
            emitEvent(BleClientConnectionEvent.Error("CCC descriptor not found"))
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val status = gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            if (status != BluetoothGatt.GATT_SUCCESS) {
                emitEvent(BleClientConnectionEvent.Error("Failed to write CCC descriptor, status=$status"))
            }
            return
        }

        @Suppress("DEPRECATION")
        run {
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            val started = gatt.writeDescriptor(descriptor)
            if (!started) {
                emitEvent(BleClientConnectionEvent.Error("Failed to start CCC descriptor write"))
            }
        }
    }

    private fun emitEvent(event: BleClientConnectionEvent) {
        _events.tryEmit(event)
        Log.d(TAG, event.toString())
    }

    private companion object {
        const val TAG = "BleClientConnectionManager"
    }
}
