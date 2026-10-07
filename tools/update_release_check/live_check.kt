package com.nico.obd2dash.updates

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/** Test explicite Internet : aucune connexion Android, sonde ou véhicule. */
fun main(args: Array<String>) {
    val directory = File(args.single())
    val downloader = UpdateDownloader { System.getenv("OBD_UPDATE_TOKEN").orEmpty() }
    val json = downloader.latestJson {}
    File(directory, "release.json").writeText(json)
    val release = parseUpdateRelease(json)
    val apk = File(directory, "app-debug.apk")
    // L'allocation Android est remplacée seulement ici, sur le système de fichiers du PC.
    downloader.download(release, apk, {}, {}, {})
    val hash = MessageDigest.getInstance("SHA-256").digest(apk.readBytes()).toHex()
    check(hash == release.sha256 && apk.length() == release.size)
    check(!File(directory, "update.part").exists())
    val cancelled = File(directory, "cancelled.apk")
    try {
        downloader.download(release, cancelled, { throw CancellationException("test") }, {}, {})
        error("Le téléchargement interrompu n'a pas échoué")
    } catch (_: CancellationException) {
        check(!cancelled.exists() && !File(directory, "update.part").exists())
    }
    println(JSONObject().put("release", release.tag).put("bytes", apk.length()).put("sha256", hash)
        .put("url", release.downloadUrl).put("cancelled_download_cleaned", true).toString())
}
