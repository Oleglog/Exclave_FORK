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

package io.nekohasekai.sagernet.ui.profile

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.olcrtc.OLCRTCBean
import io.nekohasekai.sagernet.fmt.olcrtc.toUri
import io.nekohasekai.sagernet.ktx.showAllowingStateLoss
import io.nekohasekai.sagernet.widget.QRCodeDialog

class OLCRTCSettingsActivity : ProfileSettingsActivity<OLCRTCBean>() {

    companion object {
        private const val MENU_SHARE_QR = 1001
        private const val MENU_SHARE_CLIPBOARD = 1002
    }

    override fun createEntity() = OLCRTCBean()

    override fun OLCRTCBean.init() {
        DataStore.profileName = name
        DataStore.serverOlcrtcProvider = provider
        DataStore.serverOlcrtcTransport = transport
        DataStore.serverOlcrtcRoomId = roomId
        DataStore.serverOlcrtcKeyHex = keyHex
        DataStore.serverOlcrtcDnsServer = dnsServer
        DataStore.serverOlcrtcVp8Fps = vp8Fps
        DataStore.serverOlcrtcVp8BatchSize = vp8BatchSize
        DataStore.serverOlcrtcKeepaliveInterval = keepaliveIntervalSec
        DataStore.serverOlcrtcPeers = peers
    }

    override fun OLCRTCBean.serialize() {
        name = DataStore.profileName
        provider = DataStore.serverOlcrtcProvider.ifEmpty { OLCRTCBean.PROVIDER_TELEMOST }
        transport = DataStore.serverOlcrtcTransport.ifEmpty { OLCRTCBean.TRANSPORT_DATACHANNEL }
        roomId = DataStore.serverOlcrtcRoomId
        keyHex = DataStore.serverOlcrtcKeyHex
        dnsServer = DataStore.serverOlcrtcDnsServer.ifEmpty { "1.1.1.1:53" }
        vp8Fps = DataStore.serverOlcrtcVp8Fps
        vp8BatchSize = DataStore.serverOlcrtcVp8BatchSize
        keepaliveIntervalSec = DataStore.serverOlcrtcKeepaliveInterval.let { if (it <= 0) 15 else it }
        peers = DataStore.serverOlcrtcPeers.coerceIn(1, 16).let { if (it <= 0) 1 else it }
        serverAddress = "olcrtc"
        serverPort = 1
    }

    private fun buildCurrentUri(): String? {
        return try {
            OLCRTCBean().apply { serialize() }.toUri()
        } catch (_: Exception) {
            null
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val result = super.onCreateOptionsMenu(menu)
        menu.add(Menu.NONE, MENU_SHARE_QR, Menu.NONE, R.string.share_qr_nfc)
        menu.add(Menu.NONE, MENU_SHARE_CLIPBOARD, Menu.NONE, R.string.action_export_clipboard)
        return result
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            MENU_SHARE_QR -> {
                val uri = buildCurrentUri()
                if (uri != null) {
                    QRCodeDialog(uri).showAllowingStateLoss(supportFragmentManager)
                } else {
                    Toast.makeText(this, R.string.action_import_err, Toast.LENGTH_SHORT).show()
                }
                true
            }
            MENU_SHARE_CLIPBOARD -> {
                val uri = buildCurrentUri()
                if (uri != null) {
                    val clipboard = getSystemService(ClipboardManager::class.java)
                    clipboard.setPrimaryClip(ClipData.newPlainText("olcRTC URI", uri))
                    Toast.makeText(this, R.string.action_export_msg, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, R.string.action_import_err, Toast.LENGTH_SHORT).show()
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.olcrtc_preferences)
        findPreference<EditTextPreference>(Key.SERVER_OLCRTC_KEY_HEX)!!.apply {
            summaryProvider = PasswordSummaryProvider
        }
    }

}
