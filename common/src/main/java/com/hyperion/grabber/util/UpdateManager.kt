package com.hyperion.grabber.common.util

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

class UpdateManager(private val context: Context) {
    private val TAG = "UpdateManager"
    private var downloadId: Long = -1
    private var onDownloadComplete: ((Boolean) -> Unit)? = null
    
    private val downloadCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) ?: -1
            if (id == downloadId) {
                Log.d(TAG, "Download completed: $id")
                installUpdate()
                onDownloadComplete?.invoke(true)
                context?.unregisterReceiver(this)
            }
        }
    }
    
    fun downloadAndInstall(downloadUrl: String, versionName: String, onComplete: (Boolean) -> Unit) {
        this.onDownloadComplete = onComplete
        
        try {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val uri = Uri.parse(downloadUrl)
            
            val request = DownloadManager.Request(uri).apply {
                setTitle("Hyperion Grabber Update")
                setDescription("Downloading version $versionName")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(
                    Environment.DIRECTORY_DOWNLOADS,
                    "hyperion-grabber-$versionName.apk"
                )
                setMimeType("application/vnd.android.package-archive")
            }
            
            downloadId = downloadManager.enqueue(request)
            Log.d(TAG, "Download started with ID: $downloadId")
            
            // Register receiver for download completion
            val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(downloadCompleteReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(downloadCompleteReceiver, filter)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting download", e)
            onComplete(false)
        }
    }
    
    private fun installUpdate() {
        try {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val uri = downloadManager.getUriForDownloadedFile(downloadId)
            
            if (uri != null) {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                context.startActivity(intent)
            } else {
                Log.e(TAG, "Download URI is null")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error installing update", e)
        }
    }
    
    fun cancelDownload() {
        if (downloadId != -1L) {
            try {
                val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                downloadManager.remove(downloadId)
                context.unregisterReceiver(downloadCompleteReceiver)
            } catch (e: Exception) {
                Log.e(TAG, "Error canceling download", e)
            }
        }
    }
}
