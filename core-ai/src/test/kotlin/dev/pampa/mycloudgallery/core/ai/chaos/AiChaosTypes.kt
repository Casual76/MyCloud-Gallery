package dev.pampa.mycloudgallery.core.ai.chaos

import kotlin.random.Random

// Il BANCO DELLE INTERRUZIONI DEL CALCOLO AI (docs/fase6-progetto.md §5.5, P10): gli invarianti 1, 4-10 del §5.5 su giri casuali ripetibili
// dal seme, sul NAS finto del calcolo (`SimNas` di P5), con i motori finti, il vero `AiComputeEngine`, il vero `DiskAiBuffer` e il vero
// `LocalModels` dei modelli. Gli invarianti 2, 3 e 6 sul NAS dei modelli li controlla il banco di core-models (`core.models.chaos`).
//
// Il banco imita quelli delle fasi 4 e 5 (docs/prova-500.md): un mondo, un piano di guasti deciso dal seme, un verificatore, un banco.

/** Gli invarianti del §5.5 che riguardano il calcolo, col numero del §5.5. */
enum class AiInvariant(val number: Int, val text: String) {
  VERIFIED_MODEL(1, "nessun modello non verificato caricato"),
  NO_RECOMPUTE(4, "nessun calcolo perso"),
  COHERENT_DATA(5, "dati coerenti"),
  OUT_OF_PLACE(6, "nessuna voce fuori posto"),
  LEASES(7, "prenotazioni"),
  REACHES_END(8, "il lavoro arriva in fondo"),
  BUFFER(9, "buffer sotto il tetto, nessun temporaneo orfano"),
  HIDDEN(10, "nascosti"),
}

enum class AiCheckPoint { AFTER_DEATH, AFTER_STEP, END, REREAD }

/**
 * Un difetto del codice di produzione che il banco ha trovato e che P10 non corregge (non e' nel suo perimetro): e' riferito nel REPORT con
 * il seme che lo riproduce, il banco lo conta a parte e [AiChaosKnownDefectsTest] verifica che sia ancora li'. Quando lo si corregge quel
 * test diventa rosso: si toglie l'etichetta da `AiChaosVerifier` e il banco lo vede come un errore vero.
 */
enum class KnownDefect(val id: String, val text: String) {
  BUFFER_ORPHAN_BLOBS(
    "AI-D1",
    "una morte fra il salvataggio di un media e lo svuotamento del buffer (fine unita' o fine tappa dei media del telefono) lascia le sue " +
      "anteprime nel buffer per sempre: `evict` usa solo i media toccati dalla tappa, e un media gia' salvato non e' piu' un candidato",
  ),
}

class AiViolation(val invariant: AiInvariant, val at: AiCheckPoint, val detail: String, val known: KnownDefect? = null) {
  override fun toString(): String = "I${invariant.number} (${invariant.text}) [$at]: $detail" + (known?.let { " [difetto noto ${it.id}]" } ?: "")
}

/** Dove, dentro un salvataggio, muore il processo (il salvataggio e' un file, poi una riga: P6, "Ordine delle scritture"). */
enum class SavePoint {
  /** Prima di scrivere qualunque cosa. */
  BEFORE_FILE,

  /** Il record nel file dei vettori e' scritto a meta': CRC sbagliato, la riga non c'e'. */
  TORN_RECORD,

  /** Il record e' tutto nel file, la riga no. */
  FILE_NO_ROW,

  /** Il salvataggio e' finito: la morte viene dopo. */
  AFTER_ROW,
}

enum class FaultKind { DROP, SESSION_EXPIRED, STALL }

enum class FaultMoment { BEFORE, AFTER }

class NasFault(val kind: FaultKind, val moment: FaultMoment)

/** Un evento fra due "controlli" del calcolo (le regole che il lavoro rilegge a ogni lotto). */
enum class CheckEvent {
  /** L'ora del NAS salta oltre la scadenza della prenotazione e un altro dispositivo prende le unita' (unita' presa a meta'). */
  OTHER_TAKES_UNITS,

  /** La share comune diventa non pubblicabile (F4-D31): le voci restano in attesa. */
  BLOCK_COMMON,

  /** La share comune torna pubblicabile. */
  UNBLOCK_COMMON,
}

/**
 * Cosa va storto in un giro. Gli indici contano da 1 su tutto il giro, vite del processo comprese; dopo [AiChaosRound.MAX_LIVES] vite
 * il banco ripristina rete e disco e chiede di arrivare in fondo.
 */
class AiRoundPlan(
  val seed: Long,
  val unitSize: Int,
  val batch: Int,
  val keepAliveEveryNanos: Long,
  val reading: Boolean,
  val commonMedia: Int,
  val privateMedia: Int,
  val brunoMedia: Int,
  val phoneMedia: Int,
  val sharedBetweenAreas: Int,
  val videos: Int,
  val badPreviews: Int,
  val hidden: Int,
  /** (numero del salvataggio, punto): il processo muore li'. */
  val deaths: Map<Int, SavePoint>,
  /** Guasti del NAS alla chiamata n (unita', scaricamento, pubblicazione, "gia' fatto"). */
  val nasFaults: Map<Int, NasFault>,
  /** Eventi alla chiamata n delle regole di A. */
  val events: Map<Int, CheckEvent>,
  /** Dopo una morte lascia nel buffer un temporaneo (la morte nel mezzo di una scrittura del buffer). */
  val orphanTempAfterDeath: Boolean,
  /** L'altro dispositivo lavora fra una vita e l'altra. */
  val otherBetweenLives: Boolean,
  /** Morte del processo alla chiamata n del fetcher del modello (scaricamento dentro il giro). */
  val modelDeaths: Set<Int>,
  /** La copia del modello sul NAS ha byte diversi (file cambiato a meta'). */
  val modelCopyChanged: Boolean,
  /** Dopo il primo modello pronto un byte del file cambia. */
  val modelTamper: Boolean,
) {
  override fun toString(): String =
    "giro $seed (unita' $unitSize, lotto $batch, lettura=$reading, media ${commonMedia}+${privateMedia}+${brunoMedia}+telefono $phoneMedia, " +
      "morti=${deaths.size}, guasti=${nasFaults.size}, eventi=${events.size}, modello: morti=${modelDeaths.size} cambiato=$modelCopyChanged manomesso=$modelTamper)"

  companion object {
    /** Un giro scritto a mano: tutto quieto tranne cio' che si dice (per le riproduzioni minime dei difetti noti). */
    fun quiet(
      seed: Long = 1,
      commonMedia: Int = 0,
      phoneMedia: Int = 0,
      reading: Boolean = false,
      deaths: Map<Int, SavePoint> = emptyMap(),
      nasFaults: Map<Int, NasFault> = emptyMap(),
    ) = AiRoundPlan(
      seed = seed, unitSize = 3, batch = 2, keepAliveEveryNanos = 20L * 60 * 1_000_000_000, reading = reading, commonMedia = commonMedia,
      privateMedia = 0, brunoMedia = 0, phoneMedia = phoneMedia, sharedBetweenAreas = 0, videos = 0, badPreviews = 0, hidden = 0,
      deaths = deaths, nasFaults = nasFaults, events = emptyMap(), orphanTempAfterDeath = false, otherBetweenLives = false,
      modelDeaths = emptySet(), modelCopyChanged = false, modelTamper = false,
    )

    fun generate(seed: Long): AiRoundPlan {
      val r = Random(seed)
      fun <T> picks(count: IntRange, upTo: Int, make: (Int) -> T): Map<Int, T> =
        (0 until r.nextInt(count.first, count.last + 1)).associate { r.nextInt(1, upTo + 1) to make(it) }
      val common = r.nextInt(3, 10)
      return AiRoundPlan(
        seed = seed,
        unitSize = r.nextInt(2, 5),
        batch = r.nextInt(1, 5),
        keepAliveEveryNanos = if (r.nextInt(100) < 45) 1L else 20L * 60 * 1_000_000_000,
        reading = r.nextInt(100) < 60,
        commonMedia = common,
        privateMedia = r.nextInt(0, 6),
        brunoMedia = r.nextInt(0, 4),
        phoneMedia = r.nextInt(0, 4),
        sharedBetweenAreas = r.nextInt(0, 3),
        videos = r.nextInt(0, 3),
        badPreviews = if (r.nextInt(100) < 25) 1 else 0,
        hidden = r.nextInt(0, 3),
        deaths = picks(0..4, 30) { SavePoint.entries[r.nextInt(SavePoint.entries.size)] },
        nasFaults = picks(0..6, 45) { NasFault(FaultKind.entries[r.nextInt(3)], FaultMoment.entries[r.nextInt(2)]) },
        events = picks(0..2, 12) { listOf(CheckEvent.OTHER_TAKES_UNITS, CheckEvent.BLOCK_COMMON, CheckEvent.UNBLOCK_COMMON)[r.nextInt(3)] },
        orphanTempAfterDeath = r.nextInt(100) < 40,
        otherBetweenLives = r.nextInt(100) < 35,
        modelDeaths = picks(0..2, 5) { 0 }.keys,
        modelCopyChanged = r.nextInt(100) < 25,
        modelTamper = r.nextInt(100) < 25,
      )
    }
  }
}

/** I numeri di un giro, per il rapporto. */
class AiRoundStats {
  var lives = 0
  var deaths = 0
  var savesDone = 0
  var embedded = 0
  var nasFaultsFired = 0
  var takeovers = 0
  var blocks = 0
  var published = 0
  var restaged = 0
  var checks = 0
  var hiddenComputed = 0
  var modelLoads = 0
  var modelDeaths = 0
  var searches = 0
  var unitsLost = 0
  var pendingReleased = 0
}

class AiRoundReport(val plan: AiRoundPlan, val violations: List<AiViolation>, val stats: AiRoundStats) {
  /** L'indice del giro nel banco (per rifarlo con `-FirstRound`); -1 se il giro e' stato lanciato da solo. */
  var index: Int = -1

  /** Le violazioni che fanno rosso il banco: quelle senza un difetto noto. */
  val failures: List<AiViolation> get() = violations.filter { it.known == null }

  val knownDefects: Set<KnownDefect> get() = violations.mapNotNull { it.known }.toSet()

  fun describe(): String = (if (index >= 0) "indice $index, " else "") + "$plan\n" + violations.joinToString("\n") { "  - $it" }
}
