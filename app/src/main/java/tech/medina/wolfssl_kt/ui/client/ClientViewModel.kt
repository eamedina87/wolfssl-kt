package tech.medina.wolfssl_kt.ui.client

import android.Manifest
import android.app.Application
import android.net.Uri
import android.os.SystemClock
import android.provider.OpenableColumns
import androidx.annotation.RequiresPermission
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import tech.medina.wolfssl.kt.PKIData
import tech.medina.wolfssl.kt.WolfSSLKt
import tech.medina.wolfssl.kt.WolfSSLKtSendCallback
import tech.medina.wolfssl_kt.bluetooth.BleClientConnectionEvent
import tech.medina.wolfssl_kt.bluetooth.BluetoothLeClientConnectionManager
import tech.medina.wolfssl_kt.bluetooth.GattBluetoothProvider
import tech.medina.wolfssl_kt.tls.TlsMaterialProvider
import tech.medina.wolfssl_kt.transfer.FileTransferProtocol
import tech.medina.wolfssl_kt.transfer.FileTransferStrategy
import java.io.FileNotFoundException
import java.security.MessageDigest

data class FileTransferMetrics(
    val strategy: FileTransferStrategy,
    val bytesTransferred: Long,
    val packetsSent: Long,
    val transmitMillis: Long,
    val totalMillis: Long,
    val sha256: String,
) {
    val verificationMillis: Long
        get() = (totalMillis - transmitMillis).coerceAtLeast(0)

    val clientCompletionMillis: Long
        get() = if (strategy == FileTransferStrategy.TX_ONLY) transmitMillis else totalMillis
}

private data class SelectedBinaryFile(
    val uri: Uri,
    val name: String,
    val size: Long,
)

class ClientViewModel(application: Application) : AndroidViewModel(application) {

    private val bluetoothProvider = GattBluetoothProvider()
    private val clientManager = BluetoothLeClientConnectionManager(application, bluetoothProvider)

    private val serverAddressByLabel = linkedMapOf<String, String>()
    private val sessionTicketByServerAddress = mutableMapOf<String, ByteArray>()
    private var connectedServerAddress: String? = null

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

    private val _clientOutputCharacteristicValue = MutableStateFlow("No TLS payload received")
    val clientOutputCharacteristicValue: StateFlow<String> = _clientOutputCharacteristicValue.asStateFlow()

    private val _clientWriteStatus = MutableStateFlow("Idle")
    val clientWriteStatus: StateFlow<String> = _clientWriteStatus.asStateFlow()
    private val _selectedFileDescription = MutableStateFlow("No binary file selected")
    val selectedFileDescription: StateFlow<String> = _selectedFileDescription.asStateFlow()
    private val _fileTransferStatus = MutableStateFlow("Idle")
    val fileTransferStatus: StateFlow<String> = _fileTransferStatus.asStateFlow()
    private val _fileTransferProgress = MutableStateFlow("No transfer in progress")
    val fileTransferProgress: StateFlow<String> = _fileTransferProgress.asStateFlow()
    private val _fileTransferMetrics = MutableStateFlow<FileTransferMetrics?>(null)
    val fileTransferMetrics: StateFlow<FileTransferMetrics?> = _fileTransferMetrics.asStateFlow()
    private val _fileTransferStrategy = MutableStateFlow(FileTransferStrategy.VERIFIED_STREAM)
    val fileTransferStrategy: StateFlow<FileTransferStrategy> = _fileTransferStrategy.asStateFlow()
    private val _isFileTransferring = MutableStateFlow(false)
    val isFileTransferring: StateFlow<Boolean> = _isFileTransferring.asStateFlow()
    val maximumBlePacketSize: StateFlow<Int> = clientManager.maximumWritePayloadSize
    private val _hasActiveConnection = MutableStateFlow(false)
    val hasActiveConnection: StateFlow<Boolean> = _hasActiveConnection.asStateFlow()
    private val _tlsStatus = MutableStateFlow("TLS idle")
    val tlsStatus: StateFlow<String> = _tlsStatus.asStateFlow()
    private val _isTlsConnected = MutableStateFlow(false)
    val isTlsConnected: StateFlow<Boolean> = _isTlsConnected.asStateFlow()
    private var isTlsPrepared = false
    private var isTlsLaunching = false
    private var tlsReadJob: Job? = null
    private var selectedBinaryFile: SelectedBinaryFile? = null
    private val fileAckLock = Any()
    private var pendingFileAck: CompletableDeferred<FileTransferProtocol.ControlMessage.Ack>? = null
    private var pendingPacketAck: CompletableDeferred<FileTransferProtocol.ControlMessage.PacketAck>? = null
    private var fileAckBuffer = ByteArray(0)
    private var progressInitialAcknowledgedPackets: Long? = null
    private var progressInitialQueuedPackets = 0L
    private var progressExpectedPackets: Long? = null
    private var progressProcessedBytes = 0L
    private var progressTotalBytes = 0L

    init {
        observeTlsState()
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
        if (!_tlsStatus.value.startsWith("TLS connected")) {
            _clientWriteStatus.value = "TLS is not connected"
            return
        }
        _clientWriteStatus.value = "Sending TLS payload..."
        viewModelScope.launch(Dispatchers.IO) {
            val result = WolfSSLKt.send(text.encodeToByteArray())
            _clientWriteStatus.value = result.fold(
                onSuccess = { "TLS payload sent" },
                onFailure = { "TLS send failed: ${it.message ?: "Unknown error"}" }
            )
        }
    }

    fun selectBinaryFile(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val resolver = getApplication<Application>().contentResolver
                val metadata = resolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                    val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
                    name to size
                }
                val name = metadata?.first?.takeIf(String::isNotBlank)
                    ?: uri.lastPathSegment?.substringAfterLast('/')
                    ?: "transfer.bin"
                val size = metadata?.second?.takeIf { it >= 0 }
                    ?: resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
                    ?: -1L
                val mimeType = resolver.getType(uri)
                require(mimeType?.startsWith("text/") != true) { "Please select a binary file" }
                require(size >= 0L) { "The selected provider did not report the file size" }
                runCatching {
                    resolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                SelectedBinaryFile(uri, name, size)
            }.fold(
                onSuccess = { file ->
                    selectedBinaryFile = file
                    _selectedFileDescription.value = "${file.name} (${formatBytes(file.size)})"
                    _fileTransferStatus.value = "Ready to transfer"
                    _fileTransferMetrics.value = null
                },
                onFailure = { error ->
                    selectedBinaryFile = null
                    _selectedFileDescription.value = "No binary file selected"
                    _fileTransferStatus.value = "File selection failed: ${error.message ?: "Unknown error"}"
                },
            )
        }
    }

    fun selectFileTransferStrategy(strategy: FileTransferStrategy) {
        if (!_isFileTransferring.value) {
            _fileTransferStrategy.value = strategy
            _fileTransferStatus.value = "${strategy.displayName} selected"
        }
    }

    fun sendSelectedFile() {
        val file = selectedBinaryFile
        if (file == null) {
            _fileTransferStatus.value = "Select a binary file first"
            return
        }
        if (!_isTlsConnected.value) {
            _fileTransferStatus.value = "TLS is not connected"
            return
        }
        if (_isFileTransferring.value) return

        _isFileTransferring.value = true
        _fileTransferMetrics.value = null
        viewModelScope.launch(Dispatchers.IO) {
            val strategy = _fileTransferStrategy.value
            clientManager.requestHighThroughputConnection()
            val startedAt = SystemClock.elapsedRealtimeNanos()
            val initialPacketCount = clientManager.sentPacketCount()
            val initialQueuedPacketCount = clientManager.queuedBlePacketCount()
            val firstEncryptedWrite = clientManager.lastQueuedEncryptedWrite()
            val digest = MessageDigest.getInstance("SHA-256")
            var bytesSent = 0L
            var clientTxSucceeded = false
            val ack = CompletableDeferred<FileTransferProtocol.ControlMessage.Ack>()
            synchronized(fileAckLock) {
                fileAckBuffer = ByteArray(0)
                pendingFileAck = ack
            }
            progressInitialAcknowledgedPackets = initialPacketCount
            progressInitialQueuedPackets = initialQueuedPacketCount
            progressExpectedPackets = null
            progressProcessedBytes = 0
            progressTotalBytes = file.size
            updateFileTransferProgress()
            try {
                val packetSize = maximumBlePacketSize.value
                val chunkSize = FileTransferProtocol.fileChunkSize(packetSize, strategy)
                _fileTransferStatus.value = "Starting ${strategy.displayName} (${chunkSize}-byte file chunks)"
                WolfSSLKt.send(FileTransferProtocol.encodeStart(file.name, file.size, strategy)).getOrThrow()

                val input = getApplication<Application>().contentResolver.openInputStream(file.uri)
                    ?: throw FileNotFoundException("Could not open ${file.name}")
                input.use { stream ->
                    val buffer = ByteArray(chunkSize)
                    var sequence = 0
                    while (bytesSent < file.size) {
                        val requested = minOf(buffer.size.toLong(), file.size - bytesSent).toInt()
                        val count = stream.read(buffer, 0, requested)
                        if (count < 0) throw IllegalStateException("File ended after $bytesSent of ${file.size} bytes")
                        if (count == 0) continue
                        val chunk = buffer.copyOf(count)
                        digest.update(chunk)
                        if (strategy == FileTransferStrategy.STOP_AND_WAIT) {
                            val packetAck = CompletableDeferred<FileTransferProtocol.ControlMessage.PacketAck>()
                            synchronized(fileAckLock) {
                                pendingPacketAck = packetAck
                            }
                            WolfSSLKt.send(FileTransferProtocol.encodeData(sequence, chunk)).getOrThrow()
                            val response = withTimeout(PACKET_ACK_TIMEOUT_MS) { packetAck.await() }
                            synchronized(fileAckLock) {
                                if (pendingPacketAck === packetAck) pendingPacketAck = null
                            }
                            check(response.sequence == sequence) {
                                "Expected ACK for packet $sequence but received ${response.sequence}"
                            }
                            check(response.success) {
                                response.message.ifBlank { "Server rejected packet $sequence" }
                            }
                            sequence++
                        } else {
                            WolfSSLKt.send(chunk).getOrThrow()
                        }
                        bytesSent += count
                        progressProcessedBytes = bytesSent
                        updateFileTransferProgress()
                    }
                }

                val sha256 = digest.digest()
                WolfSSLKt.send(FileTransferProtocol.encodeEnd(sha256)).getOrThrow()
                val lastEncryptedWrite = clientManager.lastQueuedEncryptedWrite()
                progressExpectedPackets =
                    clientManager.queuedBlePacketCount() - progressInitialQueuedPackets
                updateFileTransferProgress()
                if (strategy != FileTransferStrategy.STOP_AND_WAIT) {
                    _fileTransferStatus.value = "TLS queue complete; waiting for BLE write acknowledgements"
                }
                withTimeout(FILE_ACK_TIMEOUT_MS) {
                    clientManager.awaitTransmittedThrough(firstEncryptedWrite, lastEncryptedWrite)
                }
                clientTxSucceeded = true
                val transmitMillis = (SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000_000
                val transmittedPackets = clientManager.sentPacketCount() - initialPacketCount
                updateFileTransferProgress(forceComplete = true)
                if (strategy == FileTransferStrategy.TX_ONLY) {
                    _fileTransferMetrics.value = FileTransferMetrics(
                        strategy = strategy,
                        bytesTransferred = bytesSent,
                        packetsSent = transmittedPackets,
                        transmitMillis = transmitMillis,
                        totalMillis = transmitMillis,
                        sha256 = FileTransferProtocol.sha256Hex(sha256),
                    )
                    _fileTransferStatus.value = "Client TX succeeded; waiting for server verification message"
                } else {
                    _fileTransferStatus.value = "TX complete; waiting for server verification"
                }
                val response = withTimeout(FILE_ACK_TIMEOUT_MS) { ack.await() }
                check(response.success) { response.message.ifBlank { "Server rejected the file" } }
                check(response.receivedBytes == bytesSent) {
                    "Server received ${response.receivedBytes} of $bytesSent bytes"
                }
                check(response.sha256.contentEquals(sha256)) { "Server digest does not match the client digest" }

                val totalMillis = (SystemClock.elapsedRealtimeNanos() - startedAt) / 1_000_000
                val metrics = FileTransferMetrics(
                    strategy = strategy,
                    bytesTransferred = bytesSent,
                    packetsSent = transmittedPackets,
                    transmitMillis = transmitMillis,
                    totalMillis = totalMillis,
                    sha256 = FileTransferProtocol.sha256Hex(sha256),
                )
                _fileTransferMetrics.value = metrics
                _fileTransferStatus.value = if (strategy == FileTransferStrategy.TX_ONLY) {
                    "Client TX succeeded. Server: ${response.message.ifBlank { "File transmission Ok" }}"
                } else {
                    "Transfer complete and verified by server"
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _fileTransferStatus.value = if (
                    strategy == FileTransferStrategy.TX_ONLY && clientTxSucceeded
                ) {
                    "Client TX succeeded; server verification failed: ${error.message ?: "Unknown error"}"
                } else {
                    "Transfer failed after ${formatBytes(bytesSent)}: ${error.message ?: "Unknown error"}"
                }
            } finally {
                synchronized(fileAckLock) {
                    if (pendingFileAck === ack) pendingFileAck = null
                    pendingPacketAck = null
                    fileAckBuffer = ByteArray(0)
                }
                progressInitialAcknowledgedPackets = null
                _isFileTransferring.value = false
                clientManager.restoreBalancedConnection()
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch(Dispatchers.IO) {
            WolfSSLKt.release()
        }
        clientManager.disconnect()
        _isScanning.value = false
        _clientConnectionStatus.value = "Disconnected"
        _connectionState.value = "Client disconnected"
        _hasActiveConnection.value = false
        isTlsPrepared = false
        isTlsLaunching = false
        _tlsStatus.value = "TLS idle"
        _isTlsConnected.value = false
        synchronized(fileAckLock) {
            pendingFileAck?.completeExceptionally(IllegalStateException("Disconnected during file transfer"))
            pendingFileAck = null
            pendingPacketAck?.completeExceptionally(IllegalStateException("Disconnected during file transfer"))
            pendingPacketAck = null
            fileAckBuffer = ByteArray(0)
        }
        tlsReadJob?.cancel()
        tlsReadJob = null
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
        if (isTlsLaunching) {
            return
        }
        isTlsLaunching = true
        _tlsStatus.value = "Launching TLS handshake..."
        viewModelScope.launch(Dispatchers.IO) {
            val result = WolfSSLKt.startConnection()
            isTlsLaunching = false
            _tlsStatus.value = result.fold(
                onSuccess = {
                    _isTlsConnected.value = true
                    startTlsReader()
                    "TLS connected"
                },
                onFailure = {
                    _isTlsConnected.value = false
                    "TLS connect failed: ${it.message ?: "Unknown error"}"
                }
            )
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onCleared() {
        super.onCleared()
        tlsReadJob?.cancel()
        clientManager.close()
        WolfSSLKt.clear()
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

    private fun observeTlsState() {
        viewModelScope.launch {
            WolfSSLKt.state.collectLatest { state ->
                if (state is WolfSSLKt.WolfSSLKtState.TlsConnected) {
                    val address = connectedServerAddress ?: return@collectLatest
                    sessionTicketByServerAddress[address] = state.sessionTicket.copyOf()
                }
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
                        connectedServerAddress = event.address
                        _clientConnectionStatus.value = "Connected to ${event.address}"
                        _connectionState.value = "Client connected"
                        _hasActiveConnection.value = true
                    }
                    is BleClientConnectionEvent.Disconnected -> {
                        if (connectedServerAddress == event.address) {
                            connectedServerAddress = null
                        }
                        _clientConnectionStatus.value = "Disconnected from ${event.address}"
                        _connectionState.value = "Client disconnected"
                        _hasActiveConnection.value = false
                        isTlsPrepared = false
                        isTlsLaunching = false
                        _tlsStatus.value = "TLS idle"
                        _isTlsConnected.value = false
                        tlsReadJob?.cancel()
                        tlsReadJob = null
                        synchronized(fileAckLock) {
                            pendingFileAck?.completeExceptionally(IllegalStateException("Disconnected during file transfer"))
                            pendingFileAck = null
                            pendingPacketAck?.completeExceptionally(IllegalStateException("Disconnected during file transfer"))
                            pendingPacketAck = null
                            fileAckBuffer = ByteArray(0)
                        }
                    }
                    is BleClientConnectionEvent.ServicesReady -> {
                        _clientConnectionStatus.value = "Services ready on ${event.address}"
                        _connectionState.value = "Client ready"
                        prepareTlsConnection()
                    }
                    is BleClientConnectionEvent.OutputCharacteristicValueReceived -> {
                        if (!_tlsStatus.value.startsWith("TLS connected")) {
                            _clientOutputCharacteristicValue.value = "Encrypted BLE packet: ${toDisplay(event.value)}"
                        }
                    }
                    BleClientConnectionEvent.InputCharacteristicWriteSuccess -> {
                        if (_isFileTransferring.value) {
                            updateFileTransferProgress()
                        } else {
                            _clientWriteStatus.value = "Input characteristic write success"
                        }
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
        val serverAddress = connectedServerAddress ?: _selectedServer.value?.let { selected ->
            serverAddressByLabel[selected] ?: selected
        }
        val materialsResult = TlsMaterialProvider.loadForRole(
            appContext,
            TlsMaterialProvider.EndpointRole.CLIENT
        )
        if (materialsResult.isFailure) {
            _tlsStatus.value = "TLS prepare failed: ${materialsResult.exceptionOrNull()?.message ?: "Could not load TLS material"}"
            isTlsPrepared = false
            _isTlsConnected.value = false
            return
        }
        WolfSSLKt.clear()
        val materials = materialsResult.getOrThrow()
        val receiveCallback = WolfSSLKt.createReceiveCallback(bluetoothProvider.incomingChannel)
        val prepareResult = WolfSSLKt.prepareTls13Connection(
            cipher = WolfSSLKt.SupportedCipher.TLS_CHACHA20_POLY1305_SHA256,
            mode = WolfSSLKt.TlsMode.CLIENT,
            pkiData = PKIData(
                caCertificate = materials.caCertificate,
                certificateChain = materials.certificateChain,
                pemPrivateKey = materials.privateKey,
            ),
            receiveCallback = receiveCallback,
            sendCallback = WolfSSLKtSendCallback(
                outgoingEncryptedDataChannel = bluetoothProvider.outgoingChannel,
                onEncryptedDataQueued = clientManager::onEncryptedDataQueued,
            ),
            previousSessionTicket = serverAddress
                ?.let(sessionTicketByServerAddress::get)
                ?.copyOf()
        )
        isTlsPrepared = prepareResult.isSuccess
        _isTlsConnected.value = false
        _tlsStatus.value = prepareResult.fold(
            onSuccess = { "TLS prepared (client). Tap Launch TLS." },
            onFailure = { "TLS prepare failed: ${it.message ?: "Unknown error"}" }
        )
    }

    private fun startTlsReader() {
        tlsReadJob?.cancel()
        tlsReadJob = viewModelScope.launch(Dispatchers.IO) {
            WolfSSLKt.read().collect { data ->
                if (synchronized(fileAckLock) { pendingFileAck != null }) {
                    handleFileAckData(data)
                } else {
                    _clientOutputCharacteristicValue.value = toDisplay(data)
                }
            }
        }
    }

    private fun handleFileAckData(data: ByteArray) {
        synchronized(fileAckLock) {
            fileAckBuffer += data
            while (fileAckBuffer.isNotEmpty()) when (val decoded = FileTransferProtocol.decode(fileAckBuffer)) {
                is FileTransferProtocol.DecodeResult.Decoded -> {
                    fileAckBuffer = fileAckBuffer.copyOfRange(decoded.consumedBytes, fileAckBuffer.size)
                    when (val message = decoded.message) {
                        is FileTransferProtocol.ControlMessage.Ack -> pendingFileAck?.complete(message)
                        is FileTransferProtocol.ControlMessage.PacketAck -> pendingPacketAck?.complete(message)
                        else -> {
                            val error = IllegalStateException("Unexpected server control message")
                            pendingFileAck?.completeExceptionally(error)
                            pendingPacketAck?.completeExceptionally(error)
                        }
                    }
                }
                is FileTransferProtocol.DecodeResult.Invalid -> {
                    pendingFileAck?.completeExceptionally(IllegalStateException(decoded.reason))
                    pendingPacketAck?.completeExceptionally(IllegalStateException(decoded.reason))
                    return
                }
                FileTransferProtocol.DecodeResult.NotAControlFrame -> {
                    pendingFileAck?.completeExceptionally(IllegalStateException("Server returned an invalid transfer acknowledgement"))
                    pendingPacketAck?.completeExceptionally(IllegalStateException("Server returned an invalid packet acknowledgement"))
                    return
                }
                FileTransferProtocol.DecodeResult.NeedMoreData -> return
            }
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.2f MiB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> "%.2f KiB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun updateFileTransferProgress(forceComplete: Boolean = false) {
        val initialPackets = progressInitialAcknowledgedPackets ?: return
        val acknowledged = if (forceComplete) {
            progressExpectedPackets ?: (clientManager.sentPacketCount() - initialPackets)
        } else {
            (clientManager.sentPacketCount() - initialPackets).coerceAtLeast(0)
        }
        val expected = progressExpectedPackets
        val percentage = if (progressTotalBytes == 0L) {
            100
        } else {
            ((progressProcessedBytes * 100) / progressTotalBytes).coerceIn(0, 100)
        }
        _fileTransferProgress.value = buildString {
            append("Processing/queueing: ${formatBytes(progressProcessedBytes)} / ")
            append("${formatBytes(progressTotalBytes)} ($percentage%)\n")
            append("BLE packets acknowledged: $acknowledged")
            if (expected != null) append(" / $expected") else append(" (total still being queued)")
        }
    }

    private companion object {
        // GATT writes use acknowledgements and can take minutes for multi-megabyte files.
        const val FILE_ACK_TIMEOUT_MS = 10 * 60_000L
        const val PACKET_ACK_TIMEOUT_MS = 30_000L
    }
}
