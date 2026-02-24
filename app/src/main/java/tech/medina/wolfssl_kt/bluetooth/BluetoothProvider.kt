package tech.medina.wolfssl_kt.bluetooth

import kotlinx.coroutines.channels.Channel

interface BluetoothProvider {
    val channel: Channel<ByteArray>
}

