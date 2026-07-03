package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.USER_AGENT
import libsagernetcore.Libsagernetcore
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class SubscriptionResponse(
    val contentString: String,
    val headers: Map<String, String>
)

object SubscriptionHttpClient {

    fun fetch(link: String, customUserAgent: String): SubscriptionResponse {
        val ua = customUserAgent.ifEmpty { USER_AGENT }
        val connected = SagerNet.started && DataStore.startedProfile > 0

        Logs.d("Subscription fetch: allowInsecure=${DataStore.allowInsecureOnRequest}, connected=$connected, link=${link.take(30)}...")

        // If the user explicitly allows insecure subscription requests, go
        // directly and bypass the local proxy even when the VPN is running.
        // Otherwise the proxy core performs the TLS handshake and still applies
        // its own certificate checks/route allowlist, so allowInsecureOnRequest
        // cannot help with self-signed or non-allowlisted subscription hosts.
        if (DataStore.allowInsecureOnRequest) {
            return try {
                fetchViaGo(link, ua, useProxy = false)
            } catch (goEx: Exception) {
                Logs.w("Go direct HTTP failed, trying Java client: ${goEx.message}")
                fetchViaJava(link, ua)
            }
        }

        if (connected) {
            return fetchViaGo(link, ua, useProxy = true)
        }

        return try {
            fetchViaJava(link, ua)
        } catch (javaEx: Exception) {
            Logs.w("Java HTTP failed, trying Go client: ${javaEx.message}")
            try {
                fetchViaGo(link, ua, useProxy = false)
            } catch (goEx: Exception) {
                Logs.w("Go HTTP also failed: ${goEx.message}")
                throw javaEx
            }
        }
    }

    private fun fetchViaGo(link: String, ua: String, useProxy: Boolean): SubscriptionResponse {
        val response = Libsagernetcore.newHttpClient().apply {
            if (useProxy) {
                useUDS(SagerNet.deviceStorage.noBackupFilesDir.toString() + "/ipc.sock")
            }
        }.newRequest().apply {
            if (DataStore.allowInsecureOnRequest) {
                allowInsecure()
            }
            setURL(link)
            setUserAgent(ua)
        }.execute()

        val headers = mutableMapOf<String, String>()
        val subInfo = response.getHeader("Subscription-Userinfo")
        if (subInfo.isNotEmpty()) {
            headers["Subscription-Userinfo"] = subInfo
        }
        return SubscriptionResponse(response.contentString, headers)
    }

    private fun fetchViaJava(link: String, ua: String): SubscriptionResponse {
        val url = URL(link)
        val conn = url.openConnection() as HttpURLConnection
        try {
            if (DataStore.allowInsecureOnRequest && conn is HttpsURLConnection) {
                conn.sslSocketFactory = insecureSslSocketFactory
                conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
            }
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", ua)
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.instanceFollowRedirects = true

            val code = conn.responseCode
            if (code !in 200..299) {
                error("HTTP $code: ${conn.responseMessage}")
            }

            val body = conn.inputStream.bufferedReader().readText()
            val headers = mutableMapOf<String, String>()
            conn.getHeaderField("Subscription-Userinfo")?.let {
                headers["Subscription-Userinfo"] = it
            }
            return SubscriptionResponse(body, headers)
        } finally {
            conn.disconnect()
        }
    }

    private val insecureSslSocketFactory by lazy {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        })
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, SecureRandom())
        sslContext.socketFactory
    }
}
