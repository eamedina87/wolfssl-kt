package tech.medina.wolfssl.kt

internal fun ByteArray.toHexString(): String =
    joinToString(separator = " ") { "%02X".format(it) }

internal fun ByteArray.toLogString(): String =
    buildString {
        append("hex=")
        append(this@toLogString.toHexString())
        append(" text=")
        append(this@toLogString.decodeToString())
    }