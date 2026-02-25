package tech.medina.wolfssl_kt.bluetooth

import kotlinx.coroutines.channels.Channel

interface BluetoothProvider {
    val incomingChannel: Channel<ByteArray>
    val outgoingChannel: Channel<ByteArray>
    fun publishIncoming(data: ByteArray)
}

class GattBluetoothProvider : BluetoothProvider {

    override val incomingChannel = Channel<ByteArray>(Channel.UNLIMITED)
    override val outgoingChannel = Channel<ByteArray>(Channel.UNLIMITED)

    override fun publishIncoming(data: ByteArray) {
        // Copy before enqueueing so BLE callback buffers can be safely reused by platform code.
        incomingChannel.trySend(data.copyOf())
    }

    fun onCharacteristicReceived(value: ByteArray) {
        publishIncoming(value)
    }
}
