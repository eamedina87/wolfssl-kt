package tech.medina.wolfssl_kt.ui.server

import android.app.Application
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tech.medina.wolfssl.kt.PKIData
import tech.medina.wolfssl.kt.WolfSSLKt
import tech.medina.wolfssl.kt.WolfSSLKtSendCallback
import tech.medina.wolfssl_kt.bluetooth.BleServerConnectionEvent
import tech.medina.wolfssl_kt.bluetooth.BluetoothLeServerConnectionManager
import tech.medina.wolfssl_kt.bluetooth.GattBluetoothProvider
import tech.medina.wolfssl_kt.tls.TlsMaterialProvider
import tech.medina.wolfssl_kt.transfer.FileTransferProtocol
import tech.medina.wolfssl_kt.transfer.FileTransferStrategy
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

private data class IncomingFileTransfer(
    val strategy: FileTransferStrategy,
    val expectedBytes: Long,
    val finalFile: File,
    val partialFile: File,
    val output: FileOutputStream,
    val digest: MessageDigest,
    var receivedBytes: Long = 0,
    var expectedSequence: Int = 0,
)

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

    private val _serverInputCharacteristicValue = MutableStateFlow("No TLS payload received")
    val serverInputCharacteristicValue: StateFlow<String> = _serverInputCharacteristicValue.asStateFlow()

    private val _serverWriteStatus = MutableStateFlow("Idle")
    val serverWriteStatus: StateFlow<String> = _serverWriteStatus.asStateFlow()
    private val _receivedFileStatus = MutableStateFlow("No file received")
    val receivedFileStatus: StateFlow<String> = _receivedFileStatus.asStateFlow()
    private val _receivedFileStrategy = MutableStateFlow("Waiting for client selection")
    val receivedFileStrategy: StateFlow<String> = _receivedFileStrategy.asStateFlow()
    private val _tlsStatus = MutableStateFlow("TLS idle")
    val tlsStatus: StateFlow<String> = _tlsStatus.asStateFlow()
    private val _isTlsConnected = MutableStateFlow(false)
    val isTlsConnected: StateFlow<Boolean> = _isTlsConnected.asStateFlow()
    private val connectedClientAddresses = linkedSetOf<String>()
    private var isTlsPrepared = false
    private var isTlsLaunching = false
    private var tlsReadJob: Job? = null
    private var incomingFileTransfer: IncomingFileTransfer? = null
    private var incomingPlaintextBuffer = ByteArray(0)

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
        viewModelScope.launch(Dispatchers.IO) {
            WolfSSLKt.release()
        }
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

    fun sendServerOutputCharacteristic(text: String) {
        if (text.isBlank()) {
            _serverWriteStatus.value = "Input is empty"
            return
        }
        if (!_tlsStatus.value.startsWith("TLS connected")) {
            _serverWriteStatus.value = "TLS is not connected"
            return
        }
        _serverWriteStatus.value = "Sending TLS payload..."
        viewModelScope.launch(Dispatchers.IO) {
            val result = WolfSSLKt.send(text.encodeToByteArray())
            _serverWriteStatus.value = result.fold(
                onSuccess = { "TLS payload sent" },
                onFailure = { "TLS send failed: ${it.message ?: "Unknown error"}" }
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        abortIncomingFile("Server closed")
        tlsReadJob?.cancel()
        serverManager.stopServer()
        WolfSSLKt.clear()
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
                        abortIncomingFile("Server stopped")
                        connectedClientAddresses.clear()
                        _hasActiveConnection.value = false
                        _isAdvertising.value = false
                        isTlsPrepared = false
                        isTlsLaunching = false
                        _tlsStatus.value = "TLS idle"
                        _isTlsConnected.value = false
                        tlsReadJob?.cancel()
                        tlsReadJob = null
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
                            abortIncomingFile("Client disconnected")
                            isTlsPrepared = false
                            isTlsLaunching = false
                            _tlsStatus.value = "TLS idle"
                            _isTlsConnected.value = false
                            tlsReadJob?.cancel()
                            tlsReadJob = null
                            WolfSSLKt.clear()
                        }
                    }
                    is BleServerConnectionEvent.InputCharacteristicValueReceived -> {
                        if (!_tlsStatus.value.startsWith("TLS connected")) {
                            _serverInputCharacteristicValue.value = "Encrypted BLE packet: ${toDisplay(event.value)}"
                        }
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
            _isTlsConnected.value = false
            return
        }
        WolfSSLKt.clear()
        val materials = materialsResult.getOrThrow()
        val receiveCallback = WolfSSLKt.createReceiveCallback(bluetoothProvider.incomingChannel)
        val prepareResult = WolfSSLKt.prepareTls13Connection(
            cipher = WolfSSLKt.SupportedCipher.TLS_CHACHA20_POLY1305_SHA256,
            mode = WolfSSLKt.TlsMode.SERVER,
            pkiData = PKIData(
                caCertificate = materials.caCertificate,
                certificateChain = materials.certificateChain,
                pemPrivateKey = materials.privateKey,
            ),
            receiveCallback = receiveCallback,
            sendCallback = WolfSSLKtSendCallback(bluetoothProvider.outgoingChannel),
        )
        isTlsPrepared = prepareResult.isSuccess
        _isTlsConnected.value = false
        _tlsStatus.value = prepareResult.fold(
            onSuccess = { "TLS prepared (server). Tap Launch TLS." },
            onFailure = { "TLS prepare failed: ${it.message ?: "Unknown error"}" }
        )
    }

    private fun startTlsReader() {
        tlsReadJob?.cancel()
        tlsReadJob = viewModelScope.launch(Dispatchers.IO) {
            WolfSSLKt.read().collect { data ->
                handleIncomingPlaintext(data)
            }
        }
    }

    private fun handleIncomingPlaintext(data: ByteArray) {
        incomingPlaintextBuffer += data
        while (incomingPlaintextBuffer.isNotEmpty()) {
            val transfer = incomingFileTransfer
            if (
                transfer != null &&
                transfer.strategy != FileTransferStrategy.STOP_AND_WAIT &&
                transfer.receivedBytes < transfer.expectedBytes
            ) {
                val count = minOf(
                    incomingPlaintextBuffer.size.toLong(),
                    transfer.expectedBytes - transfer.receivedBytes,
                ).toInt()
                val fileBytes = incomingPlaintextBuffer.copyOfRange(0, count)
                runCatching {
                    transfer.output.write(fileBytes)
                    transfer.digest.update(fileBytes)
                    transfer.receivedBytes += count
                    incomingPlaintextBuffer = incomingPlaintextBuffer.copyOfRange(count, incomingPlaintextBuffer.size)
                    _receivedFileStatus.value =
                        "Receiving ${transfer.finalFile.name}: ${formatBytes(transfer.receivedBytes)} / ${formatBytes(transfer.expectedBytes)}"
                }.onFailure { error ->
                    failIncomingFile("Could not write received file: ${error.message ?: "Unknown error"}")
                }
                if (incomingFileTransfer == null) return
                continue
            }

            when (val decoded = FileTransferProtocol.decode(incomingPlaintextBuffer)) {
                FileTransferProtocol.DecodeResult.NeedMoreData -> return
                FileTransferProtocol.DecodeResult.NotAControlFrame -> {
                    if (transfer == null) {
                        _serverInputCharacteristicValue.value = toDisplay(incomingPlaintextBuffer)
                        incomingPlaintextBuffer = ByteArray(0)
                    } else {
                        failIncomingFile("Missing file completion marker")
                    }
                    return
                }
                is FileTransferProtocol.DecodeResult.Invalid -> {
                    if (transfer != null) {
                        failIncomingFile(decoded.reason)
                    } else {
                        _serverInputCharacteristicValue.value = "Invalid transfer control message: ${decoded.reason}"
                        incomingPlaintextBuffer = ByteArray(0)
                    }
                    return
                }
                is FileTransferProtocol.DecodeResult.Decoded -> {
                    incomingPlaintextBuffer = incomingPlaintextBuffer.copyOfRange(
                        decoded.consumedBytes,
                        incomingPlaintextBuffer.size,
                    )
                    when (val message = decoded.message) {
                        is FileTransferProtocol.ControlMessage.Start -> startIncomingFile(message)
                        is FileTransferProtocol.ControlMessage.Data -> receiveDataPacket(message)
                        is FileTransferProtocol.ControlMessage.End -> finishIncomingFile(message.sha256)
                        is FileTransferProtocol.ControlMessage.Ack -> {
                            _serverInputCharacteristicValue.value = "Unexpected file acknowledgement"
                        }
                        is FileTransferProtocol.ControlMessage.PacketAck -> {
                            _serverInputCharacteristicValue.value = "Unexpected packet acknowledgement"
                        }
                    }
                }
            }
        }
    }

    private fun startIncomingFile(start: FileTransferProtocol.ControlMessage.Start) {
        if (incomingFileTransfer != null) {
            failIncomingFile("A second file transfer was started before the first completed")
            return
        }
        runCatching {
            val directory = receivedFilesDirectory().also { check(it.exists() || it.mkdirs()) }
            val safeName = sanitizeFileName(start.fileName)
            val finalFile = uniqueDestination(directory, safeName)
            val partialFile = File(directory, "${finalFile.name}.part")
            IncomingFileTransfer(
                strategy = start.strategy,
                expectedBytes = start.fileSize,
                finalFile = finalFile,
                partialFile = partialFile,
                output = FileOutputStream(partialFile),
                digest = MessageDigest.getInstance("SHA-256"),
            )
        }.fold(
            onSuccess = { transfer ->
                incomingFileTransfer = transfer
                _receivedFileStrategy.value = transfer.strategy.displayName
                _receivedFileStatus.value =
                    "Receiving ${transfer.finalFile.name} with ${transfer.strategy.displayName} (0 B / ${formatBytes(transfer.expectedBytes)})"
            },
            onFailure = { error ->
                _receivedFileStatus.value = "Could not start file receive: ${error.message ?: "Unknown error"}"
                sendFileAck(false, 0, ByteArray(FileTransferProtocol.DIGEST_SIZE), _receivedFileStatus.value)
            },
        )
    }

    private fun receiveDataPacket(data: FileTransferProtocol.ControlMessage.Data) {
        val transfer = incomingFileTransfer
        if (transfer == null || transfer.strategy != FileTransferStrategy.STOP_AND_WAIT) {
            sendPacketAck(data.sequence, false, "Data packet is not expected for this strategy")
            return
        }
        if (data.sequence != transfer.expectedSequence) {
            val message = "Expected packet ${transfer.expectedSequence} but received ${data.sequence}"
            sendPacketAck(data.sequence, false, message)
            failIncomingFile(message)
            return
        }
        val remaining = transfer.expectedBytes - transfer.receivedBytes
        if (data.bytes.isEmpty() || data.bytes.size.toLong() > remaining) {
            val message = "Packet ${data.sequence} has an invalid payload size"
            sendPacketAck(data.sequence, false, message)
            failIncomingFile(message)
            return
        }
        runCatching {
            transfer.output.write(data.bytes)
            transfer.digest.update(data.bytes)
            transfer.receivedBytes += data.bytes.size
            transfer.expectedSequence++
        }.fold(
            onSuccess = {
                _receivedFileStatus.value =
                    "Receiving ${transfer.finalFile.name}: ${formatBytes(transfer.receivedBytes)} / ${formatBytes(transfer.expectedBytes)}"
                sendPacketAck(data.sequence, true)
            },
            onFailure = { error ->
                val message = "Could not write packet ${data.sequence}: ${error.message ?: "Unknown error"}"
                sendPacketAck(data.sequence, false, message)
                failIncomingFile(message)
            },
        )
    }

    private fun finishIncomingFile(expectedDigest: ByteArray) {
        val transfer = incomingFileTransfer ?: run {
            _receivedFileStatus.value = "Received file end marker without a file"
            return
        }
        val actualDigest = transfer.digest.digest()
        val sizeMatches = transfer.receivedBytes == transfer.expectedBytes
        val digestMatches = actualDigest.contentEquals(expectedDigest)
        val closeResult = runCatching { transfer.output.flush() }
        runCatching { transfer.output.close() }
        val success = runCatching {
            closeResult.getOrThrow()
            check(sizeMatches) { "Expected ${transfer.expectedBytes} bytes but received ${transfer.receivedBytes}" }
            check(digestMatches) { "SHA-256 digest mismatch" }
            check(transfer.partialFile.renameTo(transfer.finalFile)) { "Could not finalize received file" }
        }
        incomingFileTransfer = null

        if (success.isSuccess) {
            _receivedFileStatus.value =
                "Received ${transfer.finalFile.name}: ${formatBytes(transfer.receivedBytes)}, SHA-256 ${FileTransferProtocol.sha256Hex(actualDigest)}\n${transfer.finalFile.absolutePath}"
            sendFileAck(true, transfer.receivedBytes, actualDigest, "File transmission Ok")
        } else {
            transfer.partialFile.delete()
            val message = success.exceptionOrNull()?.message ?: "Could not finalize received file"
            _receivedFileStatus.value = "File receive failed: $message"
            sendFileAck(false, transfer.receivedBytes, actualDigest, message)
        }
    }

    private fun failIncomingFile(message: String) {
        val transfer = incomingFileTransfer
        incomingFileTransfer = null
        runCatching { transfer?.output?.close() }
        transfer?.partialFile?.delete()
        incomingPlaintextBuffer = ByteArray(0)
        _receivedFileStatus.value = "File receive failed: $message"
        sendFileAck(
            success = false,
            receivedBytes = transfer?.receivedBytes ?: 0,
            digest = ByteArray(FileTransferProtocol.DIGEST_SIZE),
            message = message,
        )
    }

    private fun abortIncomingFile(reason: String) {
        val transfer = incomingFileTransfer ?: return
        incomingFileTransfer = null
        runCatching { transfer.output.close() }
        transfer.partialFile.delete()
        incomingPlaintextBuffer = ByteArray(0)
        _receivedFileStatus.value = "Partial file removed: $reason"
    }

    private fun sendFileAck(success: Boolean, receivedBytes: Long, digest: ByteArray, message: String) {
        WolfSSLKt.send(FileTransferProtocol.encodeAck(success, receivedBytes, digest, message))
            .onFailure { error ->
                _serverWriteStatus.value = "Could not send file acknowledgement: ${error.message ?: "Unknown error"}"
            }
    }

    private fun sendPacketAck(sequence: Int, success: Boolean, message: String = "") {
        WolfSSLKt.send(FileTransferProtocol.encodePacketAck(sequence, success, message))
            .onFailure { error ->
                _serverWriteStatus.value = "Could not send ACK for packet $sequence: ${error.message ?: "Unknown error"}"
            }
    }

    private fun receivedFilesDirectory(): File {
        val app = getApplication<Application>()
        val downloads = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: app.filesDir
        return File(downloads, "received_files")
    }

    private fun sanitizeFileName(name: String): String {
        val baseName = name.substringAfterLast('/').substringAfterLast('\\')
        val sanitized = baseName.replace(Regex("[^A-Za-z0-9._-]"), "_").trim('.', '_')
        return sanitized.take(120).ifBlank { "transfer.bin" }
    }

    private fun uniqueDestination(directory: File, name: String): File {
        val original = File(directory, name)
        if (!original.exists() && !File(directory, "$name.part").exists()) return original
        val stem = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "").let { if (it.isBlank()) "" else ".$it" }
        var index = 1
        while (true) {
            val candidate = File(directory, "$stem ($index)$extension")
            if (!candidate.exists() && !File(directory, "${candidate.name}.part").exists()) return candidate
            index++
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.2f MiB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> "%.2f KiB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
