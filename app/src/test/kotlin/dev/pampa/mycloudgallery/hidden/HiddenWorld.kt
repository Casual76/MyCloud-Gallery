package dev.pampa.mycloudgallery.hidden

import dev.pampa.mycloudgallery.core.model.MediaGroupKind
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import java.time.LocalDate
import kotlin.random.Random

// Fase 7 (P9, D22): il MONDO CASUALE del test di proprieta' sui Nascosti. Una Libreria con nascosti a caso, gruppi di copie simili con un nascosto
// dentro (E-L11), raffiche, copie nell'area privata e in quella comune, preferiti: descritta qui come dati puri (la stessa descrizione costruisce
// il catalogo vero in `HiddenWorldBuilder` e, per i riferimenti, la Libreria finta degli album). L'ORACOLO ([HiddenOracle]) si ricava dalla
// descrizione, mai dal catalogo: se il catalogo e il codice sbagliassero insieme, l'oracolo no.

/** Dove sta un media. */
internal enum class Where {
  /** Solo sul telefono. */
  PHONE,

  /** Solo nell'area privata del NAS. */
  NAS_PRIVATE,

  /** Solo nell'area comune del NAS: la vedono tutti i familiari. */
  NAS_COMMON,

  /** Sul telefono e nell'area privata (la stessa foto in due posti: un doppione "identico", Utility, P8). */
  PHONE_AND_PRIVATE,
}

internal class MediaSpec(
  val index: Int,
  val kind: MediaKind,
  val source: MediaSource,
  val day: LocalDate,
  val minuteOfDay: Int,
  val where: Where,
) {
  val onNas: Boolean get() = where != Where.PHONE
  val onPhone: Boolean get() = where == Where.PHONE || where == Where.PHONE_AND_PRIVATE

  override fun toString(): String = "MediaSpec($index, $kind, $where)"
}

/** Un gruppo (raffica o copie simili) con la sua copertina: gli indici dei membri. */
internal class GroupSpec(val kind: MediaGroupKind, val members: List<Int>, val cover: Int) {
  init {
    require(cover in members && members.size >= 2) { "Un gruppo ha almeno due membri e la copertina e' uno di loro." }
  }

  override fun toString(): String = "GroupSpec($kind, ${members.size})"
}

internal class WorldSpec(
  val seed: Long,
  val media: List<MediaSpec>,
  val groups: List<GroupSpec>,
  /** Nascosti con il gesto dell'utente: il codice vero li propaga a tutto il gruppo. */
  val hiddenByUser: Set<Int>,
  /**
   * Nascosti **a meta'** (E-L11): una sola copia di un gruppo di copie simili, scritta con la sola API del catalogo (`setPersonalFlags` su un media,
   * come fa la proiezione dei dati personali che arrivano da un altro dispositivo), senza il gesto che espande al gruppo. Il catalogo porta il
   * nascosto a tutto il gruppo (F5-D36): l'oracolo lo sa, e la prova lo verifica sul catalogo vero.
   */
  val hiddenPartial: Set<Int>,
  val favorites: Set<Int>,
  /**
   * Nascosti **dopo** l'istantanea della Libreria ("nascosto un attimo fa"), scritti direttamente in `media_personal` senza espandere al gruppo e senza
   * ricalcolare niente: un dato arrivato a meta' o appena scritto. L'istantanea non li ha ancora tolti; le superfici che la usano e poi chiedono
   * ancora al catalogo (il filtro finale: `VisibleMedia`, `HiddenMask`, il widget) non devono mostrarli, ne' mostrare chi sta nel loro gruppo.
   */
  val lateHidden: Set<Int> = emptySet(),
  /**
   * Media con una **seconda copia dalla stessa parte** (Fase 7, P8, D25): sul telefono un secondo file in `Download/`, sul NAS un secondo file in
   * `Backup/<dispositivo>/`, con gli stessi byte. Sono i doppioni identici che la pagina Doppioni propone di pulire; non cambiano le celle della
   * Libreria (una copia in piu' non e' un media in piu').
   */
  val sameSideCopies: Set<Int> = emptySet(),
) {
  override fun toString(): String = "WorldSpec(seed=$seed, media=${media.size}, groups=${groups.size})"

  companion object {
    /** Una Libreria casuale riproducibile da [seed]. [size] media (circa), nascosti circa il 15%. */
    fun random(seed: Long, size: Int = 90): WorldSpec {
      val random = Random(seed)
      val first = LocalDate.of(2026, 3, 1)
      val media = (0 until size).map { i ->
        val kind = if (random.nextInt(100) < 15) MediaKind.VIDEO else MediaKind.PHOTO
        val source = when (random.nextInt(100)) {
          in 0 until 10 -> MediaSource.SCREENSHOT
          in 10 until 15 -> MediaSource.WHATSAPP
          else -> MediaSource.CAMERA
        }
        val where = when (random.nextInt(100)) {
          in 0 until 50 -> Where.PHONE
          in 50 until 70 -> Where.NAS_PRIVATE
          in 70 until 85 -> Where.NAS_COMMON
          else -> Where.PHONE_AND_PRIVATE
        }
        MediaSpec(i, kind, source, first.plusDays(random.nextInt(14).toLong()), random.nextInt(24 * 60), where)
      }
      // Gruppi disgiunti: ogni media in al piu' uno. Un gruppo di due o tre membri ogni tanto.
      val free = media.indices.shuffled(random).toMutableList()
      val groups = ArrayList<GroupSpec>()
      repeat(random.nextInt(3, 7)) {
        val take = random.nextInt(2, 5).coerceAtMost(free.size)
        if (take < 2) return@repeat
        val members = (0 until take).map { free.removeAt(free.lastIndex) }.sorted()
        // Le copie simili e le raffiche sono gruppi diversi del catalogo: qui meta' e meta'.
        groups += GroupSpec(if (random.nextBoolean()) MediaGroupKind.BURST else MediaGroupKind.SIMILAR_COPIES, members, members[random.nextInt(members.size)])
      }
      val hiddenByUser = media.indices.filterTo(HashSet()) { random.nextInt(100) < 13 }
      // Una o due copie nascoste "a meta'": scelte fra i membri dei gruppi di copie simili, cosi' l'oracolo deve estendere al gruppo.
      val similarMembers = groups.filter { it.kind == MediaGroupKind.SIMILAR_COPIES }.flatMap { it.members }
      val partial = similarMembers.shuffled(random).take(random.nextInt(0, 3)).filterTo(HashSet()) { it !in hiddenByUser }
      val favorites = media.indices.filterTo(HashSet()) { random.nextInt(100) < 20 }
      // Gli "ultimi nascosti": un membro di un gruppo ancora tutto visibile (il gruppo deve sparire intero) e qualche media qualunque.
      val alreadyHidden = HiddenOracle(WorldSpec(seed, media, groups, hiddenByUser, partial, favorites)).mustNeverShow
      val intactGroups = groups.filter { g -> g.members.none { it in alreadyHidden } }
      val late = HashSet<Int>()
      intactGroups.shuffled(random).take(random.nextInt(0, 3)).forEach { late += it.members[random.nextInt(it.members.size)] }
      media.indices.filter { it !in alreadyHidden }.shuffled(random).take(random.nextInt(0, 3)).forEach { late += it }
      // Le seconde copie dalla stessa parte (P8): da un generatore a parte, cosi' i mondi di prima restano gli stessi di prima per ogni altra scelta.
      val copyRandom = Random(seed * 7_919 + 13)
      val copies = media.indices.filterTo(HashSet()) { copyRandom.nextInt(100) < 18 }
      return WorldSpec(seed, media, groups, hiddenByUser, partial, favorites, late, copies)
    }

    /** Il caso peggiore: tutto nascosto. */
    fun everythingHidden(seed: Long = 9_001, size: Int = 40): WorldSpec = random(seed, size).let {
      WorldSpec(it.seed, it.media, it.groups, it.media.indices.toSet(), emptySet(), it.favorites, sameSideCopies = it.sameSideCopies)
    }

    /** Solo "ultimi nascosti": niente era nascosto quando e' stata fatta l'istantanea. */
    fun onlyLateHidden(seed: Long = 9_003, size: Int = 60): WorldSpec = random(seed, size).let {
      val groupMember = it.groups.first().members.first()
      WorldSpec(
        it.seed, it.media, it.groups, emptySet(), emptySet(), it.favorites,
        it.lateHidden + groupMember + it.media.indices.first { i -> it.groups.none { g -> i in g.members } }, it.sameSideCopies,
      )
    }

    /** Il caso opposto: niente nascosto. */
    fun nothingHidden(seed: Long = 9_002, size: Int = 40): WorldSpec = random(seed, size).let {
      WorldSpec(it.seed, it.media, it.groups, emptySet(), emptySet(), it.favorites, sameSideCopies = it.sameSideCopies)
    }
  }
}

/**
 * Cio' che **non deve mai comparire** da nessuna parte, ricavato dalla sola descrizione: i media nascosti (con il gesto o a meta') e **tutti i
 * membri di ogni gruppo** in cui qualcuno e' nascosto (il nascosto e' contagioso, F5-D36, E-L11). Cio' che invece **deve** comparire nella
 * Libreria: ogni altro media, e per un gruppo visibile la sola copertina (D20).
 */
internal class HiddenOracle(val spec: WorldSpec) {

  private val hiddenAtAll: Set<Int> = spec.hiddenByUser + spec.hiddenPartial

  /** Gli indici dei media che non devono mai uscire dalle superfici che usano l'istantanea: i nascosti e i loro gruppi. */
  val mustNeverShow: Set<Int> = expand(hiddenAtAll)

  /**
   * Come [mustNeverShow], con gli "ultimi nascosti" ([WorldSpec.lateHidden]) e i loro gruppi: cio' che non deve uscire dalle superfici che dopo
   * l'istantanea chiedono ancora al catalogo (il filtro finale).
   */
  val liveMustNeverShow: Set<Int> = expand(hiddenAtAll + spec.lateHidden)

  private fun expand(hidden: Set<Int>): Set<Int> = buildSet {
    addAll(hidden)
    for (group in spec.groups) if (group.members.any { it in hidden }) addAll(group.members)
  }

  /** Gli indici delle celle della Libreria: i non nascosti, e di un gruppo solo la copertina. */
  val libraryCells: Set<Int> = buildSet {
    val groupOf = spec.groups.flatMap { g -> g.members.map { it to g } }.toMap()
    for (m in spec.media) {
      if (m.index in mustNeverShow) continue
      val group = groupOf[m.index]
      if (group == null || group.cover == m.index) add(m.index)
    }
  }

  /** Gli indici di tutti i media che si possono mostrare (non nascosti, anche i membri non copertina di un gruppo visibile). */
  val showable: Set<Int> = spec.media.indices.filterTo(HashSet()) { it !in mustNeverShow }

  /** Come [showable] e [libraryCells], per le superfici che chiedono ancora al catalogo dopo l'istantanea. */
  val liveShowable: Set<Int> = spec.media.indices.filterTo(HashSet()) { it !in liveMustNeverShow }

  val liveLibraryCells: Set<Int> = libraryCells.filterTo(HashSet()) { it in liveShowable }
}
