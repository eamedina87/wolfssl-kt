package tech.medina.wolfssl_kt.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

sealed class BleServerConnectionEvent {
    data object ServerStarted : BleServerConnectionEvent()
    data object ServerStopped : BleServerConnectionEvent()
    data object AdvertisingStarted : BleServerConnectionEvent()
    data object AdvertisingStopped : BleServerConnectionEvent()
    data class DeviceConnected(val address: String) : BleServerConnectionEvent()
    data class DeviceDisconnected(val address: String) : BleServerConnectionEvent()
    data class InputCharacteristicValueReceived(val value: ByteArray) : BleServerConnectionEvent()
    data class OutputCharacteristicWriteSuccess(val address: String) : BleServerConnectionEvent()
    data class OutputCharacteristicWriteFailed(val address: String, val status: Int) : BleServerConnectionEvent()
    data class Error(val message: String) : BleServerConnectionEvent()
}

class BluetoothLeServerConnectionManager(
    context: Context,
    private val bluetoothProvider: BluetoothProvider,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val appContext = context.applicationContext
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter
    private val advertiser: BluetoothLeAdvertiser?
        get() = bluetoothAdapter?.bluetoothLeAdvertiser

    private val _events = MutableSharedFlow<BleServerConnectionEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<BleServerConnectionEvent> = _events.asSharedFlow()

    private var gattServer: BluetoothGattServer? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null
    private val connectedDevices = LinkedHashSet<BluetoothDevice>()
    private val subscribedDevices = LinkedHashSet<BluetoothDevice>()
    private var outgoingNotifyJob: Job? = null
    private var isAdvertising = false
    private val notifyMutex = Mutex()
    private val notifyAckLock = Any()
    private var pendingNotifyAck: CompletableDeferred<Int>? = null

    @SuppressLint("MissingPermission")
    fun startServer() {
        val manager = bluetoothManager
        if (manager == null) {
            emitEvent(BleServerConnectionEvent.Error("Bluetooth manager unavailable"))
            return
        }

        stopServer()

        val server = manager.openGattServer(appContext, gattServerCallback)
        if (server == null) {
            emitEvent(BleServerConnectionEvent.Error("Failed to open GATT server"))
            return
        }

        gattServer = server
        val service = buildService()
        notifyCharacteristic = service.getCharacteristic(BluetoothGattProfile.NOTIFY_CHARACTERISTIC_UUID)
        val added = server.addService(service)
        if (!added) {
            emitEvent(BleServerConnectionEvent.Error("Failed to add GATT service"))
            return
        }

        startOutgoingNotifier()
        emitEvent(BleServerConnectionEvent.ServerStarted)
    }

    @SuppressLint("MissingPermission")
    fun stopServer() {
        stopAdvertising()
        outgoingNotifyJob?.cancel()
        outgoingNotifyJob = null
        connectedDevices.clear()
        subscribedDevices.clear()
        notifyCharacteristic = null
        gattServer?.close()
        gattServer = null
        synchronized(notifyAckLock) {
            pendingNotifyAck?.complete(BluetoothGatt.GATT_FAILURE)
            pendingNotifyAck = null
        }
        emitEvent(BleServerConnectionEvent.ServerStopped)
    }

    @SuppressLint("MissingPermission")
    fun startAdvertising() {
        val bleAdvertiser = advertiser
        if (bleAdvertiser == null) {
            emitEvent(BleServerConnectionEvent.Error("BLE advertiser not available"))
            return
        }

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addManufacturerData(
                BluetoothGattProfile.DISCOVERY_MANUFACTURER_ID,
                BluetoothGattProfile.DISCOVERY_MANUFACTURER_DATA
            )
            .build()

        bleAdvertiser.startAdvertising(settings, data, advertiseCallback)
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        if (!isAdvertising) {
            return
        }
        advertiser?.stopAdvertising(advertiseCallback)
        isAdvertising = false
        emitEvent(BleServerConnectionEvent.AdvertisingStopped)
    }

    fun writeOutputCharacteristic(data: ByteArray) {
        scope.launch {
            notifyConnectedDevices(data)
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnectConnectedClients() {
        val server = gattServer ?: return
        connectedDevices.toList().forEach { device ->
            server.cancelConnection(device)
        }
    }

    private fun buildService(): BluetoothGattService {
        val service = BluetoothGattService(
            BluetoothGattProfile.SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        val writeCharacteristic = BluetoothGattCharacteristic(
            BluetoothGattProfile.WRITE_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )

        val notify = BluetoothGattCharacteristic(
            BluetoothGattProfile.NOTIFY_CHARACTERISTIC_UUID,
            BluetoothGattCharacteristic.PROPERTY_INDICATE,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        val cccDescriptor = BluetoothGattDescriptor(
            BluetoothGattProfile.CCC_DESCRIPTOR_UUID,
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        notify.addDescriptor(cccDescriptor)

        service.addCharacteristic(writeCharacteristic)
        service.addCharacteristic(notify)
        return service
    }

    private fun startOutgoingNotifier() {
        outgoingNotifyJob?.cancel()
        outgoingNotifyJob = scope.launch {
            for (data in bluetoothProvider.outgoingChannel) {
                notifyConnectedDevices(data)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun notifyConnectedDevices(data: ByteArray) {
        notifyMutex.withLock {
            for (packet in BleTransport.chunk(data)) {
                val device = waitForSubscribedDevice() ?: run {
                    emitEvent(BleServerConnectionEvent.Error("No subscribed client available for output characteristic write"))
                    return
                }
                val server = gattServer ?: return
                val characteristic = notifyCharacteristic ?: return
                val ack = CompletableDeferred<Int>()
                synchronized(notifyAckLock) {
                    pendingNotifyAck = ack
                }

                val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    server.notifyCharacteristicChanged(device, characteristic, true, packet) == BluetoothGatt.GATT_SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    run {
                        characteristic.value = packet
                        server.notifyCharacteristicChanged(device, characteristic, true)
                    }
                }
                if (!started) {
                    synchronized(notifyAckLock) {
                        if (pendingNotifyAck === ack) {
                            pendingNotifyAck = null
                        }
                    }
                    emitEvent(BleServerConnectionEvent.Error("Notify start failed for ${device.address}"))
                    return
                }

                val status = withTimeoutOrNull(GATT_OPERATION_TIMEOUT_MS) { ack.await() }
                synchronized(notifyAckLock) {
                    if (pendingNotifyAck === ack) {
                        pendingNotifyAck = null
                    }
                }
                if (status == null) {
                    emitEvent(BleServerConnectionEvent.Error("Indication timed out for ${device.address}"))
                    return
                }
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    emitEvent(BleServerConnectionEvent.OutputCharacteristicWriteFailed(device.address, status))
                    return
                }
            }
        }
    }

    private suspend fun waitForSubscribedDevice(): BluetoothDevice? {
        repeat(DEVICE_WAIT_RETRIES) {
            subscribedDevices.firstOrNull()?.let { return it }
            if (connectedDevices.isEmpty()) {
                return null
            }
            delay(DEVICE_WAIT_INTERVAL_MS)
        }
        return subscribedDevices.firstOrNull()
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            isAdvertising = true
            emitEvent(BleServerConnectionEvent.AdvertisingStarted)
        }

        override fun onStartFailure(errorCode: Int) {
            isAdvertising = false
            emitEvent(BleServerConnectionEvent.Error("Advertising failed with code=$errorCode"))
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                emitEvent(BleServerConnectionEvent.Error("Connection state error status=$status"))
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (isAdvertising) {
                        stopAdvertising()
                    }
                    connectedDevices += device
                    emitEvent(BleServerConnectionEvent.DeviceConnected(device.address))
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectedDevices -= device
                    subscribedDevices -= device
                    emitEvent(BleServerConnectionEvent.DeviceDisconnected(device.address))
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (characteristic.uuid == BluetoothGattProfile.WRITE_CHARACTERISTIC_UUID) {
                bluetoothProvider.publishIncoming(value)
                emitEvent(BleServerConnectionEvent.InputCharacteristicValueReceived(value.copyOf()))
            }

            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (descriptor.uuid == BluetoothGattProfile.CCC_DESCRIPTOR_UUID) {
                when {
                    value.contentEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE) -> {
                        subscribedDevices += device
                    }
                    value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE) -> {
                        subscribedDevices -= device
                    }
                }
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            synchronized(notifyAckLock) {
                pendingNotifyAck?.complete(status)
                pendingNotifyAck = null
            }
            if (status == BluetoothGatt.GATT_SUCCESS) {
                emitEvent(BleServerConnectionEvent.OutputCharacteristicWriteSuccess(device.address))
            } else {
                emitEvent(BleServerConnectionEvent.OutputCharacteristicWriteFailed(device.address, status))
            }
        }
    }

    private fun emitEvent(event: BleServerConnectionEvent) {
        _events.tryEmit(event)
        Log.d(TAG, event.toString())
    }

    private companion object {
        const val TAG = "BleServerConnectionManager"
        const val GATT_OPERATION_TIMEOUT_MS = 5_000L
        const val DEVICE_WAIT_INTERVAL_MS = 50L
        const val DEVICE_WAIT_RETRIES = 100
    }
}
