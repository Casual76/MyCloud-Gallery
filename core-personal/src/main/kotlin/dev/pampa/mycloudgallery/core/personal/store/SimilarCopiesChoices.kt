package dev.pampa.mycloudgallery.core.personal.store

import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKind
import dev.pampa.mycloudgallery.core.personal.contract.PersonalMerge
import dev.pampa.mycloudgallery.core.personal.contract.PersonalStore
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import dev.pampa.mycloudgallery.core.personal.contract.SimilarCopiesRecords

// Fase 5A, P15 (docs/fase5-progetto.md §2.12, D36, E-L13): le scelte dell'utente sulle copie simili ("Mostra questa come principale" e
// "Separa") come dati personali sincronizzati. Le legge e le scrive il rilevatore di core-library (`SimilarChoices`, che core-personal
// non vede): `:app` lega le due con un adattatore di una riga per metodo, tipi di core-model. La proiezione sul catalogo e' in
// `CatalogPersonalProjection`.

/**
 * Le scelte sulle copie simili sopra lo [PersonalStore]. Le letture vedono anche le modifiche locali non ancora datate
 * ([PersonalStore.effective]): una separazione fatta senza rete vale subito e il gruppo non si riforma.
 *
 * @param resolve l'identita' attuale di un media (`MediaCatalog.resolve`): una voce scritta con l'id che un'unione o l'adozione
 *   dell'indice (D7) ha poi sostituito vale ancora per il sopravvissuto. Senza (di serie) le voci valgono per l'id con cui sono scritte.
 */
class SimilarCopiesChoices(
  private val store: PersonalStore,
  private val resolve: suspend (MediaId) -> MediaId? = { it },
) {

  /**
   * La copia che l'utente ha scelto come principale fra [members]: la voce accesa piu' recente ([PersonalMerge.compare]). Null se non
   * ne ha scelta nessuna.
   */
  suspend fun chosenCover(members: Collection<MediaId>): MediaId? {
    if (members.isEmpty()) return null
    val wanted = members.toSet()
    val best = HashMap<MediaId, PersonalEntry>()
    for ((media, entry) in coverEntries(onlyOn = true)) {
      if (media !in wanted) continue
      val known = best[media]
      if (known == null || PersonalMerge.compare(entry, known) > 0) best[media] = entry
    }
    return best.entries.maxWithOrNull { a, b -> PersonalMerge.compare(a.value, b.value) }?.key
  }

  /** Le coppie separate con entrambi i media in [ids]: (minore, maggiore) per identita'. Le lapidi (di nuovo unibili) non contano. */
  suspend fun separatedPairs(ids: Collection<MediaId>): Set<Pair<MediaId, MediaId>> {
    if (ids.size < 2) return emptySet()
    val wanted = ids.toSet()
    val out = LinkedHashSet<Pair<MediaId, MediaId>>()
    val cache = HashMap<String, MediaId?>()
    suspend fun current(raw: String): MediaId? = cache.getOrPut(raw) { runCatching { resolve(MediaId(raw)) }.getOrNull() }
    for ((key, entry) in store.effective()) {
      if (key.kind != PersonalKind.MEDIA_FLAG || !PersonalFields.isSimilarApart(key.field)) continue
      if ((entry.value as? PersonalValue.Flag)?.on != true) continue
      val (low, high) = SimilarCopiesRecords.separatedPair(key) ?: continue
      val a = current(low) ?: continue
      val b = current(high) ?: continue
      if (a != b && a in wanted && b in wanted) out += if (a.value <= b.value) a to b else b to a
    }
    return out
  }

  /**
   * "Mostra questa come principale": la voce di [chosen] si accende e quella di ogni altra copia che l'aveva si spegne, cosi' la
   * scelta successiva e' sempre un cambiamento vero con un'ora nuova (lo store non riscrive un valore uguale).
   */
  suspend fun saveCover(chosen: MediaId, others: Collection<MediaId>) {
    val wanted = others.toSet() - chosen
    for ((media, entry) in coverEntries(onlyOn = true)) {
      if (media in wanted) store.set(entry.key, PersonalValue.Flag(false))
    }
    store.set(SimilarCopiesRecords.cover(chosen.value), PersonalValue.Flag(true))
  }

  /** "Separa": una voce per ogni altra copia del gruppo (la separazione e' simmetrica e vale per sempre). */
  suspend fun saveSeparation(member: MediaId, others: Collection<MediaId>) {
    for (other in others) {
      if (other == member) continue
      store.set(SimilarCopiesRecords.separation(member.value, other.value), PersonalValue.Flag(true))
    }
  }

  /** Le voci `similar_cover` accese (o tutte), con l'identita' **attuale** del media a cui si riferiscono. */
  private suspend fun coverEntries(onlyOn: Boolean): List<Pair<MediaId, PersonalEntry>> {
    val out = ArrayList<Pair<MediaId, PersonalEntry>>()
    for ((key, entry) in store.effective()) {
      if (key.kind != PersonalKind.MEDIA_FLAG || key.field != PersonalFields.SIMILAR_COVER) continue
      if (onlyOn && (entry.value as? PersonalValue.Flag)?.on != true) continue
      val media = runCatching { resolve(MediaId(key.subject)) }.getOrNull() ?: continue
      out += media to entry
    }
    return out
  }
}
