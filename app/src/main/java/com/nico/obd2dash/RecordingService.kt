package com.nico.obd2dash

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * Service de premier plan sans autre rôle que d'exister pendant un enregistrement CSV :
 * l'enregistrement tourne dans une session partagée avec ce service, conservée hors
 * du lifecycle de l'Activity tant que REC est actif. Sans lui, Android peut ralentir ou tuer le
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
    private var sessionId: Long? = null
    private val sessions get() = (application as Obd2DashApp).sessions

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requestedId = intent?.getLongExtra(EXTRA_SESSION_ID, -1L)?.takeIf { it >= 0L }
        val model = sessions.current
        if (intent?.action == ACTION_STOP) {
            if (requestedId != null && model?.state?.value?.recordingSessionId == requestedId) {
                model.stopRecording()
            }
            if (model?.state?.value?.isRecording != true) stopSelf(startId)
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_recording), NotificationManager.IMPORTANCE_LOW)
        )
        val openApp = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, RecordingService::class.java).setAction(ACTION_STOP)
                .setData(Uri.parse(recordingStopActionUri(requestedId ?: -1L)))
                .putExtra(EXTRA_SESSION_ID, requestedId ?: -1L),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_recording_content))
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_media_pause, getString(R.string.notification_recording_stop), stop)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)

        // Fulfil Android's foreground deadline even for a start queued before REC stopped.
        // A stale service instance must never adopt or stop a replacement recording.
        if (requestedId == null || model?.state?.value?.recordingSessionId != requestedId) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        sessionId = requestedId

        if (wakeLock == null) {
            val powerManager = getSystemService(PowerManager::class.java)
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                .apply {
                    // Non compté : chaque renouvellement ci-dessous repousse l'échéance du
                    // même verrou au lieu d'empiler des acquisitions à relâcher une par une.
                    setReferenceCounted(false)
                    acquire(WAKE_LOCK_DURATION_MS)
                }
            handler.postDelayed(renewWakeLock, WAKE_LOCK_RENEW_INTERVAL_MS)
        }
        return START_NOT_STICKY
    }

    // Renouvelé tant que le service vit (c.-à-d. tant que l'enregistrement dure) : un seul
    // acquire(3h) laissait le processeur libre de se mettre en veille au-delà de 3h alors
    // que l'enregistrement, lui, continuait. Garder une échéance courte (plutôt qu'un
    // verrou sans limite) borne toujours la fuite si onDestroy() n'était jamais appelé.
    private val handler = Handler(Looper.getMainLooper())
    private val renewWakeLock: Runnable = object : Runnable {
        override fun run() {
            val lock = wakeLock ?: return
            lock.acquire(WAKE_LOCK_DURATION_MS)
            handler.postDelayed(this, WAKE_LOCK_RENEW_INTERVAL_MS)
        }
    }

    override fun onDestroy() {
        // Fermer la capture si Android détruit normalement son service propriétaire.
        // Un kill du processus ne garantit pas onDestroy() : l'OS libère alors ses
        // ressources, et START_NOT_STICKY ne recrée pas une capture interrompue.
        handler.removeCallbacks(renewWakeLock)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        sessions.current?.let { model ->
            if (sessionId != null && model.state.value.recordingSessionId == sessionId) {
                model.stopRecording()
            }
        }
        sessionId = null
        super.onDestroy()
    }

    companion object {
        internal const val EXTRA_SESSION_ID = "recording_session_id"
        private const val ACTION_STOP = "com.nico.obd2dash.STOP_RECORDING"
        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_TAG = "obd2dash:recording"
        // Échéance courte renouvelée périodiquement (voir renewWakeLock) plutôt qu'une
        // seule longue échéance fixe : un enregistrement plus long que celle-ci perdait
        // silencieusement son wake lock bien avant l'arrêt réel.
        private const val WAKE_LOCK_DURATION_MS = 30 * 60 * 1000L
        private const val WAKE_LOCK_RENEW_INTERVAL_MS = 10 * 60 * 1000L
    }
}
