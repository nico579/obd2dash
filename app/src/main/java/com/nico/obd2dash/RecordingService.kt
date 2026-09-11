package com.nico.obd2dash

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Service de premier plan sans autre rôle que d'exister pendant un enregistrement CSV :
 * l'enregistrement lui-même tourne dans ObdViewModel.viewModelScope, déjà propre au
 * processus entier, pas à ce service. Sans lui, Android peut ralentir ou tuer le
 * processus une fois l'écran éteint ou l'app en arrière-plan pendant un enregistrement
 * long (voir audit, "Écran éteint et arrière-plan") : un ViewModel qui survit à la
 * navigation ne garantit rien une fois le processus lui-même mis en veille par l'OS.
 *
 * START_NOT_STICKY : si le système tue quand même le processus, redémarrer ce seul
 * service ne recréerait pas un enregistrement cohérent (fichier, colonnes, client ELM
 * perdus avec lui) ; aucune tentative de reprise automatique n'est faite.
 */
class RecordingService : Service() {

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
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1
    }
}
