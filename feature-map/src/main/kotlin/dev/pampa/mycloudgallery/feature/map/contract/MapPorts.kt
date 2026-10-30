package dev.pampa.mycloudgallery.feature.map.contract

import android.content.Context
import dev.pampa.mycloudgallery.core.imaging.contract.PlaceholderCache
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbnailLoader
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.map.contract.GeoE6
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef

// LE PORTE DELLE SCHERMATE DELLA MAPPA (docs/fase9-progetto.md §1.4 `MapGraph`, §2.10, D28-D30; riga P4 del §4.4). Aggiunte di P4 ai contratti di
// feature-map: le miniature locali delle bolle, i nomi dei luoghi, la posizione di un media, l'avvio pigro di MapLibre. Le lega `:app` (P13);
// finche' non lo fa le schermate partono lo stesso con i null-object, che **non fanno niente di pericoloso** (nessuna miniatura: segnaposto;
// nessun nome; nessuna posizione; nessuna mappa vera). Nessuna porta porta coordinate fuori dal telefono. Mai niente nei log (D33).

/** La sorgente di una miniatura: per una cella dell'istantanea (come la Libreria). */
interface MapRefs {
  /** La sorgente **locale** della miniatura per la cella [index], o null se il media non ha una sorgente locale (segnaposto sfocato). */
  fun refAt(snapshot: TimelineSnapshot, index: Int): MediaRef?
}

/** Nessuna sorgente: ogni miniatura e' un segnaposto. */
object NoMapRefs : MapRefs {
  override fun refAt(snapshot: TimelineSnapshot, index: Int): MediaRef? = null
}

/** Tutto cio' che serve a disegnare le copertine delle bolle e le celle della griglia: miniature della Libreria (mai il NAS durante il gesto) e segnaposto. */
class MapThumbnails(
  val loader: ThumbnailLoader,
  val placeholders: PlaceholderCache,
  val refs: MapRefs,
)

/**
 * I nomi dei luoghi (id GeoNames), **gia' scritti** da `PlaceNames` (D28, E-MAP18): legata da `:app` (P13). Mai la parola "casa": se la porta dovesse
 * restituire "Casa" o "Home", [MapLabels] lo scarta comunque (due difese, una sola regola in `MapLabels`).
 */
fun interface MapPlaceNames {
  fun label(placeId: Int): String?
}

/** Nessun nome di luogo: le bolle restano senza etichetta e Luoghi senza righe. */
object NoMapPlaceNames : MapPlaceNames {
  override fun label(placeId: Int): String? = null
}

/**
 * La posizione **effettiva** di un media (la correzione se c'e', altrimenti i metadati: D27, E-MAP8), per "Mostra sulla mappa" e la mini-mappa. Legata da
 * `:app` (P13) alla stessa lettura del pannello info, **solo per media visibili**; null = nessuna posizione (o coordinate scartate, E-MAP7).
 */
fun interface MapMediaPositions {
  suspend fun positionOf(media: MediaId): GeoE6?
}

/** Nessuna posizione per nessun media: niente riga "Mostra sulla mappa", nessuna mini-mappa. */
object NoMapMediaPositions : MapMediaPositions {
  override suspend fun positionOf(media: MediaId): GeoE6? = null
}

/**
 * L'avvio **pigro** di MapLibre (D21, §1.4): la prima schermata che mostra una mappa (o la mini-mappa) lo chiama sul thread principale, mai l'avvio dell'app.
 * `:app` (P13) lo lega a `MapLibreInit.ensure(context, MapNetWiring(...), scopeDelProcesso)`. Risponde se MapLibre e' pronto **con il nostro client gia' installato**
 * (`MapLibreInit.isReady`): senza (null-object) nessuna `MapView` nasce, e la schermata mostra la carta come riquadro vuoto con l'avviso.
 */
fun interface MapEngineStarter {
  fun ensure(context: Context): Boolean
}

/** MapLibre non e' legato: nessuna mappa vera (le schermate si vedono, senza il disegno della carta). */
object UnboundMapEngine : MapEngineStarter {
  override fun ensure(context: Context): Boolean = false
}
