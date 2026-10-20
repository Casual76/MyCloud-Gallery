package dev.pampa.mycloudgallery.core.personal.store

import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFingerprint
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
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
 *   Un id che qui non si risolve (la voce e' di un altro dispositivo, dove la stessa foto ha un altro id) si cerca per impronta nel
 *   catalogo dello [store] ([PersonalStore.mediaWithFingerprint]).
 */
class SimilarCopiesChoices(
  private val store: PersonalStore,
  /**
   * BK-11: una scelta e' stata scritta sullo store (almeno una voce nuova). Chi sincronizza i dati personali parte da qui: "Mostra come
   * principale" e "Separa" non partivano, come invece fanno preferiti e nascosti, e la scelta arrivava agli altri dispositivi solo al giro
   * periodico (fino a sei ore dopo). Di serie niente. Prima di [resolve], che resta l'ultimo parametro (si passa come lambda finale).
   */
  private val onSaved: () -> Unit = {},
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

  /**
   * Le coppie separate con entrambi i media in [ids]: (minore, maggiore) per identita'. Le lapidi (di nuovo unibili) non contano. Una
   * voce scritta da un altro dispositivo nomina i media con i suoi id: chi non si ritrova per id si ritrova per impronta, se l'impronta
   * indica un media solo ([PersonalStore.mediaWithFingerprint]); stesse regole della proiezione (`CatalogPersonalProjection`).
   */
  suspend fun separatedPairs(ids: Collection<MediaId>): Set<Pair<MediaId, MediaId>> {
    if (ids.size < 2) return emptySet()
    val wanted = ids.toSet()
    return separations { it in wanted }.mapTo(LinkedHashSet()) { (_, pair) -> pair }
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
    onSaved()
  }

  /**
   * "Separa": una voce per ogni altra copia del gruppo (la separazione e' simmetrica e vale per sempre). La voce porta l'impronta di
   * entrambi i media: quella del soggetto la mette lo store, quella dell'altro sta nel valore ([SimilarCopiesRecords.apart]), cosi' un
   * altro dispositivo li ritrova anche se li' hanno id diversi.
   */
  suspend fun saveSeparation(member: MediaId, others: Collection<MediaId>) {
    var written = false
    for (other in others) {
      if (other == member) continue
      val key = SimilarCopiesRecords.separation(member.value, other.value)
      val (_, high) = checkNotNull(SimilarCopiesRecords.separatedPair(key))
      store.set(key, SimilarCopiesRecords.apart(store.fingerprintOfMedia(high)))
      written = true
    }
    if (written) onSaved()
  }

  /**
   * "Di nuovo unibili": la lapide su ogni separazione accesa fra [a] e [b], anche su quelle scritte da un altro dispositivo con i suoi
   * id (ritrovate qui per id o per impronta, come in [separatedPairs]): due "Separa" della stessa coppia fatti su due dispositivi con id
   * diversi sono due voci, e la scelta di riunirle le toglie entrambe. Il rilevatore puo' riunire le copie al giro dopo.
   */
  suspend fun removeSeparation(a: MediaId, b: MediaId) {
    val x = runCatching { resolve(a) }.getOrNull() ?: a
    val y = runCatching { resolve(b) }.getOrNull() ?: b
    if (x == y) return
    val wanted = if (x.value <= y.value) x to y else y to x
    var written = false
    for ((key, pair) in separations { it == x || it == y }) {
      if (pair != wanted) continue
      store.set(key, PersonalValue.Removed)
      written = true
    }
    if (written) onSaved()
  }

  /**
   * Le separazioni accese, ognuna con le sue due copie nel catalogo di qui ((minore, maggiore) per identita'): per id, e chi non si
   * ritrova per id per impronta (il soggetto con quella della voce, l'altro con quella del valore). Un'impronta che indica piu' media non
   * ne indica nessuno: quella separazione resta in attesa, mai applicata a una copia a caso. Le due copie sono lo stesso media: niente.
   * Solo le separazioni con entrambe le copie in [accept]: chi ne ha una fuori non si cerca oltre (il rilevatore chiede a ogni secondo).
   */
  private suspend fun separations(accept: (MediaId) -> Boolean): List<Pair<PersonalKey, Pair<MediaId, MediaId>>> {
    val out = ArrayList<Pair<PersonalKey, Pair<MediaId, MediaId>>>()
    val locator = Locator()
    for ((key, entry) in store.effective()) {
      if (key.kind != PersonalKind.MEDIA_FLAG || !PersonalFields.isSimilarApart(key.field)) continue
      if (!SimilarCopiesRecords.separates(entry.value)) continue
      val (low, high) = SimilarCopiesRecords.separatedPair(key) ?: continue
      val a = locator.locate(low, entry.fingerprint)?.takeIf(accept) ?: continue
      val b = locator.locate(high, SimilarCopiesRecords.otherFingerprint(entry.value))?.takeIf(accept) ?: continue
      if (a != b) out += key to (if (a.value <= b.value) a to b else b to a)
    }
    return out
  }

  /**
   * Le voci `similar_cover` accese (o tutte), con l'identita' **attuale** del media a cui si riferiscono: per id, e chi non si ritrova per
   * id per impronta (quella della voce, se indica un media solo). **La stessa regola di "Separa"** ([Locator], 20/10, LA-2 della verifica
   * funzionale della 5B): una voce scritta da un altro dispositivo nomina il media con l'id di li', che qui puo' non esserci (D7, D8);
   * risolta solo per id la scelta restava ignorata e il rilevatore, al giro dopo, rimetteva la copertina della regola.
   */
  private suspend fun coverEntries(onlyOn: Boolean): List<Pair<MediaId, PersonalEntry>> {
    val out = ArrayList<Pair<MediaId, PersonalEntry>>()
    val locator = Locator()
    for ((key, entry) in store.effective()) {
      if (key.kind != PersonalKind.MEDIA_FLAG || key.field != PersonalFields.SIMILAR_COVER) continue
      if (onlyOn && (entry.value as? PersonalValue.Flag)?.on != true) continue
      val media = locator.locate(key.subject, entry.fingerprint) ?: continue
      out += media to entry
    }
    return out
  }

  /**
   * Ritrova un media nominato da una voce: per id (anche tramite un alias, [resolve]) e, se non c'e', per impronta **solo se indica un media
   * solo** ([PersonalStore.mediaWithFingerprint]; due media con la stessa impronta rapida non si confondono, L32). Un id che non e' un'identita'
   * di media (testo ostile) non si cerca nemmeno per impronta. Le risposte si ricordano per tutta la lettura: il rilevatore chiede a ogni secondo.
   */
  private inner class Locator {
    private val byId = HashMap<String, MediaId?>()
    private val byFingerprint = HashMap<PersonalFingerprint, MediaId?>()

    suspend fun locate(raw: String, fingerprint: PersonalFingerprint?): MediaId? {
      val id = runCatching { MediaId(raw) }.getOrNull() ?: return null
      val known = byId.getOrPut(raw) { runCatching { resolve(id) }.getOrNull() }
      if (known != null || fingerprint == null) return known
      return byFingerprint.getOrPut(fingerprint) { store.mediaWithFingerprint(fingerprint) }
    }
  }
}
