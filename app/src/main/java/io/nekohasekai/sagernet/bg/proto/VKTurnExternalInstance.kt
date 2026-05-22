package io.nekohasekai.sagernet.bg.proto

import android.app.PendingIntent
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.AbstractInstance
import io.nekohasekai.sagernet.fmt.vkturn.VKTurnBean
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.lang.reflect.Proxy
import java.util.regex.Pattern

class VKTurnExternalInstance(
    private val bean: VKTurnBean,
    private val port: Int,
    private val onFatalError: (String) -> Unit = {},
) : AbstractInstance {

    companion object {
        private const val MAX_RESTARTS = 8
        private const val CAPTCHA_NOTIFICATION_ID = 7301
        private val CAPTCHA_URL_REGEX = Pattern.compile(
            """(?:manually open this URL|Open this URL in your browser):\s*(https?://\S+)""",
            Pattern.CASE_INSENSITIVE,
        )
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var monitorJob: Job? = null
    @Volatile
    private var closing = false
    @Volatile
    private var started = false
    private var restartCount = 0
    @Volatile
    private var lastCaptchaUrl = ""

    private fun setupLogging() {
        VKTurnMobileBridge.setLogWriter { msg ->
            if (msg.isNullOrBlank()) return@setLogWriter
            msg.lineSequence().forEach { line ->
                if (line.isBlank()) return@forEach
                Logs.d("[vkturn] $line")
                val captcha = CAPTCHA_URL_REGEX.matcher(line)
                if (captcha.find()) {
                    openCaptcha(captcha.group(1))
                }
            }
        }
    }

    private fun openCaptcha(url: String?) {
        if (url.isNullOrBlank() || url == lastCaptchaUrl) return
        lastCaptchaUrl = url
        Logs.w("[vkturn] manual captcha required: $url")
        runCatching {
            SagerNet.clipboard.setPrimaryClip(ClipData.newPlainText("VK TURN captcha", url))
        }.onFailure {
            Logs.w("[vkturn] failed to copy captcha URL: ${it.message}")
        }
        showCaptchaNotification(url)
        runCatching {
            SagerNet.application.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }.onFailure {
            Logs.w("[vkturn] failed to open captcha URL: ${it.message}")
        }
    }

    private fun showCaptchaNotification(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
        val pendingIntent = PendingIntent.getActivity(
            SagerNet.application,
            CAPTCHA_NOTIFICATION_ID,
            intent,
            flags,
        )
        val notification = NotificationCompat.Builder(SagerNet.application, "service-captcha")
            .setSmallIcon(R.drawable.ic_service_active)
            .setContentTitle(SagerNet.application.getString(R.string.vkturn_captcha_title))
            .setContentText(SagerNet.application.getString(R.string.vkturn_captcha_text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(url))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        runCatching {
            NotificationManagerCompat.from(SagerNet.application)
                .notify(CAPTCHA_NOTIFICATION_ID, notification)
        }.onFailure {
            Logs.w("[vkturn] failed to show captcha notification: ${it.message}")
        }
    }

    private fun startGoClient() {
        setupLogging()
        val wrapKey = if (bean.wrapEnabled) bean.wrapKeyHex.orEmpty() else ""
        VKTurnMobileBridge.start(
            "${bean.serverAddress}:${bean.serverPort}",
            bean.vkLink,
            "127.0.0.1:$port",
            bean.vlessMode,
            bean.vlessBond && bean.vlessMode,
            bean.streams.toLong(),
            bean.streamsPerCred.toLong(),
            bean.udpToTurn,
            bean.manualCaptcha,
            bean.turnHost.orEmpty(),
            bean.turnPort.orEmpty(),
            bean.wrapEnabled,
            wrapKey,
            bean.debug || BuildConfig.DEBUG,
            bean.dnsMode.ifEmpty { "auto" },
            bean.dnsServers.orEmpty(),
        )
        VKTurnMobileBridge.waitReady(300_000L)
    }

    override fun launch() {
        closing = false
        startGoClient()
        started = true
        monitorJob = scope.launch {
            while (!closing) {
                delay(1_000L)
                val err = VKTurnMobileBridge.lastError()
                if (err.isNullOrEmpty() || closing) continue
                Logs.w("[vkturn] session ended: $err")
                if (++restartCount > MAX_RESTARTS) {
                    onFatalError("VK TURN: reconnect failed after $MAX_RESTARTS attempts: $err")
                    return@launch
                }
                delay((restartCount * 1_000L).coerceAtMost(30_000L))
                if (closing) return@launch
                runCatching { VKTurnMobileBridge.stop() }
                runCatching { startGoClient() }
                    .onSuccess { restartCount = 0 }
                    .onFailure { Logs.w("[vkturn] restart failed: ${it.message}") }
            }
        }
    }

    override fun close() {
        closing = true
        monitorJob?.cancel()
        scope.cancel()
        if (!started) return
        try {
            VKTurnMobileBridge.stop()
        } catch (e: Exception) {
            Logs.w(e)
        } finally {
            started = false
        }
    }
}

private object VKTurnMobileBridge {

    private val clazz: Class<*> by lazy {
        Class.forName("vkturnmobile.Vkturnmobile")
    }

    private val logWriterClass: Class<*> by lazy {
        Class.forName("vkturnmobile.LogWriter")
    }

    fun setLogWriter(writer: (String?) -> Unit) {
        val proxy = Proxy.newProxyInstance(
            logWriterClass.classLoader,
            arrayOf(logWriterClass),
        ) { _, method, args ->
            if (method.name == "writeLog") {
                writer(args?.getOrNull(0) as? String)
            }
            null
        }
        clazz.getMethod("setLogWriter", logWriterClass).invoke(null, proxy)
    }

    fun start(
        peerAddr: String,
        vkLink: String,
        listenAddr: String,
        vless: Boolean,
        vlessBond: Boolean,
        streams: Long,
        streamsPerCred: Long,
        udp: Boolean,
        manualCaptcha: Boolean,
        turnHost: String,
        turnPort: String,
        wrap: Boolean,
        wrapKeyHex: String,
        debug: Boolean,
        dnsMode: String,
        dnsServers: String,
    ) {
        clazz.getMethod(
            "start",
            String::class.java,
            String::class.java,
            String::class.java,
            java.lang.Boolean.TYPE,
            java.lang.Boolean.TYPE,
            java.lang.Long.TYPE,
            java.lang.Long.TYPE,
            java.lang.Boolean.TYPE,
            java.lang.Boolean.TYPE,
            String::class.java,
            String::class.java,
            java.lang.Boolean.TYPE,
            String::class.java,
            java.lang.Boolean.TYPE,
            String::class.java,
            String::class.java,
        ).invoke(
            null,
            peerAddr,
            vkLink,
            listenAddr,
            vless,
            vlessBond,
            streams,
            streamsPerCred,
            udp,
            manualCaptcha,
            turnHost,
            turnPort,
            wrap,
            wrapKeyHex,
            debug,
            dnsMode,
            dnsServers,
        )
    }

    fun waitReady(timeoutMillis: Long) {
        clazz.getMethod("waitReady", java.lang.Long.TYPE).invoke(null, timeoutMillis)
    }

    fun stop() {
        clazz.getMethod("stop").invoke(null)
    }

    fun lastError(): String {
        return clazz.getMethod("lastError").invoke(null) as? String ?: ""
    }
}
