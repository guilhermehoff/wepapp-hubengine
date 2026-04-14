# HubEngine — PDV Web Wrapper Android

**Data:** 2026-04-14
**Status:** Aprovado

## Visão Geral

App Android que encapsula o sistema PDV web `https://pdv.premiumtrip.com.br/` em uma WebView fullscreen (modo quiosque), adicionando suporte nativo a:
- Leitura de QR code via câmera ou scanner Bluetooth HID
- Impressão térmica via Bluetooth Classic (SPP + ESC/POS)
- Download de PDFs com notificação no sistema

---

## Arquitetura

```
┌─────────────────────────────────────────┐
│            HubEngine (Android)           │
│                                         │
│  ┌──────────────────────────────────┐   │
│  │        MainActivity              │   │
│  │   WebView (tela cheia/kiosk)     │   │
│  │   └── carrega pdv.premiumtrip    │   │
│  │                                  │   │
│  │   JavascriptInterface            │   │
│  │   └── Android.print(escData)     │   │
│  └──────────────────────────────────┘   │
│                                         │
│  ┌──────────────┐  ┌─────────────────┐  │
│  │ BluetoothMgr │  │  DownloadMgr    │  │
│  │  - Printer   │  │  - Intercepta   │  │
│  │    (SPP/ESC) │  │    PDFs         │  │
│  │  - Scanner   │  │  - Notificação  │  │
│  │    (HID kbd) │  │    no sistema   │  │
│  └──────────────┘  └─────────────────┘  │
└─────────────────────────────────────────┘
```

---

## Componentes

### MainActivity
- Activity única; WebView ocupa 100% da tela sem ActionBar
- Flags: `SYSTEM_UI_FLAG_FULLSCREEN` + `IMMERSIVE_STICKY`
- WebView configurado com: JavaScript habilitado, `setDomStorageEnabled(true)`, `setMixedContentMode(MIXED_CONTENT_ALWAYS_ALLOW)`, `hardwareAccelerated = true`
- Botão flutuante (FAB) para abrir câmera QR quando scanner Bluetooth não está disponível
- Ao perder conexão: exibe `OfflineFragment` com botão "Tentar novamente"

### BluetoothPrinterManager
- Mantém `BluetoothSocket` conectado via UUID SPP padrão (`00001101-0000-1000-8000-00805F9B34FB`)
- Tela de configuração simples (acessível por gesto ou menu oculto) para selecionar a impressora pareada
- Reconecta automaticamente se a conexão cair

### PrinterBridge (JavascriptInterface)
- Exposto ao WebView como `Android`
- Método `Android.print(base64EscPos)`: decodifica Base64 e envia bytes via socket Bluetooth
- O site PDV é responsável por montar os bytes ESC/POS e chamar este método

### QrScannerActivity
- Abre câmera com preview em tempo real via CameraX
- ML Kit Barcode Scanning detecta QR code offline
- Ao detectar: fecha a câmera e injeta o valor no WebView via `evaluateJavascript()`
- Comportamento idêntico ao scanner HID do ponto de vista da página web

### PdfDownloadHandler
- `WebViewClient` customizado intercepta downloads de PDF (extensão `.pdf` ou `Content-Type: application/pdf`)
- Usa `DownloadManager` para salvar em `Downloads/HubEngine/`
- Ao completar: dispara `NotificationCompat` com ação "Abrir" (abre PDF no visualizador do sistema)

### OfflineFragment
- Exibido quando WebView detecta erro de rede (`onReceivedError`)
- Botão "Tentar novamente" recarrega a URL do PDV

---

## Fluxos

### Leitura de QR Code (duplo modo)

```
Modo 1 — Scanner Bluetooth HID:
  Scanner pareado como teclado → digita texto no campo ativo da web
  → PDV web valida o ingresso via API (sem código Android extra)

Modo 2 — Câmera do celular:
  Usuário toca FAB → QrScannerActivity abre
  → ML Kit detecta QR → fecha câmera
  → webView.evaluateJavascript() injeta valor no campo ativo
  → PDV web valida o ingresso via API
```

### Impressão Térmica

```
PDV web monta bytes ESC/POS em Base64
→ chama Android.print(base64)
→ PrinterBridge decodifica e envia via BluetoothSocket
→ Impressora imprime o cupom
```

### Download PDF

```
PDV web gera link para PDF
→ PdfDownloadHandler intercepta
→ DownloadManager baixa para Downloads/HubEngine/
→ Notificação "PDF baixado — Toque para abrir"
→ Usuário abre no visualizador do sistema
```

---

## Permissões

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" /> <!-- API 31+ -->
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />    <!-- API 31+ -->
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
    android:maxSdkVersion="28" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" /> <!-- API 33+ -->
```

Permissões de runtime (pedidas em contexto de uso):
- `CAMERA` — pedida ao abrir QrScannerActivity pela primeira vez
- `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN` — pedidas ao tentar conectar à impressora
- `POST_NOTIFICATIONS` — pedida na primeira inicialização (API 33+)

---

## Estrutura de Arquivos

```
app/src/main/
├── AndroidManifest.xml
├── java/com/example/hubengine/
│   ├── MainActivity.kt
│   ├── bluetooth/
│   │   ├── BluetoothPrinterManager.kt
│   │   └── PrinterBridge.kt
│   ├── camera/
│   │   └── QrScannerActivity.kt
│   ├── download/
│   │   └── PdfDownloadHandler.kt
│   └── ui/
│       └── OfflineFragment.kt
└── res/
    ├── layout/
    │   ├── activity_main.xml
    │   └── activity_qr_scanner.xml
    └── drawable/
        └── ic_qr_scan.xml
```

---

## Dependências (app/build.gradle.kts)

```kotlin
// ML Kit — leitura QR code via câmera (offline)
implementation("com.google.mlkit:barcode-scanning:17.3.0")

// CameraX
implementation("androidx.camera:camera-camera2:1.4.1")
implementation("androidx.camera:camera-lifecycle:1.4.1")
implementation("androidx.camera:camera-view:1.4.1")

// Core
implementation("androidx.core:core-ktx:1.13.1")
implementation("androidx.activity:activity-ktx:1.9.0")

// UI
implementation("com.google.android.material:material:1.12.0")
implementation("androidx.constraintlayout:constraintlayout:2.1.4")
```

## Configuração de Build

- `minSdk = 26` (Android 8.0)
- `targetSdk = 35`
- `compileSdk = 35`

---

## Resumo

| Funcionalidade | Tecnologia |
|---|---|
| PDV web encapsulado | WebView fullscreen/kiosk |
| QR via scanner Bluetooth | HID (sem código extra) |
| QR via câmera | ML Kit + CameraX |
| Impressora térmica | Bluetooth Classic SPP + ESC/POS |
| Download PDF + notificação | DownloadManager + NotificationCompat |
| Tela offline | OfflineFragment customizado |
