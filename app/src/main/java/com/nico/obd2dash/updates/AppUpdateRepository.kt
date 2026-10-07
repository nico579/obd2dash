package com.nico.obd2dash.updates

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.storage.StorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

internal fun parseUpdateRelease(json: String): UpdateRelease {
    val root = JSONObject(json)
    require(!root.getBoolean("draft") && !root.getBoolean("prerelease")) { "Cette version n’est pas une publication stable." }
    val assets = root.getJSONArray("assets")
    val matches = (0 until assets.length()).map { assets.getJSONObject(it) }
        .filter { it.optString("name") == "app-debug.apk" }
    require(matches.size == 1) { "La publication ne contient pas un APK unique à installer." }
    val asset = matches.single()
    require(asset.getString("state") == "uploaded") { "Le fichier de mise à jour n’est pas encore disponible." }
    return UpdateRelease.validated(root.getString("tag_name"), root.optString("body"),
        asset.getString("browser_download_url"), asset.getLong("size"), asset.getString("digest"), asset.getLong("id"))
}

internal class AppUpdateRepository(private val context: Context) {
    private val credentials = UpdateCredentials(context)
    private val downloader = UpdateDownloader { credentials.read() }
    private val apk get() = File(context.cacheDir, "updates/app-update.apk")
    val installed: UpdateApkIdentity get() = packageIdentity(installedPackage())
    val hasAccessToken: Boolean get() = credentials.read().isNotEmpty()

    suspend fun saveAccessToken(token: String) = withContext(Dispatchers.IO) { credentials.save(token) }

    suspend fun latest(): UpdateRelease = withContext(Dispatchers.IO) {
        val coroutine = currentCoroutineContext()
        parseUpdateRelease(downloader.latestJson { coroutine.ensureActive() })
    }

    suspend fun download(release: UpdateRelease, onProgress: (Long) -> Unit): File = withContext(Dispatchers.IO) {
        val coroutine = currentCoroutineContext()
        downloader.download(release, apk, onProgress, { coroutine.ensureActive() }) { descriptor ->
            try {
                // Android 26+ peut réserver l'espace en libérant uniquement du cache récupérable.
                context.getSystemService(StorageManager::class.java).allocateBytes(descriptor, release.size)
            } catch (error: IOException) {
                throw IllegalArgumentException("Espace libre insuffisant ou stockage indisponible pour la mise à jour.", error)
            }
        }
        try {
            validateApk(apk, release)
            apk
        } catch (error: Exception) {
            apk.delete()
            throw error
        }
    }

    /** Revalide le cache juste avant de donner temporairement sa lecture à Android. */
    suspend fun prepareInstall(release: UpdateRelease): File = withContext(Dispatchers.IO) {
        val coroutine = currentCoroutineContext()
        require(apk.isFile && apk.length() == release.size) { "Le fichier a été supprimé. Téléchargez à nouveau la mise à jour." }
        val digest = MessageDigest.getInstance("SHA-256")
        apk.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                coroutine.ensureActive()
                val count = input.read(buffer)
                if (count == -1) break
                digest.update(buffer, 0, count)
            }
        }
        require(digest.digest().toHex() == release.sha256) { "Le fichier téléchargé a changé. Téléchargez-le à nouveau." }
        validateApk(apk, release)
        apk
    }

    @Suppress("DEPRECATION") // API int requise sur Android 26–32 ; mêmes indicateurs sur 33+.
    private fun installedPackage(): PackageInfo = context.packageManager.getPackageInfo(context.packageName, signatureFlags())

    @Suppress("DEPRECATION")
    private fun signatureFlags(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
        PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES

    @Suppress("DEPRECATION")
    private fun validateApk(file: File, release: UpdateRelease) {
        val info = context.packageManager.getPackageArchiveInfo(file.absolutePath, signatureFlags())
            ?: throw IllegalArgumentException("Le fichier téléchargé n’est pas un APK valide.")
        requireCompatibleUpdate(installed, packageIdentity(info), release)
    }

    @Suppress("DEPRECATION") // versionCode/signatures pour Android 26–27 seulement.
    private fun packageIdentity(info: PackageInfo): UpdateApkIdentity {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            info.signingInfo?.apkContentsSigners else info.signatures
        return UpdateApkIdentity(info.packageName, info.versionName.orEmpty(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong(),
            signatures.orEmpty().map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).toHex() }.toSet())
    }
}
