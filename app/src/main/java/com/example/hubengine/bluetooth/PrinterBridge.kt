package com.example.hubengine.bluetooth

import android.webkit.JavascriptInterface
import java.util.Base64

class PrinterBridge(private val port: PrinterPort) {

    @JavascriptInterface
    fun print(base64EscPos: String): Boolean =
        try {
            val bytes = Base64.getDecoder().decode(base64EscPos)
            port.send(bytes)
        } catch (e: IllegalArgumentException) {
            false
        }

    @JavascriptInterface
    fun isPrinterConnected(): Boolean = port.isConnected()
}
