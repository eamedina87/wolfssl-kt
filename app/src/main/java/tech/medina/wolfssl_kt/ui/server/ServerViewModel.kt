package tech.medina.wolfssl_kt.ui.server

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tech.medina.wolfssl_kt.bluetooth.BleServerConnectionEvent
import tech.medina.wolfssl_kt.bluetooth.BluetoothLeServerConnectionManager
import tech.medina.wolfssl_kt.bluetooth.GattBluetoothProvider

class ServerViewModel(application: Application) : AndroidViewModel(application) {

    private val bluetoothProvider = GattBluetoothProvider()
    private val serverManager = BluetoothLeServerConnectionManager(application, bluetoothProvider)

    private val _connectionState = MutableStateFlow("Idle")
    val connectionState: StateFlow<String> = _connectionState.asStateFlow()

    private val _deviceName = MutableStateFlow("WolfSSL Device")
    val deviceName: StateFlow<String> = _deviceName.asStateFlow()

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    private val _serverConnectionStatus = MutableStateFlow("Waiting for clients")
    val serverConnectionStatus: StateFlow<String> = _serverConnectionStatus.asStateFlow()

    private val _serverInputCharacteristicValue = MutableStateFlow("No input characteristic value")
    val serverInputCharacteristicValue: StateFlow<String> = _serverInputCharacteristicValue.asStateFlow()

    private val _serverWriteStatus = MutableStateFlow("Idle")
    val serverWriteStatus: StateFlow<String> = _serverWriteStatus.asStateFlow()

    init {
        observeServerEvents()
    }

    fun updateDeviceName(newName: String) {
        _deviceName.value = newName
    }

    fun startAdvertising() {
        _isAdvertising.value = true
        _serverConnectionStatus.value = "Starting BLE server as ${_deviceName.value}"
        _connectionState.value = "Server starting"
        serverManager.startServer()
        serverManager.startAdvertising(_deviceName.value)
    }

    fun stopAdvertising() {
        _isAdvertising.value = false
        _serverConnectionStatus.value = "Advertising stopped"
        _connectionState.value = "Server stopped"
        serverManager.stopServer()
    }

    fun sendServerOutputCharacteristic(text: String) {
        if (text.isBlank()) {
            _serverWriteStatus.value = "Input is empty"
            return
        }
        _serverWriteStatus.value = "Writing to output characteristic..."
        serverManager.writeOutputCharacteristic(text.encodeToByteArray())
    }

    override fun onCleared() {
        super.onCleared()
        serverManager.stopServer()
    }

    private fun observeServerEvents() {
        viewModelScope.launch {
            serverManager.events.collect { event ->
                when (event) {
                    BleServerConnectionEvent.ServerStarted -> {
                        _serverConnectionStatus.value = "Server started"
                        _connectionState.value = "Server ready"
                    }
                    BleServerConnectionEvent.ServerStopped -> {
                        _serverConnectionStatus.value = "Server stopped"
                    }
                    BleServerConnectionEvent.AdvertisingStarted -> {
                        _serverConnectionStatus.value = "Advertising started"
                        _connectionState.value = "Server advertising"
                    }
                    BleServerConnectionEvent.AdvertisingStopped -> {
                        _serverConnectionStatus.value = "Advertising stopped"
                    }
                    is BleServerConnectionEvent.DeviceConnected -> {
                        _serverConnectionStatus.value = "Client connected: ${event.address}"
                    }
                    is BleServerConnectionEvent.DeviceDisconnected -> {
                        _serverConnectionStatus.value = "Client disconnected: ${event.address}"
                    }
                    is BleServerConnectionEvent.InputCharacteristicValueReceived -> {
                        _serverInputCharacteristicValue.value = toDisplay(event.value)
                    }
                    is BleServerConnectionEvent.OutputCharacteristicWriteSuccess -> {
                        _serverWriteStatus.value = "Output write success to ${event.address}"
                    }
                    is BleServerConnectionEvent.OutputCharacteristicWriteFailed -> {
                        _serverWriteStatus.value = "Output write failed to ${event.address} (status=${event.status})"
                    }
                    is BleServerConnectionEvent.Error -> {
                        _serverConnectionStatus.value = event.message
                        _serverWriteStatus.value = event.message
                    }
                }
            }
        }
    }

    private fun toDisplay(value: ByteArray): String {
        val text = value.decodeToString()
        val hex = value.joinToString(" ") { b -> "%02X".format(b) }
        return "$text (hex: $hex)"
    }
}
