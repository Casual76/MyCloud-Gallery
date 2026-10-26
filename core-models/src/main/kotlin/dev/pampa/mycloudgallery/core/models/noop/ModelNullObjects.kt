package dev.pampa.mycloudgallery.core.models.noop

import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.models.contract.BundledAssets
import dev.pampa.mycloudgallery.core.models.contract.EnsureResult
import dev.pampa.mycloudgallery.core.models.contract.ModelFailCode
import dev.pampa.mycloudgallery.core.models.contract.ModelFiles
import dev.pampa.mycloudgallery.core.models.contract.ModelId
import dev.pampa.mycloudgallery.core.models.contract.ModelManager
import dev.pampa.mycloudgallery.core.models.contract.ModelNasArea
import dev.pampa.mycloudgallery.core.models.contract.ModelNetworkGate
import dev.pampa.mycloudgallery.core.models.contract.ModelNetworkVerdict
import dev.pampa.mycloudgallery.core.models.contract.ModelShelf
import dev.pampa.mycloudgallery.core.models.contract.ModelShelfGate
import dev.pampa.mycloudgallery.core.models.contract.ModelSourceKind
import dev.pampa.mycloudgallery.core.models.contract.ModelSpec
import dev.pampa.mycloudgallery.core.models.contract.ModelState
import dev.pampa.mycloudgallery.core.models.contract.ModelTraffic
import dev.pampa.mycloudgallery.core.models.contract.ModelTrigger
import dev.pampa.mycloudgallery.core.models.contract.ModelUsage
import dev.pampa.mycloudgallery.core.models.contract.ShelfCopy
import dev.pampa.mycloudgallery.core.models.contract.ShelfDelete
import dev.pampa.mycloudgallery.core.models.contract.ShelfResult
import dev.pampa.mycloudgallery.core.models.contract.ShelfSource
import dev.pampa.mycloudgallery.core.models.contract.ShelfWrite
import dev.pampa.mycloudgallery.core.models.contract.ShelfWriter
import dev.pampa.mycloudgallery.core.models.contract.VerifiedModel
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.io.InputStream
import kotlin.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// I null-object dei modelli (Fase 6, P0): falliscono CHIUSI. Nessun modello presente, nessuno scaricamento, nessuna rete, niente sul
// NAS: la ricerca resta utilizzabile con filtri e testo (D35).

object NoModelManager : ModelManager {
  override val states: StateFlow<Map<ModelId, ModelState>> = MutableStateFlow(emptyMap())

  override suspend fun ensure(id: ModelId, trigger: ModelTrigger, budget: Duration): EnsureResult =
    EnsureResult.Failed(ModelFailCode.FETCH_FAILED)

  override suspend fun remove(id: ModelId): Boolean = false

  override suspend fun usage(): ModelUsage = ModelUsage(emptyMap(), 0)
}

/** Nessun modello verificato: i motori restano spenti. */
object NoModelFiles : ModelFiles {
  override suspend fun open(id: ModelId): VerifiedModel? = null
}

object NoModelShelf : ModelShelf {
  override suspend fun publish(spec: ModelSpec, share: String): ShelfResult = ShelfResult.Blocked("not_wired")

  override suspend fun copies(share: String, sha256: Sha256): List<ShelfCopy> = emptyList()

  override suspend fun pruneOwn(share: String, keep: Set<Sha256>): Int = 0
}

/** Nessuna rete per i modelli. */
object ClosedModelNetworkGate : ModelNetworkGate {
  override suspend fun verdict(trigger: ModelTrigger, bytes: Long, source: ModelSourceKind): ModelNetworkVerdict = ModelNetworkVerdict.Refused("closed")
}

/** P3: nessun traffico registrato. */
object NoModelTraffic : ModelTraffic {
  override suspend fun record(bytes: Long) = Unit
}

/** P3: nessun NAS, restano gli indirizzi HTTPS. */
object NoModelNasArea : ModelNasArea {
  override suspend fun commonShare(): String? = null
}

/** P3: nessun asset nell'APK. */
object NoBundledAssets : BundledAssets {
  override fun open(asset: String): InputStream? = null
}

/** P3: chiuso, nessuna scrittura sul NAS finche' `:app` non lega il cancello vero (F4-D31). */
object ClosedModelShelfGate : ModelShelfGate {
  override suspend fun blockFor(share: String): String = "not_wired"
}

/** P3: nessuno scrittore, ogni scrittura e' rifiutata e ogni rimozione fallisce. */
class NoShelfWriter(override val ownerDeviceId: String) : ShelfWriter {
  override suspend fun publishExclusive(share: String, path: SharePath, source: ShelfSource): ShelfWrite = ShelfWrite.Refused("not_wired")

  override suspend fun deleteOwn(share: String, path: SharePath, size: Long): ShelfDelete = ShelfDelete.Failed
}
