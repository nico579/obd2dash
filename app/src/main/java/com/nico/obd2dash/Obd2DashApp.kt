package com.nico.obd2dash

import android.app.Application

/**
 * Seul rôle : initialiser EventLog le plus tôt possible dans le cycle de vie du processus,
 * avant la moindre Activity. Nécessaire en particulier pour RecordingService : après
 * qu'Android a tué le processus pour libérer de la mémoire, un service de premier plan
 * redémarré (redelivery) peut s'exécuter sans qu'aucune Activity n'ait encore tourné ;
 * sans cette classe, EventLog.init ne serait appelé que depuis MainActivity.onCreate et
 * manquerait ce cas.
 */
class Obd2DashApp : Application() {
    override fun onCreate() {
        super.onCreate()
        EventLog.init(this)
    }
}
