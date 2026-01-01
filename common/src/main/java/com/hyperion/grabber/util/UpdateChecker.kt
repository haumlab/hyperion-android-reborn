package com.hyperion.grabber.common.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class GithubRelease(
    val tagName: String,
    val name: String,
    val body: String,
    val downloadUrl: String,
    val publishedAt: String
)

class UpdateChecker(private val context: Context) {
    private val TAG = "UpdateChecker"
    private val GITHUB_API_URL = "https://api.github.com/repos/haumlab/hyperion-android-reborn/releases"
    private val GITHUB_RELEASES_URL = "https://github.com/haumlab/hyperion-android-reborn/releases"
    
    fun checkForUpdates(): GithubRelease? {
        try {
            val currentVersion = getCurrentVersion()
            val latestRelease = fetchLatestRelease() ?: return null
            
            Log.d(TAG, "Current version: $currentVersion, Latest version: ${latestRelease.tagName}")
            
            if (isNewerVersion(latestRelease.tagName, currentVersion)) {
                return latestRelease
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for updates", e)
        }
        return null
    }
    
    private fun getCurrentVersion(): String {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            packageInfo.versionName ?: "0.0"
        } catch (e: Exception) {
            Log.e(TAG, "Error getting current version", e)
            "0.0"
        }
    }
    
    private fun fetchLatestRelease(): GithubRelease? {
        var connection: HttpURLConnection? = null
        try {
            val url = URL(GITHUB_API_URL)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/vnd.github.v3+json")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.e(TAG, "GitHub API returned code: $responseCode")
                return null
            }
            
            val reader = BufferedReader(InputStreamReader(connection.inputStream))
            val response = reader.readText()
            reader.close()
            
            return parseReleases(response)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching releases", e)
            return null
        } finally {
            connection?.disconnect()
        }
    }
    
    private fun parseReleases(json: String): GithubRelease? {
        try {
            val releases = JSONArray(json)
            
            // Find the first non-prerelease, non-draft release
            for (i in 0 until releases.length()) {
                val release = releases.getJSONObject(i)
                val isDraft = release.optBoolean("draft", false)
                val isPrerelease = release.optBoolean("prerelease", false)
                
                if (!isDraft && !isPrerelease) {
                    val tagName = release.getString("tag_name")
                    val name = release.optString("name", tagName)
                    val body = release.optString("body", "")
                    val publishedAt = release.optString("published_at", "")
                    
                    // Find APK asset
                    val assets = release.optJSONArray("assets")
                    var downloadUrl = ""
                    
                    if (assets != null) {
                        for (j in 0 until assets.length()) {
                            val asset = assets.getJSONObject(j)
                            val assetName = asset.getString("name")
                            if (assetName.endsWith(".apk")) {
                                downloadUrl = asset.getString("browser_download_url")
                                break
                            }
                        }
                    }
                    
                    if (downloadUrl.isEmpty()) {
                        Log.w(TAG, "No APK found for release $tagName")
                        continue
                    }
                    
                    return GithubRelease(tagName, name, body, downloadUrl, publishedAt)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing releases", e)
        }
        return null
    }
    
    private fun isNewerVersion(newVersion: String, currentVersion: String): Boolean {
        try {
            val newParts = newVersion.removePrefix("v").split(".")
            val currentParts = currentVersion.removePrefix("v").split(".")
            
            for (i in 0 until maxOf(newParts.size, currentParts.size)) {
                val newPart = newParts.getOrNull(i)?.toIntOrNull() ?: 0
                val currentPart = currentParts.getOrNull(i)?.toIntOrNull() ?: 0
                
                if (newPart > currentPart) return true
                if (newPart < currentPart) return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error comparing versions", e)
        }
        return false
    }
}
