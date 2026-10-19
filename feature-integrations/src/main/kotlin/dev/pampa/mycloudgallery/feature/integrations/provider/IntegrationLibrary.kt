package dev.pampa.mycloudgallery.feature.integrations.provider

import android.net.Uri
import dev.pampa.mycloudgallery.core.imaging.contract.PlaceholderCache
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailLoader
import dev.pampa.mycloudgallery.core.imaging.noop.NoPlaceholderCache
import dev.pampa.mycloudgallery.core.imaging.noop.NoThumbnailLoader
import dev.pampa.mycloudgallery.core.library.contract.ViewerItems
import dev.pampa.mycloudgallery.core.library.noop.EmptyViewerItems
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.feature.integrations.contract.DocumentReader

// Fase 5B (P10, docs/fase5-progetto.md §1.1, §2.7, D23, E-H1, E-X1): la Libreria vista da chi risponde alle altre app, cioe' il selettore
// (`PickerActivity`) e il provider dei documenti (`MediaDocumentsProvider`). E' UNA porta sola, che `:app` lega (P14) a cio' che ha gia':
// la vista della Libreria (`ViewerItems`), le miniature, l'URI del MediaStore, il catalogo e il lettore dei byte del NAS. Sta qui, e non
// nel `core-*`, perche' i `feature-*` non vedono `core-nas` ne' `core-nasops`: i byte di un media solo-NAS arrivano da `openNas`.
//
// **Il solo posto da cui esce un media e' la vista della Libreria** (`items`): per costruzione non contiene i nascosti (E-H1), ne' i membri di
// un gruppo di copie simili con un nascosto (il nascosto e' contagioso, D36), ne' (con l'accesso parziale) cio' che il telefono non concede.
// Tutto il resto della porta (le cartelle del NAS, le informazioni dei file, i byte) risponde su un `MediaId` e non e' mai fidato per dire
// "questo si vede": chi la usa (`LibraryMediaDocuments`, `PickerViewModel`) controlla ogni id contro la vista di adesso prima di dire
// qualunque cosa su di lui, anche se la porta ne restituisce uno che non dovrebbe. Lo provano `HiddenNeverLeavesTest` (entrambe le vie) e
// `LibraryMediaDocumentsTest` con una porta che "sbaglia" apposta.

/** Il nome di un file e le sue misure, come le vede l'altra app. Il nome e' un dato dell'utente: mai nei log ([toString] non lo scrive). */
data class MediaFileInfo(
  val displayName: String,
  val mime: String,
  val size: Long,
  val modifiedAtMillis: Long?,
) {
  init {
    require(size >= 0) { "Dimensione negativa." }
  }

  override fun toString(): String = "MediaFileInfo($mime, $size B)"
}

/**
 * Una cartella dei media sul NAS, come la porta la descrive. La [key] e' un'identita' opaca per chi la porta (il percorso, la share, l'area
 * non compaiono mai negli id dei documenti: il provider li sostituisce con un token); il [name] lo vede solo l'utente, nel selettore di
 * documenti di sistema.
 */
data class NasFolder(val key: NasFolderKey, val name: String) {
  override fun toString(): String = "NasFolder"
}

/** La chiave di una cartella del NAS, opaca per il provider. Non si scrive mai in un log ([toString]). */
@JvmInline
value class NasFolderKey(val value: String) {
  override fun toString(): String = "NasFolderKey"
}

/** Perche' i media solo-NAS non si possono consegnare adesso (E-X1). Solo codici: nessun nome, nessun indirizzo. */
enum class NasUnreachableReason {
  /** Nessuna rete, o il NAS non risponde. */
  OFFLINE,

  /** Dati mobili (anche attraverso la VPN) senza il consenso dell'utente per questi media (D23, E-V2). */
  METERED,

  /** L'accesso al NAS non e' disponibile adesso (famiglia non pronta, nessun NAS configurato). */
  UNAVAILABLE,
}

/** Se i byte di un media solo-NAS si possono leggere adesso. */
sealed interface NasReach {
  data object Reachable : NasReach

  data class Unreachable(val reason: NasUnreachableReason) : NasReach
}

/**
 * La Libreria per le altre app. `:app` la lega (P14): [items] a `GalleryGraph.viewerItems(ALL)` (indice senza i nascosti e con l'accesso
 * parziale), [thumbnails] e [placeholders] alla pipeline delle miniature, [phoneUri] a `PhoneMediaIntents.uriOf`, le informazioni al
 * catalogo, [openPhone] a `ContentUriMediaBytes` e [openNas] al lettore dei byte del NAS, con la scadenza a ogni lettura (E-X1).
 *
 * Nessun metodo restituisce un percorso, una share o un account: le cartelle del NAS hanno una chiave opaca e un nome da mostrare.
 */
interface IntegrationLibrary {

  /** Gli elementi visibili (E-H1: mai i nascosti) e come arrivare ai byte di ognuno. */
  val items: ViewerItems

  /** Le miniature: la stessa pipeline della Libreria. */
  val thumbnails: ThumbnailLoader

  /** I segnaposto sfocati. */
  val placeholders: PlaceholderCache

  /** L'URI `content://media/...` di una copia del telefono (si consegna con `FLAG_GRANT_READ_URI_PERMISSION`). */
  fun phoneUri(ref: MediaRef.Phone): Uri

  /** Nome, tipo, dimensione e data dei media, dal catalogo (senza aprire i file). Un media che il catalogo non conosce piu' manca dalla mappa. */
  suspend fun fileInfos(ids: List<MediaId>): Map<MediaId, MediaFileInfo>

  /** Apre una copia del telefono in lettura a intervalli, o null se la riga non c'e' piu' o non e' leggibile. */
  suspend fun openPhone(ref: MediaRef.Phone): DocumentReader?

  /**
   * Apre un media solo-NAS in lettura a intervalli, o null se il NAS non risponde entro la scadenza (E-X1): **mai un'attesa infinita**.
   * La lettura non carica il file in memoria: ogni [DocumentReader.read] legge un pezzo.
   */
  suspend fun openNas(ref: MediaRef.Nas): DocumentReader?

  /** Se il NAS risponde adesso, per dirlo all'utente **prima** di consegnare dei media solo-NAS (E-X1). Veloce: risponde entro pochi secondi. */
  suspend fun nasReach(): NasReach

  /** Le sottocartelle di [parent] ("Cartelle NAS"); `null` e' la radice (le aree dell'utente). */
  suspend fun nasFolders(parent: NasFolderKey?): List<NasFolder>

  /** I media di una cartella, dal catalogo, al piu' [limit]. Chi la chiama li ricontrolla contro [items]: i nascosti non escono comunque. */
  suspend fun mediaIn(folder: NasFolderKey, limit: Int): List<MediaId>
}

/** Nessuna Libreria: l'app non e' pronta. Falliscono tutti chiusi (vuoto, null). */
object NoIntegrationLibrary : IntegrationLibrary {
  override val items: ViewerItems = EmptyViewerItems
  override val thumbnails: ThumbnailLoader = NoThumbnailLoader
  override val placeholders: PlaceholderCache = NoPlaceholderCache

  override fun phoneUri(ref: MediaRef.Phone): Uri = Uri.EMPTY

  override suspend fun fileInfos(ids: List<MediaId>): Map<MediaId, MediaFileInfo> = emptyMap()

  override suspend fun openPhone(ref: MediaRef.Phone): DocumentReader? = null

  override suspend fun openNas(ref: MediaRef.Nas): DocumentReader? = null

  override suspend fun nasReach(): NasReach = NasReach.Unreachable(NasUnreachableReason.UNAVAILABLE)

  override suspend fun nasFolders(parent: NasFolderKey?): List<NasFolder> = emptyList()

  override suspend fun mediaIn(folder: NasFolderKey, limit: Int): List<MediaId> = emptyList()
}
