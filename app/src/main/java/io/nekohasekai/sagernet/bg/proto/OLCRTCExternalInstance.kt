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
import mobile.LogWriter
import mobile.Mobile
import mobile.SocketProtector

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
) : AbstractInstance {

    @Volatile
    private var started = false

    override fun launch() {
        Mobile.setProtector(object : SocketProtector {
            override fun protect(fd: Long): Boolean {
                val vpn = VpnService.instance ?: return true
                return vpn.protect(fd.toInt())
            }
        })
        Mobile.setLogWriter(object : LogWriter {
            override fun writeLog(msg: String?) {
                if (!msg.isNullOrEmpty()) Logs.d("[olcrtc] $msg")
            }
        })
        Mobile.setDebug(BuildConfig.DEBUG)

        Mobile.start(bean.roomId, bean.keyHex, port.toLong(), username, password)
        try {
            Mobile.waitReady(15_000L)
        } catch (e: Exception) {
            try {
                Mobile.stop()
            } catch (_: Exception) {
            }
            throw e
        }
        started = true
    }

    override fun close() {
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
