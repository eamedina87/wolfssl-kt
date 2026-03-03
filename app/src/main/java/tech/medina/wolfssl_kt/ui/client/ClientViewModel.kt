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
import tech.medina.wolfssl.kt.WolfSslKt
import tech.medina.wolfssl_kt.bluetooth.BleClientConnectionEvent
import tech.medina.wolfssl_kt.bluetooth.BluetoothLeClientConnectionManager
import tech.medina.wolfssl_kt.bluetooth.GattBluetoothProvider
import tech.medina.wolfssl_kt.tls.TlsMaterialProvider

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
    private val _hasActiveConnection = MutableStateFlow(false)
    val hasActiveConnection: StateFlow<Boolean> = _hasActiveConnection.asStateFlow()
    private val _tlsStatus = MutableStateFlow("TLS idle")
    val tlsStatus: StateFlow<String> = _tlsStatus.asStateFlow()
    private var isTlsPrepared = false

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
        _hasActiveConnection.value = false
        isTlsPrepared = false
        _tlsStatus.value = "TLS idle"
        WolfSslKt.clear()
    }

    fun launchTlsConnection() {
        if (!_hasActiveConnection.value) {
            _tlsStatus.value = "Connect BLE first"
            return
        }
        if (!isTlsPrepared) {
            _tlsStatus.value = "TLS not prepared yet"
            return
        }
        _tlsStatus.value = "Launching TLS handshake..."
        val result = WolfSslKt.startConnection()
        _tlsStatus.value = result.fold(
            onSuccess = { "TLS connected" },
            onFailure = { "TLS connect failed: ${it.message ?: "Unknown error"}" }
        )
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onCleared() {
        super.onCleared()
        clientManager.close()
        WolfSslKt.clear()
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
                        _hasActiveConnection.value = true
                    }
                    is BleClientConnectionEvent.Disconnected -> {
                        _clientConnectionStatus.value = "Disconnected from ${event.address}"
                        _connectionState.value = "Client disconnected"
                        _hasActiveConnection.value = false
                        isTlsPrepared = false
                        _tlsStatus.value = "TLS idle"
                    }
                    is BleClientConnectionEvent.ServicesReady -> {
                        _clientConnectionStatus.value = "Services ready on ${event.address}"
                        _connectionState.value = "Client ready"
                        prepareTlsConnection()
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

    private fun prepareTlsConnection() {
        val appContext = getApplication<Application>()
        val materialsResult = TlsMaterialProvider.loadForRole(
            appContext,
            TlsMaterialProvider.EndpointRole.CLIENT
        )
        if (materialsResult.isFailure) {
            _tlsStatus.value = "TLS prepare failed: ${materialsResult.exceptionOrNull()?.message ?: "Could not load TLS material"}"
            isTlsPrepared = false
            return
        }
        WolfSslKt.clear()
        val materials = materialsResult.getOrThrow()
        val prepareResult = WolfSslKt.prepareTls13Connection(
            cipher = WolfSslKt.SupportedCipher.TLS_CHACHA20_POLY1305_SHA256,
            mode = WolfSslKt.TlsMode.CLIENT,
            pemPrivateKey = materials.privateKey,
            caCertificate = materials.caCertificate,
            certificateChain = materials.certificateChain,
            incomingEncryptedDataChannel = bluetoothProvider.incomingChannel,
            outgoingEncryptedDataChannel = bluetoothProvider.outgoingChannel
        )
        isTlsPrepared = prepareResult.isSuccess
        _tlsStatus.value = prepareResult.fold(
            onSuccess = { "TLS prepared (client). Tap Launch TLS." },
            onFailure = { "TLS prepare failed: ${it.message ?: "Unknown error"}" }
        )
    }
}
