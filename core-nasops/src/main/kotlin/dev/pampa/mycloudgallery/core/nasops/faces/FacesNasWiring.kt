package dev.pampa.mycloudgallery.core.nasops.faces

import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.nas.contract.NasSessionManager
import dev.pampa.mycloudgallery.core.nas.ops.NasFileOpsFactory
import dev.pampa.mycloudgallery.core.nasops.contract.FacesEraseFacts
import dev.pampa.mycloudgallery.core.nasops.contract.FacesEraseLimits
import dev.pampa.mycloudgallery.core.nasops.contract.FacesEraseStop
import dev.pampa.mycloudgallery.core.nasops.contract.FacesNasEraser
import dev.pampa.mycloudgallery.core.nasops.contract.OpClocks
import dev.pampa.mycloudgallery.core.nasops.contract.OwnFilesCleaning
import dev.pampa.mycloudgallery.core.nasops.engine.CatalogPresence
import dev.pampa.mycloudgallery.core.trust.registry.RegistryState

/**
 * Cio' che `:app` (P13) lega alla cancellazione dei volti sul NAS (Fase 8, P8; D29, D31, D35). Solo porte e valori: nessun tipo delle primitive
 * di scrittura (come `NasOpsConfig`).
 *
 * @property deviceId l'id di questo dispositivo.
 * @property accountId l'account del NAS di questo dispositivo (le teste dei volti valgono solo per lui).
 * @property facts la generazione corrente dell'account (stato fresco, anche le scelte non ancora datate) e i dispositivi revocati o persi.
 * @property privateShare l'area privata dichiarata dell'account (null = nessuna: niente si cancella).
 * @property commonShare l'area comune del registro accettato (null = non si sa: niente si cancella).
 * @property gate il cancello di D35 composto da `:app`: congelamento (`FROZEN`), emergenza (`EMERGENCY_STOP`), salto dell'ora (`CLOCK_JUMP`),
 *   sola lettura (`READ_ONLY`), fiducia (`NOT_TRUSTED`), recinzione delle share vere (`TEST_SHARES_ONLY`, D34); null = si puo'.
 * @property registry il registro accettato (la pulizia dei propri file verifica le firme).
 * @property publishedSegments il diario del pubblicatore dei volti per generazione (`FacesPublishState.diary`).
 * @property catalogPresence cio' che il catalogo vede come media: un file con quell'identita' non si toglie mai, anche sotto `faces/`.
 */
class FacesNasConfig(
  val deviceId: String,
  val accountId: String,
  val facts: FacesEraseFacts,
  val privateShare: () -> String?,
  val commonShare: () -> String?,
  val gate: suspend (String) -> FacesEraseStop?,
  val clocks: OpClocks,
  val registry: () -> RegistryState? = { null },
  val publishedSegments: suspend (FaceGeneration) -> Set<String> = { emptySet() },
  val catalogPresence: CatalogPresence = CatalogPresence.NONE,
  val maxFilesPerRound: Int = FacesEraseLimits.MAX_FILES_PER_ROUND,
) {
  override fun toString(): String = "FacesNasConfig"
}

/** La cancellazione stretta dei volti e la pulizia dei propri file dei volti, pronte. */
class FacesNasServices internal constructor(val eraser: FacesNasEraser, val cleaning: OwnFilesCleaning)

/**
 * L'unico posto in produzione dove una sessione del NAS diventa la cancellazione dei volti (`NasFileOpsFactory`, D2): `:app` passa la sessione e
 * la configurazione e riceve i servizi, senza mai vedere le primitive (`Phase4ArchitectureTest`).
 */
object FacesNasWiring {

  fun build(session: NasSessionManager, config: FacesNasConfig): FacesNasServices {
    val fileOps = NasFileOpsFactory.create(session, config.deviceId)
    val reader = session.mediaReader()
    val eraser = NasFacesEraser(
      fileOps = fileOps,
      reader = reader,
      facts = config.facts,
      privateShare = config.privateShare,
      commonShare = config.commonShare,
      gate = config.gate,
      clocks = config.clocks,
      presence = config.catalogPresence,
      maxFilesPerRound = config.maxFilesPerRound,
    )
    val cleaning = FacesOwnFilesCleaner(
      deviceId = config.deviceId,
      accountId = config.accountId,
      fileOps = fileOps,
      reader = reader,
      facts = config.facts,
      registry = config.registry,
      publishedSegments = config.publishedSegments,
      privateShare = config.privateShare,
      commonShare = config.commonShare,
      gate = config.gate,
      clocks = config.clocks,
    )
    return FacesNasServices(eraser, cleaning)
  }
}
