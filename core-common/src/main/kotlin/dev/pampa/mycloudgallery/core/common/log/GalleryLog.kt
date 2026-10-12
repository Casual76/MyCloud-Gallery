package dev.pampa.mycloudgallery.core.common.log

/** Gravita' di una riga di log, dalla piu' chiacchierona alla piu' seria. */
enum class LogLevel { VERBOSE, DEBUG, INFO, WARN, ERROR }

/** Dove finisce una riga di log. Ne esiste una sola implementazione vera, ed e' solo nella build debug. */
fun interface LogSink {
  fun write(level: LogLevel, tag: String, message: String, throwable: Throwable?)
}

/**
 * Il logger interno della galleria.
 *
 * **In release non scrive nulla, e per costruzione**: senza un [LogSink] installato ogni chiamata
 * esce alla prima riga senza nemmeno costruire il messaggio, e l'unico sink che scrive su logcat
 * vive nel source set `debug` di `:app`, quindi nella build release non esiste proprio codice che
 * possa farlo. Un test, o un controllo del genere, non basta: dev'essere impossibile.
 *
 * Il messaggio e' una lambda perche' costruirlo costa (e a volte contiene dati): con il logger
 * spento non viene mai valutato.
 *
 * Regola d'oro (docs/sicurezza.md, C14 e C21): **mai** password, token, chiavi, nomi di file o
 * percorsi personali, coordinate. Se serve loggare qualcosa che le contiene, passa da
 * [dev.pampa.mycloudgallery.core.common.security.Redacted].
 */
object GalleryLog {

  @Volatile
  private var sink: LogSink? = null

  /** Vero solo se qualcuno ha installato un sink: in release e' sempre falso. */
  val isEnabled: Boolean get() = sink != null

  fun install(sink: LogSink) {
    this.sink = sink
  }

  fun uninstall() {
    sink = null
  }

  fun v(tag: String, message: () -> String) = log(LogLevel.VERBOSE, tag, null, message)

  fun d(tag: String, message: () -> String) = log(LogLevel.DEBUG, tag, null, message)

  fun i(tag: String, message: () -> String) = log(LogLevel.INFO, tag, null, message)

  fun w(tag: String, throwable: Throwable? = null, message: () -> String) =
    log(LogLevel.WARN, tag, throwable, message)

  fun e(tag: String, throwable: Throwable? = null, message: () -> String) =
    log(LogLevel.ERROR, tag, throwable, message)

  private fun log(level: LogLevel, tag: String, throwable: Throwable?, message: () -> String) {
    val target = sink ?: return
    // Un log che fa cadere l'app e' peggio di un log perso: ne' il messaggio ne' il sink possono
    // propagare un'eccezione al chiamante.
    val text = try {
      message()
    } catch (_: Exception) {
      "<messaggio di log non valido>"
    }
    try {
      target.write(level, tag, text, throwable)
    } catch (_: Exception) {
      // ignorata di proposito
    }
  }
}
