package tech.medina.wolfssl_kt

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import tech.medina.wolfssl.kt.WolfSSLKt

class App : Application() {

    private val appScope = CoroutineScope(Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            WolfSSLKt.init(appScope, enableLogging = false)
        }
    }

}
