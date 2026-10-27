package dev.pampa.mycloudgallery.hidden

import dev.pampa.mycloudgallery.core.albums.contract.PeopleMembership
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.albums.noop.NoPeopleMembership
import dev.pampa.mycloudgallery.core.albums.personal.SmartRulesText
import dev.pampa.mycloudgallery.core.albums.smart.CatalogSmartCandidates
import dev.pampa.mycloudgallery.core.albums.smart.DeviceNormalizer
import dev.pampa.mycloudgallery.core.albums.smart.NoSmartContentSearch
import dev.pampa.mycloudgallery.core.albums.smart.RoomSmartRows
import dev.pampa.mycloudgallery.core.albums.smart.RuleSmartNameInterpreter
import dev.pampa.mycloudgallery.core.albums.smart.RulesSmartEngine
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumClocks
import dev.pampa.mycloudgallery.core.database.entity.AlbumEntity
import dev.pampa.mycloudgallery.core.database.entity.AlbumRuleEntity
import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.library.data.CatalogTimelineSource
import dev.pampa.mycloudgallery.core.library.visibility.CatalogHiddenMask
import dev.pampa.mycloudgallery.core.library.visibility.CatalogVisibleMedia
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.search.contract.PeopleLookup
import dev.pampa.mycloudgallery.core.search.contract.PlaceLookup
import dev.pampa.mycloudgallery.core.search.contract.PlaceName
import dev.pampa.mycloudgallery.core.search.noop.NoPeopleLookup
import dev.pampa.mycloudgallery.core.search.parse.ItalianQueryParser
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Fase 7 (P6, D14, D22, E-AL8; integrazione): lo STACK VERO degli album smart (P6: `RulesSmartEngine`, `RuleSmartNameInterpreter`, i candidati del catalogo
// vero `CatalogSmartCandidates`, le righe sulle tabelle v6 `RoomSmartRows`) sopra il catalogo VERO del mondo casuale, con la visibilita' vera
// (`CatalogVisibleMedia`, `CatalogHiddenMask`). Il mondo non ha ne' GPS ne' luoghi: [enrich] li scrive (in modo deterministico dall'indice) cosi' le regole di
// luogo hanno di che provare. Gli album si creano come li scriverebbe la proiezione dei dati personali: la riga `album` e le righe `album_rule`.

// Fase 8 (P10, D27, `albums-people`): con [people] il motore ha la regola delle persone (di serie nessuno: la regola non inserisce niente); con [leaky] la visibilita' e il
// filtro finale sono tolti (la prova al contrario: la sonda deve diventare rossa).

internal class RealSmartStack(val w: BuiltWorld, leaky: Boolean = false, people: PeopleMembership = NoPeopleMembership) {

  val visibility: VisibleMedia = if (leaky) EverythingVisible(MutableStateFlow(w.snapshot)) else CatalogVisibleMedia(w.db, MutableStateFlow(w.snapshot))
  val mask: HiddenMask = if (leaky) HiddenMask { emptySet() } else CatalogHiddenMask(w.db)
  val candidates = CatalogSmartCandidates(w.catalog)
  val rows = RoomSmartRows(w.db)
  val lookup = PlaceLookup { id -> if (id == OSLO_ID) PlaceName("Oslo", "Oslo", "Norvegia") else null }
  val engine = RulesSmartEngine(rows, candidates, visibility, mask, NoSmartContentSearch, DeviceNormalizer(null), lookup, FakeAlbumClocks(), people = people)

  /**
   * Il GPS di ogni media dal suo indice: due su cinque a Oslo (entro 6 km), uno a Bergen, uno qualunque lontano, uno senza GPS. I media di Oslo hanno il
   * luogo di Oslo (per il dizionario dei luoghi dell'editor). Va chiamato una volta, prima di creare gli album.
   */
  fun enrich() {
    val sql = w.db.openHelper.writableDatabase
    for ((index, id) in w.ids.withIndex()) {
      val (lat, lon, place) = when (index % 5) {
        0, 1 -> Triple(59.9139 + (index % 7) * 0.008, 10.7522, OSLO_ID)
        2 -> Triple(60.3913, 5.3221, 0)
        3 -> Triple(41.9028, 12.4964, 0)
        else -> Triple(null, null, 0)
      }
      if (lat == null) continue
      sql.execSQL("UPDATE version_metadata SET latitude = ?, longitude = ? WHERE version_id = (SELECT current_version_id FROM media WHERE media_id = ?)", arrayOf<Any?>(lat, lon, id.value))
      if (place != 0) sql.execSQL("UPDATE media SET place_id = ? WHERE media_id = ?", arrayOf<Any?>(place, id.value))
    }
  }

  /** Gli indici dei media con il GPS a Oslo, dopo [enrich]. */
  fun osloIndices(): List<Int> = w.ids.indices.filter { it % 5 == 0 || it % 5 == 1 }

  suspend fun createAlbum(n: Int, rules: SmartRules): AlbumId {
    val id = AlbumId(UUID(0x5_0000_0000L + n, n.toLong()).toString())
    w.db.albumDao().upsert(AlbumEntity(id.value, "Prova", null, 0, 0, kind = "smart"))
    val fields = SmartRulesText.encode(rules).fields
    val rows = fields.mapNotNull { (field, text) -> text?.let { AlbumRuleEntity(id.value, field.removePrefix("smart:"), 0, it, null) } }
    if (rows.isNotEmpty()) w.db.albumRuleDao().upsertAll(rows)
    return id
  }

  /** Cio' che il motore ha scritto per [id]: i sicuri e i suggeriti. */
  suspend fun written(id: AlbumId): Pair<Set<MediaId>, Set<MediaId>> =
    w.db.albumDao().items(id.value).filter { it.origin == "auto" }.mapTo(LinkedHashSet()) { MediaId(it.mediaId) } to
      w.db.albumChoiceDao().suggestions(id.value).mapTo(LinkedHashSet()) { MediaId(it.mediaId) }

  /** L'interprete del nome sopra un'istantanea che ha i luoghi di [enrich] (e, come la Libreria, i nascosti di prima dell'istantanea non li ha). */
  suspend fun interpreter(peopleLookup: PeopleLookup = NoPeopleLookup): RuleSmartNameInterpreter {
    val snapshot = CatalogTimelineSource(w.db, w.catalog).buildIndex()
    val shown: VisibleMedia = if (visibility is EverythingVisible) EverythingVisible(MutableStateFlow(snapshot)) else CatalogVisibleMedia(w.db, MutableStateFlow(snapshot))
    return RuleSmartNameInterpreter(ItalianQueryParser(), shown, mask, candidates, lookup, people = peopleLookup)
  }

  companion object {
    const val OSLO_ID: Int = 3_143_244
  }
}

/** Una visibilita' senza filtro: tutto e' visibile (la mutazione della prova `albums-people`). */
internal class EverythingVisible(override val snapshot: StateFlow<TimelineSnapshot?>) : VisibleMedia {
  override suspend fun visible(ids: Collection<MediaId>): Set<MediaId> = ids.toSet()
}
