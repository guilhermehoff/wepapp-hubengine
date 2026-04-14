package com.example.hubengine

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.hubengine.bluetooth.BluetoothPrinterManager
import com.example.hubengine.bluetooth.PrinterBridge
import com.example.hubengine.camera.QrScannerActivity
import com.example.hubengine.download.PdfDownloadHandler
import com.example.hubengine.ui.OfflineFragment
import com.google.android.material.floatingactionbutton.FloatingActionButton

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var fab: FloatingActionButton
    private lateinit var printerManager: BluetoothPrinterManager
    private lateinit var pdfHandler: PdfDownloadHandler

    private val cameraPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) openQrScanner() }

    private val qrScannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val value = result.data?.getStringExtra(QrScannerActivity.EXTRA_QR_VALUE)
                ?: return@registerForActivityResult
            injectQrIntoWebView(value)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterKioskMode()
        setContentView(R.layout.activity_main)

        printerManager = BluetoothPrinterManager(this)
        pdfHandler = PdfDownloadHandler(this)

        setupWebView()
        setupFab()
    }

    private fun enterKioskMode() {
        supportActionBar?.hide()
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        )
    }

    private fun setupWebView() {
        webView = findViewById(R.id.webview)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            setSupportZoom(false)
            builtInZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
        }

        webView.addJavascriptInterface(PrinterBridge(printerManager), "Android")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                if (PdfDownloadHandler.isPdfUrl(url)) {
                    pdfHandler.download(url)
                    return true
                }
                // Return false so WebView handles the URL natively (preserves method/headers)
                return false
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                // Only show offline screen for main frame errors, not sub-resources
                if (request.isForMainFrame) showOfflineScreen()
            }
        }

        webView.setDownloadListener { url, _, _, mimetype, _ ->
            if (PdfDownloadHandler.isPdfUrl(url) || PdfDownloadHandler.isPdfMimeType(mimetype)) {
                pdfHandler.download(url)
            }
        }

        webView.loadUrl(PDV_URL)
    }

    private fun setupFab() {
        fab = findViewById(R.id.fab_qr_scan)
        fab.setOnClickListener { requestCameraAndScan() }
    }

    private fun requestCameraAndScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            openQrScanner()
        } else {
            cameraPermLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun openQrScanner() {
        qrScannerLauncher.launch(Intent(this, QrScannerActivity::class.java))
    }

    /**
     * Injeta o QR code no campo ativo da página web, disparando eventos input e change
     * para que frameworks como Vue/React detectem a mudança.
     */
    private fun injectQrIntoWebView(value: String) {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
        webView.evaluateJavascript(
            """
            (function() {
                var el = document.activeElement;
                if (el && (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA')) {
                    el.value = '$escaped';
                    el.dispatchEvent(new Event('input', { bubbles: true }));
                    el.dispatchEvent(new Event('change', { bubbles: true }));
                }
            })();
            """.trimIndent(),
            null
        )
    }

    private fun showOfflineScreen() {
        if (supportFragmentManager.findFragmentByTag(OfflineFragment.TAG) != null) return
        supportFragmentManager.beginTransaction()
            .replace(
                R.id.fragment_container,
                OfflineFragment.newInstance { reloadWebView() },
                OfflineFragment.TAG
            )
            .commit()
    }

    private fun reloadWebView() {
        supportFragmentManager.findFragmentByTag(OfflineFragment.TAG)?.let {
            supportFragmentManager.beginTransaction().remove(it).commit()
        }
        webView.reload()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        else super.onBackPressed()
    }

    override fun onDestroy() {
        super.onDestroy()
        printerManager.disconnect()
        pdfHandler.unregister()
    }

    companion object {
        const val PDV_URL = "https://pdv.premiumtrip.com.br/"
    }
}
