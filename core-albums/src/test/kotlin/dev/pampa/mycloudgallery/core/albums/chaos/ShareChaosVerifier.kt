package dev.pampa.mycloudgallery.core.albums.chaos

import dev.pampa.mycloudgallery.core.albums.contract.MediaPlace
import dev.pampa.mycloudgallery.core.albums.contract.ShareFailure
import dev.pampa.mycloudgallery.core.albums.contract.ShareItemAction
import dev.pampa.mycloudgallery.core.albums.contract.ShareItemRecord
import dev.pampa.mycloudgallery.core.albums.contract.ShareItemState
import dev.pampa.mycloudgallery.core.albums.share.ShareBench
import dev.pampa.mycloudgallery.core.albums.share.ShareNasTarget
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.nasops.contract.OpErrorCode
import dev.pampa.mycloudgallery.core.nasops.contract.OpState
import dev.pampa.mycloudgallery.core.nasops.contract.OpTemps
import java.security.MessageDigest
import java.util.Locale

// IL VERIFICATORE. Gli invarianti del §5.5 stanno in `ShareBench.violations` (P4: file a meta', doppioni, perdite, byte giusti, elementi mancanti
// in app, voci senza file, conferme, lavoro arrivato in fondo, nascosti); qui si aggiunge cio' che serve a un banco con le morti: i temporanei
// solo dove devono stare e citati da un diario, il catalogo che dice la stessa cosa del diario in ogni istante, il legame fra le operazioni di
// core-nasops e le conferme anche a meta', gli errori permanenti solo per un motivo vero, e cio' che gli osservatori hanno visto accadere.
// Il verificatore non conosce il codice sotto prova: guarda il NAS, i diari e il catalogo.

class ShareChaosVerifier(
  private val bench: ShareBench,
  private val observer: ShareChaosObserver,
  private val stats: ShareChaosStats,
  /** Un colpo "spazio finito" e' scattato: un trasferimento che il motore ferma con `NO_SPACE` a meta' copia e' un errore con il suo motivo (E-SH8). */
  private val spaceFault: () -> Boolean = { false },
) {
  /** I media che il PC ha cambiato prima della copia (E-SH18): possono finire `SOURCE_CHANGED`. */
  val modifiedByPc: MutableSet<MediaId> = HashSet()

  /** I nascosti lasciati fuori da una condivisione (R11, invariante 9): non si muovono mai. */
  val hiddenLeftOut: MutableSet<MediaId> = HashSet()

  /** I nascosti condivisi con la scelta E-SH9: non fanno piu' parte dei "lasciati fuori". */
  val hiddenShared: MutableSet<MediaId> = HashSet()

  /** Quante morti del processo ci sono state: `NOT_STARTED` (la conferma si e' persa con il processo) vale solo dopo almeno una. */
  var processDeaths: Int = 0

  /**
   * Il giro ha avuto almeno un guasto (un colpo scattato o un guasto dell'ambiente). Un trasferimento rifiutato prima di partire (spazio, scritture
   * ferme, rete) si riprova finche' la conferma ha meno di 10 minuti (P4, decisione 9, L73): dopo, `NOT_STARTED` e "Riprova" fa un foglio nuovo.
   * Senza nessun guasto `NOT_STARTED` non ha una causa.
   */
  var disturbed: Boolean = false

  private val seen = HashSet<String>()

  /**
   * Tutti gli invarianti, adesso. [final]: a operazioni finite (il lavoro e' arrivato in fondo, nessun temporaneo, ogni operazione ha la sua
   * conferma); altrimenti a meta' (quelli che valgono sempre). Ogni violazione si riporta una volta sola per giro.
   */
  suspend fun check(phase: String, final: Boolean): List<Violation> {
    if (final) stats.quiescentChecks++ else stats.midChecks++
    val found = ArrayList<Violation>()
    val left = hiddenLeftOut - hiddenShared
    val stopped = stoppedTemps()
    bench.violations(final = final, hiddenLeftOut = left, transientOnly = false)
      .filterNot { line -> line.startsWith("1: temporaneo rimasto") && stopped.any { line.lowercase(Locale.ROOT).endsWith("/$it)") } }
      .forEach { found += Violation.fromBench(it, phase) }
    found += appArea(phase, final, stopped)
    found += catalogAgainstDiary(phase)
    found += confirmations(phase)
    if (final) found += permanentFailures(phase)
    found += observer.violations.map { it.copy(phase = it.phase.ifEmpty { phase }) }
    observer.violations.clear()
    return found.filter { seen.add("${it.invariant}|${it.text}") }
  }

  // --- I temporanei e la cartella dell'app ----------------------------------------------------------------------------------------

  /**
   * Invariante 1: fuori da `.mycloudgallery/` solo file interi (lo dice `violations`); dentro, solo `tmp/` (temporanei propri, citati da un elemento
   * non finito del diario di core-nasops) e `trash/` (il cestino proprio). Un temporaneo che nessuno cita e' un file a meta' che nessuno finira'.
   */
  private suspend fun appArea(phase: String, final: Boolean, stopped: Set<String>): List<Violation> {
    val out = ArrayList<Violation>()
    val cited = HashSet<String>()
    stopped.forEach { cited += "$APP_DIR/tmp/$it" }
    val store = bench.target.store
    for (op in store.ops(OpState.entries.toSet())) {
      for (item in store.items(op.id)) {
        if (!item.state.terminal) cited += OpTemps.tempFor(ShareNasTarget.DEVICE, op.id, item.index).value.lowercase(Locale.ROOT)
      }
    }
    for (share in listOf(ShareNasTarget.PRIVATE, ShareNasTarget.COMMON)) {
      for (path in bench.target.files(share)) {
        val lower = path.lowercase(Locale.ROOT)
        if (!lower.startsWith(APP_DIR)) continue
        when {
          lower.startsWith("$APP_DIR/tmp/") -> if (lower !in cited) out += Violation(ShareInvariant.NO_HALF_FILE, phase, "temporaneo che nessun elemento del diario cita ($share)")
          lower.startsWith("$APP_DIR/trash/") -> Unit
          else -> out += Violation(ShareInvariant.NO_HALF_FILE, phase, "file dell'app fuori posto ($share)")
        }
      }
    }
    return out
  }

  /**
   * I nomi dei temporanei di un elemento che il motore ha fermato con `NO_SPACE` a meta' (il NAS e' pieno): il motore prova a toglierli, ma se la
   * chiamata che li toglie, o quella prima, fallisce per lo stesso motivo restano nell'area dell'app (non visibili), e li toglie la pulizia dei propri
   * file (D33). Sono un file "a meta'" che nessuno finira': ma non lo vede nessuno, non e' un media e non e' un doppione. Il banco li dichiara.
   */
  private suspend fun stoppedTemps(): Set<String> {
    val out = HashSet<String>()
    val store = bench.target.store
    for (op in store.ops(OpState.entries.toSet())) {
      for (item in store.items(op.id)) {
        if (item.state.terminal && item.errorCode in SPACE_ERRORS) out += OpTemps.tempFor(ShareNasTarget.DEVICE, op.id, item.index).value.substringAfterLast('/').lowercase(Locale.ROOT)
      }
    }
    return out
  }

  // --- Il catalogo e il diario dicono la stessa cosa -----------------------------------------------------------------------------------

  /**
   * Invariante 5, ogni istante: un elemento arrivato nell'area comune (`IN_COMMON` e oltre) ha il suo file li' per il catalogo; un "Riporta"
   * arrivato (`IN_PRIVATE` e oltre) ha il file nell'area privata. Il catalogo non resta indietro rispetto al diario.
   */
  private fun catalogAgainstDiary(phase: String): List<Violation> {
    val out = ArrayList<Violation>()
    val arrived = setOf(ShareItemState.IN_COMMON, ShareItemState.ENTRY_STAGED, ShareItemState.PUBLISHED, ShareItemState.DONE)
    // Un media "riportato" da un'operazione successiva non e' piu' nell'area comune: l'elemento `DONE` della condivisione di prima non lo dice piu'.
    // Anche un "Riporta" fermo a meta' (il file e' tornato e il catalogo lo sa, ma la sorgente non e' andata nel cestino: spazio finito) lascia il catalogo di la'.
    val brokenOut = bench.items().filter { it.action == ShareItemAction.MOVE_OUT }.map { it.media }.toSet()
    for (item in bench.items()) {
      if (item.media in brokenOut && item.action != ShareItemAction.MOVE_OUT) continue
      val m = bench.library[item.media] ?: continue
      val key = item.contentKey
      val attested = key?.let { bench.library.attestedNow(listOf(it))[it] }
      when (item.action) {
        ShareItemAction.MOVE_IN, ShareItemAction.UPLOAD, ShareItemAction.REFERENCE, ShareItemAction.ADOPT ->
          if (item.state in arrived && m.place != MediaPlace.COMMON && attested == null) {
            out += Violation(ShareInvariant.NOTHING_MISSING, phase, "elemento ${item.state.code} ma il catalogo non lo vede nell'area comune (${item.action.code}, media ${item.media.value.takeLast(4)} ora in ${m.place.code} ${m.path})")
          }
        ShareItemAction.MOVE_OUT ->
          if (item.state in setOf(ShareItemState.IN_PRIVATE, ShareItemState.DONE) && m.place != MediaPlace.MY_PRIVATE) {
            out += Violation(ShareInvariant.NOTHING_MISSING, phase, "\"Riporta\" ${item.state.code} ma il catalogo non lo vede nell'area privata")
          }
      }
    }
    return out
  }

  // --- Le conferme anche a meta' ---------------------------------------------------------------------------------------------------

  /**
   * Invariante 7, ogni istante: ogni operazione nel diario di core-nasops nasce da un trasferimento della condivisione (la sua chiave, scritta
   * prima di avviarla, porta la sua impronta) e ogni chiave rimanda a un'operazione nel diario della condivisione, nata da una conferma.
   */
  private suspend fun confirmations(phase: String): List<Violation> {
    val out = ArrayList<Violation>()
    val planned = bench.mover.keys.values.toSet()
    for (op in bench.target.store.ops(OpState.entries.toSet())) {
      if (op.planHash !in planned) out += Violation(ShareInvariant.NO_ACTION_WITHOUT_CONFIRMATION, phase, "operazione di core-nasops senza una conferma della condivisione")
    }
    val shareOps = bench.diary.allOps().map { it.id.value }.toSet()
    for (key in bench.mover.keys.keys) {
      if (key.value.substringBefore(':') !in shareOps) out += Violation(ShareInvariant.NO_ACTION_WITHOUT_CONFIRMATION, phase, "chiave di un trasferimento senza operazione nel diario")
    }
    return out
  }

  // --- Il lavoro arriva in fondo, senza errori permanenti inventati -------------------------------------------------------------------

  /** Invariante 8: un elemento `FAILED` ha un motivo vero: il PC ha cambiato il file, o la conferma si e' persa con una morte prima della partenza. */
  private suspend fun permanentFailures(phase: String): List<Violation> {
    val out = ArrayList<Violation>()
    for (item in bench.items().filter { it.state == ShareItemState.FAILED }) {
      val ok = when (item.failure) {
        ShareFailure.NOT_STARTED -> processDeaths > 0 || disturbed
        ShareFailure.SOURCE_CHANGED, ShareFailure.SOURCE_GONE -> item.media in modifiedByPc
        // Lo spazio finito a meta' copia non si risolve da solo (come "Riprova" di un piano nuovo): il motore ferma l'elemento con il suo motivo.
        ShareFailure.TRANSFER_FAILED -> spaceFault() && engineErrorOf(item) in SPACE_ERRORS
        else -> false
      }
      if (!ok) out += Violation(ShareInvariant.REACHES_THE_END, phase, "errore permanente per un guasto passeggero (#${item.index}, ${item.action.code}, ${item.failure?.code}, media ${item.media.value.takeLast(4)}, motore ${engineErrorOf(item)?.name})")
      // `NOT_STARTED` vuol dire che niente si e' mosso: il media e' dove era (a meno che un altro elemento non lo abbia portato altrove).
      if (item.failure == ShareFailure.NOT_STARTED && bench.items().none { it.media == item.media && it.state != ShareItemState.FAILED && it.state != ShareItemState.CANCELLED }) {
        val origin = when (item.action) {
          ShareItemAction.MOVE_IN -> MediaPlace.MY_PRIVATE
          ShareItemAction.UPLOAD -> MediaPlace.PHONE_ONLY
          ShareItemAction.MOVE_OUT -> MediaPlace.COMMON
          else -> null
        }
        if (origin != null && bench.library[item.media]?.place != origin) {
          out += Violation(ShareInvariant.NO_LOSS, phase, "trasferimento mai partito ma il media ha cambiato posto (#${item.index}, ${item.action.code}, media ${item.media.value.takeLast(4)} ora in ${bench.library[item.media]?.place?.code} ${bench.library[item.media]?.path})")
        }
      }
    }
    return out
  }

  /** Il codice con cui il motore di core-nasops ha fermato il file di [item], se c'e' (si ritrova dal percorso della sorgente). */
  private suspend fun engineErrorOf(item: ShareItemRecord): OpErrorCode? {
    val source = item.source ?: return null
    val store = bench.target.store
    for (op in store.ops(OpState.entries.toSet())) {
      store.items(op.id).firstOrNull { it.srcShare == source.share && it.srcPath == source.path }?.let { return it.errorCode }
    }
    return null
  }

  // --- La rilettura finale con l'hash di ogni file ----------------------------------------------------------------------------------

  /** Ogni file di entrambe le share (anche quelli dell'app) rilegge i suoi byte e ne calcola lo SHA-256: nessuna cache. Restituisce le impronte. */
  fun reread(): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    for (share in listOf(ShareNasTarget.PRIVATE, ShareNasTarget.COMMON)) {
      for (path in bench.target.files(share).sorted()) {
        val bytes = bench.target.bytes(share, path) ?: continue
        out["$share:$path"] = sha256(bytes)
        stats.filesHashed++
        stats.bytesHashed += bytes.size
      }
    }
    return out
  }

  /** Cio' che deve restare uguale dopo una morte senza colpo e una ripresa a vuoto: i file con le impronte, il diario, il catalogo. */
  suspend fun fingerprint(): String = buildString {
    reread().forEach { (k, v) -> append(k).append('=').append(v).append(';') }
    append('|')
    for (item in bench.items().sortedWith(compareBy({ it.op.value }, { it.index }))) append(item.index).append(':').append(item.state.code).append(':').append(item.failure?.code).append(';')
    append('|')
    for (m in bench.library.all().sortedBy { it.id.value }) append(m.id.value.takeLast(6)).append(':').append(m.place.code).append(':').append(m.path).append(';')
    append('|').append(bench.target.store.ops(OpState.entries.toSet()).joinToString(";") { it.state.code })
  }

  private companion object {
    const val APP_DIR = ".mycloudgallery"

    /** Un `DISK_FULL` il motore lo chiama `NO_SPACE`, o `QUOTA` se l'account ha una quota (lo decide lo spazio che il NAS dice). */
    val SPACE_ERRORS = setOf(OpErrorCode.NO_SPACE, OpErrorCode.QUOTA)

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
  }
}
