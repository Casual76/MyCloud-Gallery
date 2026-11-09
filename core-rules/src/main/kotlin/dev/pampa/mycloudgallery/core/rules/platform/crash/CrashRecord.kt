package dev.pampa.mycloudgallery.core.rules.platform.crash

import dev.pampa.mycloudgallery.core.common.diagnostics.ExportValue

/** Cos'e' successo: l'app si e' chiusa per un'eccezione non gestita, o il thread principale non rispondeva (ANR). */
enum class CrashKind { CRASH, ANR }

/**
 * Il thread in cui e' successo, ridotto a una **famiglia chiusa**: il nome vero di un thread puo' portare un indirizzo o un nome di rete (smbj chiama i suoi thread
 * "Packet Reader for <indirizzo>", OkHttp mette il nome dell'host). Qui non ne resta niente: solo una costante di questo elenco.
 */
enum class ThreadKind {
  MAIN,

  /** `DefaultDispatcher-worker-N`, `kotlinx.coroutines.DefaultExecutor`: le coroutine. */
  COROUTINE,

  /** `pool-N-thread-M`: gli esecutori di Java. */
  POOL,

  /** `WM.task-N`: WorkManager. */
  WORK,

  /** `Binder:PID_N`. */
  BINDER,
  OTHER,
  ;

  companion object {
    fun of(name: String?): ThreadKind = when {
      name == null -> OTHER
      name == "main" -> MAIN
      name.startsWith("DefaultDispatcher-worker") || name.startsWith("kotlinx.coroutines.") -> COROUTINE
      name.startsWith("pool-") -> POOL
      name.startsWith("WM.task") -> WORK
      name.startsWith("Binder:") -> BINDER
      else -> OTHER
    }
  }
}

/**
 * Un'eccezione della catena (la prima, poi le sue cause): la **classe** e le sue prime cornici nostre. Mai il messaggio (C472).
 *
 * @property className il nome della classe dell'eccezione (`java.io.IOException`).
 * @property frames le cornici di codice nostro come `classe.metodo(File.kt:riga)`, dalla piu' vicina al punto in cui e' stata lanciata.
 */
class CrashCause(val className: String, val frames: List<String>) {

  init {
    require(ExportValue.Trace.isEntry(className)) { "nome di classe non valido" }
    require(frames.size <= CrashDigest.MAX_FRAMES) { "troppe cornici" }
    require(frames.all { ExportValue.Trace.isEntry(it) }) { "cornice non valida" }
  }

  override fun equals(other: Any?): Boolean = other is CrashCause && other.className == className && other.frames == frames

  override fun hashCode(): Int = 31 * className.hashCode() + frames.hashCode()

  override fun toString(): String = "CrashCause($className, ${frames.size} cornici)"
}

/**
 * Un crash (o un ANR) registrato: quando, in quale thread, con quale versione dell'app e dove (la catena delle eccezioni con le cornici nostre).
 *
 * **Niente testo libero**, per costruzione: ogni campo e' un numero, una costante o un identificativo di codice ([ExportValue.Trace.isEntry]). Un messaggio
 * di eccezione (che puo' portare un percorso, un nome di file, un indirizzo) non ha un posto dove stare.
 *
 * @property atMillis l'ora del telefono (ms dall'epoca).
 * @property versionCode la versione dell'app che si e' chiusa.
 * @property mainProcess il processo principale dell'app (oggi l'unico: il manifest non dichiara altri processi).
 * @property chain la catena: l'eccezione lanciata e poi le sue cause.
 */
class CrashRecord(
  val kind: CrashKind,
  val atMillis: Long,
  val versionCode: Long,
  val thread: ThreadKind,
  val mainProcess: Boolean,
  val chain: List<CrashCause>,
) {

  init {
    require(chain.size <= CrashDigest.MAX_CAUSES) { "catena troppo lunga" }
  }

  /** Il punto di codice nostro piu' vicino all'inizio della catena: e' la risposta a "dove". Null se nessuna cornice e' nostra. */
  val where: String? get() = chain.firstNotNullOfOrNull { it.frames.firstOrNull() }

  override fun equals(other: Any?): Boolean = other is CrashRecord && other.kind == kind && other.atMillis == atMillis &&
    other.versionCode == versionCode && other.thread == thread && other.mainProcess == mainProcess && other.chain == chain

  override fun hashCode(): Int = listOf(kind, atMillis, versionCode, thread, mainProcess, chain).hashCode()

  override fun toString(): String = "CrashRecord($kind, $atMillis, ${chain.size} eccezioni)"
}

/**
 * Da un'eccezione a una catena registrabile **senza i messaggi** (C472). Non legge mai `message`, `localizedMessage` o `toString()` di un'eccezione: del messaggio di
 * smbj o di una libreria non resta niente, perche' puo' contenere il percorso o il nome di un file dell'utente (C14, C21, C467).
 */
object CrashDigest {

  /** Le eccezioni della catena che si registrano: una catena patologica (circolare, lunghissima) non si scrive tutta. */
  const val MAX_CAUSES = 4

  /** Le cornici nostre per eccezione: le prime 25 dicono dove si e' rotto, il resto e' il ciclo del sistema o delle coroutine. */
  const val MAX_FRAMES = 25

  /** I pacchetti del nostro codice: l'app e il motore Fluid che porta con se' (nessun dato dell'utente in nessuno dei due). */
  private val OWN_PACKAGES = listOf("dev.pampa.", "dev.antigravity.fluidengine.")

  /** Il nome di una classe senza forma di identificativo (non succede con le classi vere): non si scrive quello che non si sa. */
  private const val UNKNOWN_CLASS = "Unknown"

  fun chain(error: Throwable): List<CrashCause> {
    val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
    val result = ArrayList<CrashCause>(MAX_CAUSES)
    var current: Throwable? = error
    while (current != null && result.size < MAX_CAUSES && seen.add(current)) {
      result += CrashCause(classNameOf(current), framesOf(current))
      current = current.cause
    }
    return result
  }

  /** Una cornice nostra come `classe.metodo(File.kt:riga)`, o null se non e' nostra o non ha la forma attesa. */
  fun frameOf(element: StackTraceElement): String? = frameOf(element.className, element.methodName, element.fileName, element.lineNumber)

  internal fun frameOf(className: String, method: String, file: String?, line: Int): String? {
    if (OWN_PACKAGES.none { className.startsWith(it) }) return null
    val where = if (file != null && line >= 0) "($file:$line)" else ""
    val text = "$className.$method$where"
    if (ExportValue.Trace.isEntry(text)) return text
    // Un file senza la forma attesa (un nome insolito): il punto resta, senza file e riga.
    return "$className.$method".takeIf { ExportValue.Trace.isEntry(it) }
  }

  private fun framesOf(error: Throwable): List<String> {
    val result = ArrayList<String>(MAX_FRAMES)
    for (element in error.stackTrace) {
      val frame = frameOf(element) ?: continue
      result += frame
      if (result.size == MAX_FRAMES) break
    }
    return result
  }

  private fun classNameOf(error: Throwable): String = error.javaClass.name.takeIf { ExportValue.Trace.isEntry(it) } ?: UNKNOWN_CLASS
}
