package io.nekohasekai.sagernet.group

import android.util.Base64
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream

object QRPayloadCodec {

    private const val GZIP_PREFIX = "olcrtc+gz:"

    fun decodeIfNeeded(text: String): String {
        val value = text.trim()
        if (!value.startsWith(GZIP_PREFIX, ignoreCase = true)) return text
        val encoded = value.substring(GZIP_PREFIX.length)
        val compressed = Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        GZIPInputStream(ByteArrayInputStream(compressed)).use { gzip ->
            return String(gzip.readBytes(), StandardCharsets.UTF_8)
        }
    }
}
