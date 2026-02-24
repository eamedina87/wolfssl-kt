package tech.medina.wolfssl_kt.ui.client

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tech.medina.wolfssl.kt.WolfSslKt
import tech.medina.wolfssl_kt.bluetooth.BluetoothProvider

class ClientViewModel(
    private val bluetoothProvider: BluetoothProvider = DefaultBluetoothProvider
) : ViewModel() {

    private val _connectionState = MutableStateFlow("Idle")
    val connectionState: StateFlow<String> = _connectionState.asStateFlow()

    private val _deviceName = MutableStateFlow("WolfSSL Device")
    val deviceName: StateFlow<String> = _deviceName.asStateFlow()

    private val _isAdvertising = MutableStateFlow(false)
    val isAdvertising: StateFlow<Boolean> = _isAdvertising.asStateFlow()

    private val _serverConnectionStatus = MutableStateFlow("Waiting for clients")
    val serverConnectionStatus: StateFlow<String> = _serverConnectionStatus.asStateFlow()

    private val _availableServers = MutableStateFlow<List<String>>(emptyList())
    val availableServers: StateFlow<List<String>> = _availableServers.asStateFlow()

    private val _selectedServer = MutableStateFlow<String?>(null)
    val selectedServer: StateFlow<String?> = _selectedServer.asStateFlow()

    private val _clientConnectionStatus = MutableStateFlow("Disconnected")
    val clientConnectionStatus: StateFlow<String> = _clientConnectionStatus.asStateFlow()

    init {
        viewModelScope.launch {
            WolfSslKt.state.collect { state ->
                _connectionState.value = state.toString()
            }
        }
    }

    fun updateDeviceName(newName: String) {
        _deviceName.value = newName
    }

    fun startAdvertising() {
        _isAdvertising.value = true
        _serverConnectionStatus.value = "Advertising as ${_deviceName.value}"
        prepareConnection(mode = WolfSslKt.TlsMode.SERVER)
    }

    fun stopAdvertising() {
        _isAdvertising.value = false
        _serverConnectionStatus.value = "Advertising stopped"
        disconnect()
    }

    fun scanForServers() {
        _availableServers.value = listOf(
            "Wolf Server Alpha",
            "Wolf Server Beta",
            "Wolf Server Gamma"
        )
        _clientConnectionStatus.value = "Scan completed"
    }

    fun selectServer(serverName: String) {
        _selectedServer.value = serverName
        _clientConnectionStatus.value = "Selected $serverName"
    }

    fun connectToSelectedServer() {
        val server = _selectedServer.value ?: return
        _clientConnectionStatus.value = "Connecting to $server"
        prepareConnection(mode = WolfSslKt.TlsMode.CLIENT)
        connect()
    }

    private fun prepareConnection(mode: WolfSslKt.TlsMode) {
        WolfSslKt.prepareTls13Connection(
            cipher = WolfSslKt.SupportedCipher.TLS_CHACHA20_POLY1305_SHA256,
            mode = mode,
            pemPrivateKey = byteArrayOf(),
            caCertificate = byteArrayOf(),
            certificateChain = byteArrayOf(),
            encryptedDataChannel = bluetoothProvider.channel
        )
    }

    private fun connect() {
        WolfSslKt.startConnection().fold(
            onSuccess = {
                _clientConnectionStatus.value = "Connected"
                _serverConnectionStatus.value = "Client connected"
            },
            onFailure = {
                _clientConnectionStatus.value = "Connection failed: ${it.message ?: "Unknown error"}"
                _serverConnectionStatus.value = "Connection failed"
            }
        )
    }

    fun send(data: String) {
        WolfSslKt.send(data.toByteArray())
    }

    val readData: StateFlow<String> = WolfSslKt.read(1000).map {
        it.toString()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    fun disconnect() {
        WolfSslKt.stop()
        _clientConnectionStatus.value = "Disconnected"
        if (!_isAdvertising.value) {
            _serverConnectionStatus.value = "Waiting for clients"
        }
    }
}

private object DefaultBluetoothProvider : BluetoothProvider {
    override val channel = Channel<ByteArray>(Channel.UNLIMITED)
}
