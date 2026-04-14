# HubEngine PDV Wrapper — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** App Android que encapsula https://pdv.premiumtrip.com.br/ em WebView fullscreen com suporte a impressora Bluetooth, leitura de QR code (câmera + scanner HID) e notificação de download de PDF.

**Architecture:** Activity única com WebView kiosk; JavascriptInterface expõe `Android.print()` para o site; ML Kit + CameraX para câmera QR; DownloadManager intercepta PDFs.

**Tech Stack:** Kotlin, Android WebView, CameraX 1.4.1, ML Kit Barcode 17.3.0, Bluetooth Classic SPP, DownloadManager, NotificationCompat, Material 3

---

## File Map

| Arquivo | Ação | Responsabilidade |
|---|---|---|
| `gradle/libs.versions.toml` | Modificar | Adicionar Kotlin, CameraX, ML Kit, ConstraintLayout |
| `app/build.gradle.kts` | Modificar | Plugin Kotlin + novas dependências |
| `app/src/main/AndroidManifest.xml` | Modificar | Permissões, MainActivity, QrScannerActivity, FileProvider |
| `app/src/main/res/values/strings.xml` | Modificar | Strings de UI |
| `app/src/main/res/xml/file_provider_paths.xml` | Criar | Caminhos do FileProvider para PDFs |
| `app/src/main/res/drawable/ic_qr_scan.xml` | Criar | Ícone do FAB de câmera |
| `app/src/main/res/layout/activity_main.xml` | Criar | WebView + FAB + container de fragment |
| `app/src/main/res/layout/activity_qr_scanner.xml` | Criar | PreviewView da câmera |
| `app/src/main/res/layout/fragment_offline.xml` | Criar | Tela de sem conexão |
| `app/src/main/java/.../ui/OfflineFragment.kt` | Criar | Fragment de sem conexão com callback retry |
| `app/src/main/java/.../download/PdfDownloadHandler.kt` | Criar | Intercepta PDFs, DownloadManager, Notificação |
| `app/src/main/java/.../bluetooth/BluetoothPrinterManager.kt` | Criar | Conexão SPP, send de bytes |
| `app/src/main/java/.../bluetooth/PrinterBridge.kt` | Criar | JavascriptInterface Android.print() |
| `app/src/main/java/.../camera/QrScannerActivity.kt` | Criar | CameraX + ML Kit → retorna QR string |
| `app/src/main/java/.../MainActivity.kt` | Criar | Orquestra WebView + todos os componentes |
| `app/src/test/java/.../download/PdfDetectionTest.kt` | Criar | Testes unitários de detecção de PDF |
| `app/src/test/java/.../bluetooth/PrinterBridgeTest.kt` | Criar | Testes unitários do decodificador Base64 |

---

## Task 1: Configurar Build — libs.versions.toml + build.gradle.kts

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Substituir gradle/libs.versions.toml pelo conteúdo completo**

```toml
[versions]
agp = "9.1.1"
kotlin = "2.0.21"
coreKtx = "1.10.1"
junit = "4.13.2"
junitVersion = "1.1.5"
espressoCore = "3.5.1"
appcompat = "1.6.1"
material = "1.12.0"
activityKtx = "1.9.0"
constraintlayout = "2.1.4"
cameraX = "1.4.1"
mlkitBarcode = "17.3.0"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
junit = { group = "junit", name = "junit", version.ref = "junit" }
androidx-junit = { group = "androidx.test.ext", name = "junit", version.ref = "junitVersion" }
androidx-espresso-core = { group = "androidx.test.espresso", name = "espresso-core", version.ref = "espressoCore" }
androidx-appcompat = { group = "androidx.appcompat", name = "appcompat", version.ref = "appcompat" }
material = { group = "com.google.android.material", name = "material", version.ref = "material" }
androidx-activity-ktx = { group = "androidx.activity", name = "activity-ktx", version.ref = "activityKtx" }
androidx-constraintlayout = { group = "androidx.constraintlayout", name = "constraintlayout", version.ref = "constraintlayout" }
camera-camera2 = { group = "androidx.camera", name = "camera-camera2", version.ref = "cameraX" }
camera-lifecycle = { group = "androidx.camera", name = "camera-lifecycle", version.ref = "cameraX" }
camera-view = { group = "androidx.camera", name = "camera-view", version.ref = "cameraX" }
mlkit-barcode = { group = "com.google.mlkit", name = "barcode-scanning", version.ref = "mlkitBarcode" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
```

- [ ] **Step 2: Substituir app/build.gradle.kts pelo conteúdo completo**

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.hubengine"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.hubengine"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.mlkit.barcode)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
```

- [ ] **Step 3: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts
git commit -m "build: add Kotlin, CameraX, ML Kit and Material dependencies"
```

---

## Task 2: AndroidManifest + strings + file_provider_paths

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Create: `app/src/main/res/xml/file_provider_paths.xml`

- [ ] **Step 1: Substituir AndroidManifest.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <uses-permission android:name="android.permission.BLUETOOTH_SCAN"
        android:usesPermissionFlags="neverForLocation"
        tools:targetApi="s" />
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
        android:maxSdkVersion="28" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <application
        android:allowBackup="true"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:hardwareAccelerated="true"
        android:theme="@style/Theme.HubEngine">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:configChanges="orientation|screenSize|keyboardHidden"
            android:screenOrientation="landscape">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <activity
            android:name=".camera.QrScannerActivity"
            android:exported="false"
            android:screenOrientation="landscape" />

        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.provider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_provider_paths" />
        </provider>

    </application>

</manifest>
```

- [ ] **Step 2: Criar app/src/main/res/xml/file_provider_paths.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <external-path name="hubengine_downloads" path="Download/HubEngine/" />
</paths>
```

- [ ] **Step 3: Substituir strings.xml**

```xml
<resources>
    <string name="app_name">HubEngine</string>
    <string name="offline_message">Sem conexão com a internet.\nVerifique sua rede e tente novamente.</string>
    <string name="retry">Tentar novamente</string>
    <string name="fab_qr_desc">Ler QR Code pela câmera</string>
    <string name="pdf_downloading">Baixando PDF…</string>
    <string name="pdf_downloaded">PDF baixado</string>
    <string name="pdf_open">Abrir</string>
</resources>
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/res/values/strings.xml app/src/main/res/xml/file_provider_paths.xml
git commit -m "feat: add manifest permissions, FileProvider and UI strings"
```

---

## Task 3: Layouts e drawable

**Files:**
- Create: `app/src/main/res/drawable/ic_qr_scan.xml`
- Create: `app/src/main/res/layout/activity_main.xml`
- Create: `app/src/main/res/layout/activity_qr_scanner.xml`
- Create: `app/src/main/res/layout/fragment_offline.xml`

- [ ] **Step 1: Criar drawable/ic_qr_scan.xml**

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FFFFFF"
        android:pathData="M9.5,6.5v3h-3v-3H9.5M11,5H5v6h6V5zM9.5,14.5v3h-3v-3H9.5M11,13H5v6h6V13zM17.5,6.5v3h-3v-3H17.5M19,5H13v6h6V5zM13,13h1.5v1.5H13zM14.5,14.5H16V16H14.5zM16,13h1.5v1.5H16zM13,16h1.5v1.5H13zM14.5,17.5H16V19H14.5zM16,16h1.5v1.5H16zM17.5,14.5H19V16H17.5zM17.5,17.5H19V19H17.5z"/>
</vector>
```

- [ ] **Step 2: Criar layout/activity_main.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <WebView
        android:id="@+id/webview"
        android:layout_width="match_parent"
        android:layout_height="match_parent" />

    <FrameLayout
        android:id="@+id/fragment_container"
        android:layout_width="match_parent"
        android:layout_height="match_parent" />

    <com.google.android.material.floatingactionbutton.FloatingActionButton
        android:id="@+id/fab_qr_scan"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_margin="16dp"
        android:contentDescription="@string/fab_qr_desc"
        app:srcCompat="@drawable/ic_qr_scan"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />

</androidx.constraintlayout.widget.ConstraintLayout>
```

- [ ] **Step 3: Criar layout/activity_qr_scanner.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<FrameLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="#000000">

    <androidx.camera.view.PreviewView
        android:id="@+id/preview_view"
        android:layout_width="match_parent"
        android:layout_height="match_parent" />

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_gravity="center"
        android:text="Aponte para o QR Code do ingresso"
        android:textColor="#FFFFFF"
        android:textSize="16sp"
        android:background="#66000000"
        android:padding="12dp"
        android:layout_marginBottom="80dp"
        android:layout_gravity="bottom|center_horizontal" />

</FrameLayout>
```

- [ ] **Step 4: Criar layout/fragment_offline.xml**

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:gravity="center"
    android:background="#FFFFFFFF"
    android:padding="32dp">

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="@string/offline_message"
        android:textSize="18sp"
        android:textColor="#333333"
        android:gravity="center"
        android:lineSpacingMultiplier="1.4" />

    <Button
        android:id="@+id/btn_retry"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginTop="24dp"
        android:text="@string/retry" />

</LinearLayout>
```

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res/drawable/ic_qr_scan.xml \
        app/src/main/res/layout/activity_main.xml \
        app/src/main/res/layout/activity_qr_scanner.xml \
        app/src/main/res/layout/fragment_offline.xml
git commit -m "feat: add layouts and QR scan icon drawable"
```

---

## Task 4: OfflineFragment

**Files:**
- Create: `app/src/main/java/com/example/hubengine/ui/OfflineFragment.kt`

- [ ] **Step 1: Criar OfflineFragment.kt**

```kotlin
package com.example.hubengine.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.fragment.app.Fragment
import com.example.hubengine.R

class OfflineFragment : Fragment() {

    private var onRetry: (() -> Unit)? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_offline, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<Button>(R.id.btn_retry).setOnClickListener {
            onRetry?.invoke()
        }
    }

    companion object {
        const val TAG = "offline_fragment"

        fun newInstance(onRetry: () -> Unit): OfflineFragment =
            OfflineFragment().also { it.onRetry = onRetry }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/example/hubengine/ui/OfflineFragment.kt
git commit -m "feat: add OfflineFragment with retry callback"
```

---

## Task 5: PdfDownloadHandler (com testes unitários)

**Files:**
- Create: `app/src/main/java/com/example/hubengine/download/PdfDownloadHandler.kt`
- Create: `app/src/test/java/com/example/hubengine/download/PdfDetectionTest.kt`

- [ ] **Step 1: Criar o teste unitário para detecção de PDF**

Criar o diretório se necessário:
```
app/src/test/java/com/example/hubengine/download/
```

Conteúdo de `PdfDetectionTest.kt`:
```kotlin
package com.example.hubengine.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfDetectionTest {

    @Test
    fun `isPdfUrl returns true for url ending in dot pdf`() {
        assertTrue(PdfDownloadHandler.isPdfUrl("https://example.com/relatorio.pdf"))
    }

    @Test
    fun `isPdfUrl returns true for pdf url with query params`() {
        assertTrue(PdfDownloadHandler.isPdfUrl("https://example.com/doc.pdf?token=abc&ts=123"))
    }

    @Test
    fun `isPdfUrl returns false for html url`() {
        assertFalse(PdfDownloadHandler.isPdfUrl("https://example.com/pagina.html"))
    }

    @Test
    fun `isPdfUrl returns false for empty string`() {
        assertFalse(PdfDownloadHandler.isPdfUrl(""))
    }

    @Test
    fun `isPdfMimeType returns true for application pdf`() {
        assertTrue(PdfDownloadHandler.isPdfMimeType("application/pdf"))
    }

    @Test
    fun `isPdfMimeType returns true for mime type with charset`() {
        assertTrue(PdfDownloadHandler.isPdfMimeType("application/pdf; charset=utf-8"))
    }

    @Test
    fun `isPdfMimeType returns false for image mime type`() {
        assertFalse(PdfDownloadHandler.isPdfMimeType("image/png"))
    }

    @Test
    fun `fileNameFromUrl extracts filename from simple url`() {
        val name = PdfDownloadHandler.fileNameFromUrl("https://example.com/relatorio_2024.pdf")
        assert(name == "relatorio_2024.pdf") { "Expected 'relatorio_2024.pdf' but got '$name'" }
    }

    @Test
    fun `fileNameFromUrl strips query params from filename`() {
        val name = PdfDownloadHandler.fileNameFromUrl("https://example.com/doc.pdf?token=abc")
        assert(name == "doc.pdf") { "Expected 'doc.pdf' but got '$name'" }
    }

    @Test
    fun `fileNameFromUrl returns timestamped fallback for blank name`() {
        val name = PdfDownloadHandler.fileNameFromUrl("https://example.com/")
        assertTrue("Should end with .pdf", name.endsWith(".pdf"))
        assertTrue("Should start with download_", name.startsWith("download_"))
    }
}
```

- [ ] **Step 2: Rodar o teste e confirmar que falha (classe não existe)**

```bash
cd /home/ghoff/AndroidStudioProjects/HubEngine && ./gradlew :app:testDebugUnitTest --tests "com.example.hubengine.download.PdfDetectionTest" 2>&1 | tail -20
```

Esperado: erro de compilação — `PdfDownloadHandler` não existe.

- [ ] **Step 3: Criar PdfDownloadHandler.kt**

```kotlin
package com.example.hubengine.download

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.example.hubengine.R
import java.io.File

class PdfDownloadHandler(private val context: Context) {

    private val downloadManager =
        context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannel()
        registerDownloadReceiver()
    }

    fun download(url: String) {
        val fileName = fileNameFromUrl(url)
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(fileName)
            .setDescription(context.getString(R.string.pdf_downloading))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "HubEngine/$fileName")
            .setMimeType("application/pdf")
        downloadManager.enqueue(request)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Downloads HubEngine",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun registerDownloadReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (id != -1L) notifyDownloadComplete(id)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
        }
    }

    private fun notifyDownloadComplete(downloadId: Long) {
        val query = DownloadManager.Query().setFilterById(downloadId)
        val cursor = downloadManager.query(query)
        if (cursor.moveToFirst()) {
            val statusCol = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
            val localUriCol = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
            if (cursor.getInt(statusCol) == DownloadManager.STATUS_SUCCESSFUL) {
                cursor.getString(localUriCol)?.let { localUri ->
                    showOpenNotification(localUri)
                }
            }
        }
        cursor.close()
    }

    private fun showOpenNotification(localUri: String) {
        val path = Uri.parse(localUri).path ?: return
        val file = File(path)
        val contentUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            file
        )
        val openIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "application/pdf")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context, notificationCounter,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(context.getString(R.string.pdf_downloaded))
            .setContentText(context.getString(R.string.pdf_open))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(notificationCounter++, notification)
    }

    companion object {
        private const val CHANNEL_ID = "hubengine_downloads"
        private var notificationCounter = 2000

        fun isPdfUrl(url: String): Boolean =
            url.lowercase().let { it.endsWith(".pdf") || it.contains(".pdf?") }

        fun isPdfMimeType(mimeType: String): Boolean =
            mimeType.lowercase().contains("pdf")

        fun fileNameFromUrl(url: String): String {
            val raw = url.substringAfterLast("/").substringBefore("?")
            return if (raw.isNotBlank() && raw.endsWith(".pdf")) raw
            else "download_${System.currentTimeMillis()}.pdf"
        }
    }
}
```

- [ ] **Step 4: Rodar o teste e confirmar que passa**

```bash
cd /home/ghoff/AndroidStudioProjects/HubEngine && ./gradlew :app:testDebugUnitTest --tests "com.example.hubengine.download.PdfDetectionTest" 2>&1 | tail -20
```

Esperado: `BUILD SUCCESSFUL` e todos os 9 testes passando.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/hubengine/download/PdfDownloadHandler.kt \
        app/src/test/java/com/example/hubengine/download/PdfDetectionTest.kt
git commit -m "feat: add PdfDownloadHandler with notification and unit tests"
```

---

## Task 6: BluetoothPrinterManager + PrinterBridge (com testes unitários)

**Files:**
- Create: `app/src/main/java/com/example/hubengine/bluetooth/BluetoothPrinterManager.kt`
- Create: `app/src/main/java/com/example/hubengine/bluetooth/PrinterBridge.kt`
- Create: `app/src/test/java/com/example/hubengine/bluetooth/PrinterBridgeTest.kt`

- [ ] **Step 1: Criar o teste unitário para PrinterBridge**

Criar diretório:
```
app/src/test/java/com/example/hubengine/bluetooth/
```

Conteúdo de `PrinterBridgeTest.kt`:
```kotlin
package com.example.hubengine.bluetooth

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class PrinterBridgeTest {

    // Implementação fake de PrinterPort para testes sem Android
    private class FakePrinterPort(private val connected: Boolean = true) : PrinterPort {
        var lastSentBytes: ByteArray? = null
        var sendResult: Boolean = true

        override fun send(data: ByteArray): Boolean {
            lastSentBytes = data
            return sendResult
        }

        override fun isConnected(): Boolean = connected
    }

    @Test
    fun `print decodes valid base64 and sends bytes to printer`() {
        val fake = FakePrinterPort()
        val bridge = PrinterBridge(fake)
        val expectedBytes = byteArrayOf(0x1B, 0x40, 0x48, 0x69)
        val base64 = Base64.getEncoder().encodeToString(expectedBytes)

        val result = bridge.print(base64)

        assertTrue(result)
        assertArrayEquals(expectedBytes, fake.lastSentBytes)
    }

    @Test
    fun `print returns false for invalid base64 string`() {
        val fake = FakePrinterPort()
        val bridge = PrinterBridge(fake)

        val result = bridge.print("!!!NOT_VALID_BASE64!!!")

        assertFalse(result)
    }

    @Test
    fun `isPrinterConnected returns true when port is connected`() {
        val bridge = PrinterBridge(FakePrinterPort(connected = true))
        assertTrue(bridge.isPrinterConnected())
    }

    @Test
    fun `isPrinterConnected returns false when port is disconnected`() {
        val bridge = PrinterBridge(FakePrinterPort(connected = false))
        assertFalse(bridge.isPrinterConnected())
    }

    @Test
    fun `print returns false when printer send fails`() {
        val fake = FakePrinterPort().also { it.sendResult = false }
        val bridge = PrinterBridge(fake)
        val base64 = Base64.getEncoder().encodeToString(byteArrayOf(0x1B, 0x40))

        assertFalse(bridge.print(base64))
    }
}
```

- [ ] **Step 2: Rodar o teste e confirmar que falha**

```bash
cd /home/ghoff/AndroidStudioProjects/HubEngine && ./gradlew :app:testDebugUnitTest --tests "com.example.hubengine.bluetooth.PrinterBridgeTest" 2>&1 | tail -20
```

Esperado: erro de compilação — `PrinterPort`, `PrinterBridge` não existem.

- [ ] **Step 3: Criar a interface PrinterPort e BluetoothPrinterManager.kt**

Criar `app/src/main/java/com/example/hubengine/bluetooth/BluetoothPrinterManager.kt`:

```kotlin
package com.example.hubengine.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.io.IOException
import java.util.UUID

interface PrinterPort {
    fun send(data: ByteArray): Boolean
    fun isConnected(): Boolean
}

class BluetoothPrinterManager(private val context: Context) : PrinterPort {

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    private var socket: BluetoothSocket? = null

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        disconnect()
        try {
            socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
            bluetoothAdapter?.cancelDiscovery()
            socket?.connect()
        } catch (e: IOException) {
            socket?.close()
            socket = null
        }
    }

    override fun send(data: ByteArray): Boolean =
        try {
            socket?.outputStream?.write(data)
            socket?.outputStream?.flush()
            true
        } catch (e: IOException) {
            false
        }

    override fun isConnected(): Boolean = socket?.isConnected == true

    fun disconnect() {
        try { socket?.close() } catch (_: IOException) {}
        socket = null
    }

    @SuppressLint("MissingPermission")
    fun getPairedDevices(): List<BluetoothDevice> =
        bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
```

- [ ] **Step 4: Criar PrinterBridge.kt**

```kotlin
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
```

- [ ] **Step 5: Rodar o teste e confirmar que passa**

```bash
cd /home/ghoff/AndroidStudioProjects/HubEngine && ./gradlew :app:testDebugUnitTest --tests "com.example.hubengine.bluetooth.PrinterBridgeTest" 2>&1 | tail -20
```

Esperado: `BUILD SUCCESSFUL` com 5 testes passando.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/example/hubengine/bluetooth/ \
        app/src/test/java/com/example/hubengine/bluetooth/PrinterBridgeTest.kt
git commit -m "feat: add BluetoothPrinterManager and PrinterBridge with unit tests"
```

---

## Task 7: QrScannerActivity

**Files:**
- Create: `app/src/main/java/com/example/hubengine/camera/QrScannerActivity.kt`

- [ ] **Step 1: Criar QrScannerActivity.kt**

```kotlin
package com.example.hubengine.camera

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.example.hubengine.R
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class QrScannerActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var cameraExecutor: ExecutorService

    // Flag para evitar múltiplos resultados
    @Volatile private var scanned = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qr_scanner)
        previewView = findViewById(R.id.preview_view)
        cameraExecutor = Executors.newSingleThreadExecutor()
        startCamera()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                if (scanned) { imageProxy.close(); return@setAnalyzer }

                val mediaImage = imageProxy.image
                if (mediaImage == null) { imageProxy.close(); return@setAnalyzer }

                val image = InputImage.fromMediaImage(
                    mediaImage,
                    imageProxy.imageInfo.rotationDegrees
                )

                BarcodeScanning.getClient()
                    .process(image)
                    .addOnSuccessListener { barcodes ->
                        barcodes
                            .firstOrNull { it.valueType == Barcode.TYPE_TEXT || it.rawValue != null }
                            ?.rawValue
                            ?.let { value ->
                                if (!scanned) {
                                    scanned = true
                                    returnResult(value)
                                }
                            }
                    }
                    .addOnCompleteListener { imageProxy.close() }
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalysis
                )
            } catch (e: Exception) {
                setResult(Activity.RESULT_CANCELED)
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun returnResult(value: String) {
        val intent = Intent().putExtra(EXTRA_QR_VALUE, value)
        setResult(Activity.RESULT_OK, intent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        const val EXTRA_QR_VALUE = "qr_value"
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/example/hubengine/camera/QrScannerActivity.kt
git commit -m "feat: add QrScannerActivity with CameraX and ML Kit barcode scanning"
```

---

## Task 8: MainActivity — orquestra tudo

**Files:**
- Create: `app/src/main/java/com/example/hubengine/MainActivity.kt`

- [ ] **Step 1: Criar MainActivity.kt**

```kotlin
package com.example.hubengine

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
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

    // Launcher para pedir permissão de câmera
    private val cameraPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) openQrScanner() }

    // Launcher para receber resultado da câmera QR
    private val qrScannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val value = result.data?.getStringExtra(QrScannerActivity.EXTRA_QR_VALUE) ?: return@registerForActivityResult
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

        // JavascriptInterface: o site chama Android.print(base64) ou Android.isPrinterConnected()
        webView.addJavascriptInterface(PrinterBridge(printerManager), "Android")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (PdfDownloadHandler.isPdfUrl(url)) {
                    pdfHandler.download(url)
                    return true
                }
                view.loadUrl(url)
                return true
            }

            override fun onReceivedError(
                view: WebView,
                errorCode: Int,
                description: String,
                failingUrl: String
            ) {
                showOfflineScreen()
            }
        }

        // Intercepta downloads iniciados pelo DownloadManager do WebView
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
     * Injeta o valor do QR no campo ativo da página web.
     * Dispara eventos `input` e `change` para que frameworks como Vue/React detectem.
     */
    private fun injectQrIntoWebView(value: String) {
        // Escapa aspas simples e quebras de linha para uso seguro no JS
        val escaped = value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
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
            .replace(R.id.fragment_container, OfflineFragment.newInstance { reloadWebView() }, OfflineFragment.TAG)
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
    }

    companion object {
        const val PDV_URL = "https://pdv.premiumtrip.com.br/"
    }
}
```

- [ ] **Step 2: Rodar todos os testes unitários**

```bash
cd /home/ghoff/AndroidStudioProjects/HubEngine && ./gradlew :app:testDebugUnitTest 2>&1 | tail -30
```

Esperado: `BUILD SUCCESSFUL` com todos os testes passando.

- [ ] **Step 3: Commit final**

```bash
git add app/src/main/java/com/example/hubengine/MainActivity.kt
git commit -m "feat: add MainActivity — WebView kiosk with QR, Bluetooth and PDF support"
```

---

## Verificação Final

Após todas as tasks:

```bash
# Compilar o projeto
cd /home/ghoff/AndroidStudioProjects/HubEngine && ./gradlew :app:assembleDebug 2>&1 | tail -30
```

Esperado: `BUILD SUCCESSFUL` e APK gerado em `app/build/outputs/apk/debug/app-debug.apk`.

Para instalar em dispositivo conectado:
```bash
./gradlew :app:installDebug
```
