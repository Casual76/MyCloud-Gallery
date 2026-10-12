package dev.pampa.mycloudgallery

import android.util.Log
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.common.log.LogLevel
import dev.pampa.mycloudgallery.core.common.log.LogSink

/**
 * SOLO build debug: l'unica implementazione al mondo che scrive su logcat.
 *
 * La versione release di questo file (src/release) e' vuota, quindi nella release non esiste codice
 * che possa trasformare una riga di `GalleryLog` in una riga di logcat. Non e' una configurazione che
 * si puo' dimenticare accesa: e' codice che non c'e'.
 */
internal fun installLogging() {
  GalleryLog.install(LogcatSink)
}

private object LogcatSink : LogSink {

  override fun write(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
    val priority = when (level) {
      LogLevel.VERBOSE -> Log.VERBOSE
      LogLevel.DEBUG -> Log.DEBUG
      LogLevel.INFO -> Log.INFO
      LogLevel.WARN -> Log.WARN
      LogLevel.ERROR -> Log.ERROR
    }
    val text = if (throwable == null) message else message + "\n" + Log.getStackTraceString(throwable)
    Log.println(priority, "MyCloud/$tag", text)
  }
}
