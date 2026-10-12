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
 * Lettura del registro dall'area comune (`docs/formato-nas.md` §4.3). Non sostituisce mai lo stato locale
 * con qualcosa di piu' vecchio o non verificato: nel caso peggiore (cartella manomessa, file cancellati,
 * spazzatura) resta lo stato che si aveva e la Diagnostica lo dice.
 */
class RegistrySync(private val commonShare: NasFileSystem) {

  class Result(
    /** Lo stato da usare (e da salvare se [changed]). Null solo per un dispositivo nuovo che non ha trovato nulla. */
    val state: RegistryState?,
    val changed: Boolean,
    val rejected: RejectionTally,
    /** La cartella aveva piu' nomi del tetto: anomalia da segnalare. */
    val listingTruncated: Boolean,
  ) {
    /** Ci sono versioni piu' nuove che non si sono potute verificare (manomissione, reset, app vecchia). */
    val newerUnverified: Boolean get() = rejected.total > 0

    /**
     * Sul NAS c'e' un registro scritto da un'app piu' nuova: si chiede di aggiornare e, se questo e' un
     * dispositivo admin, non si firmano altre versioni finche' non si aggiorna (E-I3).
     */
    val needsAppUpdate: Boolean get() = rejected.count(RejectReason.NEWER_FORMAT) > 0
  }

  fun refresh(current: RegistryState?, anchor: TrustAnchor): Result {
    val tally = RejectionTally()
    val listing = commonShare.list(NasLayout.REGISTRY_DIR, TrustLimits.MAX_LIST_REGISTRY)
    val floor = current?.registry?.version ?: (anchor.minRegistryVersion - 1)
    val versions = listing.names.mapNotNull(NasLayout::parseVersionName).filter { it > floor }.sorted()
    if (versions.isEmpty()) return Result(current, changed = false, tally, listing.truncated)

    // Caso comune: la versione piu' alta e' firmata dalla chiave attesa. Le intermedie non servono.
    val newest = tryVersion(versions.last(), current, anchor)
    if (newest is Verdict.Accepted) return Result(newest.value, changed = true, tally, listing.truncated)

    // Altrimenti (rotazione della chiave in mezzo, o spazzatura in cima) si risale in ordine.
    var state = current
    for (version in versions) {
      when (val verdict = tryVersion(version, state, anchor)) {
        is Verdict.Accepted -> state = verdict.value
        is Verdict.Rejected -> tally.add(verdict.reason)
      }
    }
    return Result(state, changed = state !== current, tally, listing.truncated)
  }

  private fun tryVersion(version: Long, state: RegistryState?, anchor: TrustAnchor): Verdict<RegistryState> {
    val bytes = when (val read = commonShare.read(NasLayout.registryFile(version), Envelope.maxFileBytes(DocType.FAMILY_REGISTRY))) {
      is NasRead.Data -> read.bytes
      NasRead.NotFound -> return Verdict.Rejected(RejectReason.MALFORMED)
      NasRead.TooLarge -> return Verdict.Rejected(RejectReason.TOO_LARGE)
    }
    val verdict = RegistryVerifier.accept(bytes, state, anchor)
    if (verdict is Verdict.Accepted && verdict.value.registry.version != version) {
      return Verdict.Rejected(RejectReason.WRONG_LOCATION)
    }
    return verdict
  }
}

/** Le richieste di registrazione in attesa, per la schermata dell'admin (piano 2.3). */
class PendingRequests(val requests: List<RegistrationRequest>, val rejected: RejectionTally, val listingTruncated: Boolean) {
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
        val read = commonShare.read(NasLayout.requestFile(name), Envelope.maxFileBytes(DocType.REGISTRATION_REQUEST))
        if (read !is NasRead.Data) {
          if (read is NasRead.TooLarge) tally.add(RejectReason.TOO_LARGE)
          continue
        }
        when (val verdict = RegistrationRequest.verify(read.bytes, state.registry.familyId, name)) {
          is Verdict.Accepted -> found.add(verdict.value)
          is Verdict.Rejected -> tally.add(verdict.reason)
        }
      }
      return PendingRequests(found, tally, listing.truncated)
    }
  }
}
