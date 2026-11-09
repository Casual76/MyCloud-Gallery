package dev.pampa.mycloudgallery.core.albums.share

import dev.pampa.mycloudgallery.core.albums.shared.SharedAlbumCopy
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.AlbumKind
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.personal.contract.PersonalChange
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKind
import dev.pampa.mycloudgallery.core.personal.contract.PersonalStore
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue

/** Una copia personale: mai scritta, viva, o eliminata dall'utente (lapide della definizione: non si ricrea). */
enum class KeptCopyState { NONE, LIVE, DELETED }

/**
 * Dove si scrivono le copie personali degli album chiusi (risposta Q5 del 22/10, D31): dati personali dell'account, area privata (P3). Ogni metodo
 * e' idempotente. "Conosciuto" vuol dire che c'e' **o c'e' stato** (una lapide: l'utente l'ha eliminato): una copia eliminata non si ricrea, un
 * elemento tolto dall'utente non si rimette.
 */
interface KeptCopyStore {
  suspend fun copyState(copy: AlbumId): KeptCopyState

  suspend fun defineCopy(copy: AlbumId, title: String, copyOf: AlbumId)

  suspend fun itemKnown(copy: AlbumId, media: MediaId): Boolean

  suspend fun addItem(copy: AlbumId, media: MediaId, position: Long)

  /**
   * Piu' elementi insieme (il media e la sua posizione), in un salvataggio solo: una copia puo' avere migliaia di elementi (review generale, OA-1).
   * Di serie uno alla volta con [addItem].
   */
  suspend fun addItems(copy: AlbumId, items: List<Pair<MediaId, Long>>) {
    for ((media, position) in items) addItem(copy, media, position)
  }

  /** L'album personale da cui e' nato l'album condiviso [shared] (voce `shared`, D4): per il creatore e' lui l'album dopo "Smetti". */
  suspend fun personalSharedAs(shared: AlbumId): AlbumId?
}

/**
 * Le copie sui dati personali di core-personal ([PersonalStore]): voci `ALBUM` con `definition` (il titolo), `kind` manuale, `copy_of` (l'album
 * condiviso) ed `item:<media>` con la posizione. L'impronta degli elementi la aggiunge P3 quando consegna `PersonalAlbums.addItem` con `fp`
 * (D4): fino ad allora gli elementi si ritrovano per id (sono i media del mio catalogo, attestati: D9).
 */
class PersonalKeptCopyStore(private val store: PersonalStore) : KeptCopyStore {
  private fun key(album: AlbumId, field: String) = PersonalKey(PersonalKind.ALBUM, album.value, field)

  override suspend fun copyState(copy: AlbumId): KeptCopyState = when (store.get(key(copy, PersonalFields.ALBUM_DEFINITION))?.value) {
    null -> KeptCopyState.NONE
    PersonalValue.Removed -> KeptCopyState.DELETED
    else -> KeptCopyState.LIVE
  }

  override suspend fun defineCopy(copy: AlbumId, title: String, copyOf: AlbumId) {
    // Le tre voci insieme (un salvataggio, tutto o niente), la definizione per ultima: finche' non c'e', l'album non si vede (e una ripresa
    // rifa' tutto da capo, idempotente).
    store.setAll(
      listOf(
        PersonalChange(key(copy, PersonalFields.ALBUM_KIND), PersonalValue.Text(AlbumKind.MANUAL.code)),
        PersonalChange(key(copy, PersonalFields.ALBUM_COPY_OF), PersonalValue.Text(copyOf.value)),
        PersonalChange(key(copy, PersonalFields.ALBUM_DEFINITION), PersonalValue.Text(title)),
      ),
    )
  }

  override suspend fun itemKnown(copy: AlbumId, media: MediaId): Boolean = store.get(key(copy, PersonalFields.albumItem(media.value))) != null

  override suspend fun addItem(copy: AlbumId, media: MediaId, position: Long) =
    store.set(key(copy, PersonalFields.albumItem(media.value)), PersonalValue.Number(position))

  /** OA-1: gli elementi di una copia in un lotto solo. Una voce alla volta riscriveva il registro intero per ognuno dei (fino a 20.000) elementi. */
  override suspend fun addItems(copy: AlbumId, items: List<Pair<MediaId, Long>>) =
    store.setAll(items.map { (media, position) -> PersonalChange(key(copy, PersonalFields.albumItem(media.value)), PersonalValue.Number(position)) })

  override suspend fun personalSharedAs(shared: AlbumId): AlbumId? = store.entries.value.values.firstOrNull { entry ->
    entry.key.kind == PersonalKind.ALBUM && entry.key.field == PersonalFields.ALBUM_SHARED && (entry.value as? PersonalValue.Text)?.text == shared.value
  }?.key?.subject?.let(::AlbumId)
}

/**
 * Q5 (22/10), D31 adeguata: **chi chiude sceglie** `REMOVE` o `KEEP_COPY`, e la scelta viaggia firmata nella definizione. Questo scrive, nei dati
 * personali, le copie che la replica dice di tenere (`ReplicaSharedAlbums.keptCopies`): per gli altri con `KEEP_COPY` (finche' non avevano lasciato
 * l'album) e per il creatore dopo "Smetti di condividere" (l'album resta suo; se era nato da un suo album personale, resta quello).
 *
 * **Nessun file si muove, mai**: ne' con `REMOVE` ne' con `KEEP_COPY` (i file restano nell'area comune; "Riporta nelle mie cartelle" e' un piano a
 * parte, solo per i propri media). Una copia eliminata dall'utente non si ricrea; un elemento tolto non si rimette; gli elementi sono quelli che il
 * **mio** catalogo attesta (D9) e che vedo (D22). Idempotente: si chiama dopo ogni giro della replica.
 */
class ClosedAlbumCopies(
  private val copies: suspend () -> List<SharedAlbumCopy>,
  private val store: KeptCopyStore,
) {
  /** Scrive le copie che mancano; restituisce quante voci ha scritto (per i test e la Diagnostica). */
  suspend fun sync(): Int {
    var written = 0
    for (copy in copies()) {
      if (copy.mine && store.personalSharedAs(copy.shared) != null) continue
      val target = copy.copy
      when (store.copyState(target)) {
        KeptCopyState.DELETED -> continue
        KeptCopyState.NONE -> {
          store.defineCopy(target, copy.title, copy.shared)
          written++
        }
        KeptCopyState.LIVE -> Unit
      }
      // Gli elementi che mancano, tutti insieme: un salvataggio dei dati personali per copia, non uno per elemento (review generale, OA-1).
      val missing = ArrayList<Pair<MediaId, Long>>()
      val listed = HashSet<MediaId>()
      copy.items.forEachIndexed { index, media ->
        // Un media ripetuto nell'elenco vale la prima volta (come quando si scriveva una voce alla volta: la seconda lo trovava gia').
        if (listed.add(media) && !store.itemKnown(target, media)) missing += media to (index + 1L) * ShareRules.POSITION_STEP
      }
      if (missing.isNotEmpty()) {
        store.addItems(target, missing)
        written += missing.size
      }
    }
    return written
  }
}
