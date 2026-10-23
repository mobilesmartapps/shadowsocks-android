package com.shadowsocks.sdk

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import com.github.shadowsocks.Core
import com.github.shadowsocks.acl.Acl
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
    private const val TAG = "ShadowsocksSDK"
    /** How long [disconnect] waits for the service to report that it stopped before it lets go of the bind anyway. */
    private const val RELEASE_TIMEOUT_MS = 8_000L
    /** The library default for [Profile.remoteDns]. */
    private const val DEFAULT_REMOTE_DNS = "dns.google"
    private val allowedApps = mutableSetOf<String>()
    @Volatile private var excludedApps: Set<String> = emptySet()
    @Volatile private var bypassLan = false
    @Volatile private var remoteDns: String? = null
    /** True from the moment the service binder is delivered until it is released or lost. */
    @Volatile private var bound = false
    /** The service's last reported state, kept so [disconnect] needs no (blocking) call into the service process. */
    @Volatile private var lastState: BaseService.State? = null
    /**
     * True from a [disconnect] that has to wait for the service until the "stopped" report it asked for has arrived. That
     * report can reach the callback after the host has already started its next [connect], and must not be handed to the
     * new connect's callback as a disconnect.
     */
    @Volatile private var expectStopReport = false
    /** True from [disconnect] until the service has reported that it stopped and the bind was let go. */
    @Volatile private var releasePending = false
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val releaseTimeout = Runnable { releaseBind() }
    private var appContext: Context? = null
    /** True from [attach] until the bind it asked for is made: only that bind is reported through onBound. */
    @Volatile private var attachPending = false

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
        appContext = app
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
        attachPending = false   // the bind of this connect is not an answer to attach()
        if (releasePending) {   // this connect keeps the bind that disconnect() was about to let go of
            releasePending = false
            mainHandler.removeCallbacks(releaseTimeout)
        }

        // Persist the server config as a Profile and activate it
        val profile = upsertProfile(config)
        Core.switchProfile(profile.id)

        // Start the foreground service
        Core.startService()

        // Bind to the running service so we receive state + traffic callbacks
        connection.connect(context.applicationContext, serviceCallback)
        connection.bandwidthTimeout = 1000L   // traffic update every 1 s
    }

    /**
     * Binds to the VPN service if it is already running, without starting a tunnel or saving a profile. Call it when the
     * host app starts, so a tunnel that was started without [connect] (Android's Always-on VPN, or a host process that was
     * killed while the service stayed up) is reported through [callback]: [ShadowsocksCallback.onConnected] or
     * [ShadowsocksCallback.onConnecting] fire as soon as the bind is made, and traffic updates follow. When no service is
     * running, nothing is reported and the state stays idle.
     *
     * [getState] answers from this bind, so it keeps saying idle until the bind is made (a moment after this call): use
     * the callback, not [getState], to learn the state right after attaching. A later [connect] reuses the bind and only
     * replaces the callback; [disconnect] releases it.
     */
    @JvmStatic
    fun attach(context: Context, callback: ShadowsocksCallback) {
        check(initialized) { "ShadowsocksSDK is not initialized. Call init() first." }
        sdkCallback = callback
        if (releasePending) releaseBind()   // a bind about to be let go would lose this attach
        // Already bound (the host's view model was recreated): onServiceConnected will not fire again, so answer now.
        // Otherwise the answer comes with the bind made below.
        attachPending = !bound
        connection.connect(context.applicationContext, serviceCallback)
        connection.bandwidthTimeout = 1000L   // traffic update every 1 s once the bind is made
        if (bound) callback.onBound(getState())
    }

    // ─── Disconnect ───────────────────────────────────────────────────────────

    /**
     * Disconnect the active VPN tunnel and release all SDK resources.
     */
    @JvmStatic
    fun disconnect(context: Context) {
        // The excluded list belongs to one session: the host sets it again before the next connect(), so a
        // forgotten call can never carry an old list into a later session.
        excludedApps = emptySet()
        bypassLan = false
        remoteDns = null
        attachPending = false
        appContext = context.applicationContext
        sdkCallback = null
        Core.stopService()
        // Keep the bind until the service has really stopped: getState() answers from it, so letting go at once made
        // the state read "idle" while the service was still shutting down, and a connect() started right away was then
        // ignored by the old service (it only starts from the stopped state) and never ran. The bind is released when
        // the service reports that it stopped (see serviceCallback), or after a timeout if it never does.
        // The state comes from the last report (lastState), not from a call into the service process, which could block
        // the calling thread when that process is frozen.
        val state = if (bound) lastState else null
        if (state == null || state == BaseService.State.Idle || state == BaseService.State.Stopped) {
            releaseBind()
        } else {
            expectStopReport = true
            releasePending = true
            mainHandler.removeCallbacks(releaseTimeout)
            mainHandler.postDelayed(releaseTimeout, RELEASE_TIMEOUT_MS)
        }
    }

    /** Lets go of the bind to the VPN service (the end of [disconnect]). */
    private fun releaseBind() {
        mainHandler.removeCallbacks(releaseTimeout)
        releasePending = false
        expectStopReport = false
        lastState = null
        bound = false
        attachPending = false
        appContext?.let { connection.disconnect(it) }
    }

    // ─── Per-app VPN ─────────────────────────────────────────────────────────

    /**
     * Adds an app to the VPN allowed list. When at least one app is added,
     * only those apps will have their traffic routed through the VPN (per-app
     * VPN mode). Call before [connect]. To change it for a running tunnel, call
     * [disconnect] and then [connect] (the service ignores a second [connect] while running).
     *
     * @param packageName Package name of the app to allow (e.g. "com.example.myapp").
     */
    @JvmStatic
    fun addAllowedApp(packageName: String) {
        allowedApps.add(packageName)
    }

    /**
     * Removes an app from the VPN allowed list. If the list becomes empty,
     * VPN reverts to device-wide mode. To change it for a running tunnel, call
     * [disconnect] and then [connect].
     *
     * @param packageName Package name of the app to remove.
     */
    @JvmStatic
    fun removeAllowedApp(packageName: String) {
        allowedApps.remove(packageName)
    }

    /**
     * Clears all allowed apps, reverting VPN to device-wide mode.
     * To change it for a running tunnel, call [disconnect] and then [connect].
     */
    @JvmStatic
    fun clearAllowedApps() {
        allowedApps.clear()
    }

    /**
     * Sets the apps that must NOT use the VPN: their traffic goes out on the normal connection
     * (split tunneling). Every other app uses the VPN. The list is written into the profile at
     * the same moment [connect] saves it, before the VPN service starts, so it is always in effect
     * for that connection. To change the list for a running tunnel, call [disconnect] and then [connect]: the
     * service ignores a second [connect] while it is running.
     *
     * The list applies to one session: [disconnect] clears it, so set it again before every [connect].
     * It is ignored (and a warning is logged) while the allowed list ([addAllowedApp]) is non-empty,
     * because that selects the opposite mode (only the listed apps use the VPN).
     *
     * @param packageNames Package names to exclude; an empty collection clears the list.
     */
    @JvmStatic
    fun setExcludedApps(packageNames: Collection<String>) {
        excludedApps = packageNames.toSet()
    }

    /**
     * Lets traffic to the local network (private ranges such as 192.168.x.x and 10.x.x.x, plus multicast used by
     * printers and TVs) bypass the VPN, so devices on the same Wi-Fi stay reachable. Everything else, including
     * all internet traffic, still goes through the VPN. The route is written into the profile when [connect]
     * saves it, before the VPN service starts. Applies to one session: [disconnect] resets it to false, so set it
     * again before every [connect]. To change it for a running tunnel, call [disconnect] and then [connect]: the
     * service ignores a second [connect] while it is running.
     */
    @JvmStatic
    fun setBypassLan(enabled: Boolean) {
        bypassLan = enabled
    }

    /**
     * Chooses the DNS resolver the tunnel uses: DNS queries from the phone go through the tunnel to the server, which
     * asks [host] on the device's behalf. [host] must be an IPv4 address such as "1.1.1.1", optionally with a port
     * ("1.1.1.1:53"); see [DnsAddress]. null or blank means the library default. The value is written into the profile
     * when [connect] saves it, before the VPN service starts. Applies to one session: [disconnect] resets it, so set it
     * again before every [connect]. The query itself is plain DNS over the encrypted tunnel (the library has no
     * DoH/DoT), so only the last hop, server to resolver, is unencrypted.
     *
     * An invalid address is not an error that stops the connection: it is logged and ignored, so the library default is
     * used. Returns true when [host] was accepted (or cleared), false when it was rejected, so a caller can react.
     */
    @JvmStatic
    fun setRemoteDns(host: String?): Boolean {
        val value = host?.trim().orEmpty()
        if (value.isNotEmpty() && !DnsAddress.isValid(value)) {
            Log.w(TAG, "Ignoring invalid DNS address \"$host\": the library default is used")
            remoteDns = null
            return false
        }
        remoteDns = value.ifEmpty { null }
        return true
    }

    /** Clears the excluded list, so every app uses the VPN again. */
    @JvmStatic
    fun clearExcludedApps() {
        excludedApps = emptySet()
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

    /**
     * The server of the profile the service uses, or null when there is none. [connect] saves the profile (and makes it
     * the active one) before it starts the service, and Always-on VPN starts the service from that same saved profile, so
     * this is the server a running tunnel goes through. It reads storage, so it works before the bind of [attach] is made.
     */
    @JvmStatic
    fun getActiveServer(): ShadowsocksServer? {
        if (!initialized) return null
        val profile = try { ProfileManager.getProfile(DataStore.profileId) } catch (_: Exception) { null } ?: return null
        if (profile.host.isBlank()) return null
        return ShadowsocksServer(profile.host, profile.name.orEmpty())
    }

    // ─── Internal ─────────────────────────────────────────────────────────────

    private val serviceCallback = object : ShadowsocksConnection.Callback {
        override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
            lastState = state
            val stopped = state == BaseService.State.Stopped || state == BaseService.State.Idle
            if (stopped && expectStopReport) {
                // The stop that disconnect() asked for. It is not the new connect's disconnect, even when connect() has
                // already replaced the callback by the time this arrives.
                expectStopReport = false
                if (releasePending) releaseBind()
                return
            }
            // A new session has started, so the report of the earlier stop has already gone by.
            if (state == BaseService.State.Connecting || state == BaseService.State.Connected) expectStopReport = false
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
            bound = true
            // Sync current state immediately after binding
            val state = try { BaseService.State.entries[service.state] } catch (_: Exception) { return }
            lastState = state
            when (state) {
                BaseService.State.Connecting -> sdkCallback?.onConnecting()
                BaseService.State.Connected  -> sdkCallback?.onConnected()
                else -> { /* nothing extra needed */ }
            }
            if (attachPending) {
                attachPending = false
                sdkCallback?.onBound(when (state) {
                    BaseService.State.Connecting -> ShadowsocksState.CONNECTING
                    BaseService.State.Connected  -> ShadowsocksState.CONNECTED
                    BaseService.State.Stopping   -> ShadowsocksState.STOPPING
                    BaseService.State.Stopped    -> ShadowsocksState.STOPPED
                    BaseService.State.Idle       -> ShadowsocksState.IDLE
                })
            }
        }

        override fun onServiceDisconnected() {
            bound = false
            lastState = null
            expectStopReport = false
            sdkCallback?.onDisconnected()
        }

        override fun onBinderDied() {
            // The service process is gone: a later attach() must bind again instead of answering from a stale bind.
            bound = false
            lastState = null
            expectStopReport = false
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
        // Routing: with the LAN bypass the VPN service leaves the private ranges out of the tunnel; otherwise all traffic.
        profile.route = if (bypassLan) Acl.BYPASS_LAN else Acl.ALL
        // DNS resolver: always written, so a value saved for an earlier session never lingers in a reused profile.
        profile.remoteDns = remoteDns ?: DEFAULT_REMOTE_DNS
        // Apply per-app VPN config — device-wide if no apps have been added. This runs before the
        // service is started, which is what makes the lists reliable (the service reads the profile
        // as soon as it starts, so a write made after connect() can arrive too late).
        val excluded = excludedApps
        if (allowedApps.isNotEmpty() && excluded.isNotEmpty()) {
            Log.w(TAG, "Excluded apps (${excluded.size}) ignored: the allowed list is in use, which selects the opposite mode")
        }
        if (allowedApps.isNotEmpty()) {
            profile.proxyApps = true
            profile.bypass = false
            profile.individual = allowedApps.joinToString("\n")
        } else if (excluded.isNotEmpty()) {
            // Split tunneling: the listed apps bypass the VPN, everything else uses it.
            profile.proxyApps = true
            profile.bypass = true
            profile.individual = excluded.joinToString("\n")
        } else {
            profile.proxyApps = false
            profile.bypass = false
            profile.individual = ""
        }
        return if (existing != null) {
            ProfileManager.updateProfile(profile)
            profile
        } else {
            ProfileManager.createProfile(profile)
        }
    }
}
