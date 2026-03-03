package tech.medina.wolfssl_kt.ui.server

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tech.medina.wolfssl.kt.WolfSslKt
import tech.medina.wolfssl_kt.bluetooth.BleServerConnectionEvent
import tech.medina.wolfssl_kt.bluetooth.BluetoothGattProfile
import tech.medina.wolfssl_kt.bluetooth.BluetoothLeServerConnectionManager
import tech.medina.wolfssl_kt.bluetooth.GattBluetoothProvider
import tech.medina.wolfssl_kt.tls.TlsMaterialProvider

class ServerViewModel(application: Application) : AndroidViewModel(application) {

    private val bluetoothProvider = GattBluetoothProvider()
    private val serverManager = BluetoothLeServerConnectionManager(application, bluetoothProvider)

    private val _connectionState = MutableStateFlow("Idle")
    val connectionState: StateFlow<String> = _connectionState.asStateFlow()

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    private val _hasActiveConnection = MutableStateFlow(false)
    val hasActiveConnection: StateFlow<Boolean> = _hasActiveConnection.asStateFlow()

    private val _serverConnectionStatus = MutableStateFlow("Waiting for clients")
    val serverConnectionStatus: StateFlow<String> = _serverConnectionStatus.asStateFlow()

    private val _serverInputCharacteristicValue = MutableStateFlow("No input characteristic value")
    val serverInputCharacteristicValue: StateFlow<String> = _serverInputCharacteristicValue.asStateFlow()

    private val _serverWriteStatus = MutableStateFlow("Idle")
    val serverWriteStatus: StateFlow<String> = _serverWriteStatus.asStateFlow()
    private val _tlsStatus = MutableStateFlow("TLS idle")
    val tlsStatus: StateFlow<String> = _tlsStatus.asStateFlow()
    private val connectedClientAddresses = linkedSetOf<String>()
    private var isTlsPrepared = false

    init {
        observeServerEvents()
    }

    fun onAdvertisingPermissionDenied() {
        _serverConnectionStatus.value = "Bluetooth advertise permission is required"
        _connectionState.value = "Server permission denied"
    }

    fun startAdvertising() {
        _serverConnectionStatus.value = "Starting BLE server"
        _connectionState.value = "Server starting"
        serverManager.startServer()
        serverManager.startAdvertising()
    }

    fun stopAdvertising() {
        _isAdvertising.value = false
        _serverConnectionStatus.value = "Advertising stopped"
        _connectionState.value = "Server stopped"
        serverManager.stopServer()
    }

    fun disconnect() {
        serverManager.disconnectConnectedClients()
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
        WolfSslKt.clear()
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
                        connectedClientAddresses.clear()
                        _hasActiveConnection.value = false
                        _isAdvertising.value = false
                        isTlsPrepared = false
                        _tlsStatus.value = "TLS idle"
                        _serverConnectionStatus.value = "Server stopped"
                    }
                    BleServerConnectionEvent.AdvertisingStarted -> {
                        _isAdvertising.value = true
                        _serverConnectionStatus.value = "Advertising started"
                        _connectionState.value = "Server advertising"
                    }
                    BleServerConnectionEvent.AdvertisingStopped -> {
                        _isAdvertising.value = false
                        _serverConnectionStatus.value = "Advertising stopped"
                    }
                    is BleServerConnectionEvent.DeviceConnected -> {
                        connectedClientAddresses += event.address
                        _hasActiveConnection.value = connectedClientAddresses.isNotEmpty()
                        _serverConnectionStatus.value = "Client connected: ${event.address}"
                        _connectionState.value = "Client connected"
                        prepareTlsConnection()
                    }
                    is BleServerConnectionEvent.DeviceDisconnected -> {
                        connectedClientAddresses -= event.address
                        _hasActiveConnection.value = connectedClientAddresses.isNotEmpty()
                        _serverConnectionStatus.value = "Client disconnected: ${event.address}"
                        _connectionState.value = "Client disconnected"
                        if (!_hasActiveConnection.value) {
                            isTlsPrepared = false
                            _tlsStatus.value = "TLS idle"
                            WolfSslKt.clear()
                        }
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

    private fun prepareTlsConnection() {
        val appContext = getApplication<Application>()
        val materialsResult = TlsMaterialProvider.loadForRole(
            appContext,
            TlsMaterialProvider.EndpointRole.SERVER
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
            mode = WolfSslKt.TlsMode.SERVER,
            pemPrivateKey = materials.privateKey,
            caCertificate = materials.caCertificate,
            certificateChain = materials.certificateChain,
            incomingEncryptedDataChannel = bluetoothProvider.incomingChannel,
            outgoingEncryptedDataChannel = bluetoothProvider.outgoingChannel
        )
        isTlsPrepared = prepareResult.isSuccess
        _tlsStatus.value = prepareResult.fold(
            onSuccess = { "TLS prepared (server). Tap Launch TLS." },
            onFailure = { "TLS prepare failed: ${it.message ?: "Unknown error"}" }
        )
    }
}
