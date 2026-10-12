package dev.pampa.mycloudgallery.core.trust.registry

import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.envelope.DocType
import dev.pampa.mycloudgallery.core.trust.envelope.Envelope
import dev.pampa.mycloudgallery.core.trust.nas.NasFileSystem
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.NasRead
import dev.pampa.mycloudgallery.core.trust.nas.PublishResult

/** Quanti file sono stati scartati e perche': per la Diagnostica (piano 2.7). */
class RejectionTally {
  private val counts = LinkedHashMap<RejectReason, Int>()

  val total: Int get() = counts.values.sum()

  fun add(reason: RejectReason) {
    counts[reason] = (counts[reason] ?: 0) + 1
  }

  fun count(reason: RejectReason): Int = counts[reason] ?: 0

  fun asMap(): Map<RejectReason, Int> = counts.toMap()
}

/**
 * Lettura del registro dall'area comune (`docs/formato-nas.md` §4.4). Le versioni nuove si leggono **per
 * nome** (`v+1`, `v+2`, … fino al primo nome assente), non dall'elenco della cartella: creare migliaia di
 * file non nasconde una revoca (revisione 1.4, M1). L'elenco serve solo come indizio per le versioni oltre
 * un buco (una versione cancellata).
 *
 * Lo stato locale non e' mai sostituito da qualcosa di piu' vecchio, non verificato o che "torna
 * indietro"; nel caso peggiore resta lo stato che si aveva e la Diagnostica lo dice. Se il file della
 * versione fidata e' sparito dal NAS, lo si ripubblica ([Result.healed]): una cancellazione non basta a
 * far sparire una revoca per gli altri.
 */
class RegistrySync(private val commonShare: NasFileSystem) {

  class Result(
    /** Lo stato da usare (e da salvare se [changed]). Null solo per un dispositivo nuovo che non ha trovato nulla. */
    val state: RegistryState?,
    val changed: Boolean,
    val rejected: RejectionTally,
    /** L'elenco della cartella era oltre il tetto: le versioni dopo un buco potrebbero non essere visibili. */
    val listingTruncated: Boolean,
    /**
     * Rami firmati dalla famiglia ma incompatibili con lo stato finale: allarme, da mostrare all'admin. I rami
     * che lo stato finale contiene gia' (uniti in seguito) sono storia e non compaiono (revisione 1.4, N5).
     */
    val forks: List<RegistryFork>,
    /** Il file della versione fidata mancava ed e' stato ripubblicato da qui. */
    val healed: Boolean,
    /**
     * Al posto della versione fidata c'e' un file non valido (revisione 1.4, verifica N3): qualcuno l'ha
     * sostituita per nasconderla a chi non l'ha ancora vista. Allarme; sul dispositivo admin si ripubblica
     * subito lo stesso contenuto con un numero nuovo (`FamilyAuthority.reissue`).
     */
    val trustedVersionReplaced: Boolean,
    /** Si e' smesso di leggere per il tetto di letture o per troppa spazzatura di fila: si continua la prossima volta. */
    val incomplete: Boolean,
  ) {
    /** Ci sono versioni piu' nuove che non si sono potute verificare (manomissione, reset, app vecchia). */
    val newerUnverified: Boolean get() = rejected.total > 0

    /**
     * Sul NAS c'e' un registro scritto da un'app piu' nuova: si chiede di aggiornare e, se questo e' un
     * dispositivo admin, non si firmano altre versioni finche' non si aggiorna (E-I3).
     */
    val needsAppUpdate: Boolean get() = rejected.count(RejectReason.NEWER_FORMAT) > 0

    /**
     * Rami attuali (equivocazione o regressione firmate dalla famiglia: un admin su una base vecchia, o la
     * chiave usata da altri) oppure versione fidata sostituita.
     */
    val tamperingSuspected: Boolean get() = forks.isNotEmpty() || trustedVersionReplaced
  }

  fun refresh(current: RegistryState?, anchor: TrustAnchor): Result {
    val tally = RejectionTally()
    val forks = ArrayList<RegistryFork>()
    var state = current
    var reads = 0
    var junkRun = 0
    var incomplete = false
    val tried = HashSet<Long>()

    // Vero se il file conta come spazzatura (allunga la serie di file non validi di fila). Le versioni di
    // un ramo firmato dalla famiglia non sono spazzatura: un ramo lungo non ferma la lettura (N5).
    fun considerIsJunk(version: Long, bytes: ByteArray?, tooLarge: Boolean): Boolean {
      tried.add(version)
      if (bytes == null) {
        if (tooLarge) tally.add(RejectReason.TOO_LARGE)
        return true
      }
      return when (val verdict = acceptAt(version, bytes, state, anchor)) {
        is Verdict.Accepted -> {
          state = verdict.value
          false
        }
        is Verdict.Rejected -> {
          tally.add(verdict.reason)
          !noteFork(verdict.reason, bytes, state, forks)
        }
      }
    }

    // 1. Lettura per nome dalla versione successiva a quella nota, fino al primo nome assente.
    var version = maxOf(1L, (current?.registry?.version ?: (anchor.minRegistryVersion - 1)) + 1)
    while (version <= TrustLimits.MAX_VERSION) {
      if (reads >= TrustLimits.MAX_SERIES_READS || junkRun > TrustLimits.MAX_JUNK_RUN) {
        incomplete = true
        break
      }
      reads++
      val read = commonShare.read(NasLayout.registryFile(version), Envelope.maxFileBytes(DocType.FAMILY_REGISTRY))
      if (read is NasRead.NotFound) break
      junkRun = if (considerIsJunk(version, (read as? NasRead.Data)?.bytes, read is NasRead.TooLarge)) junkRun + 1 else 0
      version++
    }

    // 2. Indizio dall'elenco: versioni oltre un buco (qualcuno ha cancellato una versione intermedia).
    val listing = commonShare.list(NasLayout.REGISTRY_DIR, TrustLimits.MAX_LIST_REGISTRY)
    if (!incomplete) {
      val beyond = listing.names.mapNotNull(NasLayout::parseVersionName)
        .filter { it > (state?.registry?.version ?: 0L) && it !in tried && it >= anchor.minRegistryVersion }
        .sorted()
      for (hint in beyond) {
        if (reads >= TrustLimits.MAX_SERIES_READS || junkRun > TrustLimits.MAX_JUNK_RUN) {
          incomplete = true
          break
        }
        reads++
        val read = commonShare.read(NasLayout.registryFile(hint), Envelope.maxFileBytes(DocType.FAMILY_REGISTRY))
        if (read is NasRead.NotFound) continue
        junkRun = if (considerIsJunk(hint, (read as? NasRead.Data)?.bytes, read is NasRead.TooLarge)) junkRun + 1 else 0
      }
    }

    // 3. La versione fidata e' ancora al suo posto? Se e' sparita la si ripubblica; se al suo posto c'e'
    //    un altro contenuto firmato dalla famiglia, e' un'equivocazione da segnalare.
    //    Un file non valido al suo posto e' una sostituzione: allarme (N3).
    var healed = false
    var replaced = false
    val trusted = state
    if (trusted != null) {
      val path = NasLayout.registryFile(trusted.registry.version)
      when (val read = commonShare.read(path, Envelope.maxFileBytes(DocType.FAMILY_REGISTRY))) {
        NasRead.NotFound -> healed = commonShare.publishExclusive(path, trusted.signedBytes) == PublishResult.CREATED
        is NasRead.Data -> {
          val verdict = RegistryVerifier.accept(read.bytes, trusted, anchor)
          if (verdict is Verdict.Rejected) {
            tally.add(verdict.reason)
            if (!noteFork(verdict.reason, read.bytes, trusted, forks)) replaced = true
          }
        }
        NasRead.TooLarge -> {
          tally.add(RejectReason.TOO_LARGE)
          replaced = true
        }
      }
    }

    // I rami che lo stato finale contiene gia' sono storia (uniti piu' avanti): niente allarme (N5).
    val finalRegistry = trusted?.registry
    val liveForks = if (finalRegistry == null) forks else forks.filterNot { finalRegistry.dominates(it.registry) }
    return Result(state, changed = state !== current, tally, listing.truncated, liveForks, healed, replaced, incomplete)
  }

  private fun acceptAt(version: Long, bytes: ByteArray, state: RegistryState?, anchor: TrustAnchor): Verdict<RegistryState> {
    val verdict = RegistryVerifier.accept(bytes, state, anchor)
    if (verdict is Verdict.Accepted && verdict.value.registry.version != version) {
      return Verdict.Rejected(RejectReason.WRONG_LOCATION)
    }
    return verdict
  }

  /** Vero se il file e' un ramo firmato dalla famiglia (e lo annota). */
  private fun noteFork(reason: RejectReason, bytes: ByteArray, state: RegistryState?, forks: MutableList<RegistryFork>): Boolean {
    if (state == null || (reason != RejectReason.EQUIVOCATION && reason != RejectReason.REGRESSION)) return false
    val fork = RegistryVerifier.readFork(bytes, state) as? Verdict.Accepted ?: return false
    if (forks.none { it.contentHash == fork.value.contentHash }) forks.add(fork.value)
    return true
  }
}

/** Le richieste di registrazione in attesa, per la schermata dell'admin (piano 2.3). */
class PendingRequests(
  val requests: List<RegistrationRequest>,
  val rejected: RejectionTally,
  /** Troppi nomi nella cartella: una richiesta vera potrebbe non comparire; si legge per id con [readOne]. */
  val listingTruncated: Boolean,
  /** Gli id delle richieste il cui nome somiglia a un'altra richiesta o a un dispositivo registrato (sosia). */
  val lookAlikes: Set<String>,
) {
  companion object {
    fun read(commonShare: NasFileSystem, state: RegistryState): PendingRequests {
      val tally = RejectionTally()
      val listing = commonShare.list(NasLayout.REQUESTS_DIR, TrustLimits.MAX_LIST_REQUESTS)
      val found = ArrayList<RegistrationRequest>()
      for (name in listing.names.sorted()) {
        if (!TrustIds.isValid(name)) {
          tally.add(RejectReason.WRONG_LOCATION)
          continue
        }
        if (state.registry.device(name) != null) continue // gia' nel registro
        when (val verdict = readVerdict(commonShare, state, name)) {
          is Verdict.Accepted -> found.add(verdict.value)
          is Verdict.Rejected -> tally.add(verdict.reason)
          null -> Unit // sparita tra l'elenco e la lettura
        }
      }
      return PendingRequests(found, tally, listing.truncated, lookAlikes(found, state))
    }

    /**
     * Legge direttamente la richiesta di [deviceId] (l'id mostrato dal dispositivo nuovo): funziona anche se
     * la cartella e' stata riempita di nomi per nasconderla. Null se non c'e'.
     */
    fun readOne(commonShare: NasFileSystem, state: RegistryState, deviceId: String): Verdict<RegistrationRequest>? {
      if (!TrustIds.isValid(deviceId)) return Verdict.Rejected(RejectReason.WRONG_LOCATION)
      return readVerdict(commonShare, state, deviceId)
    }

    /** Null se il file non c'e'. */
    private fun readVerdict(commonShare: NasFileSystem, state: RegistryState, name: String): Verdict<RegistrationRequest>? =
      when (val read = commonShare.read(NasLayout.requestFile(name), Envelope.maxFileBytes(DocType.REGISTRATION_REQUEST))) {
        is NasRead.Data -> RegistrationRequest.verify(read.bytes, state.registry.familyId, name)
        NasRead.NotFound -> null
        NasRead.TooLarge -> Verdict.Rejected(RejectReason.TOO_LARGE)
      }

    private fun lookAlikes(requests: List<RegistrationRequest>, state: RegistryState): Set<String> {
      val registered = state.registry.devices.map { LookAlike.key(it.name) }.toSet()
      val byKey = requests.groupBy { LookAlike.key(it.name) }
      return requests.filter { r ->
        val key = LookAlike.key(r.name)
        key in registered || (byKey[key]?.size ?: 0) > 1
      }.map { it.deviceId }.toSet()
    }
  }
}
