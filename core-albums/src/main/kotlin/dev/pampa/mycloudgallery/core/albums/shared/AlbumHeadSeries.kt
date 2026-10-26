package dev.pampa.mycloudgallery.core.albums.shared

import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.crypto.Signer
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.data.AcceptedAlbumHead
import dev.pampa.mycloudgallery.core.trust.data.AlbumDocs
import dev.pampa.mycloudgallery.core.trust.data.AlbumHeadLocation
import dev.pampa.mycloudgallery.core.trust.data.AlbumIds
import dev.pampa.mycloudgallery.core.trust.data.EncodedAlbumHead
import dev.pampa.mycloudgallery.core.trust.data.HeadMark
import dev.pampa.mycloudgallery.core.trust.envelope.DocType
import dev.pampa.mycloudgallery.core.trust.nas.NasFileSystem
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.NasRead
import dev.pampa.mycloudgallery.core.trust.nas.PublishResult
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.core.trust.registry.RegistryState

// LE SERIE DI TESTE D'ALBUM (formato §2.3, §5.5, §5.8): le stesse regole delle teste dei dispositivi (`HeadSeries` di core-trust), nella
// cartella `albums/<album>/<dispositivo>/head/`. Chi pubblica sceglie il primo nome libero e scavalca la spazzatura (al piu' 32 nomi di fila);
// chi legge va PER NOME dall'ultima testa accettata (salti doppi e bisezione), mai dall'elenco, che serve solo come indizio oltre un buco.
// Solo `list`, `read` e `publishExclusive`: nessuna cancellazione, nessuna sovrascrittura.

/** Il budget delle letture per nome di un giro (tutti gli album della share insieme). */
class AlbumReadBudget(limit: Int) {
  var remaining: Int = limit
    private set

  fun take(): Boolean {
    if (remaining <= 0) return false
    remaining--
    return true
  }
}

/** L'esito della lettura della testa piu' recente di un contributo. */
sealed interface AlbumHeadRead {
  /** Nessuna testa piu' nuova di quella nota. */
  data object NoChange : AlbumHeadRead

  /** Il dispositivo non ha (ancora) una testa valida per l'album. */
  data object None : AlbumHeadRead

  class Accepted(val head: AcceptedAlbumHead) : AlbumHeadRead {
    override fun toString(): String = "AlbumHeadRead.Accepted(v${head.head.version})"
  }

  /** Nomi occupati ma nessuna testa valida (firma, revocato, posto, versione, rollback, equivocazione...). */
  data class Rejected(val reason: RejectReason) : AlbumHeadRead

  data object OutOfBudget : AlbumHeadRead
}

/** L'esito di una pubblicazione della testa propria. */
sealed interface AlbumHeadPublish {
  class Published(val version: Long, val mark: HeadMark, val skipped: List<SharePath>) : AlbumHeadPublish

  /** A quel nome c'e' gia' una testa valida e mia (morte del processo dopo la pubblicazione): decide chi chiama. */
  class ExistingOwn(val version: Long, val head: AcceptedAlbumHead) : AlbumHeadPublish

  /** Oltre 32 nomi non validi di fila: l'elenco dei file da togliere dal PC. */
  class Blocked(val junk: List<SharePath>) : AlbumHeadPublish

  data object NoRoom : AlbumHeadPublish
}

object AlbumHeadSeries {

  /**
   * La testa piu' recente e valida del contributo di [deviceId] ad [album] dopo [previous] (anti-rollback: mai sotto), letta per nome.
   * Con [listingHint] (di default alla prima lettura) l'elenco della cartella indica versioni oltre un buco; una testa gia' accettata
   * sparita senza una piu' nuova e' [RejectReason.ROLLBACK].
   */
  fun readLatest(
    fs: NasFileSystem,
    registry: RegistryState,
    album: AlbumId,
    deviceId: String,
    previous: HeadMark?,
    budget: AlbumReadBudget,
    listingHint: Boolean = previous == null,
  ): AlbumHeadRead {
    if (!TrustIds.isValid(deviceId)) return AlbumHeadRead.Rejected(RejectReason.INVALID_FIELD)
    val text = AlbumIds.text(album)
    val base = previous?.version ?: 0L
    val nothing: AlbumHeadRead = if (previous == null) AlbumHeadRead.None else AlbumHeadRead.NoChange
    var outOfBudget = false

    fun probe(version: Long): Boolean {
      if (version < 1 || version > TrustLimits.MAX_VERSION) return false
      if (!budget.take()) {
        outOfBudget = true
        return false
      }
      return fs.read(NasLayout.albumHead(text, deviceId, version), 0) !is NasRead.NotFound
    }

    var low: Long
    if (probe(base + 1)) {
      low = base + 1
    } else {
      if (outOfBudget) return AlbumHeadRead.OutOfBudget
      if (!listingHint) return nothing
      if (!budget.take()) return AlbumHeadRead.OutOfBudget
      val listing = fs.list(NasLayout.albumHeadDir(text, deviceId), TrustLimits.MAX_LIST_HEADS)
      val versions = listing.names.mapNotNull(NasLayout::parseVersionName)
      if (previous != null && !listing.truncated && versions.none { it >= previous.version } && !probe(previous.version)) {
        return if (outOfBudget) AlbumHeadRead.OutOfBudget else AlbumHeadRead.Rejected(RejectReason.ROLLBACK)
      }
      val hinted = versions.filter { it > base }.maxOrNull() ?: return nothing
      if (!probe(hinted)) return if (outOfBudget) AlbumHeadRead.OutOfBudget else nothing
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
    val maxBytes = DocType.SHARED_ALBUM_HEAD.maxFileBytes
    var version = low
    var tries = 0
    var topReason: RejectReason? = null
    while (version > base && tries <= TrustLimits.MAX_JUNK_RUN) {
      tries++
      if (!budget.take()) return AlbumHeadRead.OutOfBudget
      val reason: RejectReason? = when (val read = fs.read(NasLayout.albumHead(text, deviceId, version), maxBytes)) {
        NasRead.NotFound -> null
        NasRead.TooLarge -> RejectReason.TOO_LARGE
        is NasRead.Data -> when (val verdict = AlbumDocs.decodeHead(read.bytes, registry, AlbumHeadLocation(album, deviceId, version), previous)) {
          is Verdict.Accepted -> return AlbumHeadRead.Accepted(verdict.value)
          is Verdict.Rejected -> verdict.reason
        }
      }
      if (reason != null && topReason == null) topReason = reason
      version--
    }
    return topReason?.let { AlbumHeadRead.Rejected(it) } ?: nothing
  }

  /**
   * Pubblica una testa propria per [album] al primo nome libero dopo [after]. [sign] riceve il numero e restituisce la testa firmata. Un nome
   * occupato da un file non valido si scavalca; uno occupato da una testa valida **mia** ferma con [AlbumHeadPublish.ExistingOwn].
   */
  fun publish(fs: NasFileSystem, registry: RegistryState, signer: Signer, album: AlbumId, after: Long, sign: (Long) -> EncodedAlbumHead): AlbumHeadPublish {
    require(after >= 0) { "Versione negativa." }
    val me = TrustIds.deviceIdOf(signer.publicKey)
    val text = AlbumIds.text(album)
    val maxBytes = DocType.SHARED_ALBUM_HEAD.maxFileBytes
    val skipped = ArrayList<SharePath>()
    var version = after + 1
    while (true) {
      if (version > TrustLimits.MAX_VERSION) return AlbumHeadPublish.NoRoom
      val path = NasLayout.albumHead(text, me, version)
      val encoded = sign(version)
      when (fs.publishExclusive(path, encoded.bytes)) {
        PublishResult.CREATED -> return AlbumHeadPublish.Published(version, encoded.mark, skipped)
        PublishResult.ALREADY_EXISTS -> {
          val existing = fs.read(path, maxBytes)
          if (existing is NasRead.Data) {
            val verdict = AlbumDocs.decodeHead(existing.bytes, registry, AlbumHeadLocation(album, me, version), null)
            if (verdict is Verdict.Accepted && verdict.value.signer.id == me) return AlbumHeadPublish.ExistingOwn(version, verdict.value)
          }
          skipped += path
          if (skipped.size > TrustLimits.MAX_JUNK_RUN) return AlbumHeadPublish.Blocked(skipped)
          version++
        }
      }
    }
  }
}
