package tech.medina.wolfssl_kt.bluetooth

import android.Manifest
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
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.bluetooth.le.ScanCallback.SCAN_FAILED_ALREADY_STARTED
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

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
    data class OutputCharacteristicValueReceived(val value: ByteArray) : BleClientConnectionEvent()
    data object InputCharacteristicWriteSuccess : BleClientConnectionEvent()
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
    private var isScanning = false
    private val writeMutex = Mutex()
    private val writeAckLock = Any()
    private val descriptorAckLock = Any()
    private var pendingWriteAck: CompletableDeferred<Int>? = null
    private var pendingDescriptorAck: CompletableDeferred<Int>? = null

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN])
    fun startScan() {
        if (isScanning) {
            emitEvent(BleClientConnectionEvent.Error("Scan already running"))
            return
        }

        val bleScanner = scanner
        if (bleScanner == null) {
            emitEvent(BleClientConnectionEvent.Error("BLE scanner not available"))
            return
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanResults.clear()
        _discoveredDevices.value = emptyList()
        bleScanner.startScan(null, settings, scanCallback)
        isScanning = true
        emitEvent(BleClientConnectionEvent.ScanStarted)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun stopScan() {
        if (!isScanning) {
            return
        }
        scanner?.stopScan(scanCallback)
        isScanning = false
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

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun close() {
        closeCurrentConnection()
    }

    fun writeInputCharacteristic(data: ByteArray) {
        scope.launch {
            writeToServerCharacteristic(data)
        }
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
    private suspend fun writeToServerCharacteristic(data: ByteArray) {
        writeMutex.withLock {
            for (packet in BleTransport.chunk(data)) {
                val gatt = currentGatt ?: return
                val characteristic = writeCharacteristic ?: return
                val ack = CompletableDeferred<Int>()
                synchronized(writeAckLock) {
                    pendingWriteAck = ack
                }

                val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(
                        characteristic,
                        packet,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    ) == BluetoothGatt.GATT_SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        characteristic.value = packet
                        gatt.writeCharacteristic(characteristic)
                    }
                }
                if (!started) {
                    synchronized(writeAckLock) {
                        if (pendingWriteAck === ack) {
                            pendingWriteAck = null
                        }
                    }
                    emitEvent(BleClientConnectionEvent.Error("Failed to start characteristic write"))
                    return
                }

                val status = withTimeoutOrNull(GATT_OPERATION_TIMEOUT_MS) { ack.await() }
                synchronized(writeAckLock) {
                    if (pendingWriteAck === ack) {
                        pendingWriteAck = null
                    }
                }

                if (status == null) {
                    emitEvent(BleClientConnectionEvent.Error("Characteristic write timed out"))
                    return
                }

                if (status != BluetoothGatt.GATT_SUCCESS) {
                    emitEvent(BleClientConnectionEvent.CharacteristicWriteFailed(status))
                    return
                }
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun closeCurrentConnection() {
        outgoingWriteJob?.cancel()
        outgoingWriteJob = null
        writeCharacteristic = null
        notifyCharacteristic = null
        currentGatt?.close()
        currentGatt = null
        synchronized(writeAckLock) {
            pendingWriteAck?.complete(BluetoothGatt.GATT_FAILURE)
            pendingWriteAck = null
        }
        synchronized(descriptorAckLock) {
            pendingDescriptorAck?.complete(BluetoothGatt.GATT_FAILURE)
            pendingDescriptorAck = null
        }
    }

    private val scanCallback = object : ScanCallback() {

        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (ENABLE_SCAN_DIAGNOSTICS) {
                Log.d(
                    TAG,
                    "Scan callback: cb=$callbackType addr=${result.device.address} " +
                        "name=${result.device.name} rssi=${result.rssi}"
                )
            }
            val scanRecord = result.scanRecord ?: return
            if (ENABLE_SCAN_DIAGNOSTICS) {
                logScanResult(callbackType, result)
            }
            val manufacturerData = scanRecord.getManufacturerSpecificData(BluetoothGattProfile.DISCOVERY_MANUFACTURER_ID)
            val hasSignature = manufacturerData?.contentEquals(BluetoothGattProfile.DISCOVERY_MANUFACTURER_DATA) == true
            if (!hasSignature) {
                if (ENABLE_SCAN_DIAGNOSTICS) {
                    Log.d(TAG, "Scan reject: missing signature for ${result.device.address}")
                }
                return
            }

            val discovered = DiscoveredBleDevice(
                name = result.device.name ?: scanRecord.deviceName,
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
            isScanning = errorCode == SCAN_FAILED_ALREADY_STARTED
            val message = if (errorCode == SCAN_FAILED_ALREADY_STARTED) {
                "Scan already started"
            } else {
                "Scan failed with code=$errorCode"
            }
            emitEvent(BleClientConnectionEvent.Error(message))
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

            scope.launch {
                val notificationsEnabled = enableNotifications(gatt, notifyCharacteristic!!)
                if (!notificationsEnabled) {
                    return@launch
                }
                startOutgoingWriter()
                emitEvent(BleClientConnectionEvent.ServicesReady(gatt.device.address))
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == BluetoothGattProfile.NOTIFY_CHARACTERISTIC_UUID) {
                bluetoothProvider.publishIncoming(value)
                emitEvent(BleClientConnectionEvent.OutputCharacteristicValueReceived(value.copyOf()))
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
                emitEvent(BleClientConnectionEvent.OutputCharacteristicValueReceived(value.copyOf()))
            }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (characteristic.uuid != BluetoothGattProfile.WRITE_CHARACTERISTIC_UUID) {
                return
            }
            if (status == BluetoothGatt.GATT_SUCCESS) {
                synchronized(writeAckLock) {
                    pendingWriteAck?.complete(status)
                    pendingWriteAck = null
                }
                emitEvent(BleClientConnectionEvent.InputCharacteristicWriteSuccess)
            } else {
                synchronized(writeAckLock) {
                    pendingWriteAck?.complete(status)
                    pendingWriteAck = null
                }
                emitEvent(BleClientConnectionEvent.CharacteristicWriteFailed(status))
            }
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            synchronized(descriptorAckLock) {
                pendingDescriptorAck?.complete(status)
                pendingDescriptorAck = null
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun enableNotifications(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ): Boolean {
        val notifySet = gatt.setCharacteristicNotification(characteristic, true)
        if (!notifySet) {
            emitEvent(BleClientConnectionEvent.Error("Failed to enable local notifications"))
            return false
        }

        val descriptor = characteristic.getDescriptor(BluetoothGattProfile.CCC_DESCRIPTOR_UUID)
        if (descriptor == null) {
            emitEvent(BleClientConnectionEvent.Error("CCC descriptor not found"))
            return false
        }

        val ack = CompletableDeferred<Int>()
        synchronized(descriptorAckLock) {
            pendingDescriptorAck = ack
        }

        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                descriptor.value = BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                gatt.writeDescriptor(descriptor)
            }
        }

        if (!started) {
            synchronized(descriptorAckLock) {
                if (pendingDescriptorAck === ack) {
                    pendingDescriptorAck = null
                }
            }
            emitEvent(BleClientConnectionEvent.Error("Failed to start CCC descriptor write"))
            return false
        }

        val status = withTimeoutOrNull(GATT_OPERATION_TIMEOUT_MS) { ack.await() }
        synchronized(descriptorAckLock) {
            if (pendingDescriptorAck === ack) {
                pendingDescriptorAck = null
            }
        }
        if (status != BluetoothGatt.GATT_SUCCESS) {
            emitEvent(BleClientConnectionEvent.Error("Failed to write CCC descriptor, status=${status ?: "timeout"}"))
            return false
        }
        return true
    }

    private fun emitEvent(event: BleClientConnectionEvent) {
        _events.tryEmit(event)
        Log.d(TAG, event.toString())
    }

    @SuppressLint("MissingPermission")
    private fun logScanResult(callbackType: Int, result: ScanResult) {
        val record = result.scanRecord
        val uuids = record?.serviceUuids?.joinToString(prefix = "[", postfix = "]") { it.toString() } ?: "[]"
        val serviceData = record?.getServiceData(ParcelUuid(BluetoothGattProfile.SERVICE_UUID))
        val manufacturerEntries = buildString {
            if (record == null) {
                append("[]")
                return@buildString
            }
            val data = record.manufacturerSpecificData
            if (data == null || data.size() == 0) {
                append("[]")
                return@buildString
            }
            append("[")
            for (i in 0 until data.size()) {
                if (i > 0) append(", ")
                val id = data.keyAt(i)
                append(id)
                append("=")
                append(data.valueAt(i)?.toHexString() ?: "null")
            }
            append("]")
        }
        Log.d(
            TAG,
            "Scan raw: cb=$callbackType addr=${result.device.address} name=${result.device.name ?: record?.deviceName} " +
                "rssi=${result.rssi} uuids=$uuids serviceData=${serviceData.toHexString()} manufacturer=$manufacturerEntries"
        )
    }

    private fun ByteArray?.toHexString(): String {
        if (this == null) return "null"
        if (isEmpty()) return ""
        return joinToString(separator = " ") { "%02X".format(it) }
    }

    private companion object {
        const val TAG = "BleClientConnectionManager"
        const val ENABLE_SCAN_DIAGNOSTICS = true
        const val GATT_OPERATION_TIMEOUT_MS = 5_000L
    }
}
