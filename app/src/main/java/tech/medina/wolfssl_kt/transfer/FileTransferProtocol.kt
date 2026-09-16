package tech.medina.wolfssl_kt.transfer

import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class FileTransferStrategy(val wireValue: Byte, val displayName: String) {
    VERIFIED_STREAM(1, "Verified stream"),
    TX_ONLY(2, "TX only"),
    STOP_AND_WAIT(3, "Packet ACK (stop-and-wait)");

    companion object {
        fun fromWireValue(value: Byte): FileTransferStrategy =
            entries.firstOrNull { it.wireValue == value }
                ?: error("Unknown file transfer strategy $value")
    }
}

internal object FileTransferProtocol {
    const val DIGEST_SIZE = 32
    const val MAX_FILE_NAME_BYTES = 255
    const val MAX_CONTROL_PAYLOAD_SIZE = 4 * 1024

    // TLS 1.3 record header + inner content type + ChaCha20-Poly1305 tag.
    private const val TLS_RECORD_OVERHEAD = 5 + 1 + 16
    private const val TARGET_FILE_CHUNK_SIZE = 250
    private const val VERSION: Byte = 1
    private const val HEADER_SIZE = 10
    private val MAGIC = byteArrayOf('W'.code.toByte(), 'F'.code.toByte(), 'T'.code.toByte(), 'P'.code.toByte())

    sealed interface ControlMessage {
        data class Start(
            val fileName: String,
            val fileSize: Long,
            val strategy: FileTransferStrategy,
        ) : ControlMessage
        data class Data(val sequence: Int, val bytes: ByteArray) : ControlMessage
        data class End(val sha256: ByteArray) : ControlMessage
        data class PacketAck(
            val sequence: Int,
            val success: Boolean,
            val message: String,
        ) : ControlMessage
        data class Ack(
            val success: Boolean,
            val receivedBytes: Long,
            val sha256: ByteArray,
            val message: String,
        ) : ControlMessage
    }

    sealed interface DecodeResult {
        data object NeedMoreData : DecodeResult
        data object NotAControlFrame : DecodeResult
        data class Decoded(val message: ControlMessage, val consumedBytes: Int) : DecodeResult
        data class Invalid(val reason: String) : DecodeResult
    }

    fun fileChunkSize(attPayloadSize: Int, strategy: FileTransferStrategy): Int {
        val framingOverhead = if (strategy == FileTransferStrategy.STOP_AND_WAIT) {
            HEADER_SIZE + Int.SIZE_BYTES
        } else {
            0
        }
        return minOf(
            TARGET_FILE_CHUNK_SIZE,
            (attPayloadSize - TLS_RECORD_OVERHEAD - framingOverhead).coerceAtLeast(1),
        )
    }

    fun encodeStart(fileName: String, fileSize: Long, strategy: FileTransferStrategy): ByteArray {
        require(fileSize >= 0) { "fileSize must not be negative" }
        val name = fileName.encodeToByteArray()
        require(name.isNotEmpty()) { "fileName must not be empty" }
        require(name.size <= MAX_FILE_NAME_BYTES) { "fileName is too long" }
        val payload = ByteBuffer.allocate(1 + Long.SIZE_BYTES + Short.SIZE_BYTES + name.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(strategy.wireValue)
            .putLong(fileSize)
            .putShort(name.size.toShort())
            .put(name)
            .array()
        return encode(TYPE_START, payload)
    }

    fun encodeData(sequence: Int, bytes: ByteArray): ByteArray {
        require(sequence >= 0) { "sequence must not be negative" }
        val payload = ByteBuffer.allocate(Int.SIZE_BYTES + bytes.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(sequence)
            .put(bytes)
            .array()
        return encode(TYPE_DATA, payload)
    }

    fun encodeEnd(sha256: ByteArray): ByteArray {
        require(sha256.size == DIGEST_SIZE) { "A SHA-256 digest must contain $DIGEST_SIZE bytes" }
        return encode(TYPE_END, sha256)
    }

    fun encodeAck(
        success: Boolean,
        receivedBytes: Long,
        sha256: ByteArray,
        message: String,
    ): ByteArray {
        require(sha256.size == DIGEST_SIZE) { "A SHA-256 digest must contain $DIGEST_SIZE bytes" }
        val messageBytes = message.encodeToByteArray()
        val payload = ByteBuffer.allocate(1 + Long.SIZE_BYTES + DIGEST_SIZE + messageBytes.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(if (success) 1 else 0)
            .putLong(receivedBytes)
            .put(sha256)
            .put(messageBytes)
            .array()
        return encode(TYPE_ACK, payload)
    }

    fun encodePacketAck(sequence: Int, success: Boolean, message: String = ""): ByteArray {
        require(sequence >= 0) { "sequence must not be negative" }
        val messageBytes = message.encodeToByteArray()
        val payload = ByteBuffer.allocate(Int.SIZE_BYTES + 1 + messageBytes.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(sequence)
            .put(if (success) 1 else 0)
            .put(messageBytes)
            .array()
        return encode(TYPE_PACKET_ACK, payload)
    }

    fun decode(bytes: ByteArray): DecodeResult {
        if (bytes.isEmpty()) return DecodeResult.NeedMoreData
        val prefixLength = minOf(bytes.size, MAGIC.size)
        if (!bytes.copyOfRange(0, prefixLength).contentEquals(MAGIC.copyOfRange(0, prefixLength))) {
            return DecodeResult.NotAControlFrame
        }
        if (bytes.size < HEADER_SIZE) return DecodeResult.NeedMoreData

        val header = ByteBuffer.wrap(bytes, MAGIC.size, HEADER_SIZE - MAGIC.size)
            .order(ByteOrder.BIG_ENDIAN)
        val version = header.get()
        val type = header.get()
        val payloadSize = header.int
        if (version != VERSION) return DecodeResult.Invalid("Unsupported protocol version $version")
        if (payloadSize !in 0..MAX_CONTROL_PAYLOAD_SIZE) {
            return DecodeResult.Invalid("Invalid control payload size $payloadSize")
        }
        val frameSize = HEADER_SIZE + payloadSize
        if (bytes.size < frameSize) return DecodeResult.NeedMoreData
        val payload = bytes.copyOfRange(HEADER_SIZE, frameSize)
        val message = runCatching { decodePayload(type, payload) }
            .getOrElse { return DecodeResult.Invalid(it.message ?: "Malformed control frame") }
        return DecodeResult.Decoded(message, frameSize)
    }

    fun sha256Hex(digest: ByteArray): String = digest.joinToString("") { "%02x".format(it) }

    private fun encode(type: Byte, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(HEADER_SIZE + payload.size)
            .order(ByteOrder.BIG_ENDIAN)
            .put(MAGIC)
            .put(VERSION)
            .put(type)
            .putInt(payload.size)
            .put(payload)
            .array()

    private fun decodePayload(type: Byte, payload: ByteArray): ControlMessage = when (type) {
        TYPE_START -> {
            require(payload.size >= 1 + Long.SIZE_BYTES + Short.SIZE_BYTES) { "Start frame is too short" }
            val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
            val strategy = FileTransferStrategy.fromWireValue(buffer.get())
            val fileSize = buffer.long
            val nameSize = buffer.short.toInt() and 0xffff
            require(fileSize >= 0) { "Negative file size" }
            require(nameSize in 1..MAX_FILE_NAME_BYTES && nameSize == buffer.remaining()) {
                "Invalid file name size"
            }
            val name = ByteArray(nameSize).also(buffer::get).decodeToString()
            ControlMessage.Start(name, fileSize, strategy)
        }
        TYPE_DATA -> {
            require(payload.size >= Int.SIZE_BYTES) { "Data frame is too short" }
            val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
            val sequence = buffer.int
            require(sequence >= 0) { "Negative packet sequence" }
            val bytes = ByteArray(buffer.remaining()).also(buffer::get)
            ControlMessage.Data(sequence, bytes)
        }
        TYPE_END -> {
            require(payload.size == DIGEST_SIZE) { "Invalid end digest size" }
            ControlMessage.End(payload.copyOf())
        }
        TYPE_ACK -> {
            require(payload.size >= 1 + Long.SIZE_BYTES + DIGEST_SIZE) { "Ack frame is too short" }
            val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
            val success = buffer.get().toInt() != 0
            val receivedBytes = buffer.long
            val digest = ByteArray(DIGEST_SIZE).also(buffer::get)
            val message = ByteArray(buffer.remaining()).also(buffer::get).decodeToString()
            ControlMessage.Ack(success, receivedBytes, digest, message)
        }
        TYPE_PACKET_ACK -> {
            require(payload.size >= Int.SIZE_BYTES + 1) { "Packet acknowledgement is too short" }
            val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
            val sequence = buffer.int
            require(sequence >= 0) { "Negative packet sequence" }
            val success = buffer.get().toInt() != 0
            val message = ByteArray(buffer.remaining()).also(buffer::get).decodeToString()
            ControlMessage.PacketAck(sequence, success, message)
        }
        else -> error("Unknown control frame type $type")
    }

    private const val TYPE_START: Byte = 1
    private const val TYPE_END: Byte = 2
    private const val TYPE_ACK: Byte = 3
    private const val TYPE_DATA: Byte = 4
    private const val TYPE_PACKET_ACK: Byte = 5
}
