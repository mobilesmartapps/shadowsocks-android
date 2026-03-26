# Keep all public SDK classes and their members
-keep public class com.shadowsocks.sdk.** { *; }

# Keep Shadowsocks service classes referenced by the manifest
-keep class com.github.shadowsocks.bg.VpnService { *; }
-keep class com.github.shadowsocks.bg.ProxyService { *; }
-keep class com.github.shadowsocks.bg.TransproxyService { *; }
