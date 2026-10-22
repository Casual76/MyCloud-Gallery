package dev.pampa.mycloudgallery.core.models.chaos

import kotlin.random.Random

// Il BANCO DELLE INTERRUZIONI DELLO SCARICAMENTO (docs/fase6-progetto.md §5.5, P10): gli invarianti del §5.5 che riguardano i modelli,
// controllati dopo ogni morte del processo, a fine passo e in una rilettura finale, su giri casuali ripetibili dal seme. Stesso impianto
// dei banchi delle fasi 4 e 5 (docs/prova-500.md): un mondo, un piano di guasti, un verificatore, un banco che somma i giri.
//
// Gli invarianti 4, 5, 7 e 10 (calcolo, dati, prenotazioni, nascosti) li controlla il banco di core-ai (`core.ai.chaos`): qui i numeri
// sono quelli del §5.5, cosi' i due rapporti si leggono insieme.

/** Gli invarianti del §5.5 che hanno senso per lo scaricamento, col numero del §5.5. */
enum class ModelInvariant(val number: Int, val text: String) {
  VERIFIED_LOAD(1, "nessun modello non verificato caricato"),
  NO_PARTIAL(2, "nessun parziale promosso"),
  TRUE_RESUME(3, "ripresa vera"),
  OUT_OF_PLACE(6, "nessuna voce fuori posto"),
  REACHES_END(8, "il lavoro arriva in fondo"),
  NO_ORPHANS(9, "nessun temporaneo orfano"),
}

/** Dove il banco ha guardato: dopo una morte, a fine passo, a fine giro, nella rilettura finale. */
enum class CheckPoint { AFTER_DEATH, AFTER_STEP, END, REREAD }

class ModelViolation(val invariant: ModelInvariant, val at: CheckPoint, val detail: String) {
  override fun toString(): String = "I${invariant.number} (${invariant.text}) [$at]: $detail"
}

/** Dove sta il NAS finto del giro: in memoria o dietro il simulatore SMB con la sessione e la lettura vere. */
enum class ModelBackend { MEMORY, SMB }

/**
 * Cosa va storto in un giro, deciso dal seme. Gli indici (salvataggi, chiamate, richieste) contano da 1 su tutto il giro, vite del
 * processo comprese. Dopo [ModelChaosRound.MAX_LIVES] vite il banco "rimette a posto la rete" e chiede di arrivare in fondo.
 */
class ModelRoundPlan(
  val seed: Long,
  val backend: ModelBackend,
  val size: Int,
  val nasCopy: Boolean,
  val pcFolder: Boolean,
  /** Il processo muore dopo il blocco salvato numero n (traffico registrato dopo la scrittura sincronizzata). */
  val deathSaves: Set<Int>,
  /** Il processo muore prima / dopo la chiamata n al fetcher. */
  val deathBeforeCalls: Set<Int>,
  val deathAfterCalls: Set<Int>,
  /** Richieste HTTPS che rispondono `200` anche con `Range` (server che ignora l'intervallo). */
  val ignoreRangeAt: Set<Int>,
  /** Richieste HTTPS il cui corpo cade dopo tanti byte (connessione persa a meta'). */
  val cutAt: Map<Int, Long>,
  /** Dalla richiesta n in poi il server ha un altro validatore (`If-Range` non torna: `200` e si riparte). */
  val etagChangeAt: Int?,
  /** Richieste HTTPS che servono un file con un byte cambiato (CDN rovinato). */
  val wrongBytesAt: Set<Int>,
  /** Percentuale di letture del NAS che cadono (prima o dopo). */
  val nasDropPercent: Int,
  /** Alla lettura n del NAS la copia cambia (byte diversi, stessa lunghezza). */
  val nasChangeAtRead: Int?,
  /** Dopo il primo `Ready` un byte di `models/<sha>` cambia (disco o altro processo). */
  val tamper: Boolean,
  val noSpaceFirst: Boolean,
  val gateRefusedFirst: Boolean,
  /** Fallimenti programmati della copia sul NAS (scaffale). */
  val shelfFailsFirst: Boolean,
) {
  /** Quante interruzioni "vere" il piano prevede: ognuna puo' costare al piu' un blocco riscaricato. */
  val interruptions: Int get() = deathSaves.size + deathBeforeCalls.size + deathAfterCalls.size + cutAt.size

  override fun toString(): String =
    "giro $seed ($backend, ${size / 1024} KiB, nas=$nasCopy, pc=$pcFolder, morti=${deathSaves.size + deathBeforeCalls.size + deathAfterCalls.size}, " +
      "tagli=${cutAt.size}, 200=${ignoreRangeAt.size}, etag=$etagChangeAt, errati=${wrongBytesAt.size}, nasPerso=$nasDropPercent%, " +
      "nasCambia=$nasChangeAtRead, manomesso=$tamper)"

  companion object {
    private const val BLOCK = 1 shl 20

    /** Il piano del giro [seed]. [backend] lo sceglie il banco (i giri SMB sono piu' lenti). */
    fun generate(seed: Long, backend: ModelBackend): ModelRoundPlan {
      val r = Random(seed)
      fun some(max: Int, upTo: Int, chance: Int = 60): Set<Int> =
        if (r.nextInt(100) >= chance) emptySet() else (0 until r.nextInt(1, max + 1)).map { r.nextInt(1, upTo + 1) }.toSet()
      // Da due a quattro blocchi da 1 MiB, l'ultimo mai pieno: la ripresa si prova a ogni confine e nel resto.
      val size = BLOCK * r.nextInt(1, 3) + r.nextInt(1, BLOCK - 1)
      return ModelRoundPlan(
        seed = seed,
        backend = backend,
        size = size,
        nasCopy = r.nextInt(100) < 45,
        pcFolder = r.nextInt(100) < 25,
        deathSaves = some(3, 8),
        deathBeforeCalls = some(2, 8, chance = 25),
        deathAfterCalls = some(2, 8, chance = 25),
        ignoreRangeAt = some(2, 6, chance = 25),
        cutAt = if (r.nextInt(100) < 45) (0 until r.nextInt(1, 4)).associate { r.nextInt(1, 7) to r.nextLong(1, size.toLong()) } else emptyMap(),
        etagChangeAt = if (r.nextInt(100) < 15) r.nextInt(2, 6) else null,
        wrongBytesAt = some(1, 3, chance = 15),
        nasDropPercent = if (r.nextInt(100) < 40) r.nextInt(5, 35) else 0,
        nasChangeAtRead = if (r.nextInt(100) < 20) r.nextInt(1, 5) else null,
        tamper = r.nextInt(100) < 25,
        noSpaceFirst = r.nextInt(100) < 8,
        gateRefusedFirst = r.nextInt(100) < 8,
        shelfFailsFirst = r.nextInt(100) < 30,
      )
    }
  }
}

/** I numeri di un giro, per il rapporto. */
class ModelRoundStats {
  var lives = 0
  var deaths = 0
  var steps = 0
  var loads = 0
  var rangedRequests = 0
  var serverRestarts = 0
  var cuts = 0
  var mismatches = 0
  var savedBlocks = 0
  var recordedBytes = 0L
  var shelfPublications = 0
  var tampered = 0
  var checks = 0
  var nasOpens = 0
  var nasReads = 0
  var nasFaultsFired = 0
}

class ModelRoundReport(val plan: ModelRoundPlan, val violations: List<ModelViolation>, val stats: ModelRoundStats) {
  /** L'indice del giro nel banco (per rifarlo con `-FirstRound`); -1 se il giro e' stato lanciato da solo. */
  var index: Int = -1

  fun describe(): String = (if (index >= 0) "indice $index, " else "") + "$plan\n" + violations.joinToString("\n") { "  - $it" }
}
