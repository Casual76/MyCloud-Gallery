package dev.pampa.mycloudgallery

import android.util.Log
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.common.log.LogLevel
import dev.pampa.mycloudgallery.core.common.log.LogSink
import java.util.Collections
import java.util.IdentityHashMap

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
    val text = if (throwable == null) message else message + "\n" + LogThrowables.withoutMessages(throwable)
    Log.println(priority, "MyCloud/$tag", text)
  }
}

/**
 * Revisione 4A, I5 (correzione D della Fase 10): lo stack di un'eccezione **senza i messaggi**. I messaggi di smbj e delle altre librerie
 * possono contenere percorsi e nomi di file del NAS (C14, C21): anche nella build debug restano solo la classe di ogni eccezione della catena
 * e i punti del codice (classe, metodo, file e riga), che bastano a capire dove si e' rotto. Mai `Log.getStackTraceString`, che li copia.
 */
internal object LogThrowables {

  /** Le cause oltre questo numero (una catena patologica) non si scrivono. */
  private const val MAX_CAUSES = 8

  /** I punti del codice per eccezione: i primi bastano, il resto e' il ciclo di WorkManager o delle coroutine. */
  private const val MAX_FRAMES = 40

  fun withoutMessages(throwable: Throwable): String = buildString {
    val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
    var current: Throwable? = throwable
    while (current != null && seen.size < MAX_CAUSES && seen.add(current)) {
      if (seen.size > 1) append("Causato da: ")
      append(current.javaClass.name).append('\n')
      val frames = current.stackTrace
      for (frame in frames.take(MAX_FRAMES)) append("\tat ").append(frame.toString()).append('\n')
      if (frames.size > MAX_FRAMES) append("\t... ").append(frames.size - MAX_FRAMES).append(" altri\n")
      current = current.cause
    }
  }
}
