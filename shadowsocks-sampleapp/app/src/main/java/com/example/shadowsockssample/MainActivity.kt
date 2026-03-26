package com.example.shadowsockssample

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.shadowsockssample.databinding.ActivityMainBinding
import com.shadowsocks.sdk.ShadowsocksCallback
import com.shadowsocks.sdk.ShadowsocksConfig
import com.shadowsocks.sdk.ShadowsocksSDK
import com.shadowsocks.sdk.ShadowsocksState

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // Pending config to connect after VPN permission is granted
    private var pendingConfig: ShadowsocksConfig? = null

    // VPN permission launcher
    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            pendingConfig?.let { startVpn(it) }
        } else {
            setStatus("VPN permission denied")
            setButtonsEnabled(true)
        }
        pendingConfig = null
    }

    // Default placeholder shown in the text box on first launch
    private val defaultConfigJson = """
{
    "ip": "194.233.87.174",
    "country_code": "SG",
    "country_name": "Singapore",
    "last_updated_at": 1771836132,
    "ss": {
        "password": "qL293fzV4egalE4hKqlx3c",
        "port": 2342,
        "method": "chacha20-ietf-poly1305"
    },
    "status": { "last_min_traffic": 0 },
    "change_location": false
}""".trimIndent()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.etConfig.setText(defaultConfigJson)
        binding.btnConnect.setOnClickListener { onConnectClicked() }
        binding.btnDisconnect.setOnClickListener { onDisconnectClicked() }

        updateUiForState(ShadowsocksSDK.getState())
    }

    // ── Button handlers ───────────────────────────────────────────────────────

    private fun onConnectClicked() {
        // Dismiss keyboard
        currentFocus?.let {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(it.windowToken, 0)
        }

        val json = binding.etConfig.text.toString().trim()
        if (json.isEmpty()) {
            binding.etConfig.error = getString(R.string.error_empty_config)
            return
        }

        val config = try {
            ShadowsocksConfig.fromJson(json)
        } catch (e: Exception) {
            binding.etConfig.error = getString(R.string.error_invalid_config) + e.message
            return
        }

        setButtonsEnabled(false)

        // Check VPN permission first
        val permIntent: Intent? = ShadowsocksSDK.prepareVpn(this)
        if (permIntent != null) {
            pendingConfig = config
            vpnPermissionLauncher.launch(permIntent)
        } else {
            startVpn(config)
        }
    }

    private fun onDisconnectClicked() {
        // Immediately restore Connect button highlight and disable Disconnect
        binding.btnConnect.isEnabled = true
        binding.btnDisconnect.isEnabled = false
        setStatus("Disconnecting…")
        ShadowsocksSDK.disconnect(this)
        // Poll state until SDK confirms stopped, then show Disconnected
        pollUntilDisconnected()
    }

    private fun pollUntilDisconnected() {
        val handler = Handler(Looper.getMainLooper())
        val pollInterval = 500L // check every 500ms
        handler.postDelayed(object : Runnable {
            override fun run() {
                val state = ShadowsocksSDK.getState()
                if (state == ShadowsocksState.STOPPED || state == ShadowsocksState.IDLE) {
                    setStatus("Disconnected")
                    binding.tvTraffic.text = ""
                    binding.progressBar.visibility = View.GONE
                } else {
                    handler.postDelayed(this, pollInterval)
                }
            }
        }, pollInterval)
    }

    // ── VPN helpers ───────────────────────────────────────────────────────────

    private fun startVpn(config: ShadowsocksConfig) {
        setStatus("Connecting…")
        ShadowsocksSDK.connect(this, config, object : ShadowsocksCallback {
            override fun onConnecting() = runOnUiThread {
                updateUiForState(ShadowsocksState.CONNECTING)
            }

            override fun onConnected() = runOnUiThread {
                updateUiForState(ShadowsocksState.CONNECTED)
            }

            override fun onDisconnected() = runOnUiThread {
                updateUiForState(ShadowsocksState.STOPPED)
            }

            override fun onError(message: String?) = runOnUiThread {
                setStatus("Error: ${message ?: "unknown"}")
                setButtonsEnabled(true)
            }

            override fun onTrafficUpdate(txRate: Long, rxRate: Long, txTotal: Long, rxTotal: Long) =
                runOnUiThread {
                    binding.tvTraffic.text = buildString {
                        append("↑ ${formatBytes(txRate)}/s   ↓ ${formatBytes(rxRate)}/s\n")
                        append("Total  ↑ ${formatBytes(txTotal)}   ↓ ${formatBytes(rxTotal)}")
                    }
                }
        })
    }

    // ── UI state ──────────────────────────────────────────────────────────────

    private fun updateUiForState(state: ShadowsocksState) {
        when (state) {
            ShadowsocksState.CONNECTING -> {
                setStatus("Connecting…")
                binding.btnConnect.isEnabled = false
                binding.btnDisconnect.isEnabled = true
                binding.progressBar.visibility = View.VISIBLE
                binding.tvTraffic.text = ""
            }
            ShadowsocksState.CONNECTED -> {
                setStatus("Connected")
                binding.btnConnect.isEnabled = false
                binding.btnDisconnect.isEnabled = true
                binding.progressBar.visibility = View.GONE
            }
            ShadowsocksState.STOPPING -> {
                setStatus("Disconnecting…")
                binding.btnConnect.isEnabled = false
                binding.btnDisconnect.isEnabled = false
                binding.progressBar.visibility = View.VISIBLE
            }
            ShadowsocksState.STOPPED,
            ShadowsocksState.IDLE -> {
                setStatus("Disconnected")
                binding.btnConnect.isEnabled = true
                binding.btnDisconnect.isEnabled = false
                binding.progressBar.visibility = View.GONE
                binding.tvTraffic.text = ""
            }
        }
    }

    private fun setStatus(msg: String) {
        binding.tvStatus.text = msg
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        binding.btnConnect.isEnabled = enabled
        binding.btnDisconnect.isEnabled = !enabled
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000     -> "%.1f KB".format(bytes / 1_000.0)
        else               -> "$bytes B"
    }
}
