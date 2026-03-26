package com.shadowsocks.sdk

import android.app.Application
import android.content.Context
import android.content.Intent
import com.github.shadowsocks.Core
import com.github.shadowsocks.aidl.IShadowsocksService
import com.github.shadowsocks.aidl.ShadowsocksConnection
import com.github.shadowsocks.aidl.TrafficStats
import com.github.shadowsocks.bg.BaseService
import com.github.shadowsocks.database.Profile
import com.github.shadowsocks.database.ProfileManager
import com.github.shadowsocks.preference.DataStore
import kotlin.reflect.KClass

/**
 * Main entry point for the Shadowsocks VPN SDK.
 *
 * ## Setup (host app Application.onCreate)
 * ```kotlin
 * ShadowsocksSDK.init(this, MainActivity::class.java)
 * ```
 *
 * ## Connect
 * ```kotlin
 * val intent = ShadowsocksSDK.prepareVpn(activity)
 * if (intent != null) {
 *     startActivityForResult(intent, VPN_REQUEST_CODE)
 * } else {
 *     ShadowsocksSDK.connect(context, configJson, callback)
 * }
 * ```
 *
 * ## Disconnect
 * ```kotlin
 * ShadowsocksSDK.disconnect(context)
 * ```
 *
 * ## Notes
 * - The host app must include `google-services.json` (Firebase is used by core).
 * - VPN permission must be granted before calling [connect].
 * - Call [disconnect] in onDestroy / whenever the session ends to free resources.
 */
object ShadowsocksSDK {

    private val connection = ShadowsocksConnection(listenForDeath = true)
    private var sdkCallback: ShadowsocksCallback? = null
    private var initialized = false

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    /**
     * Initialize the SDK. Must be called once from [Application.onCreate] before
     * any other SDK call.
     *
     * @param app                    Host application instance.
     * @param notificationActivityClass  Activity opened when the VPN notification is tapped.
     */
    @JvmStatic
    fun init(app: Application, notificationActivityClass: Class<*>) {
        if (initialized) return
        @Suppress("UNCHECKED_CAST")
        Core.init(app, (notificationActivityClass.kotlin as KClass<out Any>))
        DataStore.initGlobal()
        initialized = true
    }

    // ─── VPN permission ───────────────────────────────────────────────────────

    /**
     * Returns an [Intent] that must be launched via `startActivityForResult` to
     * request VPN permission, or `null` if permission is already granted.
     *
     * Check the result in `onActivityResult`: if `resultCode == RESULT_OK` you
     * can proceed to call [connect].
     */
    @JvmStatic
    fun prepareVpn(context: Context): Intent? =
        android.net.VpnService.prepare(context)

    // ─── Connect ──────────────────────────────────────────────────────────────

    /**
     * Connect to a Shadowsocks server using a JSON configuration string.
     *
     * @param context  Any context (Application context is used internally).
     * @param configJson  Server JSON. See [ShadowsocksConfig] for the expected format.
     * @param callback    Lifecycle and traffic callbacks.
     */
    @JvmStatic
    fun connect(context: Context, configJson: String, callback: ShadowsocksCallback) =
        connect(context, ShadowsocksConfig.fromJson(configJson), callback)

    /**
     * Connect to a Shadowsocks server using a [ShadowsocksConfig] object.
     *
     * @param context  Any context (Application context is used internally).
     * @param config   Server configuration.
     * @param callback Lifecycle and traffic callbacks.
     */
    @JvmStatic
    fun connect(context: Context, config: ShadowsocksConfig, callback: ShadowsocksCallback) {
        check(initialized) { "ShadowsocksSDK is not initialized. Call init() first." }

        sdkCallback = callback

        // Persist the server config as a Profile and activate it
        val profile = upsertProfile(config)
        Core.switchProfile(profile.id)

        // Start the foreground service
        Core.startService()

        // Bind to the running service so we receive state + traffic callbacks
        connection.connect(context.applicationContext, serviceCallback)
        connection.bandwidthTimeout = 1000L   // traffic update every 1 s
    }

    // ─── Disconnect ───────────────────────────────────────────────────────────

    /**
     * Disconnect the active VPN tunnel and release all SDK resources.
     */
    @JvmStatic
    fun disconnect(context: Context) {
        Core.stopService()
        connection.disconnect(context.applicationContext)
        sdkCallback = null
    }

    // ─── Status ───────────────────────────────────────────────────────────────

    /**
     * Returns the current [ShadowsocksState]. Queries the running service; returns
     * [ShadowsocksState.IDLE] if no service is bound.
     */
    @JvmStatic
    fun getState(): ShadowsocksState {
        val raw = try { connection.service?.state } catch (_: Exception) { null }
            ?: return ShadowsocksState.IDLE
        return when (BaseService.State.entries[raw]) {
            BaseService.State.Connecting -> ShadowsocksState.CONNECTING
            BaseService.State.Connected  -> ShadowsocksState.CONNECTED
            BaseService.State.Stopping   -> ShadowsocksState.STOPPING
            BaseService.State.Stopped    -> ShadowsocksState.STOPPED
            BaseService.State.Idle       -> ShadowsocksState.IDLE
        }
    }

    // ─── Internal ─────────────────────────────────────────────────────────────

    private val serviceCallback = object : ShadowsocksConnection.Callback {
        override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
            when (state) {
                BaseService.State.Connecting -> sdkCallback?.onConnecting()
                BaseService.State.Connected  -> sdkCallback?.onConnected()
                BaseService.State.Stopping   -> { /* transitioning — no callback */ }
                BaseService.State.Stopped    -> {
                    if (msg != null) sdkCallback?.onError(msg)
                    sdkCallback?.onDisconnected()
                }
                BaseService.State.Idle       -> sdkCallback?.onDisconnected()
            }
        }

        override fun onServiceConnected(service: IShadowsocksService) {
            // Sync current state immediately after binding
            val state = try { BaseService.State.entries[service.state] } catch (_: Exception) { return }
            when (state) {
                BaseService.State.Connecting -> sdkCallback?.onConnecting()
                BaseService.State.Connected  -> sdkCallback?.onConnected()
                else -> { /* nothing extra needed */ }
            }
        }

        override fun onServiceDisconnected() {
            sdkCallback?.onDisconnected()
        }

        override fun onBinderDied() {
            sdkCallback?.onError("VPN service died unexpectedly")
            sdkCallback?.onDisconnected()
        }

        override fun trafficUpdated(profileId: Long, stats: TrafficStats) {
            sdkCallback?.onTrafficUpdate(stats.txRate, stats.rxRate, stats.txTotal, stats.rxTotal)
        }
    }

    /**
     * Creates or updates a [Profile] in the local database matching [config].
     * Matched by (host, remotePort) so reconnecting to the same server reuses the entry.
     */
    private fun upsertProfile(config: ShadowsocksConfig): Profile {
        val existing = ProfileManager.getAllProfiles()
            ?.firstOrNull { it.host == config.ip && it.remotePort == config.ss.port }
        val profile = existing ?: Profile()
        profile.name = buildString {
            if (config.countryName.isNotBlank()) append("${config.countryName} — ")
            append(config.ip)
        }
        profile.host = config.ip
        profile.remotePort = config.ss.port
        profile.password = config.ss.password
        profile.method = config.ss.method
        return if (existing != null) {
            ProfileManager.updateProfile(profile)
            profile
        } else {
            ProfileManager.createProfile(profile)
        }
    }
}
