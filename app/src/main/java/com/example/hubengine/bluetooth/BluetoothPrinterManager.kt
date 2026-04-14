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
            val out = socket?.outputStream ?: return false
            out.write(data)
            out.flush()
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
