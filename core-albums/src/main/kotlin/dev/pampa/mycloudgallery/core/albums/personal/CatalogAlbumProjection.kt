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
//
// R19 della revisione di sicurezza della Fase 7: il lavoro che i dati personali di un altro dispositivo dello stesso account impongono qui ha
// dei tetti. Al piu' `MAX_ALBUMS` album personali e `MAX_REFS_PER_ALBUM` riferimenti per album si proiettano (gli altri si contano in
// [overCap]); le impronte si cercano a blocchi ([MediaResolver]); un album con elementi "in attesa" si riproietta dopo una scansione solo se i
// media cambiati possono risolvere uno dei suoi riferimenti (per id o per impronta), non a ogni cambiamento del catalogo.

/** Cosa ha fatto un giro. [pending] sono gli elementi vivi senza media in questo catalogo (`ALBUM_PROJECTION_PENDING`), solo numeri. */
data class AlbumProjectionReport(val albums: Int, val items: Int, val pending: Int, val removed: Int) {
  override fun toString(): String = "AlbumProjectionReport(albums=$albums, items=$items, pending=$pending, removed=$removed)"
}

class CatalogAlbumProjection(
  private val catalog: MediaCatalog,
  private val database: GalleryDatabase,
  /** R19: gli album personali proiettati al piu'. */
  private val maxAlbums: Int = MAX_ALBUMS,
  /** R19: i riferimenti di un album risolti al piu'. */
  private val maxRefsPerAlbum: Int = MAX_REFS_PER_ALBUM,
) {

  private val resolver = MediaResolver(catalog, database)
  private val lock = Mutex()
  private val pendingByAlbum = ConcurrentHashMap<String, Int>()

  /** R19: i riferimenti non risolti di ogni album (id e impronte): quali media cambiati li possono risolvere. */
  private val unresolvedByAlbum = ConcurrentHashMap<String, UnresolvedRefs>()

  /** R19: album e riferimenti lasciati fuori dai tetti nell'ultimo giro (Diagnostica: solo numeri). */
  @Volatile var overCap: Int = 0
    private set
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
    val all = PersonalAlbums.statesOf(entries, only)
    // R19: al piu' MAX_ALBUMS album personali (in ordine di id: sempre gli stessi); gli altri non si proiettano e si contano.
    val states = if (all.size <= maxAlbums) all else all.sortedBy { it.id }.take(maxAlbums)
    overCap = all.size - states.size
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
      unresolvedByAlbum.keys.retainAll(seen)
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
    val out = HashSet<String>()
    // R19: un album in attesa torna solo se i media cambiati possono risolvere un suo riferimento (per id o per impronta).
    if (unresolvedByAlbum.isNotEmpty() && media.isNotEmpty()) {
      val ids = media.mapTo(HashSet()) { it.value }
      val fingerprints = fingerprintsOf(media)
      for ((album, refs) in unresolvedByAlbum) if (refs.ids.any { it in ids } || refs.fingerprints.any { it in fingerprints }) out += album
    }
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

  /** R19: le impronte (dimensione e `qh1`) delle versioni dei media [media], a blocchi. */
  private suspend fun fingerprintsOf(media: Collection<MediaId>): Set<Pair<Long, String>> = withContext(Dispatchers.IO) {
    val out = HashSet<Pair<Long, String>>()
    for (chunk in media.map { it.value }.distinct().chunked(MAX_VARIABLES)) {
      val sql = "SELECT size, quick_hash FROM media_version WHERE media_id IN (${chunk.joinToString(",") { "?" }})"
      database.openHelper.readableDatabase.query(sql, chunk.toTypedArray<Any?>()).use { cursor ->
        while (cursor.moveToNext()) out += cursor.getLong(0) to cursor.getString(1)
      }
    }
    out
  }

  /** Il numero di elementi scritti per l'album, o null se l'album non deve avere righe (e le ha perse). */
  private suspend fun projectOne(state: PersonalAlbumState): Int? = database.transaction {
    val existing = database.albumDao().get(state.id)
    // Un album condiviso con lo stesso id e' della replica (P2): non si tocca, ne' qui ne' sotto.
    if (existing?.state != null) return@transaction null
    // R19: al piu' MAX_REFS_PER_ALBUM riferimenti si risolvono (in ordine: sempre gli stessi); gli altri restano senza media e si contano.
    val refs = AlbumDerivation.refsOf(state)
    val bounded = if (refs.size <= maxRefsPerAlbum) refs else refs.sortedBy { it.subject }.take(maxRefsPerAlbum).toSet()
    if (bounded.size < refs.size) overCap += refs.size - bounded.size
    val resolution = resolver.resolve(bounded)
    val derived = AlbumDerivation.derive(state, resolution)
    if (derived == null) {
      if (existing != null) database.albumDao().delete(state.id)
      pendingByAlbum.remove(state.id)
      unresolvedByAlbum.remove(state.id)
      expected.remove(state.id)
      return@transaction null
    }
    write(derived, existing)
    val unresolved = bounded.filter { resolution.of(it.subject, it.fingerprint) == null }
    if (unresolved.isEmpty()) {
      unresolvedByAlbum.remove(state.id)
    } else {
      unresolvedByAlbum[state.id] = UnresolvedRefs(
        unresolved.mapTo(HashSet()) { it.subject }, unresolved.mapNotNullTo(HashSet()) { ref -> ref.fingerprint?.let { it.size to it.quickHash.hex } },
      )
    }
    pendingByAlbum[state.id] = derived.pending
    expected[state.id] = Footprint(derived.items.size, derived.album.coverMediaId, derived.exclusions.size, derived.rules.count { it.rule == AlbumDerivation.EXAMPLE_RULE })
    derived.items.size
  }

  private suspend fun dropPersonal(id: String): Boolean = database.transaction {
    val existing = database.albumDao().get(id)
    if (existing == null || existing.state != null) {
      pendingByAlbum.remove(id)
      unresolvedByAlbum.remove(id)
      expected.remove(id)
      return@transaction false
    }
    database.albumDao().delete(id)
    pendingByAlbum.remove(id)
    unresolvedByAlbum.remove(id)
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

  /** R19: i riferimenti non risolti di un album: gli id e le impronte (dimensione, `qh1` esadecimale) che un media nuovo puo' risolvere. */
  private class UnresolvedRefs(val ids: Set<String>, val fingerprints: Set<Pair<Long, String>>)

  internal companion object {
    /** R19: gli album personali proiettati al piu' (molti piu' di quanti una persona ne faccia). */
    const val MAX_ALBUMS: Int = 5_000

    /** R19: i riferimenti di un album risolti al piu' (come le voci di un contributo d'album condiviso). */
    const val MAX_REFS_PER_ALBUM: Int = 20_000

    private const val MAX_VARIABLES = 500

    /** Gli album che hanno una riga su un media: elementi, foto d'esempio, esclusioni e copertina (la scelta dell'utente, non il calcolo). */
    private const val REFERENCING_SQL: String =
      "SELECT album_id FROM album_item WHERE media_id = ? UNION SELECT album_id FROM album_rule WHERE media_id = ? " +
        "UNION SELECT album_id FROM album_exclusion WHERE media_id = ? UNION SELECT album_id FROM album WHERE cover_media_id = ?"
    private const val ORIGIN_MANUAL = "manual"
    private const val ORIGIN_ACCEPTED = "accepted"
    private const val ORIGIN_AUTO = "auto"
  }
}
