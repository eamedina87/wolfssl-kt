package tech.medina.wolfssl.kt

import android.util.Log
import com.wolfssl.WolfSSL
import com.wolfssl.WolfSSLContext
import com.wolfssl.WolfSSLException
import com.wolfssl.WolfSSLLoggingCallback
import com.wolfssl.WolfSSLSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object WolfSslKt {

    private var session: WolfSSLSession? = null
    private var context: WolfSSLContext? = null
    private lateinit var appScope: CoroutineScope
    private val loggingCb = WolfSSLLoggingCallback { logLevel, logMessage -> Log.d("WolfSSL-JNI", "${logLevel}: $logMessage") }


    sealed class WolfSSLKtState {

        sealed class InitStates : WolfSSLKtState() {
            object DEFAULT : InitStates()
            object CONTEXT_CREATED : InitStates()
            object SESSION_CREATED : InitStates()
        }
        object IDLE : WolfSSLKtState()
        object LOADING : WolfSSLKtState()

        class INITIALIZED(initState: InitStates) : WolfSSLKtState()

        class ERROR(description: String) : WolfSSLKtState()
    }

    private val _state = MutableStateFlow<WolfSSLKtState>(WolfSSLKtState.IDLE)
    val state: StateFlow<WolfSSLKtState> = _state.asStateFlow()

    suspend fun init(applicationScope: CoroutineScope, enableLogging: Boolean = false) {
        try {
            _state.emit(WolfSSLKtState.LOADING)
            appScope = applicationScope
            WolfSSL.loadLibrary()
            if (enableLogging) {
                WolfSSL.debuggingON()
                WolfSSL.setLoggingCb(loggingCb)
            }
            _state.emit(WolfSSLKtState.INITIALIZED(WolfSSLKtState.InitStates.DEFAULT))
        } catch (e: UnsatisfiedLinkError) {
            val message = "Failed to load WolfSSL library: ${e.message}"
            Log.e("WolfSSL-JNI",message)
            _state.emit(WolfSSLKtState.ERROR(message))
            _state.emit(WolfSSLKtState.IDLE)
        }
    }

    fun prepareConnection() = appScope.launch {
            createTLSv13Context()
            createSession()
        }

    private suspend fun createTLSv13Context() {
        try {
            if (context != null) {
                val message = "Context already created"
                Log.w("WolfSSL-JNI", message)
                _state.emit(WolfSSLKtState.ERROR(message))
                return
            }
            context = WolfSSLContext(WolfSSL.TLSv1_3_Method())
            _state.emit(WolfSSLKtState.INITIALIZED(WolfSSLKtState.InitStates.CONTEXT_CREATED))
        } catch (e: WolfSSLException) {
            val message = "Failed to create TLSv1.3 context: ${e.message}"
            Log.e("WolfSSL-JNI", message)
            _state.emit(WolfSSLKtState.ERROR(message))
        }
    }

    private suspend fun createSession() {
        try {
            session = WolfSSLSession(context)
            _state.emit(WolfSSLKtState.INITIALIZED(WolfSSLKtState.InitStates.SESSION_CREATED))
        } catch (e: WolfSSLException) {
            val message = "Failed to create session: ${e.message}"
            Log.e("WolfSSL-JNI", message)
            _state.emit(WolfSSLKtState.ERROR(message))
        }
    }



}
