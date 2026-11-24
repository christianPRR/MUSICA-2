package com.musica.app

import android.app.Application
import android.util.Log
import java.io.File

/** Guarda el error si la app se cierra sola, para mostrarlo en el siguiente arranque. */
class MusicaApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try { File(filesDir, "crash.txt").writeText(Log.getStackTraceString(e)) } catch (x: Exception) { }
            old?.uncaughtException(t, e)
        }
    }
}
