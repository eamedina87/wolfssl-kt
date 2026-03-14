package tech.medina.wolfssl.kt

import android.util.Log
import com.wolfssl.WolfSSL.*
import com.wolfssl.WolfSSLContext
import com.wolfssl.WolfSSLException
import com.wolfssl.WolfSSLLoggingCallback
import com.wolfssl.WolfSSLSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.UNLIMITED
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

private fun Int.checkSuccessful() {
    if (this == SSL_FAILURE) {
        throw WolfSSLException("Failed execution action")
    }
}

object WolfSslKt {

    private var currentMode: TlsMode? = null
    private var currentSessionTicket: ByteArray? = null
    private lateinit var receiveJob: Job

    //todo protect
    private var currentSession: WolfSSLSession? = null
    private val sessionList: ConcurrentMap<Int, WolfSSLSession> = ConcurrentHashMap()

    //todo protect
    //private var context: WolfSSLContext? = null
    private lateinit var appScope: CoroutineScope
    private val loggingCb = WolfSSLLoggingCallback { logLevel, logMessage -> Log.d("WolfSSL-JNI", "${logLevel}: $logMessage") }

    sealed class WolfSSLKtState {
        object Idle : WolfSSLKtState()
        object Loading : WolfSSLKtState()
        object Initialized : WolfSSLKtState()

        class Error(description: String) : WolfSSLKtState()
    }

    private val _state = MutableStateFlow<WolfSSLKtState>(WolfSSLKtState.Idle)
    val state: StateFlow<WolfSSLKtState> = _state.asStateFlow()

    suspend fun init(applicationScope: CoroutineScope, enableLogging: Boolean = false) : Result<Unit> {
        return try {
            _state.emit(WolfSSLKtState.Loading)
            appScope = applicationScope
            loadLibrary()
            if (enableLogging) {
                debuggingON()
                setLoggingCb(loggingCb)
            }
            _state.emit(WolfSSLKtState.Initialized)
            Result.success(Unit)
        } catch (e: UnsatisfiedLinkError) {
            val message = "Failed to load WolfSSL library: ${e.message}"
            Log.e("WolfSSL-JNI",message)
            _state.emit(WolfSSLKtState.Error(message))
            _state.emit(WolfSSLKtState.Idle)
            Result.failure(e)
        }
    }

    fun prepareTls13Connection(
        cipher: SupportedCipher,
        mode: TlsMode,
        pemPrivateKey: ByteArray,
        caCertificate: ByteArray,
        certificateChain: ByteArray,
        incomingEncryptedDataChannel: Channel<ByteArray>,
        outgoingEncryptedDataChannel: Channel<ByteArray>
    ) : Result<Unit> {
        return try {
            if (currentSession != null) {
                throw IllegalStateException("There's a session already created. Stop or release current session before creating a new one.")
            }
            currentMode = mode
            val internalChannel = Channel<Byte>(capacity = UNLIMITED)
            val context = WolfSSLContext(mode.value)
            with(context) {
                val verifyMode = when (mode) {
                    TlsMode.CLIENT -> SSL_VERIFY_PEER
                    TlsMode.SERVER -> SSL_VERIFY_PEER or SSL_VERIFY_FAIL_IF_NO_PEER_CERT
                }
                setVerify(verifyMode, null)
                setCipherList(cipher.value).checkSuccessful()
                loadVerifyBuffer(caCertificate, caCertificate.size.toLong(), SSL_FILETYPE_PEM).checkSuccessful()
                useCertificateChainBufferFormat(certificateChain, certificateChain.size.toLong(), SSL_FILETYPE_PEM).checkSuccessful()
                usePrivateKeyBuffer(pemPrivateKey, pemPrivateKey.size.toLong(), SSL_FILETYPE_PEM).checkSuccessful()
                receiveJob = appScope.launch {
                    incomingEncryptedDataChannel.receiveAsFlow()
                        .buffer(UNLIMITED)
                        .collect { chunk ->
                            chunk.forEach {
                                internalChannel.send(it)
                            }
                        }
                }
            }
            currentSession = WolfSSLSession(context)
            with(currentSession!!) {
                setIORecv { session: WolfSSLSession, buffer: ByteArray, size: Int, context: Any ->
                    runBlocking {
                        for (i in 0 until size) {
                            buffer[i] = internalChannel.receive()
                        }
                        size
                    }
                }
                setIOSend { session: WolfSSLSession, buffer: ByteArray, size: Int, context: Any ->
                    runBlocking {
                        outgoingEncryptedDataChannel.send(buffer.copyOf(size))
                        size
                    }
                }
                when (mode) {
                    TlsMode.CLIENT -> {
                        setConnectState()
                        currentSessionTicket?.let {
                            val sessionTicketResult = setSessionTicket(it)
                            if (sessionTicketResult != SSL_SUCCESS) {
                                Log.e("WolfSSL-JNI", "Failed to set session ticket for resumption")
                            } else {
                                Log.d("WolfSSL-JNI", "Session ticket set for resumption")
                            }
                        }
                        setSessionTicketCb({ session, sessionTicket, context ->
                            Log.d("WolfSSL-JNI", "Session ticket received for resumption")
                            currentSessionTicket = sessionTicket
                            0
                        }, context)
                    }
                    TlsMode.SERVER -> setAcceptState()
                }
                usingNonblock = 1
                useSessionTicket()


            }
            //pending informs the amount of bytes that are pending to be read
            Log.i("WolfSSL-Kt", "WolfSSL session created successfully")
            Result.success(Unit)
        } catch (e: WolfSSLException) {
            Log.e("WolfSSL-Kt", "Exception preparing connection: ${e.message}")
            Result.failure(e)
        } catch (e: IllegalStateException) {
            Log.e("WolfSSL-Kt", "Exception preparing connection: ${e.message}")
            Result.failure(e)
        }
    }

    fun startConnection(timeoutMs: Int = 15000) : Result<Unit> {
        //operate on the session
        //calling to accept when its a server, and connect when its a client
        //each method has a timeout
        if (currentSession == null) {
            return Result.failure(WolfSSLException("First prepare a TLS connection with prepareTls13Connection and then connect"))
        }
        val mode = currentMode
            ?: return Result.failure(WolfSSLException("TLS mode is unknown. Prepare TLS connection first"))
        val session = currentSession!!
        val deadline = System.currentTimeMillis() + timeoutMs

        while (System.currentTimeMillis() < deadline) {
            val connectionResult = when (mode) {
                TlsMode.CLIENT -> session.connect(timeoutMs)
                TlsMode.SERVER -> session.accept(timeoutMs)
            }
            if (connectionResult == SSL_SUCCESS) {
                return Result.success(Unit)
            }

            val error = session.getError(connectionResult)
            val shouldRetry = error == SSL_ERROR_WANT_READ || error == SSL_ERROR_WANT_WRITE ||
                error == -323 || error == -327
            if (!shouldRetry) {
                val action = if (mode == TlsMode.CLIENT) "connect" else "accept"
                return Result.failure(WolfSSLException("Failed to $action. WolfSSL error: $error"))
            }

            Thread.sleep(10)
        }

        val action = if (mode == TlsMode.CLIENT) "connect" else "accept"
        return Result.failure(WolfSSLException("Failed to $action within timeout=${timeoutMs}ms"))
    }

    fun send(dataToBeSent: ByteArray) : Result<Unit> {
        if (currentSession == null) {
            return Result.failure(WolfSSLException("First prepare a TLS connection with prepareTls13Connection and then connect"))
        }
        if (currentSession!!.gotCloseNotify()) {

        }
        val sentBytes = currentSession!!.write(dataToBeSent, dataToBeSent.size)
        return if (sentBytes == dataToBeSent.size) {
            //success
            Result.success(Unit)
        } else {
            //failure
            val error = currentSession!!.getError(sentBytes)
            Log.e("WolfSSL", "Failed to read data. WolfSSL error: $error")
            Result.failure(WolfSSLException("Failed to send data: $dataToBeSent. WolfSSL error: $error"))
        }
    }

    fun read(delay: Long) : Flow<ByteArray> = flow {
        val buffer = ByteArray(1024)
        while (appScope.isActive) {
            delay(delay)
            if (currentSession == null) {
                return@flow
            }
            if (currentSession!!.shutdown == SSL_SENT_SHUTDOWN || currentSession!!.shutdown == SSL_RECEIVED_SHUTDOWN) {
                return@flow
            }
            if (currentSession!!.gotCloseNotify()) {
                return@flow
            }
            val readBytes = currentSession!!.read(buffer, buffer.size)
            if (readBytes > 0) {
                emit(buffer.copyOf(readBytes))
            } else {
                val error = currentSession!!.getError(readBytes)
                Log.e("WolfSSL", "Failed to read data. WolfSSL error: $error")
            }
        }

    }


    //TODO store session ticket
    //todo check if a session is resumable
    //todo check if a session is done with handshakeDone
    //todo gotCloseNotify to check for shutdown
    //todo useSessionTicket
    //todo check if shutdown is inplace with getShutdown()

    /*
    Stops the current session, we can resume the connection with the session ticket internally stored
     */
    fun stop() {
        //operate on the session
        //freeSSL
        currentSession!!.freeSSL()
        receiveJob.cancel()
    }

    /*
    Releases the current session, we can't resume the connection with the session ticket internally stored, so a new TLS handshake should be performed
     */
    fun release() {
        currentSession?.shutdownSSL()
    }

    fun clear() : Result<Unit> {
        return try {
            currentSession?.shutdownSSL()
            currentSession = null
            currentMode = null
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }

    }

    enum class SupportedCipher(val value: String) {
        TLS_CHACHA20_POLY1305_SHA256("TLS_CHACHA20_POLY1305_SHA256")
    }

    enum class TlsMode(val value: Long) {
        CLIENT(TLSv1_3_ClientMethod()),
        SERVER(TLSv1_3_ServerMethod())
    }

    private fun <T> checkNotNull(value: T?, name: String = "value"): T? {
        if (value == null) {
            val message = "Required $name was null"
            Log.w("WolfSSL-JNI", message)
            return null
        }
        return value
    }

}
