package tech.medina.wolfssl_kt.bluetooth

internal object BleTransport {
    private const val ATT_HEADER_SIZE = 3
    const val DEFAULT_PACKET_SIZE = 20
    const val DESIRED_MTU = 256
    const val TARGET_PACKET_SIZE = DESIRED_MTU - ATT_HEADER_SIZE

    fun packetSizeForMtu(mtu: Int): Int =
        (mtu - ATT_HEADER_SIZE).coerceIn(DEFAULT_PACKET_SIZE, TARGET_PACKET_SIZE)

    fun chunk(data: ByteArray, packetSize: Int = DEFAULT_PACKET_SIZE): List<ByteArray> {
        require(packetSize > 0) { "packetSize must be positive" }
        if (data.isEmpty()) {
            return listOf(ByteArray(0))
        }

        val packets = ArrayList<ByteArray>((data.size + packetSize - 1) / packetSize)
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + packetSize, data.size)
            packets += data.copyOfRange(offset, end)
            offset = end
        }
        return packets
    }
}
