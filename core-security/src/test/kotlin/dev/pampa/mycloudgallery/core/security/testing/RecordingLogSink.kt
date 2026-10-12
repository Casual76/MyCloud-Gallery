package dev.pampa.mycloudgallery.core.security.testing

import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.common.log.LogLevel
import dev.pampa.mycloudgallery.core.common.log.LogSink

/** Raccoglie tutto cio' che il modulo scrive nei log, eccezioni comprese, per verificare che non ci siano segreti. */
internal class RecordingLogSink : LogSink {

  val lines = mutableListOf<String>()

  override fun write(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
    lines += "$level $tag $message"
    var cause = throwable
    while (cause != null) {
      lines += "$level $tag ${cause.javaClass.name}: ${cause.message}"
      cause = cause.cause
    }
  }

  val everything: String get() = lines.joinToString("\n")

  fun install(): RecordingLogSink {
    GalleryLog.install(this)
    return this
  }

  fun uninstall() {
    GalleryLog.uninstall()
  }
}
