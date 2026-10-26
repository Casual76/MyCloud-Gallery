package dev.pampa.mycloudgallery.core.library.duplicates

import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.database.SimilarCandidate
import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.library.similar.SignaturePixels
import dev.pampa.mycloudgallery.core.library.similar.SignatureSource
import dev.pampa.mycloudgallery.core.library.similar.VisualSignatures
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.model.NasArea
import dev.pampa.mycloudgallery.core.model.SafetyStatus
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.model.VisualSignature
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Fase 7, P8 (docs/fase7-progetto.md D25, §2.1.1): i DOPPIONI SIMILI sul catalogo vero. Solo letture, e le firme visive che mancano (locali,
// mai pubblicate). Nessuna cancellazione parte da qui.

/** Le porte di sola lettura dei doppioni simili, legate da `:app` (core-library non vede ne' l'indice della Fase 6 ne' gli album). */
fun interface ShotVectorSource {
  /** I vettori dei media che ne hanno uno (della ricetta in uso); gli altri mancano dalla mappa. Mai un errore per un vettore che manca. */
  suspend fun vectorsOf(media: Collection<MediaId>): Map<MediaId, FloatArray>
}

/** Quali media stanno in almeno un album (manuale, smart o condiviso): una foto in un album e' protetta (E-U3). */
fun interface AlbumMembership {
  suspend fun inAlbums(media: Collection<MediaId>): Set<MediaId>
}

/** I doppioni simili: un lotto di giorni per volta, dal piu' recente. */
interface SimilarShots {

  /**
   * Cerca i gruppi nei giorni **prima** di [beforeDay] (`aaaammgg`; null = dal piu' recente), un giorno dopo l'altro, per al massimo
   * [budget] (almeno un giorno per volta). Solo letture. [SimilarBatch.nextBeforeDay] dice da dove riprendere; null = finito.
   */
  suspend fun scan(beforeDay: Int?, budget: Duration): SimilarBatch

  /**
   * Manutenzione: la firma visiva dei candidati che hanno un compagno nella finestra e non hanno ne' vettore ne' firma (la Fase 5 la firmava
   * solo nello stesso secondo). Al massimo [budget]; idempotente, riprende da sola dai giorni piu' recenti.
   */
  suspend fun sign(budget: Duration): SigningReport

  /** Dove stanno i media [media] (telefono, area privata, area comune), per il foglio di eliminazione. Mai un nascosto. */
  suspend fun placesOf(media: Collection<MediaId>): RemovalPlaces
}

/** Un lotto di gruppi. [undecided]: scatti con compagni vicini che non si sono potuti giudicare (nessun vettore, nessuna firma). */
class SimilarBatch(val clusters: List<SimilarCluster>, val nextBeforeDay: Int?, val undecided: Int, val failed: Boolean = false) {
  override fun toString(): String = "SimilarBatch(clusters=${clusters.size}, undecided=$undecided, failed=$failed)"

  companion object {
    val EMPTY: SimilarBatch = SimilarBatch(emptyList(), null, 0)
  }
}

/** Il resoconto di una manutenzione delle firme: soli numeri. */
class SigningReport(val signed: Int, val waiting: Int, val skipped: Int, val finished: Boolean) {
  override fun toString(): String = "SigningReport(signed=$signed, waiting=$waiting, skipped=$skipped, finished=$finished)"
}

/**
 * Le copie di un insieme di media che un'eliminazione toccherebbe, divise per posto. Le copie in un'area di un altro account non ci sono
 * (nessun permesso). [unsafePhoneMedia] sono i media con una copia sul telefono e nessuna al sicuro (E-D14).
 */
class RemovalPlaces(
  val media: Int,
  val phone: List<PhoneTrashRef>,
  val nasPrivate: List<NasTrashRef>,
  val nasCommon: List<NasTrashRef>,
  val unsafePhoneMedia: Int,
) {
  /** Il piano di "Solo dal telefono": le copie del telefono. */
  val phoneOnly: CleanupPlan get() = CleanupPlan(media, phone, emptyList(), unsafePhoneMedia, 0)

  /** Il piano di "Ovunque": telefono, area privata e area comune (dove si hanno i diritti). */
  val everywhere: CleanupPlan get() = CleanupPlan(media, phone, nasPrivate + nasCommon, unsafePhoneMedia, 0)

  override fun toString(): String = "RemovalPlaces(media=$media, phone=${phone.size}, private=${nasPrivate.size}, common=${nasCommon.size})"

  companion object {
    val EMPTY: RemovalPlaces = RemovalPlaces(0, emptyList(), emptyList(), emptyList(), 0)
  }
}

/** Nessun doppione simile (nessun catalogo). */
object NoSimilarShots : SimilarShots {
  override suspend fun scan(beforeDay: Int?, budget: Duration): SimilarBatch = SimilarBatch.EMPTY

  override suspend fun sign(budget: Duration): SigningReport = SigningReport(0, 0, 0, finished = true)

  override suspend fun placesOf(media: Collection<MediaId>): RemovalPlaces = RemovalPlaces.EMPTY
}

/**
 * La lettura sul catalogo e sull'istantanea della Libreria.
 *
 * **Candidati**: le celle **foto** dell'istantanea di [visible] (che non ha nascosti, E-H1, e ha una cella per gruppo), fuori dalle celle che
 * sono gia' un gruppo (raffica, RAW+JPEG, copie simili, "da verificare": non si raggruppa un gruppo). Dopo la formazione dei gruppi il filtro
 * dei nascosti si rifa' con [hidden], l'ultimo passo prima di consegnare (D22): un media nascosto un attimo fa non compare.
 *
 * @param signatures chi sa dare i pixel per firmare; null = [sign] non fa niente (le firme restano quelle della Fase 5).
 */
class CatalogSimilarShots(
  private val catalog: MediaCatalog,
  private val visible: VisibleMedia,
  private val hidden: HiddenMask,
  private val vectors: ShotVectorSource = ShotVectorSource { emptyMap() },
  private val albums: AlbumMembership = AlbumMembership { emptySet() },
  private val signatures: SignatureSource? = null,
  private val timeSource: TimeSource = TimeSource.Monotonic,
  private val compute: CoroutineDispatcher = Dispatchers.Default,
) : SimilarShots {

  /** I giorni (dal piu' recente) con le posizioni delle loro celle candidate, per un'istantanea: si rifa quando l'indice cambia. */
  private class DayIndex(val seq: Long, val snapshot: TimelineSnapshot, val days: List<Pair<Int, IntArray>>)

  @Volatile
  private var cached: DayIndex? = null

  override suspend fun scan(beforeDay: Int?, budget: Duration): SimilarBatch {
    val started = timeSource.markNow()
    try {
      val index = dayIndex() ?: return SimilarBatch.EMPTY
      val clusters = ArrayList<SimilarCluster>()
      var undecided = 0
      var last: Int? = null
      for ((day, positions) in index.days) {
        if (beforeDay != null && day >= beforeDay) continue
        if (last != null && started.elapsedNow() >= budget) return SimilarBatch(clusters, last, undecided)
        val shots = positions.map { shotAt(index.snapshot, it) }
        val plan = clusterDay(index.snapshot, shots)
        clusters += plan.first
        undecided += plan.second
        last = day
      }
      return SimilarBatch(clusters, null, undecided)
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      return SimilarBatch(emptyList(), null, 0, failed = true)
    }
  }

  /** I gruppi di un giorno e quanti scatti non si sono potuti giudicare. */
  private suspend fun clusterDay(snapshot: TimelineSnapshot, shots: List<Shot>): Pair<List<SimilarCluster>, Int> {
    val withNeighbors = shotsWithNeighbors(shots)
    if (withNeighbors.isEmpty()) return emptyList<SimilarCluster>() to 0
    val ids = withNeighbors.map { it.media }
    val vectorMap = vectors.vectorsOf(ids)
    val signed = signaturesOf(ids.filter { it !in vectorMap })
    val affinity = CombinedAffinity(VectorAffinity(ShotVectors(vectorMap)), SignatureAffinity(signed))
    val plan = SimilarShotsPlanner.plan(shots, affinity)
    if (plan.clusters.isEmpty()) return emptyList<SimilarCluster>() to plan.undecided
    val members = plan.clusters.flatten().map { it.media }.distinct()
    // L'ultimo passo prima di consegnare: i nascosti (anche di un attimo fa) e chi non e' piu' visibile escono, e un gruppo con meno di due resta fuori.
    val stillHidden = hidden.hiddenAmong(members)
    val shown = visible.visible(members) - stillHidden
    val details = detailsOf(snapshot, shown)
    val clusters = plan.clusters.mapNotNull { cluster ->
      val kept = cluster.filter { it.media in shown }
      if (kept.size < 2) null else SimilarShotsPlanner.cluster(kept, details)
    }
    return clusters to plan.undecided
  }

  /** Gli scatti con almeno un compagno nella finestra (e di proporzione compatibile): gli unici che servono vettore o firma. */
  private fun shotsWithNeighbors(shots: List<Shot>): List<Shot> {
    val ordered = shots.sortedBy { it.takenAt }
    val out = LinkedHashSet<Shot>()
    for (i in ordered.indices) {
      var j = i + 1
      while (j < ordered.size && j - i <= SimilarShotsRules.MAX_NEIGHBORS && ordered[j].takenAt - ordered[i].takenAt <= SimilarShotsRules.WINDOW_MS) {
        if (SimilarShotsRules.aspectCompatible(ordered[i].aspectMilli, ordered[j].aspectMilli)) {
          out += ordered[i]
          out += ordered[j]
        }
        j++
      }
    }
    return out.toList()
  }

  private suspend fun signaturesOf(media: Collection<MediaId>): Map<MediaId, VisualSignature> {
    if (media.isEmpty()) return emptyMap()
    val facts = catalog.albums.facts(media)
    val byVersion: Map<VersionId, VisualSignature> = catalog.signaturesOf(facts.values.map { it.version }.distinct())
    return media.mapNotNull { id -> facts[id]?.let { fact -> byVersion[fact.version]?.let { id to it } } }.toMap()
  }

  private suspend fun detailsOf(snapshot: TimelineSnapshot, media: Collection<MediaId>): Map<MediaId, ShotDetails> {
    if (media.isEmpty()) return emptyMap()
    val facts = catalog.albums.facts(media)
    val cells = catalog.albums.candidatesOf(media).associateBy { it.media }
    val inAlbum = albums.inAlbums(media)
    return media.associateWith { id ->
      val position = snapshot.indexOf(id)
      val flags = if (position >= 0) snapshot.flagsAt(position) else 0
      ShotDetails(
        favorite = CellFlags.has(flags, CellFlags.FAVORITE),
        edited = CellFlags.has(flags, CellFlags.EDITED),
        inAlbum = id in inAlbum,
        pixels = cells[id]?.pixels ?: 0,
        bytes = facts[id]?.size ?: 0,
      )
    }
  }

  private fun shotAt(snapshot: TimelineSnapshot, i: Int): Shot =
    Shot(snapshot.idAt(i), snapshot.sortTimeAt(i), snapshot.localDayAt(i), snapshot.aspectMilliAt(i))

  private fun dayIndex(): DayIndex? {
    val snapshot = visible.snapshot.value ?: return null
    cached?.let { if (it.snapshot === snapshot || it.seq == snapshot.seq) return it }
    val byDay = java.util.TreeMap<Int, MutableList<Int>>(Comparator.reverseOrder())
    for (i in 0 until snapshot.size) {
      if (!isCandidate(snapshot, i)) continue
      byDay.getOrPut(snapshot.localDayAt(i)) { ArrayList() } += i
    }
    val days = byDay.entries.filter { it.value.size >= 2 }.map { it.key to it.value.toIntArray() }
    return DayIndex(snapshot.seq, snapshot, days).also { cached = it }
  }

  private fun isCandidate(snapshot: TimelineSnapshot, i: Int): Boolean {
    val kind = snapshot.kindAt(i)
    if (kind.isVideo || kind == dev.pampa.mycloudgallery.core.model.MediaKind.ANIMATED_IMAGE) return false
    if (snapshot.localDayAt(i) <= 0 || snapshot.aspectMilliAt(i) <= 0) return false
    val flags = snapshot.flagsAt(i)
    return flags and NOT_A_CANDIDATE == 0
  }

  // --- Manutenzione delle firme -----------------------------------------------------------------------------------------------

  override suspend fun sign(budget: Duration): SigningReport {
    val source = signatures ?: return SigningReport(0, 0, 0, finished = true)
    val started = timeSource.markNow()
    var signed = 0
    var waiting = 0
    var skipped = 0
    try {
      val index = dayIndex() ?: return SigningReport(0, 0, 0, finished = true)
      for ((_, positions) in index.days) {
        if (started.elapsedNow() >= budget) return SigningReport(signed, waiting, skipped, finished = false)
        val shots = positions.map { shotAt(index.snapshot, it) }
        val need = shotsWithNeighbors(shots).map { it.media }
        if (need.isEmpty()) continue
        val withVector = vectors.vectorsOf(need).keys
        val missing = need.filter { it !in withVector }
        if (missing.isEmpty()) continue
        val facts = catalog.albums.facts(missing)
        val existing = catalog.signaturesOf(facts.values.map { it.version }.distinct())
        for (id in missing) {
          if (started.elapsedNow() >= budget) return SigningReport(signed, waiting, skipped, finished = false)
          val fact = facts[id] ?: continue
          if (existing[fact.version]?.algorithm == VisualSignatures.ALGORITHM) continue
          val position = index.snapshot.indexOf(id)
          if (position < 0) continue
          val candidate = SimilarCandidate(
            mediaId = id,
            versionId = fact.version,
            kind = index.snapshot.kindAt(position),
            takenAtUtcMillis = index.snapshot.sortTimeAt(position),
            aspectMilli = index.snapshot.aspectMilliAt(position),
            durationMs = null,
            group = null,
            signatureAlgorithm = null,
          )
          when (val pixels = askPixels(source, candidate)) {
            is SignaturePixels.Ready -> {
              val signature = try {
                withContext(compute) { VisualSignatures.of(pixels.frames.take(1)) }
              } catch (_: IllegalArgumentException) {
                skipped++
                continue
              }
              if (catalog.saveSignature(fact.version, signature)) signed++ else skipped++
            }
            is SignaturePixels.Later -> waiting++
            SignaturePixels.Never -> skipped++
          }
        }
      }
      return SigningReport(signed, waiting, skipped, finished = true)
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      return SigningReport(signed, waiting, skipped, finished = false)
    }
  }

  private suspend fun askPixels(source: SignatureSource, candidate: SimilarCandidate): SignaturePixels = try {
    source.pixelsOf(candidate)
  } catch (e: CancellationException) {
    throw e
  } catch (_: Exception) {
    SignaturePixels.Never
  }

  // --- Dove stanno i media da eliminare ---------------------------------------------------------------------------------------

  override suspend fun placesOf(media: Collection<MediaId>): RemovalPlaces {
    if (media.isEmpty()) return RemovalPlaces.EMPTY
    val shown = try {
      visible.visible(media.distinct()) - hidden.hiddenAmong(media.distinct())
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      emptySet()
    }
    val phone = ArrayList<PhoneTrashRef>()
    val privateCopies = ArrayList<NasTrashRef>()
    val commonCopies = ArrayList<NasTrashRef>()
    var unsafe = 0
    var count = 0
    for (id in media.distinct()) {
      if (id !in shown) continue
      val current = catalog.versionLocations(id).firstOrNull { it.isCurrent } ?: continue
      count++
      val mime = runCatching { catalog.versionMetadata(current.versionId)?.mimeType }.getOrNull()
      var phoneHere = false
      for (located in current.locations) {
        val place = located.location
        if (!place.isPresent) continue
        when (val where = place.location) {
          is Location.Phone -> {
            val msId = where.mediaStoreId ?: continue
            phone += PhoneTrashRef(MediaRef.Phone(where.volume, msId, mime), place.stamp.size)
            phoneHere = true
          }
          is Location.Nas -> {
            val area = when (located.area) {
              NasArea.PRIVATE -> CopyArea.MY_PRIVATE
              NasArea.COMMON -> CopyArea.COMMON
              null -> continue
            }
            val ref = NasTrashRef(where.share, where.path.value, place.stamp.size, place.stamp.modifiedAtMillis, where.fileId, area)
            if (area == CopyArea.MY_PRIVATE) privateCopies += ref else commonCopies += ref
          }
        }
      }
      if (phoneHere && catalog.mediaSafety(id)?.status != SafetyStatus.SAFE) unsafe++
    }
    return RemovalPlaces(count, phone, privateCopies, commonCopies, unsafe)
  }

  private companion object {
    /** Le celle che sono gia' un gruppo (raffica, RAW+JPEG, copie simili, "da verificare") o non si leggono: non si raggruppano. */
    const val NOT_A_CANDIDATE: Int = CellFlags.GROUP_COVER or CellFlags.GROUP_MEMBER or CellFlags.SIMILAR_COPIES or CellFlags.TO_VERIFY or CellFlags.UNREADABLE
  }
}
