package com.nico.obd2dash.updates

import java.io.File
import java.io.FileDescriptor
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/** Borné par la taille publiée, même si le serveur omet Content-Length. */
internal fun copyVerifiedUpdate(
    input: InputStream,
    output: OutputStream,
    expectedSize: Long,
    expectedSha256: String,
    onProgress: (Long) -> Unit,
    checkCancelled: () -> Unit
) {
    require(expectedSize in 1..MAX_APK_BYTES)
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(32 * 1024)
    var count = 0L
    while (true) {
        checkCancelled()
        val read = input.read(buffer)
        if (read == -1) break
        count += read
        require(count <= expectedSize) { "Le téléchargement dépasse la taille annoncée." }
        output.write(buffer, 0, read)
        digest.update(buffer, 0, read)
        onProgress(count)
    }
    checkCancelled()
    require(count == expectedSize) { "Le téléchargement est incomplet. Réessayez." }
    require(digest.digest().toHex().equals(expectedSha256, ignoreCase = true)) {
        "Le fichier téléchargé est endommagé. Réessayez."
    }
}

internal class UpdateHttpException(val status: Int) : IOException("HTTP $status")

internal class UpdateDownloader(private val accessToken: () -> String = { "" }) {
    private fun open(url: String, accept: String, checkCancelled: () -> Unit): HttpURLConnection {
        val token = accessToken()
        var current = URL(url)
        repeat(6) { attempt ->
            checkCancelled()
            requireUpdateUrl(current.toString())
            val connection = current.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", accept)
            connection.setRequestProperty("User-Agent", "OBD2Dash-Android")
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (current.host == "api.github.com") {
                connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            }
            updateAuthorization(current.toString(), token)?.let { connection.setRequestProperty("Authorization", it) }
            try {
                val status = connection.responseCode
                if (status in setOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location") ?: throw IOException("Redirection sans adresse.")
                    require(attempt < 5) { "Trop de redirections pendant le téléchargement." }
                    current = URL(current, location)
                    connection.disconnect()
                } else {
                    if (status != HttpURLConnection.HTTP_OK) throw UpdateHttpException(status)
                    return connection
                }
            } catch (error: Exception) {
                connection.disconnect()
                throw error
            }
        }
        throw IOException("Téléchargement indisponible.")
    }

    fun latestJson(checkCancelled: () -> Unit): String {
        val connection = open(UPDATE_API, "application/vnd.github+json", checkCancelled)
        try {
            return connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    checkCancelled()
                    val read = input.read(buffer)
                    if (read == -1) break
                    require(output.size() + read <= 1024 * 1024) { "Réponse du serveur trop volumineuse." }
                    output.write(buffer, 0, read)
                }
                output.toString("UTF-8")
            }
        } finally {
            connection.disconnect()
        }
    }

    fun download(release: UpdateRelease, destination: File, onProgress: (Long) -> Unit,
        checkCancelled: () -> Unit, allocateSpace: (FileDescriptor) -> Unit) {
        val partial = File(destination.parentFile, "update.part")
        require(destination.parentFile?.let { it.isDirectory || it.mkdirs() } == true) { "Stockage indisponible." }
        partial.delete()
        // Un APK précédent ne doit jamais devenir installable après un téléchargement échoué.
        destination.delete()
        try {
            // L'API d'asset accepte un jeton pour les dépôts privés. Le jeton ne
            // suit jamais la redirection vers le CDN, ni vers github.com.
            val connection = open(release.assetApiUrl, "application/octet-stream", checkCancelled)
            try {
                val length = connection.contentLengthLong
                require(length == -1L || length == release.size) { "Taille du téléchargement inattendue." }
                connection.inputStream.use { input ->
                    partial.outputStream().use { output ->
                        allocateSpace(output.fd)
                        copyVerifiedUpdate(input, output, release.size, release.sha256, onProgress, checkCancelled)
                        output.fd.sync()
                    }
                }
            } finally {
                connection.disconnect()
            }
            checkCancelled()
            require(partial.renameTo(destination)) { "Impossible de préparer le fichier téléchargé." }
        } finally {
            partial.delete()
        }
    }
}
