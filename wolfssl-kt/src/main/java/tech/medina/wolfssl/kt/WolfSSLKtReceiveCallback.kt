package tech.medina.wolfssl.kt

import android.util.Log
import com.wolfssl.WolfSSL.WOLFSSL_CBIO_ERR_WANT_READ
import com.wolfssl.WolfSSLIORecvCallback
import com.wolfssl.WolfSSLSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

// This is the callback that WolfSSL will use to receive encrypted data from the peer.
// To instantiate this callback, you need to use the WolfSSLKt class factory method, since its constructor is internal.
class WolfSSLKtReceiveCallback internal constructor(
    appScope: CoroutineScope,
    incomingEncryptedDataChannel: Channel<ByteArray>,
) : WolfSSLIORecvCallback {
    private val inboundBuffer = ArrayDeque<Byte>()
    private val inboundLock = Any()
    private val dataAvailable = Channel<Unit>(Channel.CONFLATED)

    private val receiveJob: Job = appScope.launch {
        incomingEncryptedDataChannel.receiveAsFlow()
            .buffer(UNLIMITED)
            .collect { chunk ->
                synchronized(inboundLock) {
                    chunk.forEach { inboundBuffer.addLast(it) }
                }
                dataAvailable.trySend(Unit)
            }
    }

    /** Suspends until encrypted input is available without adding a fixed polling delay. */
    suspend fun awaitData(): Boolean {
        if (synchronized(inboundLock) { inboundBuffer.isNotEmpty() }) return true
        return dataAvailable.receiveCatching().isSuccess
    }

    override fun receiveCallback(
        ssl: WolfSSLSession?,
        buffer: ByteArray,
        size: Int,
        ctx: Any?
    ): Int {
        return synchronized(inboundLock) {
            if (inboundBuffer.isEmpty()) {
                return@synchronized WOLFSSL_CBIO_ERR_WANT_READ
            }

            var bytesRead = 0
            while (bytesRead < size && inboundBuffer.isNotEmpty()) {
                buffer[bytesRead] = inboundBuffer.removeFirst()
                bytesRead++
            }
            Log.d("WolfSSLKtReceiveCallback", "TLS recv encrypted ($bytesRead): ${buffer.copyOf(bytesRead).toLogString()}")
            bytesRead
        }
    }

    fun cancel() {
        receiveJob.cancel()
        dataAvailable.close()
    }
}
