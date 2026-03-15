package tech.medina.wolfssl.kt

data class PKIData(val caCertificate: ByteArray, val certificateChain: ByteArray, val pemPrivateKey: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PKIData

        if (!caCertificate.contentEquals(other.caCertificate)) return false
        if (!certificateChain.contentEquals(other.certificateChain)) return false
        if (!pemPrivateKey.contentEquals(other.pemPrivateKey)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = caCertificate.contentHashCode()
        result = 31 * result + certificateChain.contentHashCode()
        result = 31 * result + pemPrivateKey.contentHashCode()
        return result
    }
}