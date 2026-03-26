package com.example.shadowsockssample

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.View
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

    // ── Sample server config ──────────────────────────────────────────────────
    // Replace with your real server details
    private val sampleConfigJson = """
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
        }
    """.trimIndent()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnConnect.setOnClickListener { onConnectClicked() }
        binding.btnDisconnect.setOnClickListener { onDisconnectClicked() }

        updateUiForState(ShadowsocksSDK.getState())
    }

    // ── Button handlers ───────────────────────────────────────────────────────

    private fun onConnectClicked() {
        setButtonsEnabled(false)
        val config = ShadowsocksConfig.fromJson(sampleConfigJson)

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
        setButtonsEnabled(false)
        ShadowsocksSDK.disconnect(this)
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
