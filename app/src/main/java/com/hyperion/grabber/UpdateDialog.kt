package com.hyperion.grabber

import android.app.AlertDialog
import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.widget.TextView
import com.hyperion.grabber.common.util.GithubRelease

private const val TAG = "UpdateDialog"

class UpdateDialog(private val context: Context) {
    
    fun show(release: GithubRelease, onUpdate: () -> Unit, onDismiss: () -> Unit) {
        try {
            val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_update, null)
            
            dialogView.findViewById<TextView>(R.id.update_version)?.text = 
                "Version ${release.tagName} available"
            
            val description = release.body.ifEmpty { "Bug fixes and improvements" }
            dialogView.findViewById<TextView>(R.id.update_description)?.text = 
                if (description.length > 300) description.take(300) + "..." else description
            
            AlertDialog.Builder(context)
                .setTitle("Update Available")
                .setView(dialogView)
                .setPositiveButton("Update Now") { dialog, _ ->
                    dialog.dismiss()
                    onUpdate()
                }
                .setNegativeButton("Later") { dialog, _ ->
                    dialog.dismiss()
                }
                .setCancelable(true)
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "Error showing update dialog", e)
            onDismiss()
        }
    }
}
