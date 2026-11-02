package dev.pampa.mycloudgallery.feature.diagnostics.release

import kotlinx.coroutines.CancellationException

// La "Simulazione del passo" (Fase 10 P10; docs/fase10-progetto.md §2.10, D35, Q6 (b)): prima del passo unico che spegne le quattro recinzioni,
// una lettura in SOLA LETTURA che dice, per ognuna delle quattro aree in un giro solo, che cosa scriverebbe sulle share vere: quanti file e
// quanti byte, in quali cartelle di primo livello del formato, e lo spazio libero del NAS dopo il passo intero. Questo file e' la parte pura
// (nessun Android, nessun NAS): i tipi, il giro e il testo. Chi legge i numeri veri (il piano del backup, il catalogo, la replica, lo spazio del
// NAS) sta in `:app/.../release/`, dietro [StepSources].
//
// "Mai nomi di file": i tipi di questo file non hanno un campo di testo libero. Le cartelle sono un insieme chiuso del formato ([StepFolder]),
// il resto sono numeri e costanti di enum; un nome letto dal NAS o scelto dall'utente (il dispositivo sotto `Backup/`, il titolo di un album,
// un file) non ha dove stare. La prova e' `StepSimulationPrivacyTest`.

/** Le quattro aree del passo unico, una per recinzione di `tools/release/recinzioni.json`. */
enum class StepArea(val fence: String) {
  /** F4-D31: segmenti dell'indice, prenotazioni e dati personali in `.mycloudgallery/`. */
  INDEX("F4-D31"),

  /** F5-D12: le copie dei media in `Backup/<dispositivo>/AAAA/MM/` e le versioni in `Versioni/`. */
  BACKUP("F5-D12"),

  /** F8-D34: `faces/` nell'area privata (solo se l'utente usa Persone). */
  FACES("F8-D34"),

  /** F7-D30: definizioni firmate e spostamenti nell'area comune (solo quando l'utente condivide un album). */
  ALBUMS("F7-D30"),
}

/** Le cartelle di primo livello **del formato** (`docs/formato-nas.md` §2.1, §2.2): un insieme chiuso, mai un nome letto da fuori. */
enum class StepFolder(val label: String) {
  APP_FOLDER(".mycloudgallery"),
  BACKUP("Backup"),
  VERSIONS("Versioni"),
  SHARED_ALBUMS("Album condivisi"),
}

/** Dove sta la cartella: nell'area privata dell'account o nell'area comune. */
enum class StepPlace { PRIVATE_AREA, COMMON_AREA }

/** Quanto ci si puo' fidare di un numero: esatto (contato) o stimato (con una regola scritta nella documentazione). */
enum class StepCertainty { EXACT, ESTIMATE }

/** Perche' un'area non scrive niente. */
enum class StepIdle {
  /** La funzione non e' attiva su questo dispositivo (Persone spenta). */
  FEATURE_OFF,

  /** Non c'e' niente da fare adesso (nessun media da copiare). */
  NOTHING_TO_DO,

  /** Scrive solo quando l'utente fa un gesto (condividere un album). */
  ON_USER_ACTION,
}

/** Perche' un'area non ha dato i suoi numeri. Il passo non parte con un'area cosi' (E-SV1). */
enum class StepUnavailable { NOT_READY, FAILED }

/** Un gruppo di scritture: [files] file per [bytes] byte dentro [folder] dell'area [place]. */
data class StepWrite(val place: StepPlace, val folder: StepFolder, val files: Long, val bytes: Long) {
  init {
    require(files >= 0 && bytes >= 0) { "Numeri negativi." }
  }
}

/** Che cosa scriverebbe un'area. */
sealed interface StepForecast {
  val area: StepArea

  data class Writes(override val area: StepArea, val certainty: StepCertainty, val writes: List<StepWrite>) : StepForecast {
    init {
      require(writes.isNotEmpty()) { "Uno scrive-niente e' Idle." }
    }

    val files: Long get() = writes.sumOf { it.files }
    val bytes: Long get() = writes.sumOf { it.bytes }
  }

  data class Idle(override val area: StepArea, val why: StepIdle) : StepForecast

  data class Unavailable(override val area: StepArea, val why: StepUnavailable) : StepForecast
}

/** Lo spazio libero per l'account sul NAS (il minore fra le due share, se non e' lo stesso disco: si conta il caso peggiore). */
data class StepSpace(val freeBytes: Long) {
  init {
    require(freeBytes >= 0) { "Spazio negativo." }
  }
}

/**
 * Cio' che la simulazione chiede: **solo letture**. Non ha un metodo che scrive; chi lo realizza (`:app/.../release/`) legge dal piano del
 * backup, dal catalogo e dallo spazio del NAS (un'interrogazione, mai una scrittura).
 */
interface StepSources {
  /** I numeri di [area]. Lancia solo `CancellationException`: un guasto diventa [StepForecast.Unavailable] nel giro. */
  suspend fun forecast(area: StepArea): StepForecast

  /** Lo spazio libero del NAS adesso, o null se non si sa. */
  suspend fun space(): StepSpace?
}

/** Le cose da guardare prima del si'. [blocking] = il passo non parte (E-SV1); le altre informano. */
enum class StepAttention(val blocking: Boolean) {
  /** Un'area non ha dato i suoi numeri. */
  AREA_UNAVAILABLE(true),

  /** Lo spazio del NAS non si e' letto. */
  SPACE_UNKNOWN(true),

  /** Dopo il passo intero lo spazio libero scenderebbe sotto [StepLimits.MIN_FREE_AFTER_BYTES]. */
  SPACE_BELOW_MARGIN(true),

  /** Almeno un'area da' una stima, non un numero contato. */
  ESTIMATES_PRESENT(false),
}

object StepLimits {
  /** Lo stesso margine del NAS (`NasOpsRules.MIN_FREE_MARGIN_BYTES`): dopo il passo devono restare almeno 1 GiB liberi. */
  const val MIN_FREE_AFTER_BYTES: Long = 1L shl 30
}

/** Il resoconto: una riga per area, nell'ordine fisso di [StepArea], e i totali. Solo numeri, cartelle del formato e costanti. */
data class StepReport(
  val forecasts: List<StepForecast>,
  val space: StepSpace?,
  val freeAfterBytes: Long?,
  val attention: Set<StepAttention>,
) {
  val totalFiles: Long get() = forecasts.filterIsInstance<StepForecast.Writes>().sumOf { it.files }
  val totalBytes: Long get() = forecasts.filterIsInstance<StepForecast.Writes>().sumOf { it.bytes }

  /** C'e' qualcosa che ferma il passo (E-SV1): l'utente decide con i numeri davanti. */
  val blocked: Boolean get() = attention.any { it.blocking }
}

/**
 * Il giro: le quattro aree **in un giro solo**, in un ordine fisso, e lo spazio. Non scrive mai: [StepSources] non ha scritture. Un guasto
 * di un'area la segna [StepUnavailable.FAILED] e il giro continua (le altre tre dicono comunque i loro numeri); la cancellazione del lavoro
 * si propaga.
 */
class StepSimulation(private val sources: StepSources) {

  suspend fun run(): StepReport {
    val forecasts = StepArea.entries.map { area -> forecastOf(area) }
    val space = try {
      sources.space()
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      null
    }
    val total = forecasts.filterIsInstance<StepForecast.Writes>().sumOf { it.bytes }
    val freeAfter = space?.let { it.freeBytes - total }
    val attention = LinkedHashSet<StepAttention>()
    if (forecasts.any { it is StepForecast.Unavailable }) attention += StepAttention.AREA_UNAVAILABLE
    if (space == null) attention += StepAttention.SPACE_UNKNOWN
    if (freeAfter != null && freeAfter < StepLimits.MIN_FREE_AFTER_BYTES) attention += StepAttention.SPACE_BELOW_MARGIN
    if (forecasts.any { it is StepForecast.Writes && it.certainty == StepCertainty.ESTIMATE }) attention += StepAttention.ESTIMATES_PRESENT
    return StepReport(forecasts, space, freeAfter, attention)
  }

  private suspend fun forecastOf(area: StepArea): StepForecast = try {
    sources.forecast(area).let { if (it.area == area) it else StepForecast.Unavailable(area, StepUnavailable.FAILED) }
  } catch (e: CancellationException) {
    throw e
  } catch (_: Exception) {
    StepForecast.Unavailable(area, StepUnavailable.FAILED)
  }
}
