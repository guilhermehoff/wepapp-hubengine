package com.example.hubengine

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import com.example.hubengine.ui.OfflineFragment
import com.example.hubengine.ui.SetupFragment
import com.google.android.material.floatingactionbutton.FloatingActionButton

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var fab: FloatingActionButton
    private lateinit var printerManager: BluetoothPrinterManager
    private lateinit var pdfHandler: PdfDownloadHandler
    private val notifManager by lazy {
        getSystemService(NOTIFICATION_SERVICE) as NotificationManager
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

                // Schemes não-web (whatsapp://, tel:, mailto:, etc.) precisam ser
                // abertos pelo sistema — se o WebView tentar carregar, dispara erro
                if (scheme != "http" && scheme != "https") {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, request.url))
                    } catch (_: Exception) {}
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

    override fun onPause() {
        super.onPause()
        showReturnNotification()
    }

    override fun onResume() {
        super.onResume()
        notifManager.cancel(RETURN_NOTIF_ID)
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
                    fab.visibility = View.VISIBLE
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
        else super.onBackPressed()
    }

    override fun onDestroy() {
        super.onDestroy()
        printerManager.disconnect()
        pdfHandler.unregister()
    }

    companion object {
        private const val PREFS_NAME = "hub_engine_prefs"
        private const val KEY_URL = "pdv_url"
        private const val RETURN_CHANNEL_ID = "hubengine_return"
        private const val RETURN_NOTIF_ID = 1001
    }
}
