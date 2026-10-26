package dev.pampa.mycloudgallery.core.faces.contract

import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.StableCode
import kotlin.time.Duration
import kotlinx.coroutines.flow.StateFlow

// "DISATTIVA E CANCELLA" (docs/fase8-progetto.md §1.2 "Cancellazione", §2.8, D29-D31, D35, E-F30-E-F35). Contratti di P0; il protocollo vero
// (erase/: diario `faces-erase` scritto prima di ogni passo, chiave del Keystore distrutta **per prima**, voce `off|G`, NAS con
// `FacesEraseOps`, verifica, conferme dei dispositivi) e' di P8, il foglio (feature-people/.../erase/) pure. "Disattiva e cancella" e'
// un'azione **locale confermata**: la conferma la costruisce SOLO il foglio (Phase8ArchitectureTest). Mai un file toccato fuori da
// `faces/` dell'area privata, mai un media (invarianti del §2.8).

/** La scelta sugli album smart con le persone (§2.7, §2.8 passo 3): congelarli con le foto di adesso (preselezionata) o togliere la regola. */
enum class PeopleAlbumsChoice(override val code: String) : StableCode {
  KEEP_PHOTOS("keep_photos"),
  DROP_RULE("drop_rule"),
}

/**
 * Il riassunto mostrato dal foglio (§2.8, `ErasePreview`): quante persone con un nome si perdono, quanti album smart usano le persone, se ci
 * sono ricerche recenti con i nomi, i dispositivi dell'account (con chi e' spento), se Persone e' solo su questo dispositivo (recinzione,
 * D34). [digest] e' l'impronta del riassunto: la conferma la porta, e se il riassunto cambia la conferma non vale piu'.
 */
data class ErasePreview(
  val namedPeople: Int,
  val albumsWithPeople: Int,
  val historyWithNames: Boolean,
  val devices: List<DeviceAck>,
  val localOnly: Boolean,
  val digest: Long,
) {
  override fun toString(): String = "ErasePreview($namedPeople, $albumsWithPeople, ${devices.size} dispositivi, localOnly=$localOnly)"
}

/**
 * La conferma di "Disattiva e cancella" (§2.8): l'impronta dell'anteprima mostrata e l'istante (orologio monotono) in cui il foglio era
 * sullo schermo. **La costruisce SOLO il foglio "Disattiva e cancella" di feature-people** (`erase/FacesErase*.kt`, P8):
 * Phase8ArchitectureTest fallisce se un altro sorgente di produzione la costruisce. Scade dopo [VALIDITY_NANOS] (10 minuti, come le altre).
 */
class FacesEraseConfirmation(val previewDigest: Long, val shownAtMonotonicNanos: Long) {
  /** Vero se all'istante monotono [nowNanos] la conferma vale ancora. */
  fun isValidAt(nowNanos: Long): Boolean = nowNanos >= shownAtMonotonicNanos && nowNanos - shownAtMonotonicNanos <= VALIDITY_NANOS

  override fun toString(): String = "Conferma di Disattiva e cancella"

  companion object {
    const val VALIDITY_NANOS: Long = 10L * 60 * 1_000_000_000
  }
}

/** Lo stato di un passo grosso della cancellazione (locale, NAS). */
enum class StepState(override val code: String) : StableCode {
  NOT_STARTED("not_started"),
  RUNNING("running"),

  /** In attesa con un motivo dichiarato ([EraseStatus.waiting]). */
  WAITING("waiting"),
  DONE("done"),

  /** Non serve (recinzione D34: sul NAS non c'e' niente). */
  NOT_NEEDED("not_needed"),
}

/** Cosa dice un dispositivo dell'account (§2.8 passo 10). */
enum class AckState(override val code: String) : StableCode {
  /** La sua ultima testa personale ha `faces` diversa da `on|G`: **ha cancellato** (D30). */
  CONFIRMED("confirmed"),

  /** Non ancora (spento, fuori casa): cancellera' appena si riaccende, prima di pubblicare (E-F32). */
  WAITING("waiting"),

  /** Revocato o perso: non si aspetta (L95); la sua cartella si toglie (D31). */
  NOT_EXPECTED("not_expected"),
}

/**
 * Un dispositivo dell'account nella pagina della cancellazione: il nome da mostrare (gia' validato dal registro, `displayName`), lo stato e
 * l'ultimo contatto (ora del NAS della sua ultima testa personale letta, null se mai). Mai l'id nei log.
 */
data class DeviceAck(val label: String, val deviceId: String, val state: AckState, val lastContactAtNas: Long?, val thisDevice: Boolean) {
  override fun toString(): String = "DeviceAck(${state.code}, questo=$thisDevice)"
}

/** Perche' la cancellazione aspetta (§2.8): un motivo dichiarato, mai un'attesa muta (invariante 6). */
enum class EraseWait(override val code: String) : StableCode {
  NAS("nas"),
  FROZEN("frozen"),
  CLOCK_JUMP("clock_jump"),
  READ_ONLY("read_only"),
  EMERGENCY_STOP("emergency_stop"),
  NOT_TRUSTED("not_trusted"),
  DEVICES("devices"),
  RECYCLE_BIN("recycle_bin"),
}

/** I passi del diario `faces-erase` (§2.8): scritti con `fsync` prima di ogni passo; una ripresa parte dal passo dopo l'ultimo segnato. */
enum class EraseStep(override val code: String) : StableCode {
  STARTED("started"),
  STOPPED("stopped"),
  ALBUMS("albums"),
  HISTORY("history"),
  SETTING("setting"),
  LOCAL_DONE("local_done"),
  ANNOUNCED("announced"),
  NAS_DONE("nas_done"),
  VERIFIED("verified"),
  CONFIRMED("confirmed"),
  DONE("done"),
}

/** Lo stato per la pagina (§1.5 `EraseSheetState.Progress`): locale, NAS, dispositivi e il motivo dell'attesa. Null = nessuna cancellazione. */
data class EraseStatus(
  val generation: FaceGeneration,
  val step: EraseStep,
  val local: StepState,
  val nas: StepState,
  val devices: List<DeviceAck>,
  val waiting: EraseWait?,
) {
  override fun toString(): String = "EraseStatus(${step.code}, ${local.code}, ${nas.code}, ${waiting?.code})"
}

/** L'esito di [FacesEraser.erase]. */
sealed interface EraseStart {
  /** Partita: i passi locali sono fatti o in corso; il resto quando il NAS e' raggiungibile. */
  data object Started : EraseStart

  /** La conferma e' scaduta (oltre 10 minuti): il foglio si riapre. */
  data object Expired : EraseStart

  /** L'anteprima e' cambiata (un'altra persona con nome, un album nuovo): il foglio si riapre con i numeri nuovi. */
  data object PreviewChanged : EraseStart

  /** Una cancellazione e' gia' in corso: la pagina mostra quella. */
  data object AlreadyRunning : EraseStart

  /** Persone non e' attiva per l'account: niente da cancellare. */
  data object NothingToErase : EraseStart
}

/** Il resoconto di una ripresa (solo numeri e codici). */
data class EraseReport(val step: EraseStep, val nasFilesRemoved: Int, val waiting: EraseWait?, val done: Boolean)

/**
 * "Disattiva e cancella" (D29-D31, §2.8). [preview] calcola il riassunto del foglio; [erase] parte con la conferma del foglio e la scelta
 * sugli album; [resume] riprende dal diario (il lavoro `FacesEraseWorker`: dopo una morte del processo, fuori casa, con il congelamento);
 * [status] e' lo stato per la pagina. Ogni passo idempotente; mai un'eccezione per un caso previsto.
 */
interface FacesEraser {
  val status: StateFlow<EraseStatus?>

  suspend fun preview(): ErasePreview

  suspend fun erase(confirmation: FacesEraseConfirmation, albums: PeopleAlbumsChoice): EraseStart

  suspend fun resume(budget: Duration): EraseReport
}
