package com.example.shadowsockssample

import android.app.Application
import com.shadowsocks.sdk.ShadowsocksSDK

class SampleApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize the SDK once, pointing notifications back to MainActivity
        ShadowsocksSDK.init(this, MainActivity::class.java)
    }
}
