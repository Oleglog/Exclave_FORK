package io.nekohasekai.sagernet.ui.profile

import android.content.ClipData
import android.content.ClipboardManager
import android.app.Activity
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreference
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.vkturn.VKTurnBean
import io.nekohasekai.sagernet.fmt.vkturn.toUri
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.showAllowingStateLoss
import io.nekohasekai.sagernet.ui.ProfileSelectActivity
import io.nekohasekai.sagernet.widget.QRCodeDialog

class VKTurnSettingsActivity : ProfileSettingsActivity<VKTurnBean>() {

    companion object {
        private const val MENU_SHARE_QR = 1001
        private const val MENU_SHARE_CLIPBOARD = 1002
    }

    private var targetProfilePreference: Preference? = null
    private var vlessModePreference: SwitchPreference? = null
    private var udpToTurnPreference: SwitchPreference? = null

    private val selectTargetProfile =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val id = result.data?.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0L) ?: 0L
            runOnDefaultDispatcher {
                val profile = ProfileManager.getProfile(id)
                if (profile == null || !isValidTarget(profile)) {
                    onMainDispatcher {
                        Toast.makeText(
                            this@VKTurnSettingsActivity,
                            R.string.vkturn_target_profile_invalid,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    return@runOnDefaultDispatcher
                }
                DataStore.serverVkturnTargetProfile = id
                DataStore.serverVkturnVlessMode = profile.type == ProxyEntity.TYPE_VLESS
                if (profile.type == ProxyEntity.TYPE_WG) {
                    DataStore.serverVkturnUdpToTurn = true
                }
                onMainDispatcher {
                    vlessModePreference?.isChecked = DataStore.serverVkturnVlessMode
                    udpToTurnPreference?.isChecked = DataStore.serverVkturnUdpToTurn
                    updateTargetSummary()
                    dirty = true
                    onBackPressedCallback.isEnabled = true
                }
            }
        }

    override fun createEntity() = VKTurnBean()

    override fun VKTurnBean.init() {
        DataStore.profileName = name
        DataStore.serverAddress = serverAddress
        DataStore.serverPort = serverPort
        DataStore.serverVkturnVkLink = vkLink
        DataStore.serverVkturnVlessMode = vlessMode
        DataStore.serverVkturnVlessBond = vlessBond
        DataStore.serverVkturnStreams = streams
        DataStore.serverVkturnStreamsPerCred = streamsPerCred
        DataStore.serverVkturnUdpToTurn = udpToTurn
        DataStore.serverVkturnManualCaptcha = manualCaptcha
        DataStore.serverVkturnWrapEnabled = wrapEnabled
        DataStore.serverVkturnWrapKeyHex = wrapKeyHex.orEmpty()
        DataStore.serverVkturnDebug = debug
        DataStore.serverVkturnDnsMode = dnsMode.ifEmpty { "auto" }
        DataStore.serverVkturnDnsServers = dnsServers.orEmpty()
        DataStore.serverVkturnTurnHost = turnHost.orEmpty()
        DataStore.serverVkturnTurnPort = turnPort.orEmpty()
        DataStore.serverVkturnTargetProfile = targetProfileId
    }

    override fun VKTurnBean.serialize() {
        name = DataStore.profileName
        serverAddress = DataStore.serverAddress
        serverPort = DataStore.serverPort.takeIf { it > 0 } ?: 56000
        vkLink = DataStore.serverVkturnVkLink
        vlessMode = DataStore.serverVkturnVlessMode
        vlessBond = DataStore.serverVkturnVlessBond && vlessMode
        streams = DataStore.serverVkturnStreams.takeIf { it > 0 } ?: 4
        streamsPerCred = DataStore.serverVkturnStreamsPerCred.takeIf { it > 0 } ?: 10
        udpToTurn = DataStore.serverVkturnUdpToTurn
        manualCaptcha = DataStore.serverVkturnManualCaptcha
        wrapEnabled = DataStore.serverVkturnWrapEnabled
        wrapKeyHex = if (wrapEnabled) DataStore.serverVkturnWrapKeyHex.orEmpty() else ""
        debug = DataStore.serverVkturnDebug
        dnsMode = DataStore.serverVkturnDnsMode.ifEmpty { "auto" }
        dnsServers = DataStore.serverVkturnDnsServers.orEmpty()
        turnHost = DataStore.serverVkturnTurnHost.orEmpty()
        turnPort = DataStore.serverVkturnTurnPort.orEmpty()
        targetProfileId = DataStore.serverVkturnTargetProfile
    }

    private fun buildCurrentUri(): String? {
        return runCatching { VKTurnBean().apply { serialize() }.toUri() }.getOrNull()
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
                    clipboard.setPrimaryClip(ClipData.newPlainText("VK TURN URI", uri))
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
        addPreferencesFromResource(R.xml.vkturn_preferences)
        findPreference<EditTextPreference>(Key.SERVER_VKTURN_WRAP_KEY_HEX)?.summaryProvider =
            PasswordSummaryProvider
        vlessModePreference = findPreference(Key.SERVER_VKTURN_VLESS_MODE)
        udpToTurnPreference = findPreference(Key.SERVER_VKTURN_UDP_TO_TURN)
        targetProfilePreference = findPreference(Key.SERVER_VKTURN_TARGET_PROFILE)
        updateTargetSummary()
        targetProfilePreference?.setOnPreferenceClickListener {
            selectTargetProfile.launch(
                android.content.Intent(
                    this@VKTurnSettingsActivity,
                    ProfileSelectActivity::class.java,
                ),
            )
            true
        }
    }

    private fun updateTargetSummary() {
        val id = DataStore.serverVkturnTargetProfile
        if (id <= 0L) {
            targetProfilePreference?.summary = getString(R.string.vkturn_target_profile_empty)
            return
        }
        runOnDefaultDispatcher {
            val profile = ProfileManager.getProfile(id)
            onMainDispatcher {
                targetProfilePreference?.summary = profile?.let {
                    "${it.displayName()} (${it.displayType()})"
                } ?: getString(R.string.vkturn_target_profile_empty)
            }
        }
    }

    private fun isValidTarget(profile: ProxyEntity): Boolean {
        return profile.type == ProxyEntity.TYPE_VLESS || profile.type == ProxyEntity.TYPE_WG
    }
}
