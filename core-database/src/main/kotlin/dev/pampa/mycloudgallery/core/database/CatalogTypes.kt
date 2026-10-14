package dev.pampa.mycloudgallery.core.database

import dev.pampa.mycloudgallery.core.database.entity.MediaStoreCursorEntity
import dev.pampa.mycloudgallery.core.database.entity.NasScanStateEntity
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.FreezeReason
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.LocationId
import dev.pampa.mycloudgallery.core.model.LocationKey
import dev.pampa.mycloudgallery.core.model.LocationState
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.NasPath
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.domain.AdoptRefusal
import dev.pampa.mycloudgallery.core.model.domain.RemoteIdentity
import dev.pampa.mycloudgallery.core.model.enumByCode
import dev.pampa.mycloudgallery.core.model.isValidMediaStoreVolume

/**
 * Un cambiamento del catalogo, annunciato da [MediaCatalog.changes] **dopo il commit** della transazione
 * che l'ha fatto: chi lo riceve e rilegge il database vede gia' i dati nuovi.
 *
 * - [seq] e' il valore di `change_seq` scritto da quella transazione: sale di **uno** a ogni
 *   transazione che cambia l'indice. Un salto (seq piu' grande dell'ultimo visto + 1) vuol dire che un
 *   cambiamento e' andato perso (il flusso non trattiene chi resta indietro): chi tiene un indice in
 *   memoria allora lo ricostruisce, come all'avvio quando l'istantanea su file ha un `seq` vecchio.
 * - [ids] sono i media la cui riga dell'indice (o il riferimento ai byte) puo' essere cambiata, compresi
 *   quelli usciti dall'indice o cancellati da un'unione: si rileggono con `TimelineIndexDao.indexRows`.
 * - [structural] dice se e' cambiato l'**insieme** o l'**ordine** delle righe (media nuovi, spariti,
 *   nascosti, uniti, raggruppati, data di scatto o giorno cambiati); se e' falso sono cambiati solo
 *   attributi di righe esistenti (preferito, proporzione, segnaposto, flag, presenza, "al sicuro").
 */
data class CatalogChange(
  val seq: Long,
  val ids: Set<MediaId>,
  val structural: Boolean,
)

/**
 * Il cursore della lettura del MediaStore su un volume (vedi `MediaStoreCursorEntity` per il
 * significato di ogni campo). Si salva insieme alle osservazioni che lo fanno avanzare
 * ([MediaCatalog.recordAll], [MediaCatalog.confirmAllPresent], [MediaCatalog.markVanishedNotSeenSince]) o
 * da solo con [MediaCatalog.saveMediaStoreCursor].
 */
data class MediaStoreCursor(
  val volume: String,
  /** La `MediaStore.getVersion()` con cui valgono gli `_ID` in cache. */
  val msVersion: String? = null,
  val generation: Long? = null,
  val deletedGeneration: Long? = null,
  /** Inizio della scansione completa in corso (ora di riferimento), `null` se non ce n'e' una. */
  val scanEpoch: Long? = null,
  /** L'ultimo `_ID` della scansione completa in corso gia' scritto: si riprende da qui (E-P4, E-P8). */
  val scanResumeAfterId: Long? = null,
  val lastFullScanAt: Long? = null,
  val lastReconcileAt: Long? = null,
) {
  init {
    require(isValidMediaStoreVolume(volume)) { "Nome di volume MediaStore non valido." }
    require(scanResumeAfterId == null || scanEpoch != null) { "Si riprende solo una scansione in corso." }
  }
}

/** Una riga del MediaStore come la vede la scorciatoia del timbro: posto e timbro, nessun byte letto. */
data class PhoneStamp(
  val location: Location.Phone,
  val stamp: FileStamp,
)

internal fun MediaStoreCursorEntity.toDomain(): MediaStoreCursor = MediaStoreCursor(
  volume = volume,
  msVersion = msVersion,
  generation = generation,
  deletedGeneration = deletedGeneration,
  scanEpoch = scanEpoch,
  scanResumeAfterId = scanResumeAfterId,
  lastFullScanAt = lastFullScanAt,
  lastReconcileAt = lastReconcileAt,
)

internal fun MediaStoreCursor.toEntity(): MediaStoreCursorEntity = MediaStoreCursorEntity(
  volume = volume,
  msVersion = msVersion,
  generation = generation,
  deletedGeneration = deletedGeneration,
  scanEpoch = scanEpoch,
  scanResumeAfterId = scanResumeAfterId,
  lastFullScanAt = lastFullScanAt,
  lastReconcileAt = lastReconcileAt,
)

// --- v3: il NAS (docs/fase4-progetto.md §2.3; porta `NasScanStore` di core-nasmedia) ---------------------------------

/**
 * Il congelamento di una share (D10): perche' (il conteggio piu' grave), da quando (ora del NAS) e i numeri del giro che
 * l'ha deciso. Finche' c'e', il catalogo non segna assenze in quella share ([MediaCatalog.markVanishedInShare]).
 */
data class NasFreeze(
  val reason: FreezeReason,
  val sinceNasMillis: Long,
  val knownPresent: Int,
  val vanished: Int,
  val changed: Int,
  val notMedia: Int,
) {
  init {
    require(knownPresent >= 0 && vanished >= 0 && changed >= 0 && notMedia >= 0) { "Conteggi negativi." }
  }
}

/**
 * Il cursore della scansione di una share per un account (tabella `nas_scan_state`; `NasScanCursor` della porta di
 * core-nasmedia campo per campo, con il congelamento in [freeze]). Si salva insieme alle osservazioni che lo fanno
 * avanzare. [epoch], [resumeAfter], [lastFullScanAt]: ora del NAS e percorso come nella porta.
 */
data class NasScanState(
  val account: AccountId,
  val share: String,
  val epoch: Long? = null,
  val resumeAfter: NasPath? = null,
  val lastFullScanAt: Long? = null,
  val freeze: NasFreeze? = null,
) {
  init {
    require(resumeAfter == null || epoch != null) { "Si riprende solo un giro in corso." }
  }

  val shareKey: String get() = Location.Nas.shareKeyOf(share)

  // Il percorso di ripresa e' un dato sensibile: fuori dal toString.
  override fun toString(): String = "NasScanState(epoch=$epoch, ripresa=${resumeAfter != null}, congelata=${freeze != null})"
}

/** Un file del NAS visto da un elenco con il suo timbro: per la scorciatoia [MediaCatalog.confirmAllPresentNas]. */
data class SeenNasFile(val location: Location.Nas, val stamp: NasStamp)

/** Un file del NAS gia' noto nel catalogo (presente o non raggiungibile), per la scorciatoia del timbro (D9). */
data class KnownNasLocation(
  val locationId: LocationId,
  val stamp: NasStamp,
  val state: LocationState,
)

/** Una richiesta di adozione d'identita' (D7): il posto del file e l'identita' pubblicata da un altro dispositivo. */
data class IdentityClaim(val location: LocationKey, val remote: RemoteIdentity)

/** L'esito di un'adozione d'identita' ([MediaCatalog.adoptIdentities]). */
sealed interface AdoptOutcome {

  /** Nessuna posizione con quella chiave: prima si registra l'osservazione ([MediaCatalog.recordAll]), poi si adotta. */
  data object NotFound : AdoptOutcome

  /** Le identita' coincidono gia'. */
  data class AlreadyShared(val mediaId: MediaId) : AdoptOutcome

  /** Adottati gli id remoti minori: [mediaId] e' l'identita' ora; il vecchio id resta come alias. */
  data class Adopted(val mediaId: MediaId, val mediaRenamed: Boolean, val versionRenamed: Boolean) : AdoptOutcome

  /** Tenuti gli id locali (i minori, o un media con copie sul telefono: D8). */
  data class KeptLocal(val mediaId: MediaId) : AdoptOutcome

  /** L'id remoto era qui un altro media con gli stessi byte (hash completi uguali): unione, sopravvive [survivor]. */
  data class Merged(val survivor: MediaId) : AdoptOutcome

  /** Rifiutata; `reason.isConflict` = conflitto di D7 da contare (`SyncReport.identityConflicts`). */
  data class Refused(val reason: AdoptRefusal) : AdoptOutcome
}

/** Dove si fa il lavoro di una coda ([MediaCatalog.pendingWork]): sul telefono o sul NAS (copie presenti li'). */
enum class WorkPlace {
  PHONE,
  NAS,
}

internal fun NasScanStateEntity.toDomain(): NasScanState = NasScanState(
  account = AccountId(accountId),
  share = shareName,
  epoch = epoch,
  resumeAfter = resumeAfter?.let(NasPath::of),
  lastFullScanAt = lastFullScanAt,
  freeze = freezeReason?.let { reason ->
    NasFreeze(
      reason = enumByCode(reason),
      sinceNasMillis = checkNotNull(freezeSince) { "Congelamento senza istante." },
      knownPresent = freezeKnownPresent ?: 0,
      vanished = freezeVanished ?: 0,
      changed = freezeChanged ?: 0,
      notMedia = freezeNotMedia ?: 0,
    )
  },
)

internal fun NasScanState.toEntity(): NasScanStateEntity = NasScanStateEntity(
  accountId = account.value,
  shareKey = shareKey,
  shareName = share,
  epoch = epoch,
  resumeAfter = resumeAfter?.value,
  lastFullScanAt = lastFullScanAt,
  freezeReason = freeze?.reason?.code,
  freezeSince = freeze?.sinceNasMillis,
  freezeKnownPresent = freeze?.knownPresent,
  freezeVanished = freeze?.vanished,
  freezeChanged = freeze?.changed,
  freezeNotMedia = freeze?.notMedia,
)
