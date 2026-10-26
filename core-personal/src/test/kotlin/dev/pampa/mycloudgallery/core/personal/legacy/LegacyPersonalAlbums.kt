package dev.pampa.mycloudgallery.core.personal.legacy

import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKind
import dev.pampa.mycloudgallery.core.personal.contract.PersonalStore
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue

// FIXTURE DI PROVA, NON CODICE DELL'APP: gli album manuali com'erano nell'app della Fase 6 (commit 6346ec8c, solo i nomi cambiati). Leggono
// soltanto `definition`, `order`, `cover` e `item:`: tutto il resto di un album (i campi nuovi della Fase 7) per loro non esiste.

/**
 * Un album manuale come lo vede lo stato unito. [items] sono gli id dei media nell'ordine dell'album (posizione, poi id).
 * Una cancellazione e' la lapide della definizione: l'album sparisce da [LegacyPersonalAlbums.albums] ovunque, i suoi elementi restano
 * nello stato (se l'album verra' ricreato con lo stesso id, li ritrova).
 */
data class LegacyPersonalAlbum(
  val id: String,
  val title: String,
  /** Il posto dell'album nell'elenco degli album (piu' piccolo prima); null = non scelto. */
  val order: Long?,
  /** L'id del media di copertina, se scelto. */
  val cover: String?,
  val items: List<String>,
) {
  override fun toString(): String = "LegacyPersonalAlbum(${items.size} elementi)"
}

/** Gli album manuali. Le scritture passano da [store]; la lettura da qualunque istantanea dello stato. */
class LegacyPersonalAlbums(private val store: PersonalStore) {

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
  fun albums(entries: Map<PersonalKey, PersonalEntry> = store.entries.value): List<LegacyPersonalAlbum> {
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
      LegacyPersonalAlbum(
        id = id,
        title = title,
        order = (fields[PersonalFields.ALBUM_ORDER]?.value as? PersonalValue.Number)?.value,
        cover = (fields[PersonalFields.ALBUM_COVER]?.value as? PersonalValue.Text)?.text,
        items = items.sortedWith(compareBy({ it.second }, { it.first })).map { it.first },
      )
    }.sortedWith(compareBy<LegacyPersonalAlbum> { it.order ?: Long.MAX_VALUE }.thenBy { it.id })
  }

  private fun key(albumId: String, field: String) = PersonalKey(PersonalKind.ALBUM, albumId, field)
}

