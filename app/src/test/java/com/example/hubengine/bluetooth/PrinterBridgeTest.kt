package com.example.hubengine.bluetooth

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class PrinterBridgeTest {

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
