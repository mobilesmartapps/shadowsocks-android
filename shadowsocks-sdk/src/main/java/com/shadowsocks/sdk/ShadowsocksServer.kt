package com.shadowsocks.sdk

/**
 * The server a saved profile points at, as returned by [ShadowsocksSDK.getActiveServer].
 *
 * @param host IP address (or host name) of the Shadowsocks server.
 * @param name Display name of the profile; the SDK saves it as "Country — host" when it connects.
 */
data class ShadowsocksServer(val host: String, val name: String)
