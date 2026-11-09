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
import dev.pampa.mycloudgallery.core.personal.settings.PersonalChoice
import dev.pampa.mycloudgallery.core.personal.settings.PersonalItem

// LE MODIFICHE AGLI ALBUM PERSONALI, COME VOCI (docs/fase7-progetto.md D3, D4, D16, D21, D39). Ogni modifica e' una o piu' voci dei dati
// personali (`PersonalAlbums`), mai un file spostato o tolto: "togliere non e' eliminare" (D39). Qui sta la regola delle voci; la
// proiezione sulle tabelle la fa chi chiama dopo la modifica ([CatalogAlbums]), cosi' l'album si aggiorna subito.
//
// **Le voci si scrivono sull'id che il catalogo ha adesso** (E-I18): chi aggiunge, riordina o sceglie la copertina di un media che qui ha
// un id assorbito da un'unione lo riscrive sul sopravvissuto, con la stessa impronta; chi toglie spegne **tutte** le voci vive che qui
// indicano quel media (un id vecchio e uno nuovo), cosi' nessun altro dispositivo lo vede tornare. Una modifica fatta senza ora del NAS
// vale subito (`PersonalStore.effective`) e prende l'ora alla prima sincronizzazione.
//
// **Una modifica, un salvataggio** (review generale, OA-1): le voci di una stessa modifica (le foto aggiunte o tolte, gli elementi di
// "Congela", le regole di uno smart) si scrivono in un lotto solo (`PersonalAlbums.batch`). Il registro dei dati personali si riscrive per
// intero a ogni salvataggio: una voce alla volta, 2.000 foto in un album erano 2.000 riscritture. E il lotto c'e' tutto o non c'e': un album
// non resta mai "congelato a meta'" o creato senza le sue regole se il processo muore in mezzo.
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
    if (encoded != null && !encoded.ok) return CreateOutcome.RulesTooLong
    val id = AlbumId.derived(account, nonce).value
    val examples = rules?.examples?.mapNotNull { catalog.resolve(it) }.orEmpty()
    albums.batch {
      // La definizione per ultima: un altro dispositivo vede l'album quando ha gia' tutte le sue regole.
      if (kind == AlbumKind.SMART) setKind(id, kind)
      encoded?.fields?.forEach { (field, text) -> if (text != null) setSmart(id, field, text) }
      for (media in examples) setExample(id, media.value, true)
      define(id, AlbumTitles.normalized(title))
    }
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
    val excluded = if (smart) liveBy(state.excluded, resolution) else emptyMap()
    albums.batch {
      if (liveSmart(state)) {
        // D14: in uno smart non congelato un media a mano e' un suggerito accettato; un'esclusione precedente si toglie.
        for (m in fresh) {
          accept(id.value, m.value, on = true)
          clearExclusions(state, excluded, m)
        }
      } else {
        place(id, AlbumPositions.append(manualPositions(rows), fresh.map { it.value }))
        if (smart) {
          for (m in fresh) {
            // AS-10: uno smart congelato mostra gli elementi a mano (`item:`, le stesse voci che scrive "Congela"). "Scongela" le toglie tutte: senza una
            // voce `accept:` anche la foto che l'utente ha aggiunto da congelato sparirebbe con il resto. Con l'accettazione resta (D16: "tenendo
            // esclusioni e accettazioni").
            accept(id.value, m.value, on = true)
            clearExclusions(state, excluded, m)
          }
        }
      }
    }
    return AddOutcome.Added(fresh.size, already)
  }

  suspend fun remove(id: AlbumId, media: List<MediaId>): EditOutcome {
    val state = definedState(id) ?: return EditOutcome.NotFound
    val resolution = resolver.resolve(AlbumDerivation.refsOf(state))
    val smart = kindOf(state) == AlbumKind.SMART
    // Le voci vive per il media che qui indicano, una volta sola: togliere duemila foto da un album di duemila non e' quattro milioni di confronti.
    val items = HashMap<MediaId, MutableList<PersonalItem>>()
    for (item in state.items) {
      if (!item.live) continue
      val real = resolution.of(item.mediaId, item.fingerprint) ?: continue
      items.getOrPut(real) { ArrayList() } += item
    }
    val accepted = liveBy(state.accepted, resolution)
    val cover = state.cover?.let { resolution.of(it.mediaId, it.fingerprint) }
    val reals = media.distinct().mapNotNull { catalog.resolve(it) }.distinct()
    albums.batch {
      for (real in reals) {
        for (item in items[real].orEmpty()) removeItem(id.value, item.mediaId, item.fingerprint)
        for (choice in accepted[real].orEmpty()) accept(id.value, choice.mediaId, on = false, fingerprint = choice.fingerprint)
        // Su uno smart togliere un elemento automatico e' un'esclusione per sempre (E-AL5).
        if (smart) exclude(id.value, real.value, on = true)
        if (cover != null && cover == real) setCover(id.value, null)
      }
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

  /** [moved] subito dopo [after] (null = in testa): una voce sola se c'e' posto fra i vicini, altrimenti si ricompatta, in un lotto solo (D21). */
  suspend fun reorder(id: AlbumId, moved: MediaId, after: MediaId?): EditOutcome {
    definedState(id) ?: return EditOutcome.NotFound
    val realMoved = catalog.resolve(moved) ?: return EditOutcome.Invalid(null)
    val realAfter = after?.let { catalog.resolve(it) ?: return EditOutcome.Invalid(null) }
    val current = manualPositions(database.albumDao().items(id.value))
    if (current.none { it.media == realMoved.value } || realAfter == realMoved) return EditOutcome.Invalid(null)
    if (realAfter != null && current.none { it.media == realAfter.value }) return EditOutcome.Invalid(null)
    val plan = AlbumPositions.insertAfter(current, realMoved.value, realAfter?.value)
    albums.batch { place(id, plan) }
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
   * se una non sta in una voce non cambia niente, e le voci che cambiano si scrivono insieme.
   */
  suspend fun setRules(id: AlbumId, rules: SmartRules): EditOutcome {
    val (state, refusal) = smartState(id)
    refusal?.let { return it }
    val encoded = SmartRulesText.encode(rules)
    if (!encoded.ok) return EditOutcome.Invalid(TitleProblem.TOO_LONG)
    val resolution = resolver.resolve(AlbumDerivation.refsOf(state!!))
    val wanted = rules.examples.mapNotNull { catalog.resolve(it) }.toSet()
    val present = state.examples.filter { it.on }.mapNotNullTo(HashSet()) { resolution.of(it.mediaId, it.fingerprint) }
    albums.batch {
      for ((field, text) in encoded.fields) {
        // Una regola che non c'e' e non si vuole non lascia una lapide.
        if (text != null || state.smart[field] != null) setSmart(id.value, field, text)
      }
      for (example in state.examples) {
        if (example.on && resolution.of(example.mediaId, example.fingerprint) !in wanted) {
          setExample(id.value, example.mediaId, on = false, fingerprint = example.fingerprint)
        }
      }
      for (media in wanted) if (media !in present) setExample(id.value, media.value, on = true)
    }
    return EditOutcome.Done
  }

  suspend fun exclude(id: AlbumId, media: List<MediaId>): EditOutcome {
    val (state, refusal) = smartState(id)
    refusal?.let { return it }
    val resolution = resolver.resolve(AlbumDerivation.refsOf(state!!))
    val accepted = liveBy(state.accepted, resolution)
    val reals = media.distinct().mapNotNull { catalog.resolve(it) }
    albums.batch {
      for (real in reals) {
        exclude(id.value, real.value, on = true)
        // Come le esclusioni in [add]: tutte le accettazioni vive che qui indicano quel media (un id vecchio e uno nuovo dello stesso file).
        for (choice in accepted[real].orEmpty()) accept(state.id, choice.mediaId, on = false, fingerprint = choice.fingerprint)
      }
    }
    return EditOutcome.Done
  }

  suspend fun accept(id: AlbumId, media: List<MediaId>): EditOutcome {
    val (state, refusal) = smartState(id)
    refusal?.let { return it }
    val resolution = resolver.resolve(AlbumDerivation.refsOf(state!!))
    val excluded = liveBy(state.excluded, resolution)
    val reals = media.distinct().mapNotNull { catalog.resolve(it) }
    albums.batch {
      for (real in reals) {
        accept(id.value, real.value, on = true)
        clearExclusions(state, excluded, real)
      }
    }
    return EditOutcome.Done
  }

  /**
   * "Congela": gli elementi di quel momento diventano elementi a mano, con impronta, e le regole smettono di agire (D16). Gli elementi e il
   * segno "congelato" si scrivono insieme: un album non resta con le righe del congelamento e le regole ancora attive.
   */
  suspend fun freeze(id: AlbumId): EditOutcome {
    val (state, refusal) = smartState(id)
    refusal?.let { return it }
    if (state!!.frozen) return EditOutcome.Done
    val rows = database.albumDao().items(id.value)
    val manual = manualPositions(rows)
    val manualMedia = manual.mapTo(HashSet()) { it.media }
    val others = AlbumPositions.sorted(rows.filter { it.mediaId !in manualMedia }.map { PositionedItem(it.mediaId, it.position, it.fp.orEmpty()) }).map { it.media }
    val plan = AlbumPositions.append(manual, others)
    albums.batch {
      place(id, plan)
      setFrozen(id.value, true)
    }
    return EditOutcome.Done
  }

  /** "Scongela": le regole tornano ad agire, esclusioni e accettazioni restano; gli elementi del congelamento si tolgono (D16), insieme al segno. */
  suspend fun unfreeze(id: AlbumId): EditOutcome {
    val (state, refusal) = smartState(id)
    refusal?.let { return it }
    if (!state!!.frozen) return EditOutcome.Done
    albums.batch {
      for (item in state.items) if (item.live) removeItem(id.value, item.mediaId, item.fingerprint)
      setFrozen(id.value, false)
    }
    return EditOutcome.Done
  }

  // --- Interni ------------------------------------------------------------------------------------------------------------

  /** Le scelte accese di [choices] per il media che qui indicano (un id vecchio e uno nuovo dello stesso file stanno insieme). */
  private fun liveBy(choices: List<PersonalChoice>, resolution: MediaResolution): Map<MediaId, List<PersonalChoice>> {
    val out = HashMap<MediaId, MutableList<PersonalChoice>>()
    for (c in choices) {
      if (!c.on) continue
      val real = resolution.of(c.mediaId, c.fingerprint) ?: continue
      out.getOrPut(real) { ArrayList() } += c
    }
    return out
  }

  /** Spegne **tutte** le esclusioni vive che qui indicano [real] (un id vecchio e uno nuovo dello stesso file): nessuna resta a vietarlo. */
  private suspend fun PersonalAlbums.Batch.clearExclusions(state: PersonalAlbumState, excluded: Map<MediaId, List<PersonalChoice>>, real: MediaId) {
    for (c in excluded[real].orEmpty()) exclude(state.id, c.mediaId, on = false, fingerprint = c.fingerprint)
  }

  /** Gli elementi a mano con il loro posto (le righe `accepted` e `auto` non hanno una voce `item:`: non si riordinano). */
  private fun manualPositions(rows: List<AlbumItemEntity>): List<PositionedItem> =
    rows.filter { it.origin == "manual" }.map { PositionedItem(it.mediaId, it.position, it.fp.orEmpty()) }

  /** Le posizioni di un piano: una voce, o tutte quelle di una ricompattazione. Si scrivono con il resto del lotto, in un salvataggio solo. */
  private suspend fun PersonalAlbums.Batch.place(id: AlbumId, plan: PositionPlan) {
    for (change in plan.changes) addItem(id.value, change.media, change.position)
  }
}
