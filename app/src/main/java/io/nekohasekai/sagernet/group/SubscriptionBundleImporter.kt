package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.SubscriptionType
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ktx.getBoolean
import io.nekohasekai.sagernet.ktx.getString
import io.nekohasekai.sagernet.ktx.getStringArray
import io.nekohasekai.sagernet.ktx.parseJson

object SubscriptionBundleImporter {

    private const val TYPE_COMPACT = "olcrtc-sub"
    private const val TYPE_VERBOSE = "olcrtc_subscription_bundle"

    suspend fun tryImport(text: String): Boolean {
        val root = runCatching { parseJson(text) }.getOrNull() ?: return false
        if (!root.isJsonObject) return false

        val obj = root.asJsonObject
        val type = obj.getString("type", ignoreCase = true) ?: return false
        if (!type.equals(TYPE_COMPACT, ignoreCase = true) && !type.equals(TYPE_VERBOSE, ignoreCase = true)) {
            return false
        }

        val subscriptionUrl = obj.getString("url", ignoreCase = true)
            ?: obj.getString("u", ignoreCase = true)
            ?: obj.getString("subscription_url", ignoreCase = true)
            ?: return false
        if (!subscriptionUrl.startsWith("http://", ignoreCase = true) &&
            !subscriptionUrl.startsWith("https://", ignoreCase = true)) {
            return false
        }

        val profileUris = (obj.getStringArray("profiles", ignoreCase = true)
            ?: obj.getStringArray("p", ignoreCase = true))
            ?.map { it.trim() }
            ?.filter { it.startsWith("olcrtc://", ignoreCase = true) }
            .orEmpty()

        val profiles = if (profileUris.isEmpty()) {
            emptyList()
        } else {
            RawUpdater.parseRaw(profileUris.joinToString("\n")) ?: return false
        }

        val name = (obj.getString("name", ignoreCase = true)
            ?: obj.getString("n", ignoreCase = true))
            ?.takeIf { it.isNotBlank() }
            ?: "olcRTC subscription"
        val mirrorKey = (obj.getString("mirror_key", ignoreCase = true)
            ?: obj.getString("mk", ignoreCase = true)).orEmpty()
        var mirrorType = ""
        var mirrorUrl = ""
        (obj.get("mirrors") ?: obj.get("m"))?.takeIf { it.isJsonArray }?.asJsonArray?.firstOrNull { it.isJsonObject }?.asJsonObject?.let { mirror ->
            mirrorType = (mirror.getString("type", ignoreCase = true) ?: mirror.getString("t", ignoreCase = true)).orEmpty()
            mirrorUrl = (mirror.getString("url", ignoreCase = true) ?: mirror.getString("u", ignoreCase = true)).orEmpty()
        }

        val group = GroupManager.createGroup(ProxyGroup(
            name = name,
            type = GroupType.SUBSCRIPTION,
            subscription = SubscriptionBean().applyDefaultValues().apply {
                this.type = SubscriptionType.RAW
                link = subscriptionUrl
                deduplication = obj.getBoolean("deduplication", ignoreCase = true)
                    ?: obj.getBoolean("d", ignoreCase = true)
                    ?: true
                updateWhenConnectedOnly = obj.getBoolean("update_when_connected_only", ignoreCase = true)
                    ?: obj.getBoolean("uc", ignoreCase = true)
                    ?: true
                autoUpdate = obj.getBoolean("auto_update", ignoreCase = true)
                    ?: obj.getBoolean("au", ignoreCase = true)
                    ?: false
                this.mirrorType = mirrorType
                this.mirrorUrl = mirrorUrl
                this.mirrorKey = mirrorKey
            }
        ))

        DataStore.selectedGroup = group.id
        for (profile in profiles) {
            ProfileManager.createProfile(group.id, profile)
        }
        runCatching {
            RawUpdater.doUpdate(group, requireNotNull(group.subscription), GroupManager.userInterface, false)
        }.onFailure {
            Logs.w("Initial URL-only subscription refresh failed: ${it.message}")
        }
        return true
    }
}
