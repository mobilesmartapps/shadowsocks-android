package com.shadowsocks.sdk

import org.json.JSONObject

/**
 * Server configuration for a Shadowsocks connection.
 *
 * Example JSON:
 * {
 *   "ip": "194.233.87.174",
 *   "country_code": "SG",
 *   "country_name": "Singapore",
 *   "last_updated_at": 1771836132,
 *   "ss": {
 *     "password": "qL293fzV4egalE4hKqlx3c",
 *     "port": 2342,
 *     "method": "chacha20-ietf-poly1305"
 *   },
 *   "status": { "last_min_traffic": 0 },
 *   "change_location": false
 * }
 */
data class ShadowsocksConfig(
    val ip: String,
    val countryCode: String = "",
    val countryName: String = "",
    val lastUpdatedAt: Long = 0L,
    val ss: SsConfig,
    val status: StatusConfig = StatusConfig(),
    val changeLocation: Boolean = false,
) {
    data class SsConfig(
        val password: String,
        val port: Int,
        val method: String,
    )

    data class StatusConfig(
        val lastMinTraffic: Long = 0L,
    )

    companion object {
        /**
         * Parse a JSON string into a [ShadowsocksConfig].
         * Throws [org.json.JSONException] if required fields are missing.
         */
        @JvmStatic
        fun fromJson(json: String): ShadowsocksConfig {
            val obj = JSONObject(json)
            val ssObj = obj.getJSONObject("ss")
            val statusObj = obj.optJSONObject("status")
            return ShadowsocksConfig(
                ip = obj.getString("ip"),
                countryCode = obj.optString("country_code", ""),
                countryName = obj.optString("country_name", ""),
                lastUpdatedAt = obj.optLong("last_updated_at", 0L),
                ss = SsConfig(
                    password = ssObj.getString("password"),
                    port = ssObj.getInt("port"),
                    method = ssObj.getString("method"),
                ),
                status = StatusConfig(
                    lastMinTraffic = statusObj?.optLong("last_min_traffic", 0L) ?: 0L,
                ),
                changeLocation = obj.optBoolean("change_location", false),
            )
        }
    }
}
