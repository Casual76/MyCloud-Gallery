package dev.pampa.mycloudgallery.core.personal.settings

import dev.pampa.mycloudgallery.core.model.AlbumKind
import dev.pampa.mycloudgallery.core.model.AlbumSort
import dev.pampa.mycloudgallery.core.personal.contract.MemoriesRecords
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFingerprint
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKind
import dev.pampa.mycloudgallery.core.personal.contract.PersonalStore
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue

// Album e ricordi visti (§2.9 "Cosa"): voci dello stesso stato unito dei preferiti. Fase 7, P3 (docs/fase7-progetto.md D3-D5, D21, §2.1):
// gli album personali completi. Qui il modello letto dallo stato e le scritture, con i campi nuovi di D4 (tipo, ordine dentro l'album,
// regole degli smart, esclusioni, accettazioni, congelamento, album condiviso nato da questo, copia personale) e **l'impronta** su ogni
// voce che nomina un media (`item:`, `exclude:`, `accept:`, `smart:example:`, `cover`): serve a ritrovare il media su un dispositivo dove
// l'id e' un altro (prima della convergenza di F4-D7, E-I18). La proiezione sul catalogo (le tabelle v6) e' di core-albums.

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
  /** Fase 7: `manual` (assente) o `smart`; il codice letto, anche se questa versione non lo conosce. */
  val kind: String? = null,
  val sort: String? = null,
  val frozen: Boolean = false,
) {
  override fun toString(): String = "PersonalAlbum(${items.size} elementi)"
}

/** Un media nominato da una voce: l'id come l'ha scritto chi l'ha scritta (puo' non essere di questo dispositivo) e la sua impronta. */
data class PersonalMediaRef(val mediaId: String, val fingerprint: PersonalFingerprint?) {
  /** Mai l'id di un media dell'utente nei log. */
  override fun toString(): String = "PersonalMediaRef"
}

/** Una voce `item:`, viva o lapide. [position] null = non e' (piu') un elemento. */
data class PersonalItem(
  val mediaId: String,
  val position: Long?,
  val fingerprint: PersonalFingerprint?,
  val atNas: Long,
  val author: String,
) {
  val live: Boolean get() = position != null

  override fun toString(): String = "PersonalItem(live=$live)"
}

/** Una voce `exclude:`, `accept:` o `smart:example:`. [on] = la scelta c'e' (flag acceso); spenta con la lapide o con il flag spento. */
data class PersonalChoice(
  val mediaId: String,
  val on: Boolean,
  val fingerprint: PersonalFingerprint?,
  val atNas: Long,
  val author: String,
) {
  override fun toString(): String = "PersonalChoice(on=$on)"
}

/**
 * Tutto cio' che lo stato unito sa di un album (anche cancellato o mai definito: [title] e' null), campo per campo. E' la lettura che la
 * proiezione sul catalogo usa; [PersonalAlbum] ne e' la sintesi per chi vuole solo l'elenco. Le voci portano l'ora del NAS: una modifica
 * ancora "da datare" ha [Long.MAX_VALUE] (vince su cio' che si sa, [PersonalStore.effective]); [lastAtNas] e [firstAtNas] sono dei soli
 * istanti datati, [hasUndated] dice se ce n'e' di non datati.
 */
data class PersonalAlbumState(
  val id: String,
  /** Il titolo della definizione viva, o null (cancellato, o voci senza definizione). */
  val title: String?,
  val kind: String?,
  val sort: String?,
  val frozen: Boolean,
  val order: Long?,
  val cover: PersonalMediaRef?,
  val sharedId: String?,
  val copyOf: String?,
  /** I testi delle regole degli smart per campo (`smart:period`, ...), solo non vuoti. */
  val smart: Map<String, String>,
  val items: List<PersonalItem>,
  val excluded: List<PersonalChoice>,
  val accepted: List<PersonalChoice>,
  val examples: List<PersonalChoice>,
  val firstAtNas: Long,
  val lastAtNas: Long,
  val hasUndated: Boolean,
) {
  val defined: Boolean get() = title != null

  override fun toString(): String = "PersonalAlbumState(${items.size} elementi)"
}

/** Gli album. Le scritture passano da [store]; la lettura da qualunque istantanea dello stato. */
class PersonalAlbums(private val store: PersonalStore) {

  suspend fun define(albumId: String, title: String) = store.set(key(albumId, PersonalFields.ALBUM_DEFINITION), PersonalValue.Text(title))

  suspend fun delete(albumId: String) = store.set(key(albumId, PersonalFields.ALBUM_DEFINITION), PersonalValue.Removed)

  suspend fun setOrder(albumId: String, order: Long) = store.set(key(albumId, PersonalFields.ALBUM_ORDER), PersonalValue.Number(order))

  /** La copertina scelta; `null` la toglie (automatica). L'impronta e' quella del catalogo se non si passa. */
  suspend fun setCover(albumId: String, mediaId: String?, fingerprint: PersonalFingerprint? = null) {
    if (mediaId == null) {
      store.set(key(albumId, PersonalFields.ALBUM_COVER), PersonalValue.Removed)
    } else {
      store.set(key(albumId, PersonalFields.ALBUM_COVER), PersonalValue.Text(mediaId), fingerprint ?: store.fingerprintOfMedia(mediaId))
    }
  }

  /**
   * [position] e' il posto del media nell'album. L'impronta (D4) e' [fingerprint] o, se non si passa, quella che il catalogo di questo
   * dispositivo ha del media (`PersonalStore.fingerprintOfMedia`); senza catalogo la voce resta senza impronta, com'era nella Fase 4.
   */
  suspend fun addItem(albumId: String, mediaId: String, position: Long, fingerprint: PersonalFingerprint? = null) =
    store.set(key(albumId, PersonalFields.albumItem(mediaId)), PersonalValue.Number(position), fingerprint ?: store.fingerprintOfMedia(mediaId))

  suspend fun removeItem(albumId: String, mediaId: String, fingerprint: PersonalFingerprint? = null) =
    store.set(key(albumId, PersonalFields.albumItem(mediaId)), PersonalValue.Removed, fingerprint ?: store.fingerprintOfMedia(mediaId))

  // --- Fase 7, P3: i campi nuovi di D4 ---------------------------------------------------------------------------------------------

  /** `manual` e' il predefinito (voce assente): per un album manuale non si scrive niente, come nella Fase 4. */
  suspend fun setKind(albumId: String, kind: AlbumKind) = store.set(key(albumId, PersonalFields.ALBUM_KIND), PersonalValue.Text(kind.code))

  /** L'ordine dentro l'album; `null` = il predefinito del tipo (D21). */
  suspend fun setSort(albumId: String, sort: AlbumSort?) =
    store.set(key(albumId, PersonalFields.ALBUM_SORT), if (sort == null) PersonalValue.Removed else PersonalValue.Text(sort.code))

  suspend fun setFrozen(albumId: String, frozen: Boolean) = store.set(key(albumId, PersonalFields.ALBUM_FROZEN), PersonalValue.Flag(frozen))

  /** L'album condiviso nato da questo album personale (D4, D17); `null` toglie il legame. */
  suspend fun setShared(albumId: String, sharedId: String?) = setText(albumId, PersonalFields.ALBUM_SHARED, sharedId)

  /** L'album condiviso di cui questo e' la copia personale (Q5 b); `null` toglie il legame. */
  suspend fun setCopyOf(albumId: String, sharedId: String?) = setText(albumId, PersonalFields.ALBUM_COPY_OF, sharedId)

  /** Una regola intera di un album smart: [field] e' uno di [PersonalFields.SMART_RULE_FIELDS]; `null` la toglie. */
  suspend fun setSmart(albumId: String, field: String, text: String?) {
    require(field in PersonalFields.SMART_RULE_FIELDS) { "Non e' un campo delle regole." }
    setText(albumId, field, text)
  }

  /** Una foto d'esempio di un album smart (al piu' 6, D13: lo fa rispettare chi scrive). */
  suspend fun setExample(albumId: String, mediaId: String, on: Boolean, fingerprint: PersonalFingerprint? = null) =
    setChoice(albumId, PersonalFields.smartExample(mediaId), mediaId, on, fingerprint)

  /** "Togli dall'album" su un elemento automatico: per sempre (E-AL5). [on] falso toglie l'esclusione. */
  suspend fun exclude(albumId: String, mediaId: String, on: Boolean = true, fingerprint: PersonalFingerprint? = null) =
    setChoice(albumId, PersonalFields.albumExclude(mediaId), mediaId, on, fingerprint)

  /** "Accetta" su un suggerito (D14). [on] falso toglie l'accettazione. */
  suspend fun accept(albumId: String, mediaId: String, on: Boolean = true, fingerprint: PersonalFingerprint? = null) =
    setChoice(albumId, PersonalFields.albumAccept(mediaId), mediaId, on, fingerprint)

  private suspend fun setText(albumId: String, field: String, text: String?) =
    store.set(key(albumId, field), if (text == null) PersonalValue.Removed else PersonalValue.Text(text))

  /** Accende la scelta, o la spegne con la lapide solo se c'e' (niente lapidi per voci mai scritte). */
  private suspend fun setChoice(albumId: String, field: String, mediaId: String, on: Boolean, fingerprint: PersonalFingerprint?) {
    val key = key(albumId, field)
    if (on) {
      store.set(key, PersonalValue.Flag(true), fingerprint ?: store.fingerprintOfMedia(mediaId))
    } else if ((store.effective()[key]?.value as? PersonalValue.Flag)?.on == true) {
      store.set(key, PersonalValue.Removed, fingerprint ?: store.fingerprintOfMedia(mediaId))
    }
  }

  /** La voce della definizione di [albumId] (viva o lapide) o null se non e' mai stata scritta: la copia eliminata non si ricrea (Q5). */
  fun definition(albumId: String, entries: Map<PersonalKey, PersonalEntry> = store.entries.value): PersonalEntry? =
    entries[key(albumId, PersonalFields.ALBUM_DEFINITION)]

  /** Gli album di [entries] (lo stato unito), per ordine e poi per id. Un album senza definizione, o cancellato, non c'e'. */
  fun albums(entries: Map<PersonalKey, PersonalEntry> = store.entries.value): List<PersonalAlbum> =
    states(entries).mapNotNull { state ->
      val title = state.title ?: return@mapNotNull null
      PersonalAlbum(
        id = state.id,
        title = title,
        order = state.order,
        cover = state.cover?.mediaId,
        items = state.items.filter { it.live }.sortedWith(compareBy({ it.position }, { it.mediaId })).map { it.mediaId },
        kind = state.kind,
        sort = state.sort,
        frozen = state.frozen,
      )
    }.sortedWith(compareBy<PersonalAlbum> { it.order ?: Long.MAX_VALUE }.thenBy { it.id })

  /** Lo stato di ogni album che compare in [entries], anche cancellato o senza definizione ([PersonalAlbumState.title] null). */
  fun states(entries: Map<PersonalKey, PersonalEntry> = store.entries.value): List<PersonalAlbumState> = statesOf(entries)

  /** Lo stato di [albumId] in [entries], o null se non ha nessuna voce. */
  fun state(albumId: String, entries: Map<PersonalKey, PersonalEntry> = store.entries.value): PersonalAlbumState? =
    statesOf(entries, setOf(albumId)).firstOrNull()

  /** L'ordine dell'elenco degli album scelto (impostazione `albums_sort`, D21): il codice, o null se non e' mai stato scelto. */
  fun listSort(entries: Map<PersonalKey, PersonalEntry> = store.entries.value): String? =
    (entries[listSortKey]?.value as? PersonalValue.Text)?.text

  suspend fun setListSort(code: String) = store.set(listSortKey, PersonalValue.Text(code))

  private fun key(albumId: String, field: String) = PersonalKey(PersonalKind.ALBUM, albumId, field)

  private val listSortKey = PersonalKey(PersonalKind.SETTING, PersonalFields.SETTINGS_SUBJECT, PersonalFields.SETTING_ALBUMS_SORT)

  companion object {
    /**
     * Lo stato degli album di [entries] senza uno store: chi proietta lo stato sul catalogo legge soltanto. Con [only] solo quegli album
     * (una sola passata sulle voci, qualunque ne sia il numero); un album senza voci non compare.
     */
    fun statesOf(entries: Map<PersonalKey, PersonalEntry>, only: Set<String>? = null): List<PersonalAlbumState> {
      val rows = HashMap<String, MutableList<PersonalEntry>>()
      for (entry in entries.values) {
        if (entry.key.kind != PersonalKind.ALBUM) continue
        if (only != null && entry.key.subject !in only) continue
        rows.getOrPut(entry.key.subject) { ArrayList() } += entry
      }
      return rows.map { (id, list) -> stateOf(id, list) }.sortedBy { it.id }
    }

    /** Le voci di un album in uno stato. Le voci con campi che questa versione non conosce si ignorano (restano nello stato). */
    private fun stateOf(id: String, rows: List<PersonalEntry>): PersonalAlbumState {
      var title: String? = null
      var kind: String? = null
      var sort: String? = null
      var frozen = false
      var order: Long? = null
      var cover: PersonalMediaRef? = null
      var sharedId: String? = null
      var copyOf: String? = null
      val smart = LinkedHashMap<String, String>()
      val items = ArrayList<PersonalItem>()
      val excluded = ArrayList<PersonalChoice>()
      val accepted = ArrayList<PersonalChoice>()
      val examples = ArrayList<PersonalChoice>()
      var first = Long.MAX_VALUE
      var last = -1L
      var undated = false
      for (row in rows) {
        if (row.atNas == Long.MAX_VALUE) {
          undated = true
        } else {
          first = minOf(first, row.atNas)
          last = maxOf(last, row.atNas)
        }
        val field = row.key.field
        val value = row.value
        val raw = (value as? PersonalValue.Text)?.text
        val text = raw?.takeIf { it.isNotEmpty() }
        when {
          field == PersonalFields.ALBUM_DEFINITION -> title = raw
          field == PersonalFields.ALBUM_KIND -> kind = text
          field == PersonalFields.ALBUM_SORT -> sort = text
          field == PersonalFields.ALBUM_FROZEN -> frozen = (value as? PersonalValue.Flag)?.on == true
          field == PersonalFields.ALBUM_ORDER -> order = (value as? PersonalValue.Number)?.value
          field == PersonalFields.ALBUM_COVER -> cover = text?.let { PersonalMediaRef(it, row.fingerprint) }
          field == PersonalFields.ALBUM_SHARED -> sharedId = text
          field == PersonalFields.ALBUM_COPY_OF -> copyOf = text
          field in PersonalFields.SMART_RULE_FIELDS -> if (text != null) smart[field] = text
          PersonalFields.isAlbumItem(field) -> {
            val media = PersonalFields.albumItemMedia(field) ?: continue
            val position = when (value) {
              is PersonalValue.Number -> value.value
              is PersonalValue.Flag -> if (value.on) Long.MAX_VALUE else null
              else -> null
            }
            items += PersonalItem(media, position, row.fingerprint, row.atNas, row.author)
          }
          PersonalFields.isAlbumExclude(field) -> choice(PersonalFields.albumExcludeMedia(field), row)?.let { excluded += it }
          PersonalFields.isAlbumAccept(field) -> choice(PersonalFields.albumAcceptMedia(field), row)?.let { accepted += it }
          PersonalFields.isSmartExample(field) -> choice(PersonalFields.smartExampleMedia(field), row)?.let { examples += it }
        }
      }
      return PersonalAlbumState(
        id = id, title = title, kind = kind, sort = sort, frozen = frozen, order = order, cover = cover, sharedId = sharedId, copyOf = copyOf,
        smart = smart, items = items, excluded = excluded, accepted = accepted, examples = examples,
        firstAtNas = if (first == Long.MAX_VALUE) 0L else first, lastAtNas = maxOf(last, 0L), hasUndated = undated,
      )
    }

    private fun choice(media: String?, row: PersonalEntry): PersonalChoice? =
      media?.let { PersonalChoice(it, (row.value as? PersonalValue.Flag)?.on == true, row.fingerprint, row.atNas, row.author) }
  }
}

/**
 * I ricordi visti e scartati (Fase 9, D10; formato §5.5-bis A1): un ricordo visto o scartato su un dispositivo risulta visto o scartato su tutti
 * i dispositivi dell'account (gli id sono deterministici, D9). Firme di P0: il valore di `seen` e' il **giorno locale** della visione
 * (`aaaammgg`), non piu' l'ora del NAS (nessun chiamante l'aveva mai scritta). Si legge lo stato "effettivo" (E-MEM22: una scelta fatta fuori
 * casa vale subito). Chiavi e lettura sono quelle di [MemoriesRecords]; le altre scelte (persone, periodi, Q9) le scrive
 * `SyncedMemoriesSettings` (P6) con le stesse chiavi.
 */
class PersonalMemories(private val store: PersonalStore) {

  /**
   * "Visto" il giorno [day] (`aaaammgg`). Non scrive se il giorno che c'e' e' gia' quello o uno piu' recente (aprire due volte lo stesso
   * ricordo non fa crescere il registro; un orologio tornato indietro non riporta indietro il giorno).
   */
  suspend fun markSeen(memoryId: String, day: Int) {
    val key = MemoriesRecords.seenKey(memoryId)
    val value = MemoriesRecords.seenValue(day)
    val now = seenDay(memoryId)
    if (now != null && now >= day) return
    store.set(key, value)
  }

  /** Il giorno (`aaaammgg`) dell'ultima visione di [memoryId], o null se non e' mai stato visto (o il valore non si capisce). */
  fun seenDay(memoryId: String, entries: Map<PersonalKey, PersonalEntry> = store.effectiveEntries.value): Int? {
    val entry = entries[MemoriesRecords.seenKey(memoryId)] ?: return null
    return MemoriesRecords.read(mapOf(entry.key to entry)).seen[memoryId]
  }

  /** "Non mostrare questo ricordo" ([dismissed] vero) o "Annulla" (falso: la lapide, D10). */
  suspend fun dismiss(memoryId: String, dismissed: Boolean = true) {
    store.set(MemoriesRecords.dismissedKey(memoryId), if (dismissed) PersonalValue.Flag(true) else PersonalValue.Removed)
  }

  /** Se [memoryId] e' scartato ("Non mostrare"): non torna su nessun dispositivo dell'account, nemmeno fra un anno (E-MEM9). */
  fun isDismissed(memoryId: String, entries: Map<PersonalKey, PersonalEntry> = store.effectiveEntries.value): Boolean =
    (entries[MemoriesRecords.dismissedKey(memoryId)]?.value as? PersonalValue.Flag)?.on == true
}
