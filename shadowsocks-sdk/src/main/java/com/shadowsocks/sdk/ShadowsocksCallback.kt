package com.shadowsocks.sdk

/**
 * Callback interface for VPN lifecycle and traffic events.
 *
 * All methods are called on the main thread. Provide empty default
 * implementations so callers only override what they need.
 */
interface ShadowsocksCallback {
    /** VPN tunnel is being established. */
    fun onConnecting() {}

    /** VPN tunnel is active and traffic is flowing. */
    fun onConnected() {}

    /**
     * The answer to [ShadowsocksSDK.attach]: the bind to the VPN service was made, and [state] is the service's state at
     * that moment. It follows [onConnecting] or [onConnected] when the service was already starting or running, so a host
     * can tell "no tunnel is running" (state idle) from "not bound yet". Fires once per [ShadowsocksSDK.attach], at once
     * when the bind already existed, and not for binds made by [ShadowsocksSDK.connect].
     */
    fun onBound(state: ShadowsocksState) {}

    /** VPN tunnel has fully stopped. */
    fun onDisconnected() {}

    /**
     * An error caused the service to stop.
     * @param message Human-readable error description, or null if unknown.
     */
    fun onError(message: String?) {}

    /**
     * Periodic traffic statistics update (delivered every ~1 second while connected).
     * @param txRate  Upload speed in bytes/s.
     * @param rxRate  Download speed in bytes/s.
     * @param txTotal Total bytes uploaded since connection started.
     * @param rxTotal Total bytes downloaded since connection started.
     */
    fun onTrafficUpdate(txRate: Long, rxRate: Long, txTotal: Long, rxTotal: Long) {}
}
