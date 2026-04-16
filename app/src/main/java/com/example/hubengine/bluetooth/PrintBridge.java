package com.example.hubengine.bluetooth;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

public class PrintBridge {

    private static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    /** Largura em dots do papel (58mm = 384, 80mm = 576). */
    private static final int PRINTER_WIDTH_PX = 384;

    /** Altura máxima da logo impressa (em dots). */
    private static final int LOGO_MAX_HEIGHT_PX = 120;

    private final WebView webView;
    private final Context context;

    public PrintBridge(WebView webView) {
        this.webView = webView;
        this.context = webView.getContext();
    }

    @JavascriptInterface
    @SuppressLint("MissingPermission")
    public void print(String json) {
        new Thread(() -> {
            try {
                // Suporta formato novo { logo, company, tickets:[] }
                // e formato antigo (array direto)
                String logoBase64 = null;
                String companyName = null;
                JSONArray tickets;

                try {
                    JSONObject root = new JSONObject(json);
                    tickets    = root.getJSONArray("tickets");
                    logoBase64 = root.isNull("logo")    ? null : root.getString("logo");
                    companyName = root.isNull("company") ? null : root.getString("company");
                } catch (Exception e) {
                    tickets = new JSONArray(json); // fallback: array direto
                }

                BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
                BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;

                if (adapter == null || !adapter.isEnabled()) {
                    returnResult(false, "Bluetooth desativado.");
                    return;
                }

                BluetoothDevice printer = findPrinter(adapter);
                if (printer == null) {
                    returnResult(false, "Nenhuma impressora pareada encontrada.");
                    return;
                }

                showToast("Conectando: " + printer.getName());

                // Constrói bytes da logo uma vez (usada em todos os tickets)
                byte[] logoBytes = (logoBase64 != null && !logoBase64.isEmpty())
                        ? buildLogoBytes(logoBase64)
                        : null;

                BluetoothSocket socket = connectSocket(printer);
                OutputStream out = socket.getOutputStream();

                for (int i = 0; i < tickets.length(); i++) {
                    JSONObject t = tickets.getJSONObject(i);
                    byte[] receipt = buildReceipt(t, logoBytes, companyName);
                    out.write(receipt);
                    out.flush();
                    Thread.sleep(300);
                }

                out.close();
                socket.close();
                returnResult(true, "OK");

            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                showToast("Erro: " + msg);
                returnResult(false, "Erro: " + msg);
            }
        }).start();
    }

    // -------------------------------------------------------------------------
    // Logo: converte base64 PNG → bytes ESC/POS (GS v 0)
    // -------------------------------------------------------------------------

    private byte[] buildLogoBytes(String base64Logo) {
        try {
            byte[] raw = android.util.Base64.decode(base64Logo, android.util.Base64.DEFAULT);
            Bitmap original = BitmapFactory.decodeByteArray(raw, 0, raw.length);
            if (original == null) return new byte[0];

            // Escala para caber na largura da impressora
            int targetWidth = PRINTER_WIDTH_PX;
            int targetHeight = (int) ((float) original.getHeight() / original.getWidth() * targetWidth);

            // Limita altura para não demorar demais
            if (targetHeight > LOGO_MAX_HEIGHT_PX) {
                targetHeight = LOGO_MAX_HEIGHT_PX;
                targetWidth  = (int) ((float) original.getWidth() / original.getHeight() * targetHeight);
                // Mantém múltiplo de 8
                targetWidth  = (targetWidth / 8) * 8;
            }
            if (targetWidth <= 0) targetWidth = 8;

            Bitmap scaled = Bitmap.createScaledBitmap(original, targetWidth, targetHeight, true);
            original.recycle();

            int width      = scaled.getWidth();
            int height     = scaled.getHeight();
            int bytesPerRow = (width + 7) / 8;

            ByteArrayOutputStream buf = new ByteArrayOutputStream();

            // Centraliza
            buf.write(new byte[]{0x1B, 0x61, 0x01});

            // GS v 0 — raster bit image
            buf.write(new byte[]{0x1D, 0x76, 0x30, 0x00});
            buf.write((byte) (bytesPerRow & 0xFF));
            buf.write((byte) ((bytesPerRow >> 8) & 0xFF));
            buf.write((byte) (height & 0xFF));
            buf.write((byte) ((height >> 8) & 0xFF));

            for (int y = 0; y < height; y++) {
                for (int xByte = 0; xByte < bytesPerRow; xByte++) {
                    int b = 0;
                    for (int bit = 0; bit < 8; bit++) {
                        int x = xByte * 8 + bit;
                        if (x < width) {
                            int pixel = scaled.getPixel(x, y);
                            int alpha = Color.alpha(pixel);
                            if (alpha >= 128) {
                                int gray = (int) (0.299 * Color.red(pixel)
                                        + 0.587 * Color.green(pixel)
                                        + 0.114 * Color.blue(pixel));
                                if (gray < 128) b |= (0x80 >> bit); // pixel escuro = ponto
                            }
                            // pixel transparente ou branco = sem ponto
                        }
                    }
                    buf.write(b);
                }
            }

            scaled.recycle();
            buf.write('\n');
            return buf.toByteArray();

        } catch (Exception e) {
            return new byte[0]; // falha silenciosa, cai no fallback de texto
        }
    }

    // -------------------------------------------------------------------------
    // Recibo
    // -------------------------------------------------------------------------

    private byte[] buildReceipt(JSONObject t, byte[] logoBytes, String companyName) throws Exception {
        String index       = t.getString("index");
        String total       = t.getString("total");
        String loc         = t.getString("loc");
        String carrier     = t.getString("carrier");
        String productName = t.getString("product_name");
        String typeName    = t.getString("type_name");
        String dscType     = t.getString("dsc_type");
        String date        = t.getString("date");
        String time        = t.isNull("time") ? null : t.getString("time");
        String orderLoc    = t.getString("order_loc");
        String buyerName   = t.getString("buyer_name");

        ByteArrayOutputStream buf = new ByteArrayOutputStream();

        // Reset
        buf.write(new byte[]{0x1B, 0x40});

        // --- Header: logo ou nome da empresa ---
        if (logoBytes != null && logoBytes.length > 0) {
            buf.write(logoBytes);
        } else {
            // Fallback: nome da empresa em texto grande
            String header = (companyName != null && !companyName.isEmpty()) ? companyName : "PDV";
            buf.write(new byte[]{0x1B, 0x61, 0x01});
            buf.write(new byte[]{0x1B, 0x21, 0x38});
            buf.write((header + "\n").getBytes(StandardCharsets.UTF_8));
            buf.write(new byte[]{0x1B, 0x21, 0x00});
        }

        buf.write(new byte[]{0x1B, 0x61, 0x01});
        buf.write(("Ingresso " + index + " / " + total + "\n").getBytes(StandardCharsets.UTF_8));
        buf.write(dashedLine());

        // --- Portador ---
        buf.write(new byte[]{0x1B, 0x61, 0x00});
        buf.write("PORTADOR\n".getBytes(StandardCharsets.UTF_8));
        buf.write(new byte[]{0x1B, 0x45, 0x01});
        buf.write((carrier + "\n").getBytes(StandardCharsets.UTF_8));
        buf.write(new byte[]{0x1B, 0x45, 0x00});
        buf.write(dashedLine());

        // --- Produto ---
        buf.write("PRODUTO\n".getBytes(StandardCharsets.UTF_8));
        buf.write(new byte[]{0x1B, 0x45, 0x01});
        buf.write((productName + "\n").getBytes(StandardCharsets.UTF_8));
        buf.write(new byte[]{0x1B, 0x45, 0x00});
        buf.write(("Tipo: " + typeName + " - " + dscType + "\n").getBytes(StandardCharsets.UTF_8));
        buf.write(("Data: " + date + "\n").getBytes(StandardCharsets.UTF_8));
        if (time != null) {
            buf.write(("Hora: " + time + "\n").getBytes(StandardCharsets.UTF_8));
        }
        buf.write(dashedLine());

        // --- QR Code ---
        buf.write(new byte[]{0x1B, 0x61, 0x01});
        buf.write("LEIA NA ENTRADA\n".getBytes(StandardCharsets.UTF_8));
        buf.write(buildQrCode(loc));
        buf.write("\n".getBytes(StandardCharsets.UTF_8));
        buf.write(new byte[]{0x1B, 0x21, 0x10});
        buf.write((loc + "\n").getBytes(StandardCharsets.UTF_8));
        buf.write(new byte[]{0x1B, 0x21, 0x00});
        buf.write(dashedLine());

        // --- Rodapé ---
        buf.write(new byte[]{0x1B, 0x61, 0x00});
        buf.write(("Pedido:    " + orderLoc + "\n").getBytes(StandardCharsets.UTF_8));
        buf.write(("Comprador: " + buyerName + "\n").getBytes(StandardCharsets.UTF_8));

        // Avanço + corte
        buf.write(new byte[]{0x0A, 0x0A, 0x0A, 0x0A});
        buf.write(new byte[]{0x1D, 0x56, 0x41, 0x0A});

        return buf.toByteArray();
    }

    // -------------------------------------------------------------------------
    // Bluetooth helpers
    // -------------------------------------------------------------------------

    @SuppressLint("MissingPermission")
    private BluetoothDevice findPrinter(BluetoothAdapter adapter) {
        Set<BluetoothDevice> paired = adapter.getBondedDevices();
        if (paired == null || paired.isEmpty()) return null;

        for (BluetoothDevice d : paired) {
            String name = d.getName() != null ? d.getName().toUpperCase() : "";
            if (name.contains("ELEPH") || name.contains("LABEL")
                    || name.contains("KAPBOM") || name.contains("KA")
                    || name.contains("PRINT") || name.contains("POS")
                    || name.contains("THERMAL") || name.contains("RPP")
                    || name.contains("MTP") || name.contains("58")
                    || name.contains("80MM")) {
                return d;
            }
        }
        return paired.iterator().next();
    }

    @SuppressLint("MissingPermission")
    private BluetoothSocket connectSocket(BluetoothDevice device) throws IOException {
        try {
            BluetoothSocket s = device.createRfcommSocketToServiceRecord(SPP_UUID);
            s.connect();
            return s;
        } catch (IOException e1) {
            try {
                Method m = device.getClass().getMethod("createRfcommSocket", int.class);
                BluetoothSocket s = (BluetoothSocket) m.invoke(device, 1);
                s.connect();
                return s;
            } catch (Exception e2) {
                throw new IOException("Falha na conexão BT: " + e2.getMessage());
            }
        }
    }

    // -------------------------------------------------------------------------
    // ESC/POS helpers
    // -------------------------------------------------------------------------

    private byte[] buildQrCode(String content) throws Exception {
        ByteArrayOutputStream q = new ByteArrayOutputStream();
        byte[] data = content.getBytes(StandardCharsets.US_ASCII);

        q.write(new byte[]{0x1D, 0x28, 0x6B, 0x04, 0x00, 0x31, 0x41, 0x32, 0x00});
        q.write(new byte[]{0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x43, 0x08});
        q.write(new byte[]{0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x45, 0x30});

        int len = data.length + 3;
        byte pl = (byte) (len & 0xFF);
        byte ph = (byte) ((len >> 8) & 0xFF);
        q.write(new byte[]{0x1D, 0x28, 0x6B, pl, ph, 0x31, 0x50, 0x30});
        q.write(data);
        q.write(new byte[]{0x1D, 0x28, 0x6B, 0x03, 0x00, 0x31, 0x51, 0x30});

        return q.toByteArray();
    }

    private byte[] dashedLine() {
        return "--------------------------------\n".getBytes(StandardCharsets.UTF_8);
    }

    // -------------------------------------------------------------------------
    // UI helpers
    // -------------------------------------------------------------------------

    private void showToast(String message) {
        webView.post(() -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show());
    }

    private void returnResult(boolean ok, String message) {
        String safeMsg = message.replace("'", "\\'");
        String js = "window.onPrintResult(" + (ok ? "true" : "false") + ", '" + safeMsg + "');";
        webView.post(() -> webView.evaluateJavascript(js, null));
    }
}
