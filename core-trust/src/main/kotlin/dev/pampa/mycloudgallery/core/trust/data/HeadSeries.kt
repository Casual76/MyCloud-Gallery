package dev.pampa.mycloudgallery.core.trust.data

import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.crypto.Signer
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.envelope.DocType
import dev.pampa.mycloudgallery.core.trust.envelope.Envelope
import dev.pampa.mycloudgallery.core.trust.nas.NasFileSystem
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.NasRead
import dev.pampa.mycloudgallery.core.trust.nas.PublishResult
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import dev.pampa.mycloudgallery.core.trust.registry.RegistryState
import dev.pampa.mycloudgallery.core.trust.registry.RejectionTally

// Le SERIE DI TESTE di un dispositivo in una share (docs/formato-nas.md §2.3, §5.5 con M3; docs/fase4-progetto.md §2.1,
// §2.2). Stesse regole del registro: chi pubblica sceglie il primo nome libero e scavalca la spazzatura; chi legge va
// PER NOME, mai dall'elenco della cartella (revisione 1.4, M1: inondare la cartella non nasconde una testa). L'elenco e'
// solo un indizio per scavalcare un buco (un nome cancellato), e lo si chiede solo quando chi legge lo vuole.

/**
 * Il budget di letture per nome delle teste di UNA share in un giro ([TrustLimits.MAX_HEAD_READS_PER_SHARE], M9):
 * condiviso fra tutti i dispositivi della share, cosi' una share piena di spazzatura costa al massimo questo.
 */
class HeadReadBudget(limit: Int = TrustLimits.MAX_HEAD_READS_PER_SHARE) {
  var remaining: Int = limit
    private set

  val exhausted: Boolean get() = remaining <= 0

  internal fun take(): Boolean {
    if (remaining <= 0) return false
    remaining--
    return true
  }
}

/** Esito della lettura della testa piu' recente di un dispositivo. */
sealed interface HeadRead {
  /** Nessuna testa valida piu' nuova di quella nota: di solito UNA lettura che risponde "non trovato". */
  data object NoChange : HeadRead

  /** Il dispositivo non ha (ancora) una testa valida in questa share. */
  data object None : HeadRead

  class Accepted(val head: AcceptedHead) : HeadRead {
    override fun toString(): String = "HeadRead.Accepted(v${head.head.version})"
  }

  /**
   * Nomi occupati sopra quella nota ma nessuna testa valida: [reason] e' il motivo del nome piu' alto (firma,
   * revocato, posto, formato piu' nuovo). [RejectReason.ROLLBACK]: la testa gia' accettata e' sparita (E-I5).
   */
  data class Rejected(val reason: RejectReason) : HeadRead

  /** Il budget della share e' finito prima di decidere: si riprova al giro dopo. */
  data object OutOfBudget : HeadRead
}

/** Una lettura: l'esito, i rifiuti incontrati (spazzatura scavalcata compresa) e le richieste fatte. */
class HeadScan(val result: HeadRead, val rejected: RejectionTally, val reads: Int)

/** Esito della pubblicazione di una testa propria. */
sealed interface HeadPublish {
  /** Scritta in [version]; [skipped] sono i nomi occupati da file non validi scavalcati (per la Diagnostica). */
  class Published(val version: Long, val mark: HeadMark, val skipped: List<SharePath>) : HeadPublish

  /**
   * Al nome scelto c'e' gia' una testa **valida e mia** (un crash dopo la pubblicazione, prima che lo stato locale lo
   * sapesse): decide chi chiama (la adotta se elenca gli stessi segmenti, altrimenti pubblica dopo di lei).
   */
  class ExistingOwn(val version: Long, val head: AcceptedHead) : HeadPublish

  /** Oltre [TrustLimits.MAX_JUNK_RUN] nomi non validi di fila: l'elenco esatto dei file da togliere dal PC. */
  class Blocked(val junk: List<SharePath>) : HeadPublish

  /** Numeri esauriti ([TrustLimits.MAX_VERSION]). */
  data object NoRoom : HeadPublish
}

/** I dispositivi con una cartella in `devices/` che il registro accettato riconosce (approvati o dismessi). */
class IndexDevices(
  val ids: List<String>,
  /** L'elenco era oltre il tetto (inondazione): si sono presi tutti i dispositivi del registro. */
  val listingTruncated: Boolean,
  /** Nomi ignorati: non id, sconosciuti, in attesa, revocati o persi (E-I15, E-S14). */
  val ignored: Int,
)

object HeadSeries {

  /**
   * La testa piu' recente e valida di [deviceId] (tipo [kind]) dopo [previous], nella share [fs].
   *
   * - Si legge per nome la versione successiva a quella nota: assente → [HeadRead.NoChange] con **una** richiesta
   *   (un'esistenza: `read` con tetto 0, nessun byte trasferito).
   * - Presente → salti doppi e bisezione fino all'ultimo nome occupato, poi indietro di al massimo
   *   [TrustLimits.MAX_JUNK_RUN] nomi se in cima c'e' spazzatura; mai sotto [previous].
   * - Con [listingHint] (di default alla prima lettura) l'elenco della cartella (al massimo [TrustLimits.MAX_LIST_HEADS]
   *   nomi) indica versioni oltre un buco, e una testa gia' accettata sparita diventa [RejectReason.ROLLBACK].
   * - Ogni richiesta consuma [budget] (condiviso dalla share).
   */
  fun readLatest(
    fs: NasFileSystem,
    registry: RegistryState,
    deviceId: String,
    kind: HeadKind,
    previous: HeadMark?,
    readerAccount: String,
    budget: HeadReadBudget = HeadReadBudget(),
    listingHint: Boolean = previous == null,
  ): HeadScan {
    val tally = RejectionTally()
    var reads = 0
    if (!TrustIds.isValid(deviceId)) return HeadScan(HeadRead.Rejected(RejectReason.INVALID_FIELD), tally, 0)
    val base = previous?.version ?: 0L
    val nothing: HeadRead = if (previous == null) HeadRead.None else HeadRead.NoChange
    var outOfBudget = false

    fun probe(version: Long): Boolean {
      if (version < 1 || version > TrustLimits.MAX_VERSION) return false
      if (!budget.take()) {
        outOfBudget = true
        return false
      }
      reads++
      return fs.read(NasLayout.headFile(deviceId, kind.wire, version), 0) !is NasRead.NotFound
    }

    var low: Long
    if (probe(base + 1)) {
      low = base + 1
    } else {
      if (outOfBudget) return HeadScan(HeadRead.OutOfBudget, tally, reads)
      if (!listingHint) return HeadScan(nothing, tally, reads)
      if (!budget.take()) return HeadScan(HeadRead.OutOfBudget, tally, reads)
      reads++
      val listing = fs.list(NasLayout.headDir(deviceId, kind.wire), TrustLimits.MAX_LIST_HEADS)
      val versions = listing.names.mapNotNull(NasLayout::parseVersionName)
      if (previous != null && !listing.truncated && versions.none { it >= previous.version } && !probe(previous.version)) {
        if (outOfBudget) return HeadScan(HeadRead.OutOfBudget, tally, reads)
        return HeadScan(HeadRead.Rejected(RejectReason.ROLLBACK), tally, reads)
      }
      val hinted = versions.filter { it > base }.maxOrNull() ?: return HeadScan(nothing, tally, reads)
      if (!probe(hinted)) return HeadScan(if (outOfBudget) HeadRead.OutOfBudget else nothing, tally, reads)
      low = hinted
    }

    // Salti doppi: `low` occupato, `high` il primo candidato libero.
    var step = 1L
    var high = low + 1
    while (!outOfBudget && high <= TrustLimits.MAX_VERSION && probe(high)) {
      low = high
      step *= 2
      high = minOf(low + step, TrustLimits.MAX_VERSION + 1)
    }
    while (!outOfBudget && high - low > 1) {
      val mid = low + (high - low) / 2
      if (probe(mid)) low = mid else high = mid
    }

    // Dall'ultimo nome occupato in giu': la prima testa valida vince; la spazzatura si conta e si scavalca.
    val maxBytes = Envelope.maxFileBytes(DocType.DEVICE_HEAD)
    var version = low
    var tries = 0
    var topReason: RejectReason? = null
    while (version > base && tries <= TrustLimits.MAX_JUNK_RUN) {
      tries++
      if (!budget.take()) return HeadScan(HeadRead.OutOfBudget, tally, reads)
      reads++
      val reason: RejectReason? = when (val read = fs.read(NasLayout.headFile(deviceId, kind.wire, version), maxBytes)) {
        NasRead.NotFound -> null
        NasRead.TooLarge -> RejectReason.TOO_LARGE
        is NasRead.Data -> {
          val location = HeadLocation(fs.shareName, deviceId, kind, version)
          when (val verdict = HeadVerifier.accept(read.bytes, registry, location, previous, readerAccount)) {
            is Verdict.Accepted -> return HeadScan(HeadRead.Accepted(verdict.value), tally, reads)
            is Verdict.Rejected -> verdict.reason
          }
        }
      }
      if (reason != null) {
        tally.add(reason)
        if (topReason == null) topReason = reason
      }
      version--
    }
    return HeadScan(topReason?.let { HeadRead.Rejected(it) } ?: nothing, tally, reads)
  }

  /**
   * Pubblica una testa propria al primo nome libero dopo [after] (l'ultima versione propria che lo stato locale conosce).
   * [sign] riceve il numero scelto e restituisce i byte firmati (la versione e' nel contenuto). Un nome occupato da un
   * file non valido si scavalca (al massimo [TrustLimits.MAX_JUNK_RUN] di fila); uno occupato da una testa valida
   * **mia** ferma la pubblicazione con [HeadPublish.ExistingOwn]. "Esiste gia' una testa piu' nuova di un altro" non
   * esiste: le teste le scrive solo il dispositivo stesso (§2.1).
   */
  fun publish(
    fs: NasFileSystem,
    registry: RegistryState,
    signer: Signer,
    kind: HeadKind,
    after: Long,
    readerAccount: String,
    sign: (Long) -> ByteArray,
  ): HeadPublish {
    require(after >= 0) { "Versione negativa." }
    val me = TrustIds.deviceIdOf(signer.publicKey)
    val maxBytes = Envelope.maxFileBytes(DocType.DEVICE_HEAD)
    val skipped = ArrayList<SharePath>()
    var version = after + 1
    while (true) {
      if (version > TrustLimits.MAX_VERSION) return HeadPublish.NoRoom
      val path = NasLayout.headFile(me, kind.wire, version)
      val bytes = sign(version)
      when (fs.publishExclusive(path, bytes)) {
        PublishResult.CREATED ->
          return HeadPublish.Published(version, HeadMark(version, Envelope.open(bytes, DocType.DEVICE_HEAD).contentHash), skipped)
        PublishResult.ALREADY_EXISTS -> {
          val existing = fs.read(path, maxBytes)
          if (existing is NasRead.Data) {
            val location = HeadLocation(fs.shareName, me, kind, version)
            val verdict = HeadVerifier.accept(existing.bytes, registry, location, null, readerAccount)
            if (verdict is Verdict.Accepted && verdict.value.signer.id == me) return HeadPublish.ExistingOwn(version, verdict.value)
          }
          skipped += path
          if (skipped.size > TrustLimits.MAX_JUNK_RUN) return HeadPublish.Blocked(skipped)
          version++
        }
      }
    }
  }

  /**
   * I dispositivi da leggere in una share (§2.2): le cartelle di `devices/` (al massimo
   * [TrustLimits.MAX_INDEX_DEVICES_PER_SHARE] nomi) che il registro accettato riconosce come approvati o dismessi. Se
   * l'elenco e' troncato (qualcuno ha inondato la cartella) si prendono **tutti** i dispositivi approvati o dismessi
   * del registro: un'inondazione non nasconde un dispositivo, costa al massimo una lettura per nome in piu' a testa.
   */
  fun devices(fs: NasFileSystem, registry: RegistryState): IndexDevices {
    val listing = fs.list(NasLayout.ROOT.child("devices"), TrustLimits.MAX_INDEX_DEVICES_PER_SHARE)
    val known = registry.registry.devices
      .filter { it.status == DeviceStatus.APPROVED || it.status == DeviceStatus.RETIRED }
      .map { it.id }
      .toSet()
    val listed = listing.names.filter { TrustIds.isValid(it) && it in known }.toSet()
    val ids = if (listing.truncated) known else listed
    return IndexDevices(ids.sorted(), listing.truncated, ignored = listing.names.size - listed.size)
  }
}
