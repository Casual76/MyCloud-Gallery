package dev.pampa.mycloudgallery.core.albums.personal

import dev.pampa.mycloudgallery.core.albums.contract.SmartLimits
import dev.pampa.mycloudgallery.core.database.entity.AlbumEntity
import dev.pampa.mycloudgallery.core.database.entity.AlbumExclusionEntity
import dev.pampa.mycloudgallery.core.database.entity.AlbumItemEntity
import dev.pampa.mycloudgallery.core.database.entity.AlbumRuleEntity
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.AlbumKind
import dev.pampa.mycloudgallery.core.model.AlbumSort
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFingerprint
import dev.pampa.mycloudgallery.core.personal.settings.PersonalAlbumState
import dev.pampa.mycloudgallery.core.personal.settings.PersonalChoice

// DALLO STATO DI UN ALBUM ALLE RIGHE DELLE TABELLE v6 (docs/fase7-progetto.md D5, §2.1; docs/modello-dati.md §14). **Pura**: lo stato (le
// voci unite) e il modo in cui i riferimenti si risolvono nel catalogo di questo dispositivo ([MediaResolution]) danno le righe che
// l'album deve avere; niente database qui. Cosi' la regola sta in un posto solo ed e' provabile senza Room: `CatalogAlbumProjection`
// la applica in una transazione.
//
// Le regole:
// - **Risoluzione** (D5): un elemento si ritrova per id e, se l'id qui non c'e', per impronta solo se indica **un** media. Uno non
//   risolto e' "in attesa": non ha riga, non si mostra e si conta ([DerivedAlbum.pending]); torna da solo quando il media arriva.
// - **Unione d'identita'** (E-I18): due voci che qui indicano lo stesso media (due id per lo stesso file, o un id e il suo alias) sono
//   **un elemento**. Vince la voce piu' recente (ora del NAS); a pari ora quella scritta con l'id che il catalogo ha adesso; poi, come
//   `PersonalMerge`, l'autore minore. Vale anche per la lapide: una rimozione piu' recente toglie l'elemento comunque lo si chiami.
// - **Gli album condivisi non si toccano**: qui nascono solo righe di album personali (`state` NULL).
// - Una riga e' **derivata**: stesso stato e stesso catalogo, stesse righe, in qualunque ordine siano arrivate le voci.

/** Un riferimento a risolvere: l'id come l'ha scritto chi l'ha scritto e l'impronta della voce. */
data class MediaRefKey(val subject: String, val fingerprint: PersonalFingerprint?) {
  override fun toString(): String = "MediaRefKey"
}

/** Come i riferimenti si sono risolti nel catalogo di qui: il media vero (un id assorbito vale il sopravvissuto), o null se non c'e' (ancora). */
class MediaResolution(private val resolved: Map<MediaRefKey, MediaId?>) {
  fun of(subject: String, fingerprint: PersonalFingerprint?): MediaId? = resolved[MediaRefKey(subject, fingerprint)]

  override fun toString(): String = "MediaResolution(${resolved.size})"

  companion object {
    val NONE = MediaResolution(emptyMap())
  }
}

/** Le righe che un album personale deve avere. [items] sono solo quelle derivate dallo stato (`manual`, `accepted`): quelle `auto` sono di P6. */
data class DerivedAlbum(
  val album: AlbumEntity,
  val items: List<AlbumItemEntity>,
  val rules: List<AlbumRuleEntity>,
  val exclusions: List<AlbumExclusionEntity>,
  /** Elementi vivi non (ancora) risolti nel catalogo di questo dispositivo (`ALBUM_PROJECTION_PENDING`). */
  val pending: Int,
  /** Gli smart non congelati non mostrano gli esclusi, neanche se automatici: la proiezione toglie le loro righe `auto`. */
  val hiddenFromAuto: Set<String>,
  /** Uno smart congelato non applica le regole: le sue righe `auto` non devono restare. */
  val dropsAuto: Boolean,
) {
  override fun toString(): String = "DerivedAlbum(${items.size} elementi, in attesa=$pending)"
}

object AlbumDerivation {

  /** Tutti i riferimenti di [state] da risolvere: elementi, copertina, esclusioni, accettazioni ed esempi. */
  fun refsOf(state: PersonalAlbumState): Set<MediaRefKey> {
    val out = LinkedHashSet<MediaRefKey>()
    state.items.mapTo(out) { MediaRefKey(it.mediaId, it.fingerprint) }
    state.cover?.let { out += MediaRefKey(it.mediaId, it.fingerprint) }
    (state.excluded + state.accepted + state.examples).mapTo(out) { MediaRefKey(it.mediaId, it.fingerprint) }
    return out
  }

  /** L'impronta come sta in `album_item.fp` ed `album_exclusion.fp` (P1-2): `<qh1 esadecimale>:<dimensione>`. */
  fun fpText(fingerprint: PersonalFingerprint?): String? = fingerprint?.let { "${it.quickHash.hex}:${it.size}" }

  /**
   * Le righe di [state], o null se l'album non deve avere righe (cancellato, mai definito, di un tipo che questa versione non conosce, o con
   * un id che non e' un id d'album: chi legge le tabelle costruisce `AlbumId` senza rete di salvataggio, e una voce di un altro dispositivo
   * e' un dato come un altro).
   */
  fun derive(state: PersonalAlbumState, resolution: MediaResolution): DerivedAlbum? {
    val title = state.title ?: return null
    if (!isAlbumId(state.id)) return null
    val kind = if (state.kind == null) AlbumKind.MANUAL else AlbumKind.fromCode(state.kind) ?: return null
    val smart = kind == AlbumKind.SMART
    val frozen = smart && state.frozen
    // Una voce senza ora del NAS (ancora "da datare") ha Long.MAX_VALUE: nelle righe vale "subito dopo l'ultima ora che si sa".
    val undatedAs = state.lastAtNas + 1
    fun stamp(at: Long): Long = if (at == Long.MAX_VALUE) undatedAs else at

    var pending = 0
    val itemVotes = ArrayList<Vote>()
    for (item in state.items) {
      val media = resolution.of(item.mediaId, item.fingerprint)
      if (media == null) {
        if (item.live && isMediaId(item.mediaId)) pending++
      } else {
        itemVotes += Vote(media, item.mediaId, item.atNas, item.author, item.live, item.position ?: 0L, item.fingerprint)
      }
    }
    val manual = winners(itemVotes).filter { it.live }.sortedWith(compareBy({ it.position }, { it.media.value }))
    val manualMedia = manual.mapTo(HashSet()) { it.media }

    val excluded = if (smart) choiceWinners(state.excluded, resolution).filter { it.live } else emptyList()
    val excludedMedia = excluded.mapTo(HashSet()) { it.media }
    val accepted = if (smart) choiceWinners(state.accepted, resolution).filter { it.live && it.media !in manualMedia } else emptyList()
    val lastManual = minOf(manual.maxOfOrNull { it.position } ?: 0L, Long.MAX_VALUE / 4)

    val rows = ArrayList<AlbumItemEntity>()
    for (vote in manual) {
      if (!frozen && vote.media in excludedMedia) continue
      rows += AlbumItemEntity(state.id, vote.media.value, vote.position, stamp(vote.atNas), "manual", fpText(vote.fingerprint), null)
    }
    // Gli accettati vengono dopo gli elementi a mano, nell'ordine in cui sono stati accettati (una posizione sola, la stessa su ogni dispositivo).
    accepted.filter { frozen || it.media !in excludedMedia }.sortedWith(compareBy({ it.atNas }, { it.media.value })).forEachIndexed { i, vote ->
      rows += AlbumItemEntity(state.id, vote.media.value, lastManual + (i + 1) * AlbumPositions.STEP, stamp(vote.atNas), "accepted", fpText(vote.fingerprint), null)
    }

    val cover = state.cover?.let { resolution.of(it.mediaId, it.fingerprint) }
    val sort = AlbumSort.fromCode(state.sort) ?: AlbumSort.defaultFor(kind)
    val album = AlbumEntity(
      albumId = state.id,
      title = title,
      coverMediaId = cover?.value,
      createdAt = stamp(state.firstAtNas.takeIf { it > 0 } ?: state.lastAtNas),
      updatedAt = if (state.hasUndated) undatedAs else state.lastAtNas,
      kind = kind.code,
      sort = sort.code,
      frozen = frozen,
      sharedId = state.sharedId,
      state = null,
    )
    return DerivedAlbum(
      album = album,
      items = rows,
      rules = if (smart) ruleRows(state, resolution) else emptyList(),
      exclusions = excluded.sortedBy { it.media.value }.map { AlbumExclusionEntity(state.id, it.media.value, fpText(it.fingerprint)) },
      pending = pending,
      hiddenFromAuto = if (smart && !frozen) excludedMedia.mapTo(HashSet()) { it.value } else emptySet(),
      dropsAuto = frozen,
    )
  }

  private fun ruleRows(state: PersonalAlbumState, resolution: MediaResolution): List<AlbumRuleEntity> {
    val rows = ArrayList<AlbumRuleEntity>()
    for (field in PersonalFields.SMART_RULE_FIELDS) {
      val text = state.smart[field] ?: continue
      rows += AlbumRuleEntity(state.id, field.removePrefix("smart:"), 0, text, null)
    }
    choiceWinners(state.examples, resolution).filter { it.live }.sortedWith(compareBy({ it.atNas }, { it.media.value }))
      .take(SmartLimits.MAX_EXAMPLES).forEachIndexed { i, vote -> rows += AlbumRuleEntity(state.id, EXAMPLE_RULE, i, "", vote.media.value) }
    return rows
  }

  private fun choiceWinners(choices: List<PersonalChoice>, resolution: MediaResolution): List<Vote> {
    val votes = choices.mapNotNull { c ->
      resolution.of(c.mediaId, c.fingerprint)?.let { Vote(it, c.mediaId, c.atNas, c.author, c.on, 0L, c.fingerprint) }
    }
    return winners(votes)
  }

  private fun isAlbumId(raw: String): Boolean = try {
    AlbumId(raw)
    true
  } catch (e: IllegalArgumentException) {
    false
  }

  private fun isMediaId(subject: String): Boolean = try {
    MediaId.parse(subject)
    true
  } catch (e: IllegalArgumentException) {
    false
  }

  /** Il nome della regola delle foto d'esempio in `album_rule`. */
  const val EXAMPLE_RULE: String = "example"

  /** Una voce che ha detto qualcosa di un media (risolto): quando, chi, se lo vuole ancora, dove, con quale impronta. */
  private class Vote(
    val media: MediaId,
    val subject: String,
    val atNas: Long,
    val author: String,
    val live: Boolean,
    val position: Long,
    val fingerprint: PersonalFingerprint?,
  )

  /** Per ogni media la voce che vince (ordine totale: stesso risultato in qualunque ordine arrivino le voci). */
  private fun winners(votes: List<Vote>): List<Vote> {
    val best = HashMap<MediaId, Vote>()
    for (vote in votes) {
      val known = best[vote.media]
      if (known == null || compare(vote, known) > 0) best[vote.media] = vote
    }
    return best.values.toList()
  }

  private fun compare(a: Vote, b: Vote): Int {
    a.atNas.compareTo(b.atNas).let { if (it != 0) return it }
    // A pari ora vince la voce scritta con l'id che il catalogo ha adesso (chi scrive riscrive sul sopravvissuto, E-I18).
    (a.subject == a.media.value).compareTo(b.subject == b.media.value).let { if (it != 0) return it }
    // Come PersonalMerge: l'autore minore; a pari tutto una lapide batte una voce viva; poi il posto e l'id.
    b.author.compareTo(a.author).let { if (it != 0) return it }
    (!a.live).compareTo(!b.live).let { if (it != 0) return it }
    a.position.compareTo(b.position).let { if (it != 0) return it }
    return a.subject.compareTo(b.subject)
  }
}
