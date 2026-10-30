package dev.pampa.mycloudgallery.core.faces.contract

import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.StableCode
import kotlin.time.Duration
import kotlinx.coroutines.flow.StateFlow

// LA SINCRONIZZAZIONE DEI DATI DEI VOLTI FRA I DISPOSITIVI DELL'ACCOUNT (docs/fase8-progetto.md §1.2 "Sincronizzazione", §2.4, D11-D13,
// D21, D30, D34-D36, E-F41, E-F42). Contratti di P0; il pubblicatore e la replica veri (sync/, l'UNICO pacchetto di core-faces che vede
// `NasFileSystem`) e il codec (`FacesDocs` in core-trust) sono di P3. Si scrive **solo** con `publishExclusive`, **solo** sotto
// `faces/<G>/<proprio dispositivo>/` dell'area privata dichiarata, **mai** nell'area comune (L23), **solo** con lo stato fresco che dice
// `On(G)` (D30), dietro [FacesWriteGate] (D34, D35). Si leggono solo teste di dispositivi **dello stesso account** e della generazione
// corrente; una generazione non corrente non si legge mai.

/** Cosa c'e' da pubblicare (le voci le prende il pubblicatore dall'archivio, P6; qui solo cosa e' cambiato). */
sealed interface FacesChange {
  /** Il corpo `faces` di [contentKey] (solo per media del NAS di questo account con timbro e `qh1` freschi: "M3 dei volti", §2.4). */
  data class Faces(val contentKey: ContentKey) : FacesChange {
    override fun toString(): String = "FacesChange.Faces"
  }

  /** L'istantanea [seq] del raggruppamento (solo il custode, D21). */
  data class Snapshot(val seq: Long) : FacesChange

  /** Voci `people` nuove (nomi e correzioni): si ripubblica lo **stato unito** (come F4-D26). */
  data class People(val edits: List<PeopleEdit>) : FacesChange {
    override fun toString(): String = "FacesChange.People(${edits.size})"
  }
}

/** Perche' si prova a pubblicare (§2.3, §2.4). */
enum class FlushReason(override val code: String) : StableCode {
  /** 30 s dopo l'ultima modifica (accorpamento). */
  DEBOUNCE("debounce"),

  /** Fine di un'unita' del calcolo: anche prima dei 5 minuti. */
  UNIT_FINISHED("unit_finished"),

  /** Fine della manutenzione notturna del custode. */
  MAINTENANCE_FINISHED("maintenance_finished"),

  /** Il giro di sincronizzazione (`IndexSyncWorker`, **dopo** i dati personali: cosi' la generazione e' fresca). */
  SYNC_ROUND("sync_round"),

  /** Dopo una ripresa: si ripubblica lo stato intero del proprio contributo. */
  RESUME("resume"),
}

/** Perche' non si scrive adesso sul NAS (D34, D35). Le scritture restano in coda, nessuna va persa. */
enum class FacesWriteBlock(override val code: String) : StableCode {
  /** Area congelata (scansione con troppi spariti o cambiati). */
  FROZEN("frozen"),

  /** "Ferma le scritture sul NAS" della Diagnostica. */
  EMERGENCY_STOP("emergency_stop"),

  /** Salto dell'ora del NAS (F4-D32). */
  CLOCK_JUMP("clock_jump"),

  /** Questo dispositivo non e' fidato (registro). */
  NOT_TRUSTED("not_trusted"),

  /** Area in sola lettura per l'account. */
  READ_ONLY("read_only"),

  /** D34: i dati dei volti sul NAS solo sulle share di prova (`facesSyncTestSharesOnly`): sulle share vere tutto in locale. */
  TEST_SHARES_ONLY("test_shares_only"),

  /** F4-D33, solo debug: `ShareFence` con un account di prova. */
  FENCE("fence"),

  /** Nessuna area privata dichiarata, o uguale all'area comune (L23): mai dati dei volti fuori dall'area privata. */
  NO_PRIVATE_AREA("no_private_area"),

  /** Nessun NAS configurato. */
  NO_NAS("no_nas"),
}

/**
 * Il cancello delle scritture dei volti (D34, D35, §1.4): `IndexWriteRules` (congelamento, emergenza, salto dell'ora, fiducia, sola lettura)
 * + `FeatureFlags.facesSyncMayUse` (D34) + `ShareFence` in debug. Lo lega :app (P13). null = si puo' scrivere in [share]. Vale per la
 * pubblicazione, le prenotazioni dei volti e la cancellazione sul NAS (con il congelamento la cancellazione sul NAS **aspetta**, quella
 * locale no).
 */
fun interface FacesWriteGate {
  suspend fun blockFor(share: String): FacesWriteBlock?
}

/** L'esito di [FacesPublisher.flush]. */
sealed interface FacesPublishOutcome {
  /** Una testa nuova ([version]) con [entries] voci. */
  data class Published(val version: Long, val entries: Int) : FacesPublishOutcome

  data object NothingToPublish : FacesPublishOutcome

  /** Meno di [FacesPublishRules.MIN_HEAD_INTERVAL_MS] dalla testa precedente: riprova fra [retryAfterMs] (monotoni). */
  data class TooSoon(val retryAfterMs: Long) : FacesPublishOutcome

  data class Blocked(val reason: FacesWriteBlock) : FacesPublishOutcome

  /** D30: nessun giro completo dei dati personali da meno di 15 minuti che dica `On(G)`: niente si pubblica. */
  data object NotFresh : FacesPublishOutcome

  /** Errore del NAS: niente perso, si riprova. */
  data object Failed : FacesPublishOutcome
}

/** I ritmi della pubblicazione dei volti (§2.4). */
object FacesPublishRules {
  /** Al piu' una testa ogni 5 minuti d'ora del NAS, salvo la fine di un'unita' o della manutenzione. */
  const val MIN_HEAD_INTERVAL_MS: Long = 5L * 60 * 1000

  /** Modifiche dei nomi accorpate in 30 s. */
  const val DEBOUNCE_MS: Long = 30L * 1000

  /** Pulizia dei propri file come `OwnFilesPolicy` (M6): le ultime 3 teste, 7 giorni di grazia. */
  const val KEEP_HEADS: Int = 3
  const val GRACE_DAYS: Int = 7
}

/**
 * Il pubblicatore dei dati dei volti di QUESTO dispositivo (§2.4): accoda ([stage], durevole: sopravvive alla morte del processo),
 * accorpa e pubblica ([flush]) dietro [FacesWriteGate] e solo con lo stato fresco. Idempotente: la stessa modifica due volte = una voce.
 */
interface FacesPublisher {
  suspend fun stage(change: FacesChange)

  suspend fun flush(reason: FlushReason): FacesPublishOutcome

  val pending: StateFlow<Int>
}

/** Perche' un giro di replica si e' fermato. */
enum class FacesSyncStop(override val code: String) : StableCode {
  COMPLETE("complete"),
  BUDGET("budget"),

  /** Nessuno stato fresco (D30): non si legge e non si pubblica niente della generazione. */
  NOT_FRESH("not_fresh"),

  /** Recinzione (D34): niente sul NAS. */
  LOCAL_ONLY("local_only"),

  /** Persone non attiva qui (spenta, in attesa dell'avviso, esclusa, in cancellazione, voce sconosciuta). */
  NOT_ACTIVE("not_active"),

  UNAVAILABLE("unavailable"),
}

/**
 * Il resoconto di un giro di replica (solo numeri, D37): dispositivi e teste lette, teste e segmenti rifiutati per motivo (codici stabili,
 * `FACES_HEAD_REJECTED`, `FACES_SEGMENT_REJECTED`), byte letti (al piu' 64 MiB per giro), generazioni non correnti trovate (D31,
 * `FACES_FOREIGN_GENERATION`), pubblicazione.
 */
data class FacesSyncReport(
  val devicesRead: Int,
  val headsAccepted: Int,
  val headsRejected: Map<String, Int>,
  /**
   * I segmenti che non sono entrati, per motivo (codici stabili di [FacesSegmentCodes] o motivi di rifiuto). Dentro ci sono anche quelli **in attesa** e quelli
   * rimandati per l'archivio pieno: `FACES_SEGMENT_REJECTED` conta solo i veri rifiuti ([segmentsRefused]), gli altri hanno i loro codici
   * ([segmentsWaiting], [segmentsArchiveFull]).
   */
  val segmentsRejected: Map<String, Int>,
  val bytesRead: Long,
  val foreignGenerations: Int,
  val published: FacesPublishOutcome?,
  val stop: FacesSyncStop,
) {
  /** Segmenti che aspettano e si riprovano (assenti, illeggibili, con l'ora nel futuro): `FACES_SEGMENT_WAITING` (C234). */
  val segmentsWaiting: Int get() = segmentsRejected.filterKeys { it in FacesSegmentCodes.WAITING }.values.sum()

  /** Segmenti e voci che non entrano perche' l'archivio dei volti e' pieno: `FACES_ARCHIVE_FULL` (C232). */
  val segmentsArchiveFull: Int get() = segmentsRejected.filterKeys { it in FacesSegmentCodes.ARCHIVE_FULL_CODES }.values.sum()

  /** Segmenti davvero rifiutati (forma, tetti dell'autore, impronta): `FACES_SEGMENT_REJECTED`. */
  val segmentsRefused: Int get() = segmentsRejected.values.sum() - segmentsWaiting - segmentsArchiveFull
}

/**
 * I motivi, nel [FacesSyncReport.segmentsRejected], che non sono un rifiuto: il segmento **aspetta** ([WAITING]) o l'archivio e' **pieno**
 * ([ARCHIVE_FULL_CODES]: C232, C234). Stessi valori che scrive `NasFacesSync`; la Diagnostica li separa dal conto dei rifiutati (solo numeri).
 */
object FacesSegmentCodes {
  /** R5: elencato e assente. */
  const val MISSING: String = "MISSING"

  /** R5: un file che il NAS non da' mentre risponde a un elenco. */
  const val UNREADABLE: String = "UNREADABLE"

  /** R6: i corpi di un autore oltre i suoi tetti (segmento rifiutato per sempre). */
  const val AUTHOR_FULL: String = "AUTHOR_FULL"

  /** R6: l'archivio e' pieno (segmento rimandato). */
  const val ARCHIVE_FULL: String = "ARCHIVE_FULL"

  /** R3: voci `people` che non entrano nell'archivio per i tetti. */
  const val PEOPLE_FULL: String = "PEOPLE_FULL"

  /** Una voce `people` con l'ora oltre i 2 minuti nel futuro (`PeopleVerdict.FROM_FUTURE.code`): aspetta. */
  const val FROM_FUTURE: String = "people_from_future"

  val WAITING: Set<String> = setOf(MISSING, UNREADABLE, FROM_FUTURE)

  val ARCHIVE_FULL_CODES: Set<String> = setOf(ARCHIVE_FULL, PEOPLE_FULL)
}

/** La replica dei dati dei volti dei dispositivi dell'account (P3). */
interface FacesSync {
  suspend fun sync(budget: Duration): FacesSyncReport
}
