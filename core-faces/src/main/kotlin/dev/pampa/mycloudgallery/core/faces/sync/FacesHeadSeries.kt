package dev.pampa.mycloudgallery.core.faces.sync

import dev.pampa.mycloudgallery.core.faces.contract.FacesPut
import dev.pampa.mycloudgallery.core.faces.contract.FacesRead
import dev.pampa.mycloudgallery.core.faces.contract.FacesRemote
import dev.pampa.mycloudgallery.core.index.contract.RemoteFailure
import dev.pampa.mycloudgallery.core.index.contract.RemoteResult
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.data.AcceptedFacesHead
import dev.pampa.mycloudgallery.core.trust.data.EncodedFacesHead
import dev.pampa.mycloudgallery.core.trust.data.FacesDocs
import dev.pampa.mycloudgallery.core.trust.data.FacesHeadLocation
import dev.pampa.mycloudgallery.core.trust.data.HeadMark
import dev.pampa.mycloudgallery.core.trust.envelope.DocType
import dev.pampa.mycloudgallery.core.trust.registry.RegistryState

// LE SERIE DI TESTE DEI VOLTI (formato §2.3, §5.5, §5.11): le stesse regole delle teste dei dispositivi (`HeadSeries` di core-trust) e degli
// album (`AlbumHeadSeries`), nella cartella `faces/<G>/<dispositivo>/head/` e sopra la porta [FacesRemote]. Chi pubblica sceglie il primo nome
// libero e scavalca la spazzatura (al piu' 32 nomi di fila); chi legge va PER NOME dall'ultima testa accettata (salti doppi e bisezione), mai
// dall'elenco, che serve solo come indizio oltre un buco. Solo letture e `publishExclusive`.

/** Il budget delle letture per nome di un giro (tutti i dispositivi della generazione insieme). */
internal class FacesReadBudget(limit: Int) {
  var remaining: Int = limit
    private set

  fun take(): Boolean {
    if (remaining <= 0) return false
    remaining--
    return true
  }
}

/** L'esito della lettura della testa piu' recente di un dispositivo. */
internal sealed interface FacesHeadRead {
  /** Nessuna testa piu' nuova di quella nota. */
  data object NoChange : FacesHeadRead

  /** Il dispositivo non ha (ancora) una testa valida in questa generazione. */
  data object None : FacesHeadRead

  /** [skipped]: i nomi piu' alti scavalcati perche' non validi (una testa dismessa non congelata, spazzatura), per il resoconto. */
  class Accepted(val head: AcceptedFacesHead, val skipped: List<RejectReason> = emptyList()) : FacesHeadRead {
    override fun toString(): String = "FacesHeadRead.Accepted(v${head.head.version})"
  }

  /** Nomi occupati ma nessuna testa valida (firma, account, revocato, posto, versione, rollback, equivocazione...). */
  data class Rejected(val reason: RejectReason) : FacesHeadRead

  data object OutOfBudget : FacesHeadRead

  /** Il NAS non ha risposto: si riprova al giro dopo. */
  data class Failed(val failure: RemoteFailure) : FacesHeadRead
}

/** L'esito della pubblicazione di una testa propria. */
internal sealed interface FacesHeadPublish {
  class Published(val version: Long, val mark: HeadMark) : FacesHeadPublish

  /** A quel nome c'e' gia' una testa valida e mia che e' proprio quella da pubblicare (un guasto dopo la scrittura): la si adotta. */
  class ExistingOwn(val version: Long, val head: AcceptedFacesHead) : FacesHeadPublish

  /** Oltre 32 nomi non validi di fila (qualcuno ha riempito la cartella): non si pubblica. */
  data object Blocked : FacesHeadPublish

  data object NoRoom : FacesHeadPublish

  data class Failed(val failure: RemoteFailure) : FacesHeadPublish
}

internal object FacesHeadSeries {

  private class Abort(val failure: RemoteFailure) : Exception()

  /**
   * La testa piu' recente e valida di [deviceId] nella generazione [generation] dopo [previous] (anti-rollback: mai sotto), letta per nome e
   * giudicata con [FacesDocs.decodeHead] (account [account], ora del NAS [nasNow]). Con [listingHint] (di default alla prima lettura)
   * l'elenco della cartella indica versioni oltre un buco; una testa gia' accettata sparita senza una piu' nuova e' `ROLLBACK`.
   */
  suspend fun readLatest(
    remote: FacesRemote,
    registry: RegistryState,
    generation: FaceGeneration,
    deviceId: String,
    previous: HeadMark?,
    account: String,
    budget: FacesReadBudget,
    nasNow: Long?,
    listingHint: Boolean = previous == null,
  ): FacesHeadRead = try {
    scan(remote, registry, generation, deviceId, previous, account, budget, nasNow, listingHint)
  } catch (abort: Abort) {
    FacesHeadRead.Failed(abort.failure)
  }

  private suspend fun scan(
    remote: FacesRemote,
    registry: RegistryState,
    generation: FaceGeneration,
    deviceId: String,
    previous: HeadMark?,
    account: String,
    budget: FacesReadBudget,
    nasNow: Long?,
    listingHint: Boolean,
  ): FacesHeadRead {
    if (!TrustIds.isValid(deviceId)) return FacesHeadRead.Rejected(RejectReason.INVALID_FIELD)
    val base = previous?.version ?: 0L
    val nothing: FacesHeadRead = if (previous == null) FacesHeadRead.None else FacesHeadRead.NoChange
    var outOfBudget = false

    suspend fun probe(version: Long): Boolean {
      if (version < 1 || version > TrustLimits.MAX_VERSION) return false
      if (!budget.take()) {
        outOfBudget = true
        return false
      }
      return when (val read = remote.readHead(generation, deviceId, version, 0)) {
        is RemoteResult.Failed -> throw Abort(read.failure)
        is RemoteResult.Ok -> read.value !is FacesRead.NotFound
      }
    }

    var low: Long
    if (probe(base + 1)) {
      low = base + 1
    } else {
      if (outOfBudget) return FacesHeadRead.OutOfBudget
      if (!listingHint) return nothing
      if (!budget.take()) return FacesHeadRead.OutOfBudget
      val listing = when (val result = remote.headNumbers(generation, deviceId)) {
        is RemoteResult.Failed -> throw Abort(result.failure)
        is RemoteResult.Ok -> result.value
      }
      if (previous != null && !listing.truncated && listing.items.none { it >= previous.version } && !probe(previous.version)) {
        return if (outOfBudget) FacesHeadRead.OutOfBudget else FacesHeadRead.Rejected(RejectReason.ROLLBACK)
      }
      val hinted = listing.items.filter { it > base }.maxOrNull() ?: return nothing
      if (!probe(hinted)) return if (outOfBudget) FacesHeadRead.OutOfBudget else nothing
      low = hinted
    }
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
    // Dall'ultimo nome occupato in giu': la prima testa valida vince; la spazzatura si scavalca (al piu' MAX_JUNK_RUN).
    val maxBytes = DocType.FACES_HEAD.maxFileBytes
    var version = low
    var tries = 0
    var topReason: RejectReason? = null
    val skipped = ArrayList<RejectReason>()
    while (version > base && tries <= TrustLimits.MAX_JUNK_RUN) {
      tries++
      if (!budget.take()) return FacesHeadRead.OutOfBudget
      val read = when (val result = remote.readHead(generation, deviceId, version, maxBytes)) {
        is RemoteResult.Failed -> throw Abort(result.failure)
        is RemoteResult.Ok -> result.value
      }
      val reason: RejectReason? = when (read) {
        FacesRead.NotFound -> null
        FacesRead.TooLarge -> RejectReason.TOO_LARGE
        is FacesRead.Data -> {
          val location = FacesHeadLocation(generation, deviceId, version)
          when (val verdict = FacesDocs.decodeHead(read.bytes, registry, location, account, previous, nasNow)) {
            is Verdict.Accepted -> return FacesHeadRead.Accepted(verdict.value, skipped)
            is Verdict.Rejected -> verdict.reason
          }
        }
      }
      if (reason != null) {
        skipped += reason
        if (topReason == null) topReason = reason
      }
      version--
    }
    return topReason?.let { FacesHeadRead.Rejected(it) } ?: nothing
  }

  /**
   * Pubblica una testa propria nella generazione [generation] al primo nome libero dopo [after]. [sign] riceve il numero e restituisce la
   * testa firmata. Un nome occupato da un file non valido si scavalca (al piu' [TrustLimits.MAX_JUNK_RUN] di fila); uno occupato da una testa
   * valida **mia** (scritta da me senza che lo sapessi: un guasto dopo la scrittura, una morte del processo, lo stato locale perso) e':
   * [FacesHeadPublish.ExistingOwn] se [matches] dice che e' gia' quella che voglio pubblicare (la si adotta), altrimenti si passa oltre
   * senza contarla come spazzatura. Cosi' la testa nuova e' sempre piu' alta di ogni mia testa gia' sul NAS (chi legge prende la piu' alta).
   */
  suspend fun publish(
    remote: FacesRemote,
    registry: RegistryState,
    generation: FaceGeneration,
    account: String,
    after: Long,
    matches: (AcceptedFacesHead) -> Boolean,
    sign: (Long) -> EncodedFacesHead,
  ): FacesHeadPublish {
    require(after >= 0) { "Versione negativa." }
    val maxBytes = DocType.FACES_HEAD.maxFileBytes
    var junk = 0
    var version = after + 1
    while (true) {
      if (version > TrustLimits.MAX_VERSION) return FacesHeadPublish.NoRoom
      val encoded = sign(version)
      when (val put = remote.publishHead(generation, version, encoded.bytes)) {
        is RemoteResult.Failed -> return FacesHeadPublish.Failed(put.failure)
        is RemoteResult.Ok -> if (put.value == FacesPut.CREATED) return FacesHeadPublish.Published(version, encoded.mark)
      }
      val existing = remote.readHead(generation, remote.deviceId, version, maxBytes)
      if (existing is RemoteResult.Failed) return FacesHeadPublish.Failed(existing.failure)
      val data = ((existing as RemoteResult.Ok).value as? FacesRead.Data)?.bytes
      val own = data?.let {
        (FacesDocs.decodeHead(it, registry, FacesHeadLocation(generation, remote.deviceId, version), account, null) as? Verdict.Accepted)?.value
      }?.takeIf { it.signer.id == remote.deviceId }
      if (own != null) {
        if (matches(own)) return FacesHeadPublish.ExistingOwn(version, own)
        junk = 0
      } else {
        junk++
        if (junk > TrustLimits.MAX_JUNK_RUN) return FacesHeadPublish.Blocked
      }
      version++
    }
  }
}
