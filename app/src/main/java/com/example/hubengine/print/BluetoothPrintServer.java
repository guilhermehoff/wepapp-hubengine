package com.example.hubengine.print;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.util.Base64;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.UUID;

/**
 * Servidor HTTP local na porta 8766 que recebe dados ESC/POS em base64
 * (mesmo protocolo do RawBT na porta 8765) e encaminha para a impressora
 * Bluetooth pareada (prioridade: InnerPrinter do Tupi 1500).
 *
 * Permite que qualquer browser no dispositivo (Fully Kiosk, Chrome, etc.)
 * imprima na impressora térmica interna sem precisar da bridge AndroidPrint.
 */
public class BluetoothPrintServer {

    private static final String TAG = "PrintServer";
    public static final int PORT = 8766;
    private static final UUID SPP_UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    private final Context context;
    private ServerSocket serverSocket;
    private volatile boolean running = false;

    public BluetoothPrintServer(Context context) {
        this.context = context.getApplicationContext();
    }

    public void start() {
        if (running) return;
        running = true;
        Thread t = new Thread(this::serve, "print-server");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) {}
    }

    public boolean isRunning() {
        return running;
    }

    // -------------------------------------------------------------------------

    private void serve() {
        try {
            serverSocket = new ServerSocket(PORT);
            Log.i(TAG, "Print server listening on port " + PORT);
            while (running) {
                try {
                    Socket client = serverSocket.accept();
                    new Thread(() -> handle(client), "print-req").start();
                } catch (IOException e) {
                    if (running) Log.w(TAG, "Accept: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Server failed: " + e.getMessage());
            running = false;
        }
    }

    private void handle(Socket client) {
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(client.getInputStream(), "UTF-8"));
            OutputStream out = client.getOutputStream();

            // Linha de requisição
            String requestLine = reader.readLine();
            if (requestLine == null) { client.close(); return; }
            boolean isPost = requestLine.startsWith("POST");

            // Headers
            int contentLength = 0;
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring(15).trim());
                }
            }

            if (!isPost || contentLength <= 0) {
                // GET ou POST vazio → health check
                respond(out, "200 OK", "OK");
                client.close();
                return;
            }

            // Lê body (base64 ESC/POS)
            char[] buf = new char[4096];
            StringBuilder sb = new StringBuilder(contentLength);
            int remaining = contentLength;
            while (remaining > 0) {
                int n = reader.read(buf, 0, Math.min(buf.length, remaining));
                if (n < 0) break;
                sb.append(buf, 0, n);
                remaining -= n;
            }

            byte[] escPos = Base64.decode(sb.toString().trim(), Base64.DEFAULT);
            boolean ok = printToBluetooth(escPos);
            respond(out, ok ? "200 OK" : "500 Error", ok ? "OK" : "BT error");

        } catch (Exception e) {
            Log.e(TAG, "Handle: " + e.getMessage());
        } finally {
            try { client.close(); } catch (IOException ignored) {}
        }
    }

    @SuppressLint("MissingPermission")
    private boolean printToBluetooth(byte[] data) {
        try {
            BluetoothManager bm =
                    (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            if (bm == null) return false;
            BluetoothAdapter adapter = bm.getAdapter();
            if (adapter == null || !adapter.isEnabled()) return false;

            BluetoothDevice printer = findPrinter(adapter);
            if (printer == null) { Log.w(TAG, "No printer paired"); return false; }

            BluetoothSocket socket;
            try {
                socket = printer.createRfcommSocketToServiceRecord(SPP_UUID);
                socket.connect();
            } catch (IOException e) {
                // Fallback por reflexão (canal fixo 1)
                java.lang.reflect.Method m =
                        printer.getClass().getMethod("createRfcommSocket", int.class);
                socket = (BluetoothSocket) m.invoke(printer, 1);
                socket.connect();
            }

            OutputStream btOut = socket.getOutputStream();
            btOut.write(data);
            btOut.flush();
            btOut.close();
            socket.close();
            return true;

        } catch (Exception e) {
            Log.e(TAG, "BT print: " + e.getMessage());
            return false;
        }
    }

    @SuppressLint("MissingPermission")
    private BluetoothDevice findPrinter(BluetoothAdapter adapter) {
        Set<BluetoothDevice> paired = adapter.getBondedDevices();
        if (paired == null || paired.isEmpty()) return null;
        for (BluetoothDevice d : paired) {
            String name = d.getName() != null ? d.getName().toUpperCase() : "";
            if (name.contains("INNER") || name.contains("ELEPH") || name.contains("LABEL")
                    || name.contains("KAPBOM") || name.contains("PRINT") || name.contains("POS")
                    || name.contains("THERMAL") || name.contains("RPP") || name.contains("MTP")
                    || name.contains("58") || name.contains("80MM")) {
                return d;
            }
        }
        return paired.iterator().next();
    }

    private void respond(OutputStream out, String status, String body) throws IOException {
        String response = "HTTP/1.1 " + status + "\r\n"
                + "Content-Length: " + body.length() + "\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "\r\n" + body;
        out.write(response.getBytes("UTF-8"));
        out.flush();
    }
}
