package tech.medina.wolfssl_kt.ui.client

import android.Manifest
import android.app.Application
import androidx.annotation.RequiresPermission
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tech.medina.wolfssl_kt.bluetooth.BleClientConnectionEvent
import tech.medina.wolfssl_kt.bluetooth.BluetoothLeClientConnectionManager
import tech.medina.wolfssl_kt.bluetooth.GattBluetoothProvider

class ClientViewModel(application: Application) : AndroidViewModel(application) {

    private val bluetoothProvider = GattBluetoothProvider()
    private val clientManager = BluetoothLeClientConnectionManager(application, bluetoothProvider)

    private val serverAddressByLabel = linkedMapOf<String, String>()

    private val _connectionState = MutableStateFlow("Idle")
    val connectionState: StateFlow<String> = _connectionState.asStateFlow()

    private val _availableServers = MutableStateFlow<List<String>>(emptyList())
    val availableServers: StateFlow<List<String>> = _availableServers.asStateFlow()

    private val _selectedServer = MutableStateFlow<String?>(null)
    val selectedServer: StateFlow<String?> = _selectedServer.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _clientConnectionStatus = MutableStateFlow("Disconnected")
    val clientConnectionStatus: StateFlow<String> = _clientConnectionStatus.asStateFlow()

    private val _clientOutputCharacteristicValue = MutableStateFlow("No output characteristic value")
    val clientOutputCharacteristicValue: StateFlow<String> = _clientOutputCharacteristicValue.asStateFlow()

    private val _clientWriteStatus = MutableStateFlow("Idle")
    val clientWriteStatus: StateFlow<String> = _clientWriteStatus.asStateFlow()

    init {
        observeDiscoveredDevices()
        observeClientEvents()
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT])
    fun scanForServers() {
        if (_isScanning.value) {
            _clientConnectionStatus.value = "Scan already running"
            return
        }
        _clientConnectionStatus.value = "Scanning..."
        _connectionState.value = "Client scanning"
        clientManager.startScan()
    }

    fun onScanPermissionDenied() {
        _clientConnectionStatus.value = "Bluetooth scan permission is required"
        _connectionState.value = "Client permission denied"
    }

    fun selectServer(serverName: String) {
        _selectedServer.value = serverName
        _clientConnectionStatus.value = "Selected $serverName"
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun connectToSelectedServer() {
        val selected = _selectedServer.value ?: return
        val address = serverAddressByLabel[selected] ?: selected
        _clientConnectionStatus.value = "Connecting to $selected"
        _connectionState.value = "Client connecting"
        _isScanning.value = false
        clientManager.stopScan()
        clientManager.connect(address)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_SCAN)
    fun stopScanning() {
        if (!_isScanning.value) {
            return
        }
        _isScanning.value = false
        _clientConnectionStatus.value = "Stopping scan..."
        _connectionState.value = "Client scan stopped"
        clientManager.stopScan()
    }

    fun sendClientInputCharacteristic(text: String) {
        if (text.isBlank()) {
            _clientWriteStatus.value = "Input is empty"
            return
        }
        _clientWriteStatus.value = "Writing to input characteristic..."
        clientManager.writeInputCharacteristic(text.encodeToByteArray())
    }

    fun disconnect() {
        clientManager.disconnect()
        _isScanning.value = false
        _clientConnectionStatus.value = "Disconnected"
        _connectionState.value = "Client disconnected"
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onCleared() {
        super.onCleared()
        clientManager.close()
    }

    private fun observeDiscoveredDevices() {
        viewModelScope.launch {
            clientManager.discoveredDevices.collect { devices ->
                serverAddressByLabel.clear()
                val labels = devices.map { device ->
                    val label = if (device.name.isNullOrBlank()) {
                        device.address
                    } else {
                        "${device.name} (${device.address})"
                    }
                    serverAddressByLabel[label] = device.address
                    label
                }
                _availableServers.value = labels
            }
        }
    }

    private fun observeClientEvents() {
        viewModelScope.launch {
            clientManager.events.collect { event ->
                when (event) {
                    BleClientConnectionEvent.ScanStarted -> {
                        _isScanning.value = true
                        _clientConnectionStatus.value = "Scan started"
                    }
                    BleClientConnectionEvent.ScanStopped -> {
                        _isScanning.value = false
                        _clientConnectionStatus.value = "Scan stopped"
                    }
                    is BleClientConnectionEvent.DeviceDiscovered -> {
                        _clientConnectionStatus.value = "Found ${event.device.address}"
                    }
                    is BleClientConnectionEvent.Connected -> {
                        _clientConnectionStatus.value = "Connected to ${event.address}"
                        _connectionState.value = "Client connected"
                    }
                    is BleClientConnectionEvent.Disconnected -> {
                        _clientConnectionStatus.value = "Disconnected from ${event.address}"
                        _connectionState.value = "Client disconnected"
                    }
                    is BleClientConnectionEvent.ServicesReady -> {
                        _clientConnectionStatus.value = "Services ready on ${event.address}"
                        _connectionState.value = "Client ready"
                    }
                    is BleClientConnectionEvent.OutputCharacteristicValueReceived -> {
                        _clientOutputCharacteristicValue.value = toDisplay(event.value)
                    }
                    BleClientConnectionEvent.InputCharacteristicWriteSuccess -> {
                        _clientWriteStatus.value = "Input characteristic write success"
                    }
                    is BleClientConnectionEvent.CharacteristicWriteFailed -> {
                        _clientWriteStatus.value = "Input characteristic write failed (status=${event.status})"
                    }
                    is BleClientConnectionEvent.Error -> {
                        if (event.message.contains("Scan already", ignoreCase = true)) {
                            _isScanning.value = true
                        }
                        _clientConnectionStatus.value = event.message
                        _clientWriteStatus.value = event.message
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
