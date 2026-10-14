package dev.pampa.mycloudgallery.core.nasmedia.contract

import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.LocationId
import dev.pampa.mycloudgallery.core.model.LocationKey
import dev.pampa.mycloudgallery.core.model.LocationState
import dev.pampa.mycloudgallery.core.model.NasPath
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.domain.Observation

// Cio' che la scansione chiede al CATALOGO (docs/fase4-progetto.md §1.3 "core-database", §2.3). Porta scritta da P0
// perche' P5 lavora in parallelo a P2 (schema v3): P2 aggiunge a `MediaCatalog` le operazioni con queste firme (cursore
// `nas_scan_state`, `confirmAllPresentNas`, `markVanishedInShare`, `markShareUnavailable`), :app (P11) lega la porta al
// catalogo come `CatalogPorts.kt` fa per la Fase 3. Il fake (`CatalogNasScanStore`) usa un catalogo vero in memoria per
// le decisioni (LinkingRules) e tiene il resto in memoria.

/**
 * Il cursore della scansione di una share per un account (P2: tabella `nas_scan_state`). Si salva **nella stessa
 * transazione** delle osservazioni che lo fanno avanzare: un riavvio non perde ne' ripete nulla (E-P4).
 *
 * @property epoch inizio del giro completo in corso, in ora del NAS (`seenAt` delle posizioni viste nel giro); `null` =
 *   nessun giro in corso.
 * @property resumeAfter l'ultima cartella **completata** del giro in corso, nell'ordine di visita deterministico della
 *   scansione; `null` = si riparte dalla radice.
 * @property lastFullScanAt fine dell'ultimo giro completo (ora del NAS): la verifica "senza novita'" si decide da qui.
 * @property freeze il congelamento in corso (D10), se c'e'.
 */
data class NasScanCursor(
  val account: AccountId,
  val share: String,
  val epoch: Long? = null,
  val resumeAfter: NasPath? = null,
  val lastFullScanAt: Long? = null,
  val freeze: Freeze? = null,
) {
  init {
    require(resumeAfter == null || epoch != null) { "Si riprende solo un giro in corso." }
  }

  override fun toString(): String = "NasScanCursor(epoch=$epoch, ripresa=${resumeAfter != null}, congelata=${freeze != null})"
}

/** Un file gia' noto nel catalogo, per la scorciatoia del timbro (D9). */
data class KnownNasFile(
  val locationId: LocationId,
  val stamp: NasStamp,
  val state: LocationState,
)

/** Un file visto con lo stesso timbro: si aggiorna solo "visto a" (nessun byte letto). */
data class NasSeen(val location: Location.Nas, val stamp: NasStamp)

/** La porta del catalogo per la scansione del NAS. */
interface NasScanStore {

  suspend fun cursor(account: AccountId, share: String): NasScanCursor?

  suspend fun saveCursor(cursor: NasScanCursor)

  /**
   * I file noti (presenti o non raggiungibili, non gli assenti) nella cartella [folder] (`null` = radice) della share,
   * per chiave di posto (`Location.Nas.key`: senza maiuscole, E-N7).
   */
  suspend fun knownIn(account: AccountId, share: String, folder: NasPath?): Map<LocationKey, KnownNasFile>

  /** Quanti file presenti ha la share nel catalogo (le soglie del congelamento, D10). */
  suspend fun countPresent(account: AccountId, share: String): Int

  /**
   * Registra un lotto (al massimo 500) di osservazioni nuove o cambiate in **una** transazione, con [cursor] salvato
   * insieme e [seenAt] (l'`epoch` del giro) come "visto a". Le decisioni sono quelle di `LinkingRules` (E-N1: spostato con
   * data di modifica **o `fileId`** uguale; D21: video con stessa impronta rapida ma senza hash completo nel gruppo
   * provvisorio). Per i media del NAS [whenUndecided] e' `KEEP_SEPARATE`: nel dubbio non si unisce.
   */
  suspend fun recordAll(
    observations: List<Observation>,
    whenUndecided: MediaCatalog.WhenUndecided,
    cursor: NasScanCursor?,
    seenAt: Long,
  ): List<MediaCatalog.RecordOutcome>

  /** La scorciatoia del timbro per un lotto: vero dove la posizione nota ha lo stesso timbro (solo "visto a"). */
  suspend fun confirmAllPresent(seen: List<NasSeen>, seenAt: Long, cursor: NasScanCursor?): List<Boolean>

  /**
   * Fine di un giro **completo** iniziato a [epoch], **senza congelamento**: le posizioni della share non viste dal giro
   * diventano assenti (tolleranza di 30 giorni, `LocationRules`). Al massimo [cap] per chiamata (si ripete finche' il
   * numero e' uguale a [cap]). Mai chiamata da un giro parziale o congelato (E-S11, D10).
   */
  suspend fun markVanishedInShare(account: AccountId, share: String, epoch: Long, cap: Int): Int

  /** La share non e' raggiungibile (E-R10, E-I8): le sue posizioni diventano "non raggiungibili", **non** assenti. */
  suspend fun markShareUnavailable(account: AccountId, share: String)
}
