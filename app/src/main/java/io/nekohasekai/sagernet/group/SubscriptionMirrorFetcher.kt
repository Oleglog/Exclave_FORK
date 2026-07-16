package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.ktx.getString
import io.nekohasekai.sagernet.ktx.parseJson
import io.nekohasekai.sagernet.ktx.USER_AGENT
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.nio.charset.StandardCharsets
import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object SubscriptionMirrorFetcher {

    fun canFetch(type: String?, url: String?, key: String?): Boolean {
        return !type.isNullOrBlank() && !url.isNullOrBlank() && !key.isNullOrBlank()
    }

    fun fetch(type: String, url: String, key: String): String {
        val mirrorContent = when (type.lowercase()) {
            "yandex_disk" -> downloadYandexPublic(url)
            "http", "https" -> httpGet(url)
            else -> error("Unsupported subscription mirror type: $type")
        }
        return decryptMirror(mirrorContent, key)
    }

    private fun downloadYandexPublic(publicUrl: String): String {
        val api = "https://cloud-api.yandex.net/v1/disk/public/resources/download?public_key=" +
                URLEncoder.encode(publicUrl, "UTF-8")
        val meta = httpGet(api)
        val href = parseJson(meta).asJsonObject.getString("href", ignoreCase = true)
            ?: error("Yandex Disk response has no download href")
        return httpGet(href)
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.useCaches = false
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Cache-Control", "no-cache, no-store")
        conn.setRequestProperty("Pragma", "no-cache")
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val body = stream?.bufferedReader()?.readText().orEmpty()
        conn.disconnect()
        if (code !in 200..299) error("Mirror HTTP $code: $body")
        return body
    }

    private fun decryptMirror(text: String, keyB64: String): String {
        val obj = parseJson(text).asJsonObject
        val type = obj.getString("type", ignoreCase = true)
        if (!type.equals("olcrtc-sub-mirror", ignoreCase = true)) {
            error("Invalid subscription mirror type: $type")
        }
        val alg = obj.getString("alg", ignoreCase = true) ?: ""
        if (!alg.equals("AES-256-GCM", ignoreCase = true)) {
            error("Unsupported subscription mirror algorithm: $alg")
        }
        val nonce = decodeUrl(obj.getString("nonce", ignoreCase = true) ?: error("Mirror nonce missing"))
        val ciphertext = decodeUrl(obj.getString("ciphertext", ignoreCase = true) ?: error("Mirror ciphertext missing"))
        val key = decodeUrl(keyB64)
        require(key.size == 32) { "Invalid mirror key size: ${key.size}" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        return String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
    }

    private fun decodeUrl(value: String): ByteArray {
        return Base64.decode(value, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }
}
