package com.nico.obd2dash

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Journal texte des événements du processus en cours (voir Obd2DashApp) : démarrage,
 * connexion/déconnexion, activation d'une fonction (enregistrement, sondage, smoke test,
 * graphique), et plantage. Un seul fichier par lancement, tenu ouvert et vidé (flush) à
 * chaque ligne sur un thread IO sérialisé pour rester exploitable après un arrêt brutal (voir
 * installCrashHandler) : même esprit que les enregistrements CSV (ObdViewModel.
 * startRecording), mais texte libre puisqu'il s'agit d'événements, pas de mesures
 * tabulaires. Objet plutôt que classe : un seul journal pour toute la durée de vie du
 * processus, initialisé une fois depuis Obd2DashApp avant la moindre Activity.
 */
object EventLog {
    @Volatile private var writer: SerialEventWriter? = null

    /** Idempotent (voir writer) : un appel répété (jamais censé arriver avec un seul point d'entrée dans Obd2DashApp, mais sans risque) ne rouvre pas un second fichier. */
    @Synchronized
    fun init(context: Context) {
        if (writer != null) return
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        val baseName = "log_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.FRANCE).format(Date())
        writer = runCatching { SerialEventWriter(uniqueFile(dir, baseName).bufferedWriter()) }.getOrNull()
        log("Démarrage de l'application")
        installCrashHandler()
    }

    fun log(message: String) {
        // Silencieux si writer est resté nul (échec de création improbable, voir init) ou si
        // le fichier a été supprimé pendant la session (voir ObdViewModel.deleteLog) : un
        // journal qui échoue à s'écrire ne doit jamais faire planter l'app qu'il surveille.
        writer?.log(message)
    }

    /** Même rôle que ObdViewModel.uniqueFile (deux lancements dans la même seconde) : pas de dépendance croisée pour qu'EventLog reste utilisable indépendamment du ViewModel (ex: avant sa création). */
    private fun uniqueFile(dir: File, baseName: String): File {
        var candidate = File(dir, "$baseName.log")
        var suffix = 2
        while (!candidate.createNewFile()) {
            candidate = File(dir, "${baseName}_$suffix.log")
            suffix++
        }
        return candidate
    }

    /**
     * Toute exception non rattrapée, sur n'importe quel thread, est d'abord journalisée ici
     * (avec sa pile d'appels complète) puis déléguée au gestionnaire par défaut d'Android :
     * on ne remplace pas le comportement système (l'app doit toujours se fermer/redémarrer
     * normalement), on ajoute seulement une trace consultable après coup. Manquait
     * totalement jusqu'ici : l'app "s'arrêtait" sans laisser la moindre trace exploitable
     * (voir le rapport utilisateur, deux occurrences pendant un enregistrement).
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writer?.crash(thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
