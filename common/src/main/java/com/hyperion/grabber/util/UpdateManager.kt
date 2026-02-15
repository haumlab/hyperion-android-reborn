package com.hyperion.grabber.common.util

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

class UpdateManager(private val context: Context) {
    private val TAG = "UpdateManager"
    private var downloadId: Long = -1
    private var downloadReceiver: BroadcastReceiver? = null
    private val handler = Handler(Looper.getMainLooper())
    
    fun downloadAndInstall(downloadUrl: String, versionName: String, onComplete: (Boolean) -> Unit) {
        try {
            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            
            val fileName = "hyperion-grabber-${versionName.replace("v", "")}.apk"
            val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            if (downloadDir == null) {
                showToast("External storage not available")
                onComplete(false)
                return
            }

            val destinationFile = File(
                downloadDir,
                fileName
            )
            if (destinationFile.exists()) {
                destinationFile.delete()
            }
            
            val uri = Uri.parse(downloadUrl)
            val request = DownloadManager.Request(uri).apply {
                setTitle("Hyperion Grabber Update")
                setDescription("Downloading $versionName")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
                setMimeType("application/vnd.android.package-archive")
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }
            
            downloadId = downloadManager.enqueue(request)
            Log.d(TAG, "Download started: $downloadId from $downloadUrl")
            
            showToast("Downloading update...")
            
            downloadReceiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) ?: -1
                    if (id == downloadId) {
                        Log.d(TAG, "Download complete: $id")
                        unregisterReceiver()
                        
                        val query = DownloadManager.Query().setFilterById(downloadId)
                        val cursor = downloadManager.query(query)
                        
                        if (cursor.moveToFirst()) {
                            val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                            val status = cursor.getInt(statusIndex)
                            
                            if (status == DownloadManager.STATUS_SUCCESSFUL) {
                                showToast("Download complete, installing...")
                                installApk(fileName)
                                onComplete(true)
                            } else {
                                Log.e(TAG, "Download failed with status: $status")
                                showToast("Download failed")
                                onComplete(false)
                            }
                        }
                        cursor.close()
                    }
                }
            }
            
            val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(downloadReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(downloadReceiver, filter)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Download error", e)
            showToast("Download failed: ${e.message}")
            onComplete(false)
        }
    }
    
    private fun installApk(fileName: String) {
        try {
            val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            if (downloadDir == null) {
                showToast("External storage not available")
                return
            }

            val file = File(
                downloadDir,
                fileName
            )
            
            if (!file.exists()) {
                Log.e(TAG, "APK file not found: ${file.absolutePath}")
                showToast("APK file not found")
                return
            }
            
            val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
            } else {
                Uri.fromFile(file)
            }
            
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            
            context.startActivity(intent)
            Log.d(TAG, "Install intent launched for: $uri")
            
        } catch (e: Exception) {
            Log.e(TAG, "Install error", e)
            showToast("Install failed: ${e.message}")
        }
    }
    
    private fun unregisterReceiver() {
        try {
            downloadReceiver?.let { context.unregisterReceiver(it) }
            downloadReceiver = null
        } catch (e: Exception) {
            Log.w(TAG, "Receiver already unregistered")
        }
    }
    
    private fun showToast(message: String) {
        handler.post { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }
    
    fun cancelDownload() {
        if (downloadId != -1L) {
            try {
                val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.remove(downloadId)
                unregisterReceiver()
            } catch (e: Exception) {
                Log.e(TAG, "Cancel error", e)
            }
        }
    }
}
