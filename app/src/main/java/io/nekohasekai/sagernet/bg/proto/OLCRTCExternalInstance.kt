/******************************************************************************
 *                                                                            *
 * Copyright (C) 2026  olcRTC for Android contributors                        *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                       *
 *                                                                            *
 * This program is distributed in the hope that it will be useful,            *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 * GNU General Public License for more details.                               *
 *                                                                            *
 * You should have received a copy of the GNU General Public License          *
 * along with this program. If not, see <https://www.gnu.org/licenses/>.      *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.bg.AbstractInstance
import io.nekohasekai.sagernet.bg.VpnService
import io.nekohasekai.sagernet.fmt.olcrtc.OLCRTCBean
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.*
import mobile.LogWriter
import mobile.Mobile
import mobile.SocketProtector
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket

/**
 * Wraps the gomobile-bound olcrtc client (`mobile.Mobile`) as an
 * [AbstractInstance] so that lifecycle is managed by the surrounding
 * [V2RayInstance].
 *
 * Only one olcRTC client may be running at a time because the upstream
 * `mobile.Mobile` API uses package-level state. Attempting to launch a
 * second instance while one is already running will fail; callers should
 * rely on the chain being built so that at most one olcRTC profile is
 * active.
 */
class OLCRTCExternalInstance(
    private val bean: OLCRTCBean,
    private val port: Int,
    private val username: String,
    private val password: String,
    private val onFatalError: (String) -> Unit = {},
) : AbstractInstance {

    companion object {
        private const val MAX_RECONNECT_ATTEMPTS = 10
        private const val INITIAL_BACKOFF_MS = 1_000L
        private const val MAX_BACKOFF_MS = 30_000L
    }

    @Volatile
    private var started = false

    @Volatile
    private var closing = false

    private var reconnectJob: Job? = null
    private var keepaliveJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private fun setupMobileCallbacks() {
        Mobile.setProtector(object : SocketProtector {
            override fun protect(fd: Long): Boolean {
                val vpn = VpnService.instance ?: run {
                    Logs.w("[olcrtc] VpnService.instance is null, cannot protect socket")
                    return false
                }
                return vpn.protect(fd.toInt())
            }
        })
        Mobile.setLogWriter(object : LogWriter {
            override fun writeLog(msg: String?) {
                if (!msg.isNullOrEmpty()) Logs.d("[olcrtc] $msg")
            }
        })
        Mobile.setDebug(BuildConfig.DEBUG)
    }

    private fun startGoClient() {
        val carrier = when (bean.provider) {
            "wb_stream" -> "wbstream"
            else -> bean.provider.ifBlank { OLCRTCBean.PROVIDER_TELEMOST }
        }
        val transport = bean.transport.ifBlank { OLCRTCBean.TRANSPORT_DATACHANNEL }

        // clientID MUST come from the URI/QR (server admin panel issues it via
        // the `client_id=` query parameter — see requirements-server.md S8).
        // The server uses fnv32(clientID) as the vp8channel binding token, so
        // a locally generated UUID would silently desync VP8 RTP frames.
        // Bail out early with a human-readable error instead of starting Go
        // with an empty clientID (which mobile.go rejects with errClientIDRequired
        // anyway, but the message would be opaque).
        val clientId = bean.clientId.orEmpty()
        if (clientId.isEmpty()) {
            throw IllegalStateException(
                "Profile is missing Client ID. Re-import the URI from the server admin panel.",
            )
        }

        // Jitsi auth can carry an optional room password.
        val roomPassword = bean.roomPassword.orEmpty()
        val effectiveRoomId = bean.roomId

        Mobile.setTransport(transport)
        Mobile.setDNS(bean.dnsServer.ifEmpty { "8.8.8.8:53" })

        if (transport == OLCRTCBean.TRANSPORT_VP8CHANNEL) {
            Mobile.setVP8Options(
                bean.vp8Fps.toLong(),
                bean.vp8BatchSize.toLong(),
            )
        }
        Mobile.setLivenessOptions(10_000, 5_000, 3)

        Mobile.startWithTransport(
            carrier,
            transport,
            effectiveRoomId,
            clientId,
            bean.keyHex,
            port.toLong(),
            username,
            password,
        )
        Mobile.waitReady(30_000L)
    }

    override fun launch() {
        closing = false
        if (VpnService.instance == null) {
            throw IllegalStateException("VpnService is not ready. Ensure the profile is started via the main toggle, not in standalone mode.")
        }
        setupMobileCallbacks()
        try {
            startGoClient()
        } catch (e: Exception) {
            try {
                Mobile.stop()
            } catch (_: Exception) {
            }
            throw classifyAndWrapError(e)
        }
        started = true
        startKeepalive()
    }

    /**
     * Classifies the raw Go/transport exception into a user-friendly message.
     * - Configuration errors (missing clientID, bad key) → no retry, clear message.
     * - Provider unavailable (connection refused, 502, timeout) → retryable.
     * - Handshake mismatch (got CLIENT_HELLO) → server not in room.
     */
    private fun classifyAndWrapError(e: Exception): Exception {
        val msg = e.message.orEmpty()
        return when {
            msg.contains("clientID") || msg.contains("Client ID") ||
                msg.contains("keyHex") || msg.contains("carrier is required") ->
                IllegalStateException("olcRTC configuration error: $msg", e)

            msg.contains("unexpected handshake message: got \"CLIENT_HELLO\"") ->
                IllegalStateException(
                    "olcRTC: server is not connected to the room. " +
                        "The client received its own CLIENT_HELLO back. " +
                        "Ensure the server is running and joined the same room.",
                    e,
                )

            msg.contains("connection refused") || msg.contains("502") ||
                msg.contains("connect: connect to room") || msg.contains("dial failed") ->
                IllegalStateException(
                    "olcRTC: provider is unavailable ($msg). " +
                        "The WebRTC provider may be temporarily down. Try a different provider.",
                    e,
                )

            msg.contains("timed out") || msg.contains("timeout") ->
                IllegalStateException(
                    "olcRTC: connection timed out. Check your network or try a different provider.",
                    e,
                )

            else -> e
        }
    }

    private fun onSessionLost(reason: String) {
        if (closing || !started) return
        Logs.w("[olcrtc] session lost: $reason — starting reconnect")
        stopKeepalive()
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            var attempt = 0
            var backoff = INITIAL_BACKOFF_MS
            while (attempt < MAX_RECONNECT_ATTEMPTS && !closing) {
                attempt++
                Logs.i("[olcrtc] reconnect attempt $attempt/$MAX_RECONNECT_ATTEMPTS (backoff ${backoff}ms)")
                try {
                    Mobile.stop()
                } catch (_: Exception) {
                }
                delay(backoff)
                if (closing) break
                try {
                    startGoClient()
                    Logs.i("[olcrtc] reconnected successfully")
                    startKeepalive()
                    return@launch
                } catch (e: Exception) {
                    Logs.w("[olcrtc] reconnect attempt $attempt failed: ${e.message}")
                }
                backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
            if (!closing) {
                val msg = "olcRTC: reconnect failed after $MAX_RECONNECT_ATTEMPTS attempts"
                Logs.e("[olcrtc] $msg")
                onFatalError(msg)
            }
        }
    }

    private fun startKeepalive() {
        val intervalSec = bean.keepaliveIntervalSec.let { if (it <= 0) return else it }
        keepaliveJob = scope.launch {
            while (isActive && started && !closing) {
                delay(intervalSec * 1000L)
                if (closing || !started) break
                try {
                    val socket = Socket(
                        Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
                    )
                    socket.soTimeout = 5_000
                    socket.connect(InetSocketAddress("77.88.8.8", 53), 5_000)
                    socket.close()
                    Logs.d("[olcrtc] keepalive OK")
                } catch (e: Exception) {
                    Logs.w("[olcrtc] keepalive failed: ${e.message}")
                    onSessionLost("keepalive failed: ${e.message}")
                    break
                }
            }
        }
    }

    private fun stopKeepalive() {
        keepaliveJob?.cancel()
    }

    override fun close() {
        closing = true
        stopKeepalive()
        reconnectJob?.cancel()
        scope.cancel()
        if (!started) return
        try {
            Mobile.stop()
        } catch (e: Exception) {
            Logs.w(e)
        } finally {
            started = false
        }
    }
}
