package io.nekohasekai.sagernet.fmt.vkturn

import io.nekohasekai.sagernet.ktx.queryParameter
import libsagernetcore.Libsagernetcore

fun parseVKTurn(url: String): VKTurnBean {
    val link = Libsagernetcore.parseURL(url)
    return VKTurnBean().apply {
        serverAddress = link.host
        serverPort = link.port.takeIf { it > 0 } ?: 56000
        vkLink = link.queryParameter("vk_link") ?: link.queryParameter("link") ?: ""
        vlessMode = link.queryParameter("vless") == "1"
        vlessBond = link.queryParameter("vless_bond") == "1"
        streams = link.queryParameter("streams")?.toIntOrNull()?.takeIf { it > 0 } ?: 4
        streamsPerCred = link.queryParameter("streams_per_cred")?.toIntOrNull()?.takeIf { it > 0 } ?: 10
        udpToTurn = link.queryParameter("udp") == "1"
        manualCaptcha = link.queryParameter("manual_captcha") == "1"
        wrapEnabled = link.queryParameter("wrap") == "1"
        wrapKeyHex = link.queryParameter("wrap_key") ?: ""
        debug = link.queryParameter("debug") == "1"
        dnsMode = link.queryParameter("dns") ?: "auto"
        dnsServers = link.queryParameter("dns_servers") ?: ""
        turnHost = link.queryParameter("turn") ?: ""
        turnPort = link.queryParameter("turn_port") ?: ""
        name = link.fragment ?: ""
    }
}

fun VKTurnBean.toUri(): String {
    val builder = Libsagernetcore.newURL("vkturn").apply {
        setHostPort(serverAddress, serverPort)
        addQueryParameter("vk_link", vkLink)
        if (vlessMode) addQueryParameter("vless", "1")
        if (vlessBond) addQueryParameter("vless_bond", "1")
        if (streams > 0 && streams != 4) addQueryParameter("streams", streams.toString())
        if (streamsPerCred > 0 && streamsPerCred != 10) {
            addQueryParameter("streams_per_cred", streamsPerCred.toString())
        }
        if (udpToTurn) addQueryParameter("udp", "1")
        if (manualCaptcha) addQueryParameter("manual_captcha", "1")
        if (wrapEnabled) addQueryParameter("wrap", "1")
        if (wrapKeyHex.isNotEmpty()) addQueryParameter("wrap_key", wrapKeyHex)
        if (debug) addQueryParameter("debug", "1")
        if (dnsMode.isNotEmpty() && dnsMode != "auto") addQueryParameter("dns", dnsMode)
        if (dnsServers.isNotEmpty()) addQueryParameter("dns_servers", dnsServers)
        if (turnHost.isNotEmpty()) addQueryParameter("turn", turnHost)
        if (turnPort.isNotEmpty()) addQueryParameter("turn_port", turnPort)
        if (name.isNotEmpty()) fragment = name
    }
    return builder.string
}
