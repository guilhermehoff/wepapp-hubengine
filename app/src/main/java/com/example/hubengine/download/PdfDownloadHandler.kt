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
import java.util.concurrent.atomic.AtomicInteger

class PdfDownloadHandler(private val context: Context) {

    private val downloadManager =
        context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private var downloadReceiver: BroadcastReceiver? = null

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

    fun unregister() {
        downloadReceiver?.let { context.unregisterReceiver(it) }
        downloadReceiver = null
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
        downloadReceiver = receiver
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
            if (statusCol < 0 || localUriCol < 0) {
                cursor.close()
                return
            }
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
        val id = notificationCounter.getAndIncrement()
        val pendingIntent = PendingIntent.getActivity(
            context, id,
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
        notificationManager.notify(id, notification)
    }

    companion object {
        private const val CHANNEL_ID = "hubengine_downloads"
        private val notificationCounter = AtomicInteger(2000)

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
