package dev.pampa.mycloudgallery.core.nasmedia.contract

import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.LocationId
import dev.pampa.mycloudgallery.core.model.LocationKey
import dev.pampa.mycloudgallery.core.model.LocationState
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaMetadata
import dev.pampa.mycloudgallery.core.model.NasPath
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.VersionId
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
 *   scansione (P5: pre-ordine, le sottocartelle di ogni cartella **dalla piu' recente**: per data di modifica se
 *   affidabile, altrimenti per nome decrescente con anni e mesi in ordine numerico, vedi `FolderOrder`); la radice non si
 *   segna mai, quindi `null` = si riparte dalla radice. Per riprendere si rileggono solo le cartelle lungo il percorso di
 *   `resumeAfter` (non quelle gia' fatte); l'ordine di quel giro lo ricorda lo scanner per tutta la durata del giro.
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

/**
 * Un file noto, con lo stesso `fileId` di uno appena visto, **in un altro posto** (E-N1: la cartella o il file sono stati
 * rinominati o spostati dal PC). Basta a riconoscere lo spostamento senza leggere un byte quando anche dimensione e data
 * coincidono e il vecchio posto non e' ancora stato rivisto in questo giro.
 *
 * @property seenAt l'ultimo "visto a" della vecchia posizione (ora del NAS, `null` = mai): se e' >= all'`epoch` del giro il
 *   vecchio posto e' ancora li' (due percorsi con lo stesso `fileId`, per esempio un collegamento fisico) e non e' uno
 *   spostamento.
 */
data class NasKnownElsewhere(
  val locationId: LocationId,
  val location: Location.Nas,
  val stamp: NasStamp,
  val state: LocationState,
  val seenAt: Long?,
)

/** Uno spostamento riconosciuto senza leggere il file: la posizione [locationId] passa a [to], con il timbro nuovo. */
data class NasMove(val locationId: LocationId, val to: Location.Nas, val stamp: NasStamp)

/** Cio' che la lettura (o l'adozione di un record dell'indice) dice dei metadati di un media appena registrato. */
sealed interface NasMetaResult {
  /** Letti (o adottati dall'indice): vanno nella versione e la fase META diventa fatta. */
  data class Read(val metadata: MediaMetadata, val kind: MediaKind) : NasMetaResult

  /** Il file e' un media ma rovinato (E-M10): META fallita con questo [code] stabile; la cella sara' "non leggibile". */
  data class Failed(val code: String) : NasMetaResult

  /** Formato che non sappiamo leggere (E-M13): META "non supportata", non e' un errore. */
  data object Unsupported : NasMetaResult
}

/** I metadati da applicare alla versione corrente di [locationId] (del media [mediaId]), dopo `recordAll`. */
data class NasMetadataApply(val mediaId: MediaId, val locationId: LocationId, val result: NasMetaResult)

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

  /**
   * La scorciatoia del timbro per un lotto: vero dove la posizione nota ha lo stesso timbro (solo "visto a"). Il timbro
   * che manda la scansione porta **dimensione e data** (`fileId` nullo); il `fileId` letto adesso sta in
   * `NasSeen.location`: se cambia con dimensione e data uguali (un NAS riavviato puo' rinumerarli, §7) la conferma vale lo
   * stesso e lo aggiorna. I timbri di `keepAlive` (file noto non trattabile adesso) portano invece il timbro noto intero.
   */
  suspend fun confirmAllPresent(seen: List<NasSeen>, seenAt: Long, cursor: NasScanCursor?): List<Boolean>

  /**
   * Fine di un giro **completo** iniziato a [epoch], **senza congelamento**: le posizioni della share non viste dal giro
   * diventano assenti (tolleranza di 30 giorni, `LocationRules`). Al massimo [cap] per chiamata (si ripete finche' il
   * numero e' uguale a [cap]). Mai chiamata da un giro parziale o congelato (E-S11, D10).
   */
  suspend fun markVanishedInShare(account: AccountId, share: String, epoch: Long, cap: Int): Int

  /** La share non e' raggiungibile (E-R10, E-I8): le sue posizioni diventano "non raggiungibili", **non** assenti. */
  suspend fun markShareUnavailable(account: AccountId, share: String)

  // --- Aggiunte di P5 (P2 le aggiunge a MediaCatalog, :app le lega) ----------------------------------------------------

  /**
   * Le posizioni note della share con uno di questi `fileId`, **a qualunque percorso** (non solo nella cartella in corso):
   * serve a riconoscere uno spostamento fatto dal PC senza leggere il file (E-N1). Per ogni `fileId` al piu' una voce (se ce
   * ne sono piu' di una, la piu' vecchia). Le posizioni assenti si includono: un file rimesso a posto torna presente.
   */
  suspend fun findByFileId(account: AccountId, share: String, fileIds: Collection<Long>): Map<Long, NasKnownElsewhere>

  /**
   * Applica [moves] (al massimo 500) in **una** transazione: la posizione cambia posto e timbro, resta della stessa
   * versione e dello stesso media (identita', preferiti e preparazione conservati), torna presente con "visto a" [seenAt].
   * Falso dove non si puo' (per esempio il posto di arrivo e' gia' occupato da un'altra posizione): chi chiama legge il
   * file e passa dal collegamento normale.
   */
  suspend fun relocateAll(moves: List<NasMove>, seenAt: Long, cursor: NasScanCursor?): List<Boolean>

  /**
   * Quante posizioni della share (presenti o non raggiungibili) **non** sono state viste dal giro iniziato a [epoch]: sono
   * le "spariti" del congelamento (D10), contate **prima** di segnare qualunque assenza.
   */
  suspend fun countUnseen(account: AccountId, share: String, epoch: Long): Int

  /**
   * Esclusioni (D11, E-N14, E-N15): le posizioni presenti sotto [folders] o con una di queste [keys] escono dalla Libreria
   * **per scelta** (l'utente ha escluso la cartella o spento le copertine), quindi diventano assenti subito e non contano
   * fra i "spariti" dei cambiamenti di massa. Restituisce quante ne ha tolte.
   */
  suspend fun retireExcluded(account: AccountId, share: String, folders: List<NasPath>, keys: List<LocationKey>): Int

  /** Scrive i metadati (o il fallimento) letti al rilevamento e aggiorna la fase META di ogni media. Una transazione. */
  suspend fun applyMetadata(items: List<NasMetadataApply>)

  /**
   * Adozione d'identita' (D7): il media locale [local] prende le identita' pubblicate da un altro dispositivo
   * ([remoteMedia], [remoteVersion]) se l'indice le ha gia' assegnate a questo file; fra due id vince il minore. Falso
   * (rifiutata) se l'id remoto e' gia' legato, qui, a un contenuto diverso: conflitto contato in Diagnostica.
   */
  suspend fun adoptIdentity(local: MediaId, remoteMedia: MediaId, remoteVersion: VersionId): Boolean
}
