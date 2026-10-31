package dev.pampa.mycloudgallery.core.map.contract

import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.StableCode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

// I PUNTI E IL RAGGRUPPAMENTO (docs/fase9-progetto.md §1.2 "Punti", "Indice", §2.10, D27, D28, E-MAP5-E-MAP10, E-MAP15, E-H9). Contratto di P0;
// l'indice vero (Morton a prefissi, livelli pigri, cache) e la query di core-database sono di P3, il riferimento a forza bruta e'
// `ReferenceClusterIndex` (testing/) con la suite `ClusterIndexContract`. **Solo celle visibili**: i punti arrivano dalla query sulle sole celle
// visibili (nascosti e membri di gruppi nascosti mai) e le copertine passano ancora dal filtro finale `HiddenMask` di chi le mostra (D31).

/** Da dove arrivano i punti (D27): una porta verso `MapPointsQueries` di core-database, legata da :app (P3, P13), a pagine per chiave. */
interface MapPointSource {
  /** Tutti i punti validi, a pagine (2.000 alla volta), e alla fine [withoutPosition] e [invalid] tramite [counts]. */
  fun pages(): Flow<List<MapPoint>>

  /** Quanti media visibili non hanno posizione (E-MAP5) e quante coordinate sono state scartate (E-MAP7), dopo l'ultima lettura. */
  suspend fun counts(): MapPointCounts
}

data class MapPointCounts(val withoutPosition: Int, val invalid: Int)

/** Una bolla (D28): la cella, il centro (media dei punti), quanti media, la copertina (punteggio piu' alto, poi il piu' recente, poi l'id), il luogo piu' frequente. */
data class GeoCluster(val key: CellKey, val center: GeoE6, val count: Int, val cover: MediaId, val placeId: Int?) {
  override fun toString(): String = "GeoCluster(${key.level}, $count)"
}

/**
 * L'indice dei punti (D28): immutabile, costruito fuori dal thread principale e scambiato in modo atomico quando cambia l'istantanea. Le regole
 * di parita' con il riferimento (`ClusterIndexContract`):
 *  - [clusters]: al livello `GeoGrid.levelFor(zoom, cellPx)`, **ogni** cella non vuota che tocca [view] (`GeoGrid.touches`), con **tutti** i suoi
 *    punti, in ordine di prefisso; il centro e' la media intera (per difetto) di latitudini e longitudini in microgradi;
 *  - la copertina: punteggio piu' alto, poi giorno piu' recente, poi l'id minore; il luogo: il piu' frequente non nullo, a parita' l'id minore;
 *  - [members]: i media della cella dal piu' recente (giorno decrescente, poi id crescente), al piu' [limit];
 *  - [boundsOfAll]: il riquadro di tutti i punti (minimi e massimi), null se non ce ne sono.
 */
interface GeoClusterIndex {
  val size: Int

  fun clusters(view: GeoBox, zoom: Double, cellPx: Int): List<GeoCluster>

  fun members(key: CellKey, limit: Int): List<MediaId>

  fun boundsOfAll(): GeoBox?
}

/** Perche' si rifa' l'indice. */
enum class RebuildReason(override val code: String) : StableCode {
  OPEN("open"),
  SNAPSHOT_CHANGED("snapshot_changed"),
  HIDDEN_CHANGED("hidden_changed"),
}

/** Lo stato dell'indice della mappa (§1.2): si costruisce, pronto (con i media senza posizione da contare), vuoto. */
sealed interface GeoIndexState {
  data object Building : GeoIndexState

  class Ready(val index: GeoClusterIndex, val withoutPosition: Int, val invalid: Int) : GeoIndexState {
    override fun toString(): String = "GeoIndexState.Ready(${index.size}, senza=$withoutPosition)"
  }

  /** Nessuna foto con la posizione (E-MAP5): la sezione dice quante sono senza. */
  data class Empty(val withoutPosition: Int) : GeoIndexState

  /**
   * La lettura dei punti e' fallita e **non c'e' un indice buono** da tenere (MA-1 della verifica funzionale della Fase 9): non e' "nessuna foto con la posizione". Le schermate
   * dicono "Non riesco a leggere le posizioni delle foto" con "Riprova" (`rebuild(OPEN)`). Se un indice c'era, resta quello (mai `Failed` sopra un indice valido).
   */
  data object Failed : GeoIndexState
}

/** I punti della mappa (D27): l'indice di adesso e la sua ricostruzione (dopo 2 s di calma quando cambia l'istantanea, `mapIndexRebuild`). */
interface MapPoints {
  val index: StateFlow<GeoIndexState>

  suspend fun rebuild(reason: RebuildReason)

  companion object {
    /** La calma prima di rifare l'indice dopo un cambio dell'istantanea (regole-attivita.md §6, `mapIndexRebuild`). */
    const val REBUILD_QUIET_MS: Long = 2_000

    /** I punti per pagina della query (D27). */
    const val PAGE_SIZE: Int = 2_000

    /** Al piu' tante bolle visibili (D28). */
    const val MAX_BUBBLES: Int = 60

    /** Sotto tanti elementi (o al livello massimo) il tocco su una bolla apre la griglia della zona (D28). */
    const val GRID_BELOW: Int = 30
  }
}
