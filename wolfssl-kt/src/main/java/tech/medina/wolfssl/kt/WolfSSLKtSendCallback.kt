package tech.medina.wolfssl.kt

import android.util.Log
import com.wolfssl.WolfSSL.WOLFSSL_CBIO_ERR_WANT_WRITE
import com.wolfssl.WolfSSLIOSendCallback
import com.wolfssl.WolfSSLSession
import kotlinx.coroutines.channels.Channel

class WolfSSLKtSendCallback @JvmOverloads constructor(
    private val outgoingEncryptedDataChannel: Channel<ByteArray>,
    private val onEncryptedDataQueued: ((Int) -> Unit)? = null,
) : WolfSSLIOSendCallback {
    //SendCallback is where we receive the data encrypted by WolfSSL that should be sent to the peer.
    //We send the data to the peer using the channel provided in the constructor.
    override fun sendCallback(
        ssl: WolfSSLSession?,
        buffer: ByteArray,
        size: Int,
        ctx: Any?
    ): Int {
        val result = outgoingEncryptedDataChannel.trySend(buffer.copyOf(size))
        return if (result.isSuccess) {
            onEncryptedDataQueued?.invoke(size)
            Log.d("WolfSSL-SendCallback", "TLS send encrypted ($size): ${buffer.copyOf(size).toLogString()}")
            size
        } else {
            WOLFSSL_CBIO_ERR_WANT_WRITE
        }
    }
}
