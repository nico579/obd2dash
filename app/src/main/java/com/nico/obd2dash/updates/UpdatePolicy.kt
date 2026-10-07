package com.nico.obd2dash.updates

import java.net.URI
import java.util.Locale

internal const val UPDATE_REPOSITORY = "https://github.com/nico579/obd2dash"
internal const val UPDATE_API = "https://api.github.com/repos/nico579/obd2dash/releases/latest"
internal const val MAX_APK_BYTES = 100L * 1024 * 1024

/** Comparaison numérique : 0.10 vient après 0.9. Les préversions ne sont pas proposées. */
internal data class UpdateVersion private constructor(private val parts: List<Int>) : Comparable<UpdateVersion> {
    override fun compareTo(other: UpdateVersion): Int {
        for (i in 0..2) {
            val comparison = parts.getOrElse(i) { 0 }.compareTo(other.parts.getOrElse(i) { 0 })
            if (comparison != 0) return comparison
        }
        return 0
    }

    companion object {
        fun parse(name: String): UpdateVersion {
            require(Regex("(0|[1-9][0-9]{0,8})(\\.(0|[1-9][0-9]{0,8})){1,2}").matches(name)) {
                "Version de l’application non reconnue."
            }
            val parts = name.split('.').map(String::toInt)
            return UpdateVersion(List(3) { parts.getOrElse(it) { 0 } })
        }
    }
}

internal data class UpdateRelease(
    val tag: String,
    val versionName: String,
    val notes: String,
    val downloadUrl: String,
    val size: Long,
    val sha256: String,
    val assetId: Long
) {
    val assetApiUrl: String get() = "https://api.github.com/repos/nico579/obd2dash/releases/assets/$assetId"

    companion object {
        fun validated(tag: String, notes: String, downloadUrl: String, size: Long, digest: String, assetId: Long): UpdateRelease {
            require(tag.startsWith('v')) { "Version publiée non reconnue." }
            val version = tag.removePrefix("v")
            UpdateVersion.parse(version)
            require(downloadUrl == "$UPDATE_REPOSITORY/releases/download/$tag/app-debug.apk") {
                "Le fichier de mise à jour ne provient pas du dépôt officiel."
            }
            require(size in 1..MAX_APK_BYTES) { "Taille du fichier de mise à jour invalide." }
            require(assetId > 0) { "Identifiant du fichier de mise à jour invalide." }
            require(Regex("sha256:[a-fA-F0-9]{64}").matches(digest)) {
                "La publication ne fournit pas d’empreinte de vérification valide."
            }
            return UpdateRelease(tag, version, notes.take(12_000), downloadUrl, size,
                digest.substringAfter(':').lowercase(Locale.ROOT), assetId)
        }
    }
}

internal fun requireUpdateUrl(url: String) {
    val uri = URI(url)
    require(uri.scheme == "https" && uri.userInfo == null && uri.port in listOf(-1, 443) &&
        uri.host in setOf("api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")) {
        "Adresse de téléchargement non autorisée."
    }
}

internal fun updateAuthorization(url: String, token: String): String? {
    requireUpdateUrl(url)
    val uri = URI(url)
    return if (uri.host == "api.github.com" && uri.path.startsWith("/repos/nico579/obd2dash/") && token.isNotEmpty())
        "Bearer ${validatedUpdateToken(token)}" else null
}

internal data class UpdateApkIdentity(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val signers: Set<String>
)

internal fun requireCompatibleUpdate(installed: UpdateApkIdentity, candidate: UpdateApkIdentity, release: UpdateRelease) {
    require(candidate.packageName == installed.packageName) { "Ce fichier n’est pas une mise à jour d’OBD2 Dash." }
    require(candidate.versionCode > installed.versionCode &&
        UpdateVersion.parse(candidate.versionName) > UpdateVersion.parse(installed.versionName) &&
        UpdateVersion.parse(candidate.versionName) == UpdateVersion.parse(release.versionName)) {
        "La version du fichier ne correspond pas à une nouvelle mise à jour."
    }
    require(installed.signers.isNotEmpty() && candidate.signers == installed.signers) {
        "La signature de cette mise à jour est différente de l’application installée."
    }
}

internal fun updateInstallationBlocked(recording: Boolean, testing: Boolean, probing: Boolean): Boolean =
    recording || testing || probing

internal fun validatedUpdateToken(raw: String): String {
    val token = raw.trim()
    require(token.isEmpty() || (token.length in 20..512 && token.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' })) {
        "Copie uniquement le jeton GitHub, sans autre texte."
    }
    return token
}
