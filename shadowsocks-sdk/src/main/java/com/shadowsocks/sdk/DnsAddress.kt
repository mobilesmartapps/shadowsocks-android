package com.shadowsocks.sdk

/**
 * Checks the DNS resolver address given to [ShadowsocksSDK.setRemoteDns]. The library builds `dns://<address>` and
 * hands it to the local DNS proxy, which asks the resolver from the VPN server, so only a public IPv4 address with an
 * optional `:port` is accepted. IPv6 would need brackets, and a hostname would need an extra lookup on the server, so
 * both are left out on purpose.
 */
object DnsAddress {

    /**
     * True for "a.b.c.d" or "a.b.c.d:port": octets 0-255 and a port 1-65535, written without leading zeros (the proxy's
     * parser rejects "01.1.1.1"), and an address the server can reach on the internet. Rejected ranges: 0.0.0.0/8,
     * 10.0.0.0/8, 100.64.0.0/10 (carrier-grade NAT), 127.0.0.0/8, 169.254.0.0/16, 172.16.0.0/12, 192.168.0.0/16 and
     * everything from 224.0.0.0 up (multicast and reserved, including 255.255.255.255).
     */
    @JvmStatic
    fun isValid(value: String): Boolean {
        val parts = value.split(':')
        if (parts.size > 2) return false
        val port = parts.getOrNull(1)
        if (port != null && !isNumber(port, maxDigits = 5, max = 65535, min = 1)) return false
        val octets = parts[0].split('.')
        if (octets.size != 4) return false
        val n = octets.map { o -> if (isNumber(o, maxDigits = 3, max = 255)) o.toInt() else return false }
        return isPublic(n[0], n[1])
    }

    /** Digits only, no leading zero (except "0" itself), and within [min]..[max]. */
    private fun isNumber(s: String, maxDigits: Int, max: Int, min: Int = 0): Boolean =
        s.isNotEmpty() && s.length <= maxDigits && s.all { it in '0'..'9' } && (s.length == 1 || s[0] != '0') && s.toInt() in min..max

    private fun isPublic(a: Int, b: Int): Boolean = when {
        a == 0 || a == 10 || a == 127 || a >= 224 -> false
        a == 100 && b in 64..127 -> false
        a == 169 && b == 254 -> false
        a == 172 && b in 16..31 -> false
        a == 192 && b == 168 -> false
        else -> true
    }
}
