package tech.medina.wolfssl_kt.tls

import android.content.Context

data class TlsMaterials(
    val privateKey: ByteArray,
    val caCertificate: ByteArray,
    val certificateChain: ByteArray
)

object TlsMaterialProvider {
    private const val TLS_ASSET_DIR = "tls/wolfsslkt-chain"

    enum class EndpointRole {
        CLIENT,
        SERVER
    }

    fun loadForRole(context: Context, role: EndpointRole): Result<TlsMaterials> {
        return runCatching {
            val caCertificate = context.assets.open("$TLS_ASSET_DIR/ca-cert.pem").use { it.readBytes() }
            val privateKeyPath = when (role) {
                EndpointRole.CLIENT -> "$TLS_ASSET_DIR/client-key.pem"
                EndpointRole.SERVER -> "$TLS_ASSET_DIR/server-key.pem"
            }
            val certificatePath = when (role) {
                EndpointRole.CLIENT -> "$TLS_ASSET_DIR/client-fullchain.pem"
                EndpointRole.SERVER -> "$TLS_ASSET_DIR/server-fullchain.pem"
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
