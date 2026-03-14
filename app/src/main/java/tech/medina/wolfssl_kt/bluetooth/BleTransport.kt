package tech.medina.wolfssl_kt.bluetooth

internal object BleTransport {
    // Safe default ATT payload without MTU negotiation.
    const val MAX_PACKET_SIZE = 20

    fun chunk(data: ByteArray): List<ByteArray> {
        if (data.isEmpty()) {
            return listOf(ByteArray(0))
        }

        val packets = ArrayList<ByteArray>((data.size + MAX_PACKET_SIZE - 1) / MAX_PACKET_SIZE)
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + MAX_PACKET_SIZE, data.size)
            packets += data.copyOfRange(offset, end)
            offset = end
        }
        return packets
    }
}
