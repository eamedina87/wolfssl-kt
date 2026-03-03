package tech.medina.wolfssl_kt.tls

import android.content.Context

data class TlsMaterials(
    val privateKey: ByteArray,
    val caCertificate: ByteArray,
    val certificateChain: ByteArray
)

object TlsMaterialProvider {
    enum class EndpointRole {
        CLIENT,
        SERVER
    }

    fun loadForRole(context: Context, role: EndpointRole): Result<TlsMaterials> {
        return runCatching {
            val caCertificate = context.assets.open("tls/ca-cert.pem").use { it.readBytes() }
            val privateKeyPath = when (role) {
                EndpointRole.CLIENT -> "tls/client-key.pem"
                EndpointRole.SERVER -> "tls/server-key.pem"
            }
            val certificatePath = when (role) {
                EndpointRole.CLIENT -> "tls/client-cert.pem"
                EndpointRole.SERVER -> "tls/server-cert.pem"
            }
            val privateKey = context.assets.open(privateKeyPath).use { it.readBytes() }
            val certificateChain = context.assets.open(certificatePath).use { it.readBytes() }
            TlsMaterials(
                privateKey = privateKey,
                caCertificate = caCertificate,
                certificateChain = certificateChain
            )
        }
    }
}
