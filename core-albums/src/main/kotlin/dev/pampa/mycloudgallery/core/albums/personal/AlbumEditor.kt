package dev.pampa.mycloudgallery.core.albums.personal

import dev.pampa.mycloudgallery.core.albums.contract.AddOutcome
import dev.pampa.mycloudgallery.core.albums.contract.AlbumTitles
import dev.pampa.mycloudgallery.core.albums.contract.CreateOutcome
import dev.pampa.mycloudgallery.core.albums.contract.EditOutcome
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.albums.contract.TitleProblem
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.database.entity.AlbumItemEntity
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.AlbumKind
import dev.pampa.mycloudgallery.core.model.AlbumSort
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.personal.contract.PersonalStore
import dev.pampa.mycloudgallery.core.personal.settings.PersonalAlbumState
import dev.pampa.mycloudgallery.core.personal.settings.PersonalAlbums
import kotlinx.coroutines.yield

// LE MODIFICHE AGLI ALBUM PERSONALI, COME VOCI (docs/fase7-progetto.md D3, D4, D16, D21, D39). Ogni modifica e' una o piu' voci dei dati
// personali (`PersonalAlbums`), mai un file spostato o tolto: "togliere non e' eliminare" (D39). Qui sta la regola delle voci; la
// proiezione sulle tabelle la fa chi chiama dopo la modifica ([CatalogAlbums]), cosi' l'album si aggiorna subito.
//
// **Le voci si scrivono sull'id che il catalogo ha adesso** (E-I18): chi aggiunge, riordina o sceglie la copertina di un media che qui ha
// un id assorbito da un'unione lo riscrive sul sopravvissuto, con la stessa impronta; chi toglie spegne **tutte** le voci vive che qui
// indicano quel media (un id vecchio e uno nuovo), cosi' nessun altro dispositivo lo vede tornare. Una modifica fatta senza ora del NAS
// vale subito (`PersonalStore.effective`) e prende l'ora alla prima sincronizzazione.
//
// Mai un'eccezione per un caso previsto: gli esiti sono quelli del contratto (`EditOutcome`, `AddOutcome`, `CreateOutcome`).

internal class AlbumEditor(
  private val store: PersonalStore,
  private val catalog: MediaCatalog,
  private val database: GalleryDatabase,
  private val resolver: MediaResolver,
  private val shown: suspend (Collection<MediaId>) -> Set<MediaId>,
) {
  val albums = PersonalAlbums(store)

  /** Lo stato di [id] se e' un album definito (non cancellato) e di un tipo che questa versione conosce. */
  fun definedState(id: AlbumId): PersonalAlbumState? =
    albums.state(id.value, store.effectiveEntries.value)?.takeIf { it.defined && kindOf(it) != null }

  fun kindOf(state: PersonalAlbumState): AlbumKind? = if (state.kind == null) AlbumKind.MANUAL else AlbumKind.fromCode(state.kind)

  private fun liveSmart(state: PersonalAlbumState): Boolean = kindOf(state) == AlbumKind.SMART && !state.frozen

  // --- Creazione, titolo, eliminazione ------------------------------------------------------------------------------------

  suspend fun create(account: AccountId, nonce: ByteArray, title: String, kind: AlbumKind, rules: SmartRules?): CreateOutcome {
    AlbumTitles.problem(title)?.let { return CreateOutcome.Invalid(it) }
    require((kind == AlbumKind.SMART) == (rules != null)) { "Le regole sono di un album smart, e solo sue." }
    val encoded = rules?.let { SmartRulesText.encode(it) }
    // Una regola che non sta in una voce non si scrive a meta': l'editor la deve restringere (SmartRulesText.encode lo dice prima).
    if (encoded != null && !encoded.ok) return CreateOutcome.Invalid(TitleProblem.TOO_LONG)
    val id = AlbumId.derived(account, nonce).value
    // La definizione per ultima: un altro dispositivo vede l'album quando ha gia' tutte le sue regole.
    if (kind == AlbumKind.SMART) albums.setKind(id, kind)
    encoded?.fields?.forEach { (field, text) -> if (text != null) albums.setSmart(id, field, text) }
    rules?.examples?.forEach { media -> catalog.resolve(media)?.let { albums.setExample(id, it.value, true) } }
    albums.define(id, AlbumTitles.normalized(title))
    return CreateOutcome.Created(AlbumId(id))
  }

  suspend fun rename(id: AlbumId, title: String): EditOutcome {
    val state = definedState(id)
    AlbumTitles.problem(title)?.let { return if (state == null) EditOutcome.NotFound else EditOutcome.Invalid(it) }
    state ?: return EditOutcome.NotFound
    albums.define(id.value, AlbumTitles.normalized(title))
    return EditOutcome.Done
  }

  suspend fun delete(id: AlbumId): EditOutcome {
    definedState(id) ?: return EditOutcome.NotFound
    albums.delete(id.value)
    return EditOutcome.Done
  }

  // --- Elementi -----------------------------------------------------------------------------------------------------------

  suspend fun add(id: AlbumId, media: List<MediaId>): AddOutcome {
    val state = definedState(id) ?: return AddOutcome.NotFound
    val real = media.distinct().mapNotNull { catalog.resolve(it) }.distinct()
    val rows = database.albumDao().items(id.value)
    val present = rows.mapTo(HashSet()) { it.mediaId }
    val fresh = real.filter { it.value !in present }
    val already = real.size - fresh.size
    if (fresh.isEmpty()) return AddOutcome.Added(0, already)
    val smart = kindOf(state) == AlbumKind.SMART
    val resolution = if (smart) resolver.resolve(AlbumDerivation.refsOf(state)) else MediaResolution.NONE
    if (liveSmart(state)) {
      // D14: in uno smart non congelato un media a mano e' un suggerito accettato; un'esclusione precedente si toglie.
      for (m in fresh) {
        albums.accept(id.value, m.value, on = true)
        clearExclusions(state, resolution, m)
      }
    } else {
      write(id, AlbumPositions.append(manualPositions(rows), fresh.map { it.value }))
      if (smart) for (m in fresh) clearExclusions(state, resolution, m)
    }
    return AddOutcome.Added(fresh.size, already)
  }

  suspend fun remove(id: AlbumId, media: List<MediaId>): EditOutcome {
    val state = definedState(id) ?: return EditOutcome.NotFound
    val resolution = resolver.resolve(AlbumDerivation.refsOf(state))
    val smart = kindOf(state) == AlbumKind.SMART
    for (m in media.distinct()) {
      val real = catalog.resolve(m) ?: continue
      for (item in state.items) {
        if (item.live && resolution.of(item.mediaId, item.fingerprint) == real) albums.removeItem(id.value, item.mediaId, item.fingerprint)
      }
      for (accepted in state.accepted) {
        if (accepted.on && resolution.of(accepted.mediaId, accepted.fingerprint) == real) {
          albums.accept(id.value, accepted.mediaId, on = false, fingerprint = accepted.fingerprint)
        }
      }
      // Su uno smart togliere un elemento automatico e' un'esclusione per sempre (E-AL5).
      if (smart) albums.exclude(id.value, real.value, on = true)
      val cover = state.cover
      if (cover != null && resolution.of(cover.mediaId, cover.fingerprint) == real) albums.setCover(id.value, null)
    }
    return EditOutcome.Done
  }

  // --- Copertina e ordine -------------------------------------------------------------------------------------------------

  suspend fun setCover(id: AlbumId, media: MediaId?): EditOutcome {
    definedState(id) ?: return EditOutcome.NotFound
    if (media == null) {
      albums.setCover(id.value, null)
      return EditOutcome.Done
    }
    // Una copertina e' un elemento mostrabile dell'album (D21): un nascosto, un tolto o un estraneo non lo e'.
    val real = catalog.resolve(media) ?: return EditOutcome.Invalid(null)
    if (database.albumDao().items(id.value).none { it.mediaId == real.value } || real !in shown(listOf(real))) return EditOutcome.Invalid(null)
    albums.setCover(id.value, real.value)
    return EditOutcome.Done
  }

  suspend fun setSort(id: AlbumId, sort: AlbumSort): EditOutcome {
    definedState(id) ?: return EditOutcome.NotFound
    albums.setSort(id.value, sort)
    return EditOutcome.Done
  }

  /** [moved] subito dopo [after] (null = in testa): una voce sola se c'e' posto fra i vicini, altrimenti si ricompatta a lotti (D21). */
  suspend fun reorder(id: AlbumId, moved: MediaId, after: MediaId?): EditOutcome {
    definedState(id) ?: return EditOutcome.NotFound
    val realMoved = catalog.resolve(moved) ?: return EditOutcome.Invalid(null)
    val realAfter = after?.let { catalog.resolve(it) ?: return EditOutcome.Invalid(null) }
    val current = manualPositions(database.albumDao().items(id.value))
    if (current.none { it.media == realMoved.value } || realAfter == realMoved) return EditOutcome.Invalid(null)
    if (realAfter != null && current.none { it.media == realAfter.value }) return EditOutcome.Invalid(null)
    write(id, AlbumPositions.insertAfter(current, realMoved.value, realAfter?.value))
    return EditOutcome.Done
  }

  // --- Le scelte sugli album smart (D14, D16) -----------------------------------------------------------------------------

  private fun smartState(id: AlbumId): Pair<PersonalAlbumState?, EditOutcome?> {
    val state = definedState(id) ?: return null to EditOutcome.NotFound
    return if (kindOf(state) == AlbumKind.SMART) state to null else null to EditOutcome.NotAllowed(null)
  }

  /**
   * Cambia le regole di uno smart (D13, E-AL13): una voce per regola, quindi le esclusioni e le accettazioni restano come sono e due
   * modifiche di due regole su due dispositivi si fondono. Le foto d'esempio si aggiornano per differenza. Nessuna regola si scrive a meta':
   * se una non sta in una voce non cambia niente.
   */
  suspend fun setRules(id: AlbumId, rules: SmartRules): EditOutcome {
    val (state, refusal) = smartState(id)
    refusal?.let { return it }
    val encoded = SmartRulesText.encode(rules)
    if (!encoded.ok) return EditOutcome.Invalid(TitleProblem.TOO_LONG)
    for ((field, text) in encoded.fields) {
      // Una regola che non c'e' e non si vuole non lascia una lapide.
      if (text != null || state!!.smart[field] != null) albums.setSmart(id.value, field, text)
    }
    val resolution = resolver.resolve(AlbumDerivation.refsOf(state!!))
    val wanted = rules.examples.mapNotNull { catalog.resolve(it) }.toSet()
    for (example in state.examples) {
      if (example.on && resolution.of(example.mediaId, example.fingerprint) !in wanted) {
        albums.setExample(id.value, example.mediaId, on = false, fingerprint = example.fingerprint)
      }
    }
    val present = state.examples.filter { it.on }.mapNotNullTo(HashSet()) { resolution.of(it.mediaId, it.fingerprint) }
    for (media in wanted) if (media !in present) albums.setExample(id.value, media.value, on = true)
    return EditOutcome.Done
  }

  suspend fun exclude(id: AlbumId, media: List<MediaId>): EditOutcome {
    val (state, refusal) = smartState(id)
    refusal?.let { return it }
    val resolution = resolver.resolve(AlbumDerivation.refsOf(state!!))
    for (m in media.distinct()) {
      val real = catalog.resolve(m) ?: continue
      albums.exclude(id.value, real.value, on = true)
      clearAcceptances(state, resolution, real)
    }
    return EditOutcome.Done
  }

  suspend fun accept(id: AlbumId, media: List<MediaId>): EditOutcome {
    val (state, refusal) = smartState(id)
    refusal?.let { return it }
    val resolution = resolver.resolve(AlbumDerivation.refsOf(state!!))
    for (m in media.distinct()) {
      val real = catalog.resolve(m) ?: continue
      albums.accept(id.value, real.value, on = true)
      clearExclusions(state, resolution, real)
    }
    return EditOutcome.Done
  }

  /** "Congela": gli elementi di quel momento diventano elementi a mano, con impronta, e le regole smettono di agire (D16). */
  suspend fun freeze(id: AlbumId): EditOutcome {
    val (state, refusal) = smartState(id)
    refusal?.let { return it }
    if (state!!.frozen) return EditOutcome.Done
    val rows = database.albumDao().items(id.value)
    val manual = manualPositions(rows)
    val manualMedia = manual.mapTo(HashSet()) { it.media }
    val others = AlbumPositions.sorted(rows.filter { it.mediaId !in manualMedia }.map { PositionedItem(it.mediaId, it.position, it.fp.orEmpty()) }).map { it.media }
    write(id, AlbumPositions.append(manual, others))
    albums.setFrozen(id.value, true)
    return EditOutcome.Done
  }

  /** "Scongela": le regole tornano ad agire, esclusioni e accettazioni restano; gli elementi del congelamento si tolgono (D16). */
  suspend fun unfreeze(id: AlbumId): EditOutcome {
    val (state, refusal) = smartState(id)
    refusal?.let { return it }
    if (!state!!.frozen) return EditOutcome.Done
    for (item in state.items) if (item.live) albums.removeItem(id.value, item.mediaId, item.fingerprint)
    albums.setFrozen(id.value, false)
    return EditOutcome.Done
  }

  // --- Interni ------------------------------------------------------------------------------------------------------------

  /** Spegne **tutte** le esclusioni vive che qui indicano [real] (un id vecchio e uno nuovo dello stesso file): nessuna resta a vietarlo. */
  private suspend fun clearExclusions(state: PersonalAlbumState, resolution: MediaResolution, real: MediaId) {
    for (c in state.excluded) {
      if (c.on && resolution.of(c.mediaId, c.fingerprint) == real) albums.exclude(state.id, c.mediaId, on = false, fingerprint = c.fingerprint)
    }
  }

  /** Come [clearExclusions] per gli accettati. */
  private suspend fun clearAcceptances(state: PersonalAlbumState, resolution: MediaResolution, real: MediaId) {
    for (c in state.accepted) {
      if (c.on && resolution.of(c.mediaId, c.fingerprint) == real) albums.accept(state.id, c.mediaId, on = false, fingerprint = c.fingerprint)
    }
  }

  /** Gli elementi a mano con il loro posto (le righe `accepted` e `auto` non hanno una voce `item:`: non si riordinano). */
  private fun manualPositions(rows: List<AlbumItemEntity>): List<PositionedItem> =
    rows.filter { it.origin == "manual" }.map { PositionedItem(it.mediaId, it.position, it.fp.orEmpty()) }

  /** Scrive le posizioni di un piano: una voce, o la ricompattazione a lotti con un passo di cortesia fra l'uno e l'altro. */
  private suspend fun write(id: AlbumId, plan: PositionPlan) {
    for (batch in plan.changes.chunked(AlbumPositions.BATCH)) {
      for (change in batch) albums.addItem(id.value, change.media, change.position)
      if (plan.recompacted) yield()
    }
  }
}
