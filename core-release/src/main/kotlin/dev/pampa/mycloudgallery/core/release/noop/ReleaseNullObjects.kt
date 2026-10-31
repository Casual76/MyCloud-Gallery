package dev.pampa.mycloudgallery.core.release.noop

import dev.pampa.mycloudgallery.core.release.contract.ManifestOrigin
import dev.pampa.mycloudgallery.core.release.contract.ManifestRead
import dev.pampa.mycloudgallery.core.release.contract.ManifestSource
import dev.pampa.mycloudgallery.core.release.contract.ManifestVerifier
import dev.pampa.mycloudgallery.core.release.contract.OfferResult
import dev.pampa.mycloudgallery.core.release.contract.RejectReason
import dev.pampa.mycloudgallery.core.release.contract.ReleaseDiag
import dev.pampa.mycloudgallery.core.release.contract.ReleaseState
import dev.pampa.mycloudgallery.core.release.contract.ReleaseStateStore
import dev.pampa.mycloudgallery.core.release.contract.ReleaseStatus
import dev.pampa.mycloudgallery.core.release.contract.StoredRelease
import dev.pampa.mycloudgallery.core.release.contract.Verified
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Fase 10 (P0, D29): i null-object del manifest, i default finche' P13 non lega i pezzi veri. Un manifest assente non ha effetti (E-REL1): lo
// stato resta `Normal`, nessuna busta e' accettata, nessun indirizzo aggiuntivo esce. Fallire chiusi qui vuol dire **non accettare niente**: il
// manifest puo' solo restringere, quindi ignorarlo non apre mai niente.

/** Nessuna sorgente: il manifest non c'e'. */
object NoManifestSource : ManifestSource {
  override val origin: ManifestOrigin = ManifestOrigin.NAS

  override suspend fun read(maxBytes: Int): ManifestRead = ManifestRead.Absent
}

/** Nessuna chiave: ogni busta e' rifiutata. */
object RejectAllManifestVerifier : ManifestVerifier {
  override fun verify(bytes: ByteArray, app: String): Verified = Verified.Rejected(RejectReason.UNKNOWN_KEY)
}

/** Nessun deposito: niente si salva, niente si legge. */
object NoReleaseStateStore : ReleaseStateStore {
  override suspend fun read(): StoredRelease? = null

  override suspend fun write(state: StoredRelease) = Unit

  override suspend fun clear() = Unit
}

/** Lo stato senza manifest: sempre `Normal`, nessun indirizzo aggiuntivo, ogni offerta rifiutata. */
object NormalReleaseState : ReleaseState {
  override val status: StateFlow<ReleaseStatus> = MutableStateFlow(ReleaseStatus.Normal)

  override val diag: StateFlow<ReleaseDiag> = MutableStateFlow(ReleaseDiag(0, null, ReleaseStatus.Normal, 0, emptyMap()))

  override suspend fun load() = Unit

  override suspend fun offer(bytes: ByteArray, origin: ManifestOrigin): OfferResult = OfferResult.REJECTED

  override fun extraUrls(id: String, sha256: String): List<String> = emptyList()

  override suspend fun clear() = Unit
}
