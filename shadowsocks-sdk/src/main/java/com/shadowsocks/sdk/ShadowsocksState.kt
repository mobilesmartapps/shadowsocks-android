package com.shadowsocks.sdk

enum class ShadowsocksState {
    /** SDK not yet connected; idle. */
    IDLE,
    /** VPN tunnel is being established. */
    CONNECTING,
    /** VPN tunnel is active. */
    CONNECTED,
    /** VPN tunnel is being torn down. */
    STOPPING,
    /** VPN tunnel has stopped. */
    STOPPED,
}
