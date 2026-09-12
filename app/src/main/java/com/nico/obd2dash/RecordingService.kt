package com.nico.obd2dash

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * Service de premier plan sans autre rôle que d'exister pendant un enregistrement CSV :
 * l'enregistrement lui-même tourne dans ObdViewModel.viewModelScope, déjà propre au
 * processus entier, pas à ce service. Sans lui, Android peut ralentir ou tuer le
 * processus une fois l'écran éteint ou l'app en arrière-plan pendant un enregistrement
 * long (voir audit, "Écran éteint et arrière-plan") : un ViewModel qui survit à la
 * navigation ne garantit rien une fois le processus lui-même mis en veille par l'OS.
 *
 * Un service de premier plan et un wake lock jouent des rôles différents (voir audit B9,
 * "choix de l'API pour garder l'appareil éveillé") : le premier évite au processus d'être
 * tué et affiche la notification obligatoire, mais ne garantit pas à lui seul que le CPU
 * continue de tourner écran éteint. Le wake lock partiel ci-dessous couvre ce second
 * besoin, acquis/libéré avec le cycle de vie de ce service.
 *
 * START_NOT_STICKY : si le système tue quand même le processus, redémarrer ce seul
 * service ne recréerait pas un enregistrement cohérent (fichier, colonnes, client ELM
 * perdus avec lui) ; aucune tentative de reprise automatique n'est faite.
 */
class RecordingService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Enregistrement OBD2", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("OBD2 Dash")
            .setContentText("Enregistrement en cours")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)

        if (wakeLock == null) {
            val powerManager = getSystemService(PowerManager::class.java)
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                .apply { acquire(MAX_WAKE_LOCK_DURATION_MS) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Libéré ici plutôt que dans ObdViewModel.stopRecording() : ce service peut être
        // arrêté par l'OS (mémoire faible) sans passer par un appel explicite de l'app,
        // onDestroy() reste le seul endroit garanti d'être appelé dans les deux cas.
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_TAG = "obd2dash:recording"
        // Filet de sécurité, pas la durée attendue d'un enregistrement : ce service n'est
        // démarré qu'une fois par session (voir ObdViewModel.startRecording), donc un
        // enregistrement qui dépasserait cette durée sans jamais repasser par
        // onStartCommand verrait sinon le wake lock expirer silencieusement bien avant
        // l'arrêt réel, avec le même risque de mise en veille qu'en son absence.
        private const val MAX_WAKE_LOCK_DURATION_MS = 3 * 60 * 60 * 1000L
    }
}
