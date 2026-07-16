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

        if (connected) {
            // When the VPN/olcRTC tunnel is already running, subscription URLs
            // must be fetched through the active proxy core first. A direct
            // app-side request can leave through the mobile network instead and
            // hit the carrier allowlist/block page or a different virtual host.
            try {
                return fetchViaGo(link, ua, useProxy = true)
            } catch (proxyEx: Exception) {
                Logs.w("Proxy subscription fetch failed, trying direct client: ${proxyEx.message}")
                try {
                    return fetchViaJava(link, ua)
                } catch (javaEx: Exception) {
                    Logs.w("Direct Java subscription fetch also failed: ${javaEx.message}")
                    throw proxyEx
                }
            }
        }

        if (DataStore.allowInsecureOnRequest) {
            return try {
                fetchViaGo(link, ua, useProxy = false)
            } catch (goEx: Exception) {
                Logs.w("Go direct HTTP failed, trying Java client: ${goEx.message}")
                fetchViaJava(link, ua)
            }
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
            setHeader("Cache-Control", "no-cache, no-store")
            setHeader("Pragma", "no-cache")
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
            conn.useCaches = false
            conn.setRequestProperty("User-Agent", ua)
            conn.setRequestProperty("Cache-Control", "no-cache, no-store")
            conn.setRequestProperty("Pragma", "no-cache")
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
