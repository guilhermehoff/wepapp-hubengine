package com.example.hubengine.camera

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.example.hubengine.R
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class QrScannerActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var cameraExecutor: ExecutorService

    // Single ML Kit client instance reused across frames
    private val barcodeScanner = BarcodeScanning.getClient()

    @Volatile private var scanned = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_qr_scanner)
        previewView = findViewById(R.id.preview_view)
        cameraExecutor = Executors.newSingleThreadExecutor()
        startCamera()
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
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

                barcodeScanner.process(image)
                    .addOnSuccessListener { barcodes ->
                        barcodes.firstOrNull { it.rawValue != null }
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
        barcodeScanner.close()
    }

    companion object {
        const val EXTRA_QR_VALUE = "qr_value"
    }
}
