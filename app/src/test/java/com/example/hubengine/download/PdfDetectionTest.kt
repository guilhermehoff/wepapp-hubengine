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
