package dev.pampa.mycloudgallery.core.map.testing

import dev.pampa.mycloudgallery.core.map.contract.GeoGrid
import dev.pampa.mycloudgallery.core.map.contract.MapCoverScore
import dev.pampa.mycloudgallery.core.map.contract.MapPoint
import dev.pampa.mycloudgallery.core.map.contract.MapPointValidity
import dev.pampa.mycloudgallery.core.map.points.MapPointPager
import dev.pampa.mycloudgallery.core.map.points.MapPointRaw
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import java.util.Random
import java.util.UUID

// LA LIBRERIA SINTETICA CON COORDINATE (Fase 9, P3; docs/fase9-progetto.md §5.1, §5.2, D27, D31, E-MAP5, E-MAP7, E-MAP9, E-MAP10). Per le prove dei
// punti e dell'indice (P3), delle schermate (P4: i nascosti non escono dalle bolle) e del banco (P12). Sta in `testing/` di core-map, quindi in
// `src/main` ma **mai nel dex di release** (`tools/check.ps1`). Nessun dato vero: id, coordinate, luoghi e giorni sono inventati dal seme.

/**
 * Un media della Libreria sintetica, con i fatti che decidono se la **query dei punti** lo vede: nascosto, presente in qualche posto, membro di
 * un gruppo di copie (e quale), e le sue coordinate **effettive** (la correzione di luogo, se c'e', e' gia' applicata: e' la regola del pannello
 * info, D27). Coordinate e id non compaiono in `toString()` (D33).
 */
class SyntheticMapMedia(
  val id: MediaId,
  val latitude: Double?,
  val longitude: Double?,
  val localDay: Int,
  val flags: Int,
  val source: MediaSource,
  val kind: MediaKind,
  val durationMs: Long?,
  val placeId: Int?,
  val hidden: Boolean,
  val present: Boolean,
  val groupId: String?,
) {
  /** Un membro di gruppo che non lo rappresenta: fuori dall'indice, resta solo la copertina (D20). */
  val groupMember: Boolean get() = CellFlags.has(flags, CellFlags.GROUP_MEMBER)

  override fun toString(): String = "SyntheticMapMedia"
}

/**
 * Una Libreria sintetica con le coordinate, e **il modello della query dei punti**: un media e' una cella visibile se e' presente in qualche
 * posto, non e' nascosto, non e' un membro di gruppo dietro la copertina e il suo gruppo non ha **nessun** nascosto (D31: un gruppo con un
 * nascosto e' nascosto per intero, anche se il segno non e' arrivato a tutti i membri). `MapPointsQueries` ha lo stesso predicato in SQL e un
 * test lo confronta con questo modello; [pager] lo da' a `PagedMapPointSource` e all'indice senza database.
 */
class SyntheticMapLibrary(val media: List<SyntheticMapMedia>) {

  private val hiddenGroups: Set<String> = media.filter { it.hidden && it.groupId != null }.map { it.groupId!! }.toSet()

  /** Le celle visibili (con o senza posizione). */
  val visible: List<SyntheticMapMedia> = media.filter { isVisible(it) }

  /** Le celle visibili con coordinate (valide o no): quelle che la query consegna. */
  val withCoordinates: List<SyntheticMapMedia> = visible.filter { it.latitude != null && it.longitude != null }

  /** Quanti media visibili non hanno coordinate (E-MAP5). */
  val withoutPosition: Int = visible.count { it.latitude == null || it.longitude == null }

  /** Gli id che **non** devono comparire mai in una bolla, in una copertina o fra i membri: tutto cio' che non e' una cella visibile. */
  val neverShown: Set<MediaId> = media.filter { !isVisible(it) }.map { it.id }.toSet()

  private fun isVisible(m: SyntheticMapMedia): Boolean = m.present && !m.hidden && !m.groupMember && (m.groupId == null || m.groupId !in hiddenGroups)

  /** I punti che la lettura deve dare all'indice: le celle visibili con coordinate valide (E-MAP7), come `PagedMapPointSource` li converte. */
  fun expectedPoints(): List<MapPoint> = withCoordinates.mapNotNull { m ->
    val e6 = MapPointValidity.toE6(m.latitude!!, m.longitude!!) ?: return@mapNotNull null
    MapPoint(m.id, e6.latE6, e6.lonE6, m.localDay, MapCoverScore.of(m.flags, m.source, m.kind, m.durationMs ?: 0L), m.placeId ?: 0)
  }

  /** Quante coordinate la lettura deve scartare (E-MAP7). */
  fun expectedInvalid(): Int = withCoordinates.count { MapPointValidity.toE6(it.latitude!!, it.longitude!!) == null }

  /** La query dei punti in memoria: righe visibili con coordinate, in ordine di id, a pagine per chiave. */
  fun pager(): MapPointPager = FakeMapPager(this)

  override fun toString(): String = "SyntheticMapLibrary"

  companion object {
    /**
     * [count] media deterministici dal [seed]: fotografie attorno a pochi centri (E-MAP9) e sparse, e in mezzo i casi che la query deve
     * gestire: senza posizione (~10%), coordinate assurde ((0, 0), oltre i limiti, ~1%), nascosti (~2%), assenti (~1%), gruppi di 2-4 copie
     * (~3% dei media, la copertina e i membri) con un nascosto **solo a meta'** (un quarto dei gruppi), e vicino all'antimeridiano e ai poli.
     */
    fun generate(count: Int, seed: Long = 5): SyntheticMapLibrary {
      val random = Random(seed)
      val centers = List(6) { (random.nextInt(140_000_000) - 70_000_000) / 1e6 to (random.nextInt(340_000_000) - 170_000_000) / 1e6 }
      val out = ArrayList<SyntheticMapMedia>(count)
      while (out.size < count) {
        val inGroup = random.nextInt(100) < 3
        val size = if (inGroup) 2 + random.nextInt(3) else 1
        val groupId = if (inGroup) UUID(random.nextLong(), random.nextLong()).toString() else null
        val where = coordinates(random, centers)
        val hiddenMember = if (inGroup && random.nextInt(4) == 0) random.nextInt(size) else -1
        for (k in 0 until size) {
          if (out.size >= count) break
          val member = inGroup && k > 0
          val wholeHidden = !inGroup && random.nextInt(100) < 2
          val present = random.nextInt(100) != 0
          val here = if (inGroup) where else coordinates(random, centers)
          val flags = (if (member) CellFlags.GROUP_MEMBER else 0) or (if (random.nextInt(20) == 0) CellFlags.FAVORITE else 0) or
            (if (random.nextInt(15) == 0) CellFlags.EDITED else 0)
          out += SyntheticMapMedia(
            id = MediaId(UUID(random.nextLong(), random.nextLong()).toString()),
            latitude = here?.first,
            longitude = here?.second,
            localDay = 20_100_101 + random.nextInt(16) * 10_000 + random.nextInt(12) * 100 + random.nextInt(28),
            flags = flags,
            source = if (random.nextInt(12) == 0) MediaSource.WHATSAPP else MediaSource.CAMERA,
            kind = if (random.nextInt(8) == 0) MediaKind.VIDEO else MediaKind.PHOTO,
            durationMs = if (random.nextInt(8) == 0) random.nextInt(90_000).toLong() else null,
            placeId = if (random.nextInt(3) == 0) null else 3_000_000 + random.nextInt(20),
            hidden = wholeHidden || k == hiddenMember,
            present = present,
            groupId = groupId,
          )
        }
      }
      return SyntheticMapLibrary(out)
    }

    /** Un punto per una foto: nel mondo (80%: attorno ai centri), senza posizione (10%), assurdo (1%), ai bordi (antimeridiano, poli). */
    private fun coordinates(random: Random, centers: List<Pair<Double, Double>>): Pair<Double, Double>? {
      val roll = random.nextInt(100)
      return when {
        roll < 10 -> null
        roll < 11 -> listOf(0.0 to 0.0, 89.0 to 10.0, -86.0 to 0.0, 10.0 to 200.0, Double.NaN to 5.0)[random.nextInt(5)]
        roll < 14 -> listOf(10.0 to 179.999999, 10.0 to -179.999999, 84.999999 to 180.0, -84.999999 to -180.0, 84.5 to 10.0)[random.nextInt(5)]
        roll < 90 -> {
          val c = centers[random.nextInt(centers.size)]
          (c.first + (random.nextInt(200_000) - 100_000) / 1e6) to (c.second + (random.nextInt(200_000) - 100_000) / 1e6)
        }
        else -> ((random.nextInt(2 * 84_000_000) - 84_000_000) / 1e6) to ((random.nextInt(2 * GeoGrid.LON_E6) - GeoGrid.LON_E6) / 1e6)
      }
    }
  }
}

/** Il modello della query dei punti (`MapPointsQueries.page`): visibili con coordinate, per id crescente, dopo il cursore. */
internal class FakeMapPager(private val library: SyntheticMapLibrary) : MapPointPager {

  private val rows: List<SyntheticMapMedia> = library.withCoordinates.sortedBy { it.id.value }

  override suspend fun page(after: MediaId?, limit: Int): List<MapPointRaw> {
    require(limit > 0) { "Pagina vuota." }
    var from = 0
    if (after != null) {
      var lo = 0
      var hi = rows.size
      while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (rows[mid].id.value <= after.value) lo = mid + 1 else hi = mid
      }
      from = lo
    }
    return rows.subList(from, minOf(rows.size, from + limit)).map {
      MapPointRaw(it.id, it.latitude!!, it.longitude!!, it.localDay, it.flags, it.source, it.kind, it.durationMs, it.placeId)
    }
  }

  override suspend fun countWithoutPosition(): Int = library.withoutPosition
}
