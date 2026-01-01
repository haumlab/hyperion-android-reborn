package com.hyperion.grabber

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.widget.TextView
import com.hyperion.grabber.common.util.GithubRelease

class UpdateDialog(private val context: Context) {
    
    fun show(release: GithubRelease, onUpdate: () -> Unit, onDismiss: () -> Unit) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_update, null)
        
        dialogView.findViewById<TextView>(R.id.update_version).text = 
            "Version ${release.tagName} is available"
        
        dialogView.findViewById<TextView>(R.id.update_description).text = 
            release.body.take(300) + if (release.body.length > 300) "..." else ""
        
        AlertDialog.Builder(context)
            .setTitle("Update Available")
            .setView(dialogView)
            .setPositiveButton("Update") { dialog, _ ->
                try {
                    onUpdate()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                dialog.dismiss()
            }
            .setNegativeButton("Later") { dialog, _ ->
                try {
                    onDismiss()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                dialog.dismiss()
            }
            .setCancelable(true)
            .setOnDismissListener { 
                try {
                    onDismiss()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            .show()
    }
    
    fun showDownloading() {
        AlertDialog.Builder(context)
            .setTitle("Downloading Update")
            .setMessage("Please wait while the update is downloaded...")
            .setCancelable(false)
            .show()
    }
}
