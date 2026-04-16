package com.example.hubengine.bluetooth;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
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

    private final WebView webView;
    private final Context context;

    public PrintBridge(WebView webView) {
        this.webView = webView;
        this.context = webView.getContext();
    }

    @JavascriptInterface
    @SuppressLint("MissingPermission")
    public void print(String jsonTickets) {
        // Captura o título na thread do WebView para garantir
        final String[] titleContainer = new String[1];
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        webView.post(() -> {
            titleContainer[0] = webView.getTitle();
            latch.countDown();
        });

        try {
            latch.await(500, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) { }

        final String siteName = (titleContainer[0] != null && !titleContainer[0].isEmpty())
                ? titleContainer[0].toUpperCase()
                : "HUB ENGINE";

        new Thread(() -> {
            try {
                JSONArray tickets = new JSONArray(jsonTickets);

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

                try { adapter.cancelDiscovery(); } catch (SecurityException ignored) { }

                BluetoothSocket socket = connectSocket(printer);

                OutputStream out = socket.getOutputStream();

                for (int i = 0; i < tickets.length(); i++) {
                    JSONObject t = tickets.getJSONObject(i);
                    byte[] receipt = buildReceipt(t, siteName);
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

    @SuppressLint("MissingPermission")
    private BluetoothDevice findPrinter(BluetoothAdapter adapter) {
        Set<BluetoothDevice> paired = adapter.getBondedDevices();
        if (paired == null || paired.isEmpty()) return null;

        // Prioridade: dispositivos com nome que lembra impressora
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

        // Fallback: primeiro dispositivo pareado
        return paired.iterator().next();
    }

    @SuppressLint("MissingPermission")
    private BluetoothSocket connectSocket(BluetoothDevice device) throws IOException {
        // Tentativa 1: SDP padrão
        try {
            BluetoothSocket s = device.createRfcommSocketToServiceRecord(SPP_UUID);
            s.connect();
            return s;
        } catch (IOException e1) {
            // Tentativa 2: reflection direto no canal 1 (fix para impressoras que não respondem SDP)
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

    private byte[] buildReceipt(JSONObject t, String siteName) throws Exception {
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

        // --- Header ---
        buf.write(new byte[]{0x1B, 0x61, 0x01});
        buf.write(new byte[]{0x1B, 0x21, 0x38});
        buf.write((siteName + "\n").getBytes(StandardCharsets.UTF_8));
        buf.write(new byte[]{0x1B, 0x21, 0x00});
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

        // --- Power By ---
        buf.write(new byte[]{0x0A}); // Linha em branco
        buf.write(new byte[]{0x1B, 0x61, 0x01}); // Centralizar
        buf.write("Power By Hub Engine\n".getBytes(StandardCharsets.UTF_8));

        // Avanço + corte parcial
        buf.write(new byte[]{0x0A, 0x0A, 0x0A, 0x0A});
        buf.write(new byte[]{0x1D, 0x56, 0x41, 0x0A});

        return buf.toByteArray();
    }

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

    private void showToast(String message) {
        webView.post(() -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show());
    }

    private void returnResult(boolean ok, String message) {
        String safeMsg = message.replace("'", "\\'");
        String js = "window.onPrintResult(" + (ok ? "true" : "false") + ", '" + safeMsg + "');";
        webView.post(() -> webView.evaluateJavascript(js, null));
    }
}
