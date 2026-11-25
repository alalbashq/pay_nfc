package com.ghallah.pos

import android.os.Bundle
import com.getcapacitor.BridgeActivity
import com.ghallah.bridge.GhallahPlugin

class MainActivity : BridgeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        registerPlugin(GhallahPlugin::class.java)
        super.onCreate(savedInstanceState)
    }
}
