package com.example.hubengine

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.CookieManager
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.hubengine.bluetooth.BluetoothPrinterManager
import com.example.hubengine.bluetooth.PrintBridge
import com.example.hubengine.bluetooth.PrinterBridge
import com.example.hubengine.camera.QrScannerActivity
import com.example.hubengine.download.PdfDownloadHandler
import com.example.hubengine.print.BluetoothPrintServer
import com.example.hubengine.ui.OfflineFragment
import com.example.hubengine.ui.SetupFragment
import com.google.android.material.floatingactionbutton.FloatingActionButton

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var fab: FloatingActionButton
    private lateinit var printerManager: BluetoothPrinterManager
    private lateinit var pdfHandler: PdfDownloadHandler
    private lateinit var printServer: BluetoothPrintServer
    private val notifManager by lazy {
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager
    }

    private var currentUrl: String = ""

    // Buffer para capturar caracteres do scanner em modo teclado (HID)
    private val scanKeyBuffer = StringBuilder()

    private val scannerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val barcode = intent.getStringExtra("EXTRA_BARCODE_DECODING_DATA") ?: return
            if (currentUrl.contains("hub-pass/validar")) {
                scanKeyBuffer.clear()
                injectQrIntoWebView(barcode)
            }
        }
    }

    private val cameraPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) openQrScanner() }

    private val bluetoothPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* permissão concedida ou negada — PrintBridge lida com SecurityException */ }

    private val notificationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* notificações habilitadas ou não */ }

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
        printServer = BluetoothPrintServer(this)
        printServer.start()

        setupReturnNotificationChannel()
        setupWebView()
        setupFab()
        requestBluetoothPermissionIfNeeded()
        requestNotificationPermissionIfNeeded()

        val savedUrl = getSavedUrl()
        if (savedUrl != null) {
            webView.loadUrl(savedUrl)
        } else {
            showSetupScreen()
        }
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
        webView.addJavascriptInterface(PrintBridge(webView), "AndroidPrint")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                val wasOnValidate = currentUrl.contains("hub-pass/validar")
                val isOnValidate  = url.contains("hub-pass/validar")
                currentUrl = url
                fab.visibility = if (isOnValidate) View.VISIBLE else View.GONE
                if (isOnValidate && !wasOnValidate) activateScanner()
                else if (!isOnValidate && wasOnValidate) deactivateScanner()
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url.toString()
                val scheme = request.url.scheme ?: ""

                if (PdfDownloadHandler.isPdfUrl(url)) {
                    pdfHandler.download(url)
                    return true
                }

                // Schemes não-web (whatsapp://, tel:, mailto:, intent://, etc.) precisam
                // ser abertos pelo sistema — se o WebView tentar carregar, dispara erro.
                if (scheme != "http" && scheme != "https") {
                    if (scheme == "intent") {
                        // URLs intent:// (ex.: deep link do WhatsApp) NÃO podem ser abertas
                        // com ACTION_VIEW — precisam ser desserializadas com parseUri. Se o
                        // app alvo não estiver instalado, usa o browser_fallback_url.
                        try {
                            val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME).apply {
                                addCategory(Intent.CATEGORY_BROWSABLE)
                                component = null
                                selector = null
                            }
                            try {
                                startActivity(intent)
                            } catch (_: ActivityNotFoundException) {
                                val fallback = intent.getStringExtra("browser_fallback_url")
                                if (!fallback.isNullOrEmpty()) {
                                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(fallback)))
                                }
                            }
                        } catch (_: Exception) {}
                    } else {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, request.url))
                        } catch (_: Exception) {}
                    }
                    return true
                }

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

    }

    private fun setupFab() {
        fab = findViewById(R.id.fab_qr_scan)
        fab.setOnClickListener { requestCameraAndScan() }
        fab.setOnLongClickListener {
            clearSavedUrl()
            showSetupScreen()
            true
        }
    }

    private fun activateScanner() {
        // START_SERVICE garante que o daemon do scanner está rodando.
        // OPEN_SCAN_BROADCAST (loop contínuo) foi removido — causava ANR/freeze.
        sendBroadcast(Intent("com.xcheng.scanner.action.START_SERVICE"))
    }

    private fun deactivateScanner() {
        scanKeyBuffer.clear()
    }

    // Fallback: scanner em modo teclado (HID) — intercepta teclas antes do WebView
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (currentUrl.contains("hub-pass/validar") && event.action == KeyEvent.ACTION_DOWN) {
            return when (event.keyCode) {
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    val code = scanKeyBuffer.toString().trim()
                    scanKeyBuffer.clear()
                    if (code.isNotEmpty()) injectQrIntoWebView(code)
                    true
                }
                else -> {
                    val ch = event.getUnicodeChar(event.metaState)
                    if (ch > 0) {
                        scanKeyBuffer.append(ch.toChar())
                        true
                    } else {
                        super.dispatchKeyEvent(event)
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
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
                if (!el || (el.tagName !== 'INPUT' && el.tagName !== 'TEXTAREA')) return;
                var nativeInputValueSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
                nativeInputValueSetter.call(el, '$escaped');
                el.dispatchEvent(new Event('input', { bubbles: true }));
                el.dispatchEvent(new Event('change', { bubbles: true }));
                el.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', keyCode: 13, bubbles: true }));
            })();
            """.trimIndent(),
            null
        )
    }

    override fun onPause() {
        super.onPause()
        showReturnNotification()
        try { unregisterReceiver(scannerReceiver) } catch (_: Exception) {}
    }

    override fun onResume() {
        super.onResume()
        notifManager.cancel(RETURN_NOTIF_ID)
        val filter = IntentFilter().apply {
            addAction("com.xcheng.scanner.action.BARCODE_DECODING_BROADCAST")
            addAction("com.xcheng.scanner.action.BARCODE_DECODING_BROADCAST_INPUT")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(scannerReceiver, filter, RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(scannerReceiver, filter)
        }
    }

    private fun setupReturnNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notifManager.createNotificationChannel(
                NotificationChannel(
                    RETURN_CHANNEL_ID,
                    "Retorno ao App",
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
    }

    private fun showReturnNotification() {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, RETURN_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_revert)
            .setContentTitle("HubEngine PDV")
            .setContentText("Toque aqui para voltar ao app")
            .setContentIntent(pi)
            .setAutoCancel(false)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .build()
        notifManager.notify(RETURN_NOTIF_ID, notif)
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun requestBluetoothPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED
        ) {
            bluetoothPermLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    private fun getSavedUrl(): String? =
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_URL, null)

    private fun saveUrl(url: String) =
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(KEY_URL, url).apply()

    private fun clearSavedUrl() =
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().remove(KEY_URL).apply()

    private fun showSetupScreen() {
        fab.visibility = View.GONE
        supportFragmentManager.beginTransaction()
            .replace(
                R.id.fragment_container,
                SetupFragment.newInstance { url ->
                    saveUrl(url)
                    supportFragmentManager.findFragmentByTag(SetupFragment.TAG)?.let {
                        supportFragmentManager.beginTransaction().remove(it).commit()
                    }
                    webView.loadUrl(url)
                },
                SetupFragment.TAG
            )
            .commit()
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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterKioskMode()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        // No PDV kiosk: block back navigation when at root to prevent leaving the app
    }

    override fun onDestroy() {
        super.onDestroy()
        printerManager.disconnect()
        pdfHandler.unregister()
        printServer.stop()
    }

    companion object {
        private const val PREFS_NAME = "hub_engine_prefs"
        private const val KEY_URL = "pdv_url"
        private const val RETURN_CHANNEL_ID = "hubengine_return"
        private const val RETURN_NOTIF_ID = 1001
    }
}
