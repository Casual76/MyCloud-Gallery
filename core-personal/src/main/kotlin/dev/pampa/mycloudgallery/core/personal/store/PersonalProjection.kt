package dev.pampa.mycloudgallery.core.personal.store

import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFingerprint
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKind
import dev.pampa.mycloudgallery.core.personal.contract.PersonalMerge
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import dev.pampa.mycloudgallery.core.personal.contract.SimilarCopiesRecords

// Dallo stato unito allo stato dell'app: i preferiti e i nascosti vivono nel catalogo (`media_personal`), che e' cio' che la Libreria
// legge (E-H1: un nascosto non compare in Libreria, filtri, Raccolte, ricerche). La proiezione e' a senso unico: lo stato unito
// decide, il catalogo lo segue. Gli album, i ricordi e le impostazioni non hanno (ancora) una tabella che li segua: vivono nello
// stato unito e li legge chi li usa (`PersonalAlbums`, `SyncedPersonalSettings`).

/** Come e' andata l'applicazione di una voce. */
enum class ProjectionResult {
  /** Il catalogo ha ora il valore della voce. */
  APPLIED,

  /** Il media non e' (ancora) nel catalogo di questo dispositivo: la voce resta nello stato e si riprova. */
  UNRESOLVED,

  /** Questa voce non ha una proiezione (album, ricordi, impostazioni, campi sconosciuti): niente da fare. */
  NOT_PROJECTED,
}

/** Il valore di un flag nel catalogo locale: una riga di `media_personal` ha sempre entrambi. */
data class LocalFlag(val mediaId: String, val field: String, val on: Boolean)

interface PersonalProjection {
  /** Porta nel catalogo il valore di [entry]. Idempotente. Mai un'eccezione per un media sconosciuto. */
  suspend fun apply(entry: PersonalEntry): ProjectionResult

  /**
   * Come [apply], ma con lo stato unito (e le modifiche locali non ancora datate: [PersonalStore.effective]) per le voci che si
   * decidono da piu' voci insieme: la copertina scelta fra copie simili (vince la piu' recente fra quelle del gruppo) e le separazioni
   * (chi esce dal gruppo dipende da tutte le separazioni dei suoi membri). Di serie ignora lo stato.
   */
  suspend fun apply(entry: PersonalEntry, state: Map<PersonalKey, PersonalEntry>): ProjectionResult = apply(entry)

  /** Dimensione e `qh1` del media a cui si riferisce [key] (per ritrovarlo su un altro dispositivo, §2.9), o null. */
  suspend fun fingerprintOf(key: PersonalKey): PersonalFingerprint?

  /** Dimensione e `qh1` del media [mediaId] (l'altro media di una separazione, [PersonalValue.Apart]), o null. Di serie null. */
  suspend fun fingerprintOfMedia(mediaId: String): PersonalFingerprint? = null

  /** Il media con l'impronta [fingerprint], solo se e' uno (mai uno a caso fra piu' candidati), o null. Di serie null. */
  suspend fun mediaWithFingerprint(fingerprint: PersonalFingerprint): MediaId? = null

  /**
   * I preferiti e i nascosti di ogni media che ha una riga nel catalogo (acceso o spento), qualunque ne sia la storia: lo store
   * adotta come proprie le differenze dallo stato unito che non sono ancora una voce (preferiti fatti prima che la
   * sincronizzazione esistesse, o dalla Libreria senza passare dallo store).
   */
  suspend fun localFlags(): List<LocalFlag>
}

/** Nessuna proiezione (i test dello stato, i dispositivi senza catalogo). */
object NoProjection : PersonalProjection {
  override suspend fun apply(entry: PersonalEntry): ProjectionResult = ProjectionResult.NOT_PROJECTED

  override suspend fun fingerprintOf(key: PersonalKey): PersonalFingerprint? = null

  override suspend fun localFlags(): List<LocalFlag> = emptyList()
}

/**
 * La proiezione sul catalogo vero (`MediaCatalog.setPersonalFlags`: stessa transazione e stesso "cambiamento strutturale" di
 * quando l'utente nasconde o preferisce dalla Libreria, quindi l'indice della Libreria e i derivati si aggiornano).
 *
 * L'identita' del media: l'id del catalogo (un'identita' assorbita da un'unione vale per il sopravvissuto, `MediaCatalog.resolve`);
 * se non lo conosce, l'impronta della voce (dimensione e `qh1`): si usa **solo se indica un media solo**. Due media con la stessa
 * impronta rapida (L32: differiscono solo nel mezzo) non si confondono mai, nemmeno per un "nascosto".
 */
class CatalogPersonalProjection(
  private val catalog: MediaCatalog,
  private val database: GalleryDatabase,
) : PersonalProjection {

  override suspend fun apply(entry: PersonalEntry): ProjectionResult = apply(entry, emptyMap())

  override suspend fun apply(entry: PersonalEntry, state: Map<PersonalKey, PersonalEntry>): ProjectionResult {
    val key = entry.key
    if (key.kind != PersonalKind.MEDIA_FLAG) return ProjectionResult.NOT_PROJECTED
    // Fase 5A, D36: le due scelte dell'utente sulle copie simili sono voci `MEDIA_FLAG` con campi nuovi (E-L13).
    if (key.field == PersonalFields.SIMILAR_COVER) return applySimilarCover(entry, state)
    if (PersonalFields.isSimilarApart(key.field)) return applySimilarApart(entry, state)
    val favorite = when (key.field) {
      PersonalFields.FAVORITE -> true
      PersonalFields.HIDDEN -> false
      else -> return ProjectionResult.NOT_PROJECTED
    }
    // Un soggetto che non e' un'identita' di media (testo ostile) non ha niente da proiettare; uno valido ma sconosciuto si riprova.
    val id = resolve(key.subject, entry.fingerprint)
      ?: return if (mediaIdOrNull(key.subject) == null) ProjectionResult.NOT_PROJECTED else ProjectionResult.UNRESOLVED
    // Una lapide su un flag vuol dire "spento".
    val on = (entry.value as? PersonalValue.Flag)?.on ?: false
    catalog.setPersonalFlags(listOf(id)) { flags -> if (favorite) flags.copy(favorite = on) else flags.copy(hidden = on) }
    return ProjectionResult.APPLIED
  }

  /**
   * "Mostra questa come principale" (D36): la copertina del gruppo di copie simili che contiene il media. In un gruppo vale la voce
   * accesa **piu' recente** fra quelle dei suoi membri ([PersonalMerge.compare], un ordine totale uguale su ogni dispositivo): due
   * scelte su due dispositivi convergono alla stessa. Una voce spenta (l'utente ha scelto un'altra copia) non fa niente: la scelta
   * accesa che la sostituisce ha la sua. Un media non ancora in un gruppo non ha niente da proiettare ora: chi forma i gruppi legge
   * la scelta dallo stato.
   */
  private suspend fun applySimilarCover(entry: PersonalEntry, state: Map<PersonalKey, PersonalEntry>): ProjectionResult {
    if ((entry.value as? PersonalValue.Flag)?.on != true) return ProjectionResult.APPLIED
    val id = resolve(entry.key.subject, entry.fingerprint)
      ?: return if (mediaIdOrNull(entry.key.subject) == null) ProjectionResult.NOT_PROJECTED else ProjectionResult.UNRESOLVED
    val group = catalog.similarGroupOf(id) ?: return ProjectionResult.APPLIED
    val members = group.members.map { it.mediaId }.toSet()
    val candidates = LinkedHashMap<MediaId, PersonalEntry>()
    // Le voci dello stato (con l'id che avevano quando sono state scritte: un'unione o l'adozione dell'indice puo' averlo sostituito,
    // e una voce di un altro dispositivo puo' averne uno che qui non c'e': allora si ritrova per impronta).
    for ((key, other) in state) {
      if (key.kind != PersonalKind.MEDIA_FLAG || key.field != PersonalFields.SIMILAR_COVER || key == entry.key) continue
      if ((other.value as? PersonalValue.Flag)?.on != true) continue
      // Con l'id con cui e' scritta o, se qui non c'e' (voce di un altro dispositivo), con l'impronta: la stessa regola di "Separa" (LA-2).
      val media = resolve(key.subject, other.fingerprint) ?: continue
      if (media in members) candidates.merge(media, other) { old, new -> if (PersonalMerge.compare(new, old) > 0) new else old }
    }
    candidates.merge(id, entry) { old, new -> if (PersonalMerge.compare(new, old) > 0) new else old }
    // Una voce spenta (o senza altre accese) non cambia la copertina: il rilevatore applica la sua regola.
    val winner = candidates.entries.maxWithOrNull { a, b -> PersonalMerge.compare(a.value, b.value) }?.key ?: return ProjectionResult.APPLIED
    if (group.cover != winner) catalog.setSimilarCover(group.group, winner)
    return ProjectionResult.APPLIED
  }

  /**
   * "Separa" (D36, E-L13): due copie che l'utente ha detto non essere copie non stanno nello stesso gruppo. Se oggi lo sono, escono
   * dal gruppo il media con piu' separazioni **dentro il gruppo** (chi e' stato separato ha una voce per ogni altra copia, quindi ha il
   * grado piu' alto), uno alla volta, finche' nel gruppo non resta nessuna coppia separata. Se il grado piu' alto e' di piu' media (le
   * voci di una stessa separazione si scrivono una alla volta) non si sceglie: si aspetta la voce dopo, e se non arriva il rilevatore
   * rifa' i gruppi con tutte le separazioni. Rimasti in due, separati, il gruppo si scioglie (`MediaCatalog.separate`). Non scrive voci:
   * il rilevatore legge le stesse voci e non rifa' mai il gruppo. Una lapide (di nuovo unibili) non fa niente: al giro dopo il
   * rilevatore puo' riunirle.
   *
   * **Id diversi** (20/10). La voce nomina i due media con gli id del dispositivo che l'ha scritta, che qui possono non esserci (D7, D8):
   * il soggetto si ritrova anche con l'impronta della voce, l'altro con quella del valore ([PersonalValue.Apart]); una voce del formato
   * di prima (`true`) ritrova l'altro solo per id. Un'impronta che indica piu' media non indica nessuno: la voce resta in attesa e non
   * separa una copia a caso (E-S11: una voce letta dal NAS e' un dato). Lo stesso per le separazioni dello stato. Chi esce non dipende
   * mai dagli id locali: dai legami (gli stessi su ogni dispositivo), e due soli media escono entrambi.
   */
  private suspend fun applySimilarApart(entry: PersonalEntry, state: Map<PersonalKey, PersonalEntry>): ProjectionResult {
    val (low, high) = SimilarCopiesRecords.separatedPair(entry.key) ?: return ProjectionResult.NOT_PROJECTED
    if (mediaIdOrNull(low) == null || mediaIdOrNull(high) == null) return ProjectionResult.NOT_PROJECTED
    if (!SimilarCopiesRecords.separates(entry.value)) return ProjectionResult.APPLIED
    val a = resolve(low, entry.fingerprint)
    val b = resolve(high, SimilarCopiesRecords.otherFingerprint(entry.value))
    if (a == null || b == null) return ProjectionResult.UNRESOLVED
    // Le due copie sono qui un media solo (un'unione): non c'e' niente da separare.
    if (a == b) return ProjectionResult.APPLIED
    val group = catalog.similarGroupOf(a) ?: return ProjectionResult.APPLIED
    val members = group.members.map { it.mediaId }
    if (b !in members) return ProjectionResult.APPLIED
    // Le coppie separate dentro il gruppo: questa e quelle dello stato (con gli id con cui sono state scritte, ritrovate qui per id o impronta).
    val edges = HashSet<Pair<MediaId, MediaId>>()
    edges += orderedPair(a, b)
    val inGroup = members.toSet()
    for ((stateKey, separation) in state) {
      if (stateKey.kind != PersonalKind.MEDIA_FLAG || !PersonalFields.isSimilarApart(stateKey.field)) continue
      val pair = separatedMedia(stateKey, separation) ?: continue
      if (pair.first in inGroup && pair.second in inGroup) edges += pair
    }
    val remaining = members.toMutableSet()
    while (edges.isNotEmpty()) {
      if (remaining.size == 2) {
        // Due soli media, separati: il gruppo si scioglie, ed escono entrambi. Nessuna scelta di chi esce, quindi niente che dipenda dagli
        // id locali (la stessa foto puo' averne due diversi su due dispositivi) ne' dall'ordine di arrivo delle voci.
        remaining.forEach { catalog.separate(it) }
        break
      }
      val degree = HashMap<MediaId, Int>()
      for ((x, y) in edges) {
        degree[x] = (degree[x] ?: 0) + 1
        degree[y] = (degree[y] ?: 0) + 1
      }
      val most = degree.values.max()
      val top = degree.filterValues { it == most }.keys
      // Piu' media con lo stesso grado (di solito la prima di piu' voci di una stessa separazione, scritte una alla volta): non si sa
      // ancora chi e' stato separato. Si aspetta la voce dopo; se non arriva, il rilevatore rifa' i gruppi con tutte le separazioni.
      if (top.size != 1) break
      val leaver = top.single()
      catalog.separate(leaver)
      remaining -= leaver
      edges.removeAll { it.first == leaver || it.second == leaver }
    }
    return ProjectionResult.APPLIED
  }

  /**
   * Le due copie di una separazione accesa ([key], [entry]) nel catalogo di qui, per id o per impronta, in ordine d'identita'. Null se non
   * e' una separazione accesa, se un media non si ritrova (o la sua impronta ne indica piu' d'uno) o se sono qui lo stesso media.
   */
  private suspend fun separatedMedia(key: PersonalKey, entry: PersonalEntry): Pair<MediaId, MediaId>? {
    if (!SimilarCopiesRecords.separates(entry.value)) return null
    val (lowRaw, highRaw) = SimilarCopiesRecords.separatedPair(key) ?: return null
    if (mediaIdOrNull(lowRaw) == null || mediaIdOrNull(highRaw) == null) return null
    val x = resolve(lowRaw, entry.fingerprint) ?: return null
    val y = resolve(highRaw, SimilarCopiesRecords.otherFingerprint(entry.value)) ?: return null
    return if (x == y) null else orderedPair(x, y)
  }

  private fun orderedPair(x: MediaId, y: MediaId): Pair<MediaId, MediaId> = if (x.value <= y.value) x to y else y to x

  override suspend fun fingerprintOf(key: PersonalKey): PersonalFingerprint? =
    if (key.kind != PersonalKind.MEDIA_FLAG) null else fingerprintOfMedia(key.subject)

  override suspend fun fingerprintOfMedia(mediaId: String): PersonalFingerprint? {
    val id = mediaIdOrNull(mediaId) ?: return null
    val media = catalog.media(id) ?: return null
    val fingerprint = media.currentVersion.fingerprint
    return PersonalFingerprint(fingerprint.size, fingerprint.quickHash)
  }

  override suspend fun mediaWithFingerprint(fingerprint: PersonalFingerprint): MediaId? =
    database.versionDao().byQuickFingerprint(fingerprint.size, fingerprint.quickHash.hex)
      .mapNotNull { catalog.resolve(MediaId(it.mediaId)) }
      .distinct()
      .singleOrNull()

  override suspend fun localFlags(): List<LocalFlag> {
    val out = ArrayList<LocalFlag>()
    database.openHelper.readableDatabase
      .query("SELECT media_id, favorite, hidden FROM media_personal")
      .use { cursor ->
        while (cursor.moveToNext()) {
          val id = cursor.getString(0)
          out += LocalFlag(id, PersonalFields.FAVORITE, cursor.getInt(1) != 0)
          out += LocalFlag(id, PersonalFields.HIDDEN, cursor.getInt(2) != 0)
        }
      }
    return out
  }

  private fun mediaIdOrNull(subject: String): MediaId? = try {
    MediaId.parse(subject)
  } catch (e: IllegalArgumentException) {
    null
  }

  private suspend fun resolve(subject: String, fingerprint: PersonalFingerprint?): MediaId? {
    mediaIdOrNull(subject)?.let { id -> catalog.resolve(id)?.let { return it } }
    return fingerprint?.let { mediaWithFingerprint(it) }
  }
}
