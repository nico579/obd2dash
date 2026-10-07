package com.nico.obd2dash

import android.app.Application

/**
 * Initialise le journal dès le début du processus et possède la session partagée.
 * Une capture active peut survivre à la fermeture de l'Activity grâce au service.
 * Une création du processus par un Intent de service initialise aussi le journal ;
 * elle ne reprend pas le CSV ni le transport d'un processus tué (START_NOT_STICKY).
 */
class Obd2DashApp : Application() {
    internal val sessions by lazy { ObdSessionOwner(this) }

    override fun onCreate() {
        super.onCreate()
        EventLog.init(this)
    }
}
