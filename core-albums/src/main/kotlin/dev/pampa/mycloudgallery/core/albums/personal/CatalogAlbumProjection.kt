package dev.pampa.mycloudgallery.core.albums.personal

import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.database.entity.AlbumEntity
import dev.pampa.mycloudgallery.core.database.entity.AlbumItemEntity
import dev.pampa.mycloudgallery.core.database.transaction
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.settings.PersonalAlbumState
import dev.pampa.mycloudgallery.core.personal.settings.PersonalAlbums
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// LA PROIEZIONE DEGLI ALBUM PERSONALI SUL CATALOGO (docs/fase7-progetto.md D5, §2.1 "Sincronizzazione"; docs/modello-dati.md §14). Porta lo
// stato unito dei dati personali (le voci `ALBUM`, `PersonalAlbums`) nelle tabelle v6 `album`, `album_item`, `album_rule` e
// `album_exclusion`: e' cio' che la Libreria, le Raccolte e gli album smart leggono, con i nascosti e i conteggi gia' filtrati da SQL.
//
// **Derivata, mai una seconda fonte di verita'** (D5): le righe sono funzione dello stato e del catalogo di qui ([AlbumDerivation]), quindi
// si possono ricostruire in ogni momento ([rebuild]) e danno le stesse righe di una proiezione fatta un album per volta (test di proprieta').
// Ogni album si riscrive in **una transazione**, con la risoluzione dei media dentro: un'unione d'identita' non si infila fra la lettura e
// la scrittura. Mai un file toccato, mai la rete, mai una riga di un album condiviso (`state` non NULL).
//
// Quando chiamarla (la collega :app, P13): dopo ogni giro dei dati personali che ha applicato voci, dopo ogni modifica locale (lo fa
// `CatalogAlbums` da se': l'album si aggiorna subito, senza aspettare la sincronizzazione) e dopo ogni scansione con novita' o unione
// d'identita' per gli album che [affectedBy] indica (un elemento "in attesa" torna quando il suo media arriva).
//
// Le righe `auto` degli album smart sono del motore (P6): qui non si scrivono; si tolgono solo quelle di un'esclusione o di un album
// congelato, perche' lo stato le vieta. Nessun titolo, id o luogo nei log (D37): questa classe non scrive log.

/** Cosa ha fatto un giro. [pending] sono gli elementi vivi senza media in questo catalogo (`ALBUM_PROJECTION_PENDING`), solo numeri. */
data class AlbumProjectionReport(val albums: Int, val items: Int, val pending: Int, val removed: Int) {
  override fun toString(): String = "AlbumProjectionReport(albums=$albums, items=$items, pending=$pending, removed=$removed)"
}

class CatalogAlbumProjection(private val catalog: MediaCatalog, private val database: GalleryDatabase) {

  private val resolver = MediaResolver(catalog, database)
  private val lock = Mutex()
  private val pendingByAlbum = ConcurrentHashMap<String, Int>()
  private val expected = ConcurrentHashMap<String, Footprint>()
  private val pendingTotal = MutableStateFlow(0)

  /** Quanti elementi personali vivi aspettano il loro media dall'ultimo giro (il conteggio della Diagnostica). */
  val pending: StateFlow<Int> = pendingTotal

  /**
   * Porta nelle tabelle gli album di [only] (null = tutti i personali) come dice [entries], lo stato unito (con sopra le modifiche
   * ancora da datare: `PersonalStore.effective`). Un album di [only] senza voci, cancellato o mai definito perde le sue righe. Idempotente:
   * un secondo giro con lo stesso stato non cambia nessuna riga.
   */
  suspend fun project(entries: Map<PersonalKey, PersonalEntry>, only: Set<String>? = null): AlbumProjectionReport = lock.withLock {
    val states = PersonalAlbums.statesOf(entries, only)
    var albums = 0
    var items = 0
    var removed = 0
    for (state in states) {
      val written = projectOne(state)
      if (written == null) {
        removed++
      } else {
        albums++
        items += written
      }
    }
    // Chi non ha piu' voci (o non le ha mai avute): niente righe. Con `only` solo quelli chiesti, altrimenti ogni album personale che resta.
    val seen = states.mapTo(HashSet()) { it.id }
    val orphans = if (only != null) only - seen else database.albumDao().all().filter { it.state == null }.map { it.albumId }.toSet() - seen
    for (id in orphans) if (dropPersonal(id)) removed++
    if (only == null) {
      pendingByAlbum.keys.retainAll(seen)
      expected.keys.retainAll(seen)
    }
    pendingTotal.value = pendingByAlbum.values.sum()
    AlbumProjectionReport(albums, items, pendingTotal.value, removed)
  }

  /** Come [project] per tutti gli album personali: la ricostruzione dallo stato (all'avvio, dopo un azzeramento delle tabelle). */
  suspend fun rebuild(entries: Map<PersonalKey, PersonalEntry>): AlbumProjectionReport = project(entries, null)

  /**
   * Gli album personali da riproiettare dopo una scansione o un'unione d'identita' che hanno toccato [media]: quelli con elementi in
   * attesa (il media puo' essere arrivato) e quelli che hanno una riga che indica questi media (un elemento, la copertina, una regola
   * d'esempio, un'esclusione: due elementi diventati uno, un id assorbito). I media si leggono come il catalogo li ha adesso: un id
   * assorbito vale il sopravvissuto.
   */
  suspend fun affectedBy(media: Collection<MediaId>): Set<String> {
    val out = pendingByAlbum.filterValues { it > 0 }.keys.toMutableSet()
    out += drifted()
    val real = media.distinct().mapNotNull { catalog.resolve(it) }.distinct()
    if (real.isNotEmpty()) {
      withContext(Dispatchers.IO) {
        for (id in real) {
          database.openHelper.readableDatabase.query(REFERENCING_SQL, arrayOf<Any?>(id.value, id.value, id.value, id.value)).use { cursor ->
            while (cursor.moveToNext()) out += cursor.getString(0)
          }
        }
      }
    }
    return out
  }

  /**
   * Gli album le cui righe non sono piu' quelle che la proiezione ha scritto: un media sparito dal catalogo (le righe che lo indicano se ne
   * vanno in cascata, la copertina diventa vuota) o due elementi uniti in uno dall'unione. Lo stato non e' cambiato: l'album va solo
   * riproiettato, e l'elemento tornera' quando il suo media torna. Quattro letture a gruppi, nessuna riga letta una per una.
   */
  suspend fun drifted(): Set<String> {
    if (expected.isEmpty()) return emptySet()
    val items = HashMap<String, Int>()
    val exclusions = HashMap<String, Int>()
    val examples = HashMap<String, Int>()
    val covers = HashMap<String, String?>()
    withContext(Dispatchers.IO) {
      val sql = database.openHelper.readableDatabase
      fun counts(query: String, into: MutableMap<String, Int>) {
        sql.query(query).use { cursor -> while (cursor.moveToNext()) into[cursor.getString(0)] = cursor.getInt(1) }
      }
      counts("SELECT album_id, COUNT(*) FROM album_item WHERE origin IN ('manual', 'accepted') GROUP BY album_id", items)
      counts("SELECT album_id, COUNT(*) FROM album_exclusion GROUP BY album_id", exclusions)
      counts("SELECT album_id, COUNT(*) FROM album_rule WHERE rule = '${AlbumDerivation.EXAMPLE_RULE}' GROUP BY album_id", examples)
      sql.query("SELECT album_id, cover_media_id FROM album WHERE state IS NULL").use { cursor ->
        while (cursor.moveToNext()) covers[cursor.getString(0)] = if (cursor.isNull(1)) null else cursor.getString(1)
      }
    }
    return expected.filter { (id, want) ->
      Footprint(items[id] ?: 0, covers[id], exclusions[id] ?: 0, examples[id] ?: 0) != want
    }.keys
  }

  /** I conti per album degli elementi in attesa (solo numeri). */
  fun pendingOf(albumId: String): Int = pendingByAlbum[albumId] ?: 0

  /** Il numero di elementi scritti per l'album, o null se l'album non deve avere righe (e le ha perse). */
  private suspend fun projectOne(state: PersonalAlbumState): Int? = database.transaction {
    val existing = database.albumDao().get(state.id)
    // Un album condiviso con lo stesso id e' della replica (P2): non si tocca, ne' qui ne' sotto.
    if (existing?.state != null) return@transaction null
    val resolution = resolver.resolve(AlbumDerivation.refsOf(state))
    val derived = AlbumDerivation.derive(state, resolution)
    if (derived == null) {
      if (existing != null) database.albumDao().delete(state.id)
      pendingByAlbum.remove(state.id)
      expected.remove(state.id)
      return@transaction null
    }
    write(derived, existing)
    pendingByAlbum[state.id] = derived.pending
    expected[state.id] = Footprint(derived.items.size, derived.album.coverMediaId, derived.exclusions.size, derived.rules.count { it.rule == AlbumDerivation.EXAMPLE_RULE })
    derived.items.size
  }

  private suspend fun dropPersonal(id: String): Boolean = database.transaction {
    val existing = database.albumDao().get(id)
    if (existing == null || existing.state != null) {
      pendingByAlbum.remove(id)
      expected.remove(id)
      return@transaction false
    }
    database.albumDao().delete(id)
    pendingByAlbum.remove(id)
    expected.remove(id)
    true
  }

  private suspend fun write(derived: DerivedAlbum, existing: AlbumEntity?) {
    val dao = database.albumDao()
    val id = derived.album.albumId
    // Una riga uguale non si riscrive: chi osserva le tabelle (le Raccolte) non vede un cambiamento che non c'e' stato.
    if (existing != derived.album) dao.upsert(derived.album)

    // Gli elementi: quelli derivati dallo stato (`manual`, `accepted`) sono esattamente quelli voluti; le righe `auto` sono del motore e si
    // tolgono solo se lo stato le vieta (un'esclusione, o l'album congelato).
    val current = dao.items(id).associateBy { it.mediaId }
    val wanted = derived.items.associateBy { it.mediaId }
    for (row in current.values) {
      if (row.mediaId in wanted) continue
      val ours = row.origin == ORIGIN_MANUAL || row.origin == ORIGIN_ACCEPTED
      val forbidden = row.origin == ORIGIN_AUTO && (derived.dropsAuto || row.mediaId in derived.hiddenFromAuto)
      if (ours || forbidden) dao.removeItem(id, row.mediaId)
    }
    val changed: List<AlbumItemEntity> = wanted.values.filter { current[it.mediaId] != it }
    if (changed.isNotEmpty()) dao.upsertItems(changed)

    val rules = database.albumRuleDao()
    if (rules.rules(id).toSet() != derived.rules.toSet()) {
      rules.clear(id)
      if (derived.rules.isNotEmpty()) rules.upsertAll(derived.rules)
    }

    val choices = database.albumChoiceDao()
    val wantedExclusions = derived.exclusions.associateBy { it.mediaId }
    val currentExclusions = choices.exclusions(id).associateBy { it.mediaId }
    for (media in currentExclusions.keys) if (media !in wantedExclusions) choices.removeExclusion(id, media)
    val newExclusions = wantedExclusions.values.filter { currentExclusions[it.mediaId] != it }
    if (newExclusions.isNotEmpty()) choices.upsertExclusions(newExclusions)

    // Un suggerito diventato elemento, accettato o escluso non e' piu' un suggerito.
    val settled = wanted.keys + derived.hiddenFromAuto + wantedExclusions.keys
    for (suggestion in choices.suggestions(id)) if (suggestion.mediaId in settled) choices.removeSuggestion(id, suggestion.mediaId)
  }

  /** Quante righe derivate ha un album, e quale copertina: cio' che la proiezione ha scritto e che il catalogo non deve cambiare da solo. */
  private data class Footprint(val items: Int, val cover: String?, val exclusions: Int, val examples: Int)

  private companion object {
    /** Gli album che hanno una riga su un media: elementi, foto d'esempio, esclusioni e copertina (la scelta dell'utente, non il calcolo). */
    const val REFERENCING_SQL: String =
      "SELECT album_id FROM album_item WHERE media_id = ? UNION SELECT album_id FROM album_rule WHERE media_id = ? " +
        "UNION SELECT album_id FROM album_exclusion WHERE media_id = ? UNION SELECT album_id FROM album WHERE cover_media_id = ?"
    const val ORIGIN_MANUAL = "manual"
    const val ORIGIN_ACCEPTED = "accepted"
    const val ORIGIN_AUTO = "auto"
  }
}
