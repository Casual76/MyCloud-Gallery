package dev.pampa.mycloudgallery.core.personal.settings

import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKind
import dev.pampa.mycloudgallery.core.personal.contract.PersonalStore
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue

// Album manuali e ricordi visti (§2.9 "Cosa"): voci dello stesso stato unito dei preferiti. Non hanno ancora una schermata ne' una
// tabella che le segua (gli album arrivano con la Fase 7, i ricordi con la 9): qui c'e' il modello letto dallo stato e le scritture,
// cosi' chi li usera' trova gia' la sincronizzazione fatta e provata.

/**
 * Un album manuale come lo vede lo stato unito. [items] sono gli id dei media nell'ordine dell'album (posizione, poi id).
 * Una cancellazione e' la lapide della definizione: l'album sparisce da [PersonalAlbums.albums] ovunque, i suoi elementi restano
 * nello stato (se l'album verra' ricreato con lo stesso id, li ritrova).
 */
data class PersonalAlbum(
  val id: String,
  val title: String,
  /** Il posto dell'album nell'elenco degli album (piu' piccolo prima); null = non scelto. */
  val order: Long?,
  /** L'id del media di copertina, se scelto. */
  val cover: String?,
  val items: List<String>,
) {
  override fun toString(): String = "PersonalAlbum(${items.size} elementi)"
}

/** Gli album manuali. Le scritture passano da [store]; la lettura da qualunque istantanea dello stato. */
class PersonalAlbums(private val store: PersonalStore) {

  suspend fun define(albumId: String, title: String) = store.set(key(albumId, PersonalFields.ALBUM_DEFINITION), PersonalValue.Text(title))

  suspend fun delete(albumId: String) = store.set(key(albumId, PersonalFields.ALBUM_DEFINITION), PersonalValue.Removed)

  suspend fun setOrder(albumId: String, order: Long) = store.set(key(albumId, PersonalFields.ALBUM_ORDER), PersonalValue.Number(order))

  suspend fun setCover(albumId: String, mediaId: String?) =
    store.set(key(albumId, PersonalFields.ALBUM_COVER), if (mediaId == null) PersonalValue.Removed else PersonalValue.Text(mediaId))

  /** [position] e' il posto del media nell'album. */
  suspend fun addItem(albumId: String, mediaId: String, position: Long) =
    store.set(key(albumId, PersonalFields.albumItem(mediaId)), PersonalValue.Number(position))

  suspend fun removeItem(albumId: String, mediaId: String) = store.set(key(albumId, PersonalFields.albumItem(mediaId)), PersonalValue.Removed)

  /** Gli album di [entries] (lo stato unito), per ordine e poi per id. Un album senza definizione, o cancellato, non c'e'. */
  fun albums(entries: Map<PersonalKey, PersonalEntry> = store.entries.value): List<PersonalAlbum> {
    val byAlbum = entries.values.filter { it.key.kind == PersonalKind.ALBUM }.groupBy { it.key.subject }
    return byAlbum.mapNotNull { (id, rows) ->
      val fields = rows.associateBy { it.key.field }
      val title = (fields[PersonalFields.ALBUM_DEFINITION]?.value as? PersonalValue.Text)?.text ?: return@mapNotNull null
      val items = rows.mapNotNull { row ->
        val media = PersonalFields.albumItemMedia(row.key.field) ?: return@mapNotNull null
        when (val value = row.value) {
          is PersonalValue.Number -> media to value.value
          is PersonalValue.Flag -> if (value.on) media to Long.MAX_VALUE else null
          else -> null
        }
      }
      PersonalAlbum(
        id = id,
        title = title,
        order = (fields[PersonalFields.ALBUM_ORDER]?.value as? PersonalValue.Number)?.value,
        cover = (fields[PersonalFields.ALBUM_COVER]?.value as? PersonalValue.Text)?.text,
        items = items.sortedWith(compareBy({ it.second }, { it.first })).map { it.first },
      )
    }.sortedWith(compareBy<PersonalAlbum> { it.order ?: Long.MAX_VALUE }.thenBy { it.id })
  }

  private fun key(albumId: String, field: String) = PersonalKey(PersonalKind.ALBUM, albumId, field)
}

/** Le date di ultima visione dei ricordi (campo pronto per la Fase 9): un ricordo visto su un dispositivo risulta visto su tutti. */
class PersonalMemories(private val store: PersonalStore) {

  /** [seenAtNas] e' l'ora del NAS in cui l'utente l'ha visto. */
  suspend fun markSeen(memoryId: String, seenAtNas: Long) = store.set(key(memoryId), PersonalValue.Number(seenAtNas))

  fun lastSeen(memoryId: String, entries: Map<PersonalKey, PersonalEntry> = store.entries.value): Long? =
    (entries[key(memoryId)]?.value as? PersonalValue.Number)?.value

  private fun key(memoryId: String) = PersonalKey(PersonalKind.MEMORY, memoryId, PersonalFields.MEMORY_SEEN)
}
