package dev.pampa.mycloudgallery.core.albums.personal

import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKind
import dev.pampa.mycloudgallery.core.personal.contract.PersonalStore
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import dev.pampa.mycloudgallery.core.personal.settings.PersonalAlbums

// LE COPIE PERSONALI DI Q5 (b), DAL LATO DEI DATI PERSONALI (docs/fase7-progetto.md D31 adeguata a Q5, `SharedAlbumCopies`): quando chi ha
// chiuso un album condiviso ha scelto "ognuno si tiene una copia", ogni familiare (e il creatore dopo "Smetti di condividere", se l'album non era
// nato personale) ha un album **personale** con le stesse foto: voci `ALBUM` nell'area privata, con l'id deterministico per account
// (`SharedAlbumCopies.copyIdFor`) e la voce `copy_of`. Chi legge la chiusura (P2/P4) chiama [keep] con gli elementi che il **suo** catalogo
// attesta nell'area comune (D9): qui non si guarda il NAS e non si muove niente.
//
// - **Idempotente**: due dispositivi dello stesso account che leggono la stessa chiusura scrivono le stesse voci (stesso id, stessi
//   elementi), nessun doppione; un secondo giro non riscrive nulla.
// - **Una copia eliminata non si ricrea**: se la definizione ha gia' la lapide (l'utente ha cancellato la copia) non si scrive piu' niente.
//   Lo stesso per un titolo cambiato e un elemento tolto dalla copia: non tornano a ogni lettura della chiusura.
// - Resta una finestra: se la lapide e' di un altro dispositivo e non e' ancora arrivata, la copia si riscrive. Vale come ogni modifica
//   concorrente dei dati personali (l'ultima scrittura vince).

class PersonalAlbumCopies(private val store: PersonalStore, private val catalog: MediaCatalog) {

  enum class Result {
    /** La copia c'e' adesso (nuova per questo account). */
    CREATED,

    /** La copia c'era: si sono aggiunti elementi che mancavano. */
    UPDATED,

    /** La copia c'era e ha gia' tutto. */
    UNCHANGED,

    /** L'utente l'ha eliminata: non si ricrea. */
    DELETED_BY_USER,
  }

  private val albums = PersonalAlbums(store)

  /** Tiene la copia [copy] dell'album condiviso [shared], con [title] e gli elementi [items] (nell'ordine dell'album condiviso). */
  suspend fun keep(copy: AlbumId, shared: AlbumId, title: String, items: List<MediaId>): Result {
    val entries = store.effective()
    val definition = albums.definition(copy.value, entries)
    if (definition != null && definition.value !is PersonalValue.Text) return Result.DELETED_BY_USER
    val state = albums.state(copy.value, entries)
    val known = state?.items?.mapTo(HashSet()) { it.mediaId }.orEmpty()
    val fresh = items.distinct().mapNotNull { catalog.resolve(it) }.distinct().filter { it.value !in known }
    // I nuovi vengono dopo gli esistenti, nell'ordine dell'album condiviso (a intervalli, D21).
    val existing = state?.items?.filter { it.live }?.map { PositionedItem(it.mediaId, it.position!!) }.orEmpty()
    val plan = AlbumPositions.append(existing, fresh.map { it.value })
    for (change in plan.changes) albums.addItem(copy.value, change.media, change.position)
    if (definition == null) {
      albums.setCopyOf(copy.value, shared.value)
      albums.define(copy.value, title)
      return Result.CREATED
    }
    return if (plan.changes.isEmpty()) Result.UNCHANGED else Result.UPDATED
  }

  /**
   * R1 della revisione di sicurezza della Fase 7: ritira la copia [copy] dell'album condiviso [shared], perche' il dispositivo che ha firmato la
   * chiusura che l'ha fatta nascere e' stato revocato ("la revoca toglie cio' che il dispositivo revocato ha causato"). Come "Elimina album" di
   * un album personale: la definizione diventa una lapide (sincronizzata; nessun file si tocca), e la copia non si ricrea. Niente se la copia non
   * c'e', e' gia' eliminata o non e' una copia di [shared]. Vero se l'ha ritirata.
   */
  suspend fun withdraw(copy: AlbumId, shared: AlbumId): Boolean {
    val entries = store.effective()
    val definition = albums.definition(copy.value, entries) ?: return false
    if (definition.value !is PersonalValue.Text) return false
    val copyOf = entries[PersonalKey(PersonalKind.ALBUM, copy.value, PersonalFields.ALBUM_COPY_OF)]?.value as? PersonalValue.Text
    if (copyOf?.text != shared.value) return false
    albums.delete(copy.value)
    return true
  }
}
