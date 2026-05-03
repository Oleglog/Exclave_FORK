package io.nekohasekai.sagernet.fmt.olcrtc

import io.nekohasekai.sagernet.ktx.queryParameter
import libsagernetcore.Libsagernetcore
import org.json.JSONObject

private val VALID_PROVIDERS = setOf(
    OLCRTCBean.PROVIDER_TELEMOST,
    OLCRTCBean.PROVIDER_JAZZ,
    OLCRTCBean.PROVIDER_WB_STREAM,
    "wb_stream",
)

private val VALID_TRANSPORTS = setOf(
    OLCRTCBean.TRANSPORT_DATACHANNEL,
    OLCRTCBean.TRANSPORT_VP8CHANNEL,
    OLCRTCBean.TRANSPORT_SEICHANNEL,
    OLCRTCBean.TRANSPORT_VIDEOCHANNEL,
)

private val HEX_REGEX = Regex("^[0-9a-fA-F]{64}$")

private fun normalizeCarrier(p: String): String = when (p) {
    "wb_stream" -> OLCRTCBean.PROVIDER_WB_STREAM
    else -> p
}

fun parseOLCRTC(url: String): OLCRTCBean {
    val link = Libsagernetcore.parseURL(url)
    return OLCRTCBean().apply {
        provider = normalizeCarrier(link.username)
        roomId = link.path.trimStart('/')
        keyHex = link.queryParameter("key") ?: ""
        dnsServer = link.queryParameter("dns") ?: "1.1.1.1:53"
        transport = link.queryParameter("transport") ?: OLCRTCBean.TRANSPORT_DATACHANNEL
        name = link.fragment ?: ""

        validate()
    }
}

fun OLCRTCBean.toUri(): String {
    val builder = Libsagernetcore.newURL("olcrtc").apply {
        setHostPort("room", 1)
        username = provider
        path = "/$roomId"
        addQueryParameter("key", keyHex)
        if (transport.isNotEmpty() && transport != OLCRTCBean.TRANSPORT_DATACHANNEL) {
            addQueryParameter("transport", transport)
        }
        if (dnsServer.isNotEmpty() && dnsServer != "1.1.1.1:53") {
            addQueryParameter("dns", dnsServer)
        }
        if (name.isNotEmpty()) {
            fragment = name
        }
    }
    return builder.string
}

fun parseOLCRTCJson(text: String): OLCRTCBean {
    val json = JSONObject(text)
    check(json.optString("type") == "olcrtc") { "Not an olcRTC config" }
    return OLCRTCBean().apply {
        name = json.optString("name", "")
        provider = normalizeCarrier(json.optString("provider", ""))
        transport = json.optString("transport", OLCRTCBean.TRANSPORT_DATACHANNEL)
        roomId = json.optString("room_id", "")
        keyHex = json.optString("key_hex", "")
        dnsServer = json.optString("dns_server", "1.1.1.1:53")

        validate()
    }
}

private fun OLCRTCBean.validate() {
    check(provider in VALID_PROVIDERS) { "Unknown carrier: $provider" }
    check(transport in VALID_TRANSPORTS) { "Unknown transport: $transport" }
    check(roomId.isNotEmpty()) { "room_id is required" }
    check(HEX_REGEX.matches(keyHex)) { "key_hex must be 64 hex characters" }
}
