package dev.pampa.mycloudgallery.core.rules.platform.crash

import android.app.Application

/**
 * Il gestore delle eccezioni non gestite che alimenta il **registro locale dei crash** ([CrashLogStore], C472).
 *
 * - **Non lancia mai.** Se non riesce a scrivere (disco pieno, cartella non scrivibile, memoria finita) passa la mano e basta: scrivere il registro non deve mai far
 *   cadere il processo una seconda volta ne' nascondere il crash vero al gestore di prima.
 * - **E' incatenato** al gestore che c'era ([previous]: su Android quello di sistema, che chiude il processo e fa comparire il dialogo di Android): lo chiama sempre,
 *   dopo la scrittura. Il comportamento dell'app davanti a un crash non cambia.
 * - **Non registra mai un messaggio** ([CrashDigest]): solo la catena delle classi e le prime cornici nostre.
 *
 * Una scrittura per volta anche se due thread cadono insieme (il registro e' sincronizzato).
 *
 * @param store dove si scrive; si risolve **al momento del crash**, cosi' installare il gestore all'avvio non costa niente (nessuna cartella creata, nessun I/O).
 * @param versionCode la versione dell'app che sta girando.
 * @param mainProcess se questo e' il processo principale dell'app.
 * @param clock l'ora del telefono (ms dall'epoca); i test ne danno una fissa.
 * @param previous il gestore di prima, a cui si passa sempre.
 */
class CrashLogHandler internal constructor(
  private val store: () -> CrashLogStore,
  private val versionCode: Long,
  private val mainProcess: () -> Boolean,
  private val clock: () -> Long,
  private val previous: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {

  override fun uncaughtException(thread: Thread, error: Throwable) {
    try {
      store().record(
        CrashRecord(
          kind = CrashKind.CRASH,
          atMillis = clock(),
          versionCode = versionCode,
          thread = ThreadKind.of(thread.name),
          mainProcess = mainProcess(),
          chain = CrashDigest.chain(error),
        ),
      )
    } catch (_: Throwable) {
      // Nemmeno un OutOfMemoryError o uno StackOverflowError devono impedire al gestore di prima di fare il suo lavoro.
    }
    previous?.uncaughtException(thread, error)
  }

  companion object {

    /**
     * Installa il gestore **all'inizio di `Application.onCreate`**, incatenato a quello che c'era. Idempotente: se il gestore di serie e' gia' questo (un secondo
     * `onCreate` nello stesso processo, i test), non si incatena due volte. Non fa I/O: la cartella del registro si apre solo se qualcosa cade.
     */
    fun install(app: Application, versionCode: Long): Boolean {
      val current = Thread.getDefaultUncaughtExceptionHandler()
      if (current is CrashLogHandler) return false
      Thread.setDefaultUncaughtExceptionHandler(
        CrashLogHandler(
          store = { CrashLogStore.inNoBackupDir(app) },
          versionCode = versionCode,
          mainProcess = { isMainProcess(app) },
          clock = System::currentTimeMillis,
          previous = current,
        ),
      )
      return true
    }

    /** Il processo principale ha per nome il pacchetto; un processo con i due punti ([Application.getProcessName]) e' un altro. Senza risposta: principale. */
    internal fun isMainProcess(app: Application): Boolean = try {
      Application.getProcessName() == app.packageName
    } catch (_: RuntimeException) {
      true
    }
  }
}
