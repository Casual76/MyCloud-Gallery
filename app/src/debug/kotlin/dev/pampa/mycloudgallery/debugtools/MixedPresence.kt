package dev.pampa.mycloudgallery.debugtools

import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.core.library.contract.LibraryNasStatus
import dev.pampa.mycloudgallery.core.library.contract.TimelineRepository
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.contract.TimelineView
import dev.pampa.mycloudgallery.core.library.index.ColumnarTimelineSnapshot
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.FreezeReason
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.NasArea
import dev.pampa.mycloudgallery.core.model.PresenceBadge
import dev.pampa.mycloudgallery.core.model.ThumbHash
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

// SOLO debug (G2, docs/grafica-progetto.md §12): la "libreria mista" del NAS sintetico, per vedere e fotografare sulla
// griglia vera TUTTI i segni di presenza (solo telefono, solo NAS, sul telefono e sul NAS, al sicuro, da verificare)
// insieme ai segni del contenuto (preferito, durata). Il NAS sintetico da solo ha media solo-NAS: qui la presenza, i
// preferiti e qualche video si assegnano in modo deterministico dall'istante di scatto, sopra l'indice vero. I dati veri
// non cambiano (database separato, niente scritture): e' una lente sull'indice, solo per gli occhi.

/** Il repository del NAS sintetico visto attraverso la lente della libreria mista. */
internal class MixedPresenceRepository(private val delegate: TimelineRepository, scope: CoroutineScope) : TimelineRepository {

  /**
   * L'ultima lente fatta, per l'indice da cui e' fatta: le viste dello stesso indice (un filtro per vista) la condividono.
   * Una sola: durante la scansione l'indice si ripubblica a ogni pacchetto, e tenerle tutte (una mappa debole i cui valori
   * tengono la chiave) riempiva la memoria a 16 mila file.
   */
  @Volatile private var last: MixedSnapshot? = null

  private fun mix(snapshot: TimelineSnapshot): MixedSnapshot = synchronized(this) {
    last?.takeIf { it.base === snapshot } ?: MixedSnapshot(snapshot).also { last = it }
  }

  override val snapshot: StateFlow<TimelineSnapshot?> =
    delegate.snapshot.map { it?.let(::mix) }.stateIn(scope, SharingStarted.Eagerly, delegate.snapshot.value?.let(::mix))

  override suspend fun start() = delegate.start()

  private val views = HashMap<LibraryFilter, StateFlow<TimelineView>>()
  private val viewScope = scope

  override fun view(f: LibraryFilter): StateFlow<TimelineView> = synchronized(views) {
    views.getOrPut(f) {
      val source = delegate.view(f)
      source.map { MixedView(it, mix(it.snapshot)) as TimelineView }.stateIn(viewScope, SharingStarted.Eagerly, MixedView(source.value, mix(source.value.snapshot)))
    }
  }
}

private class MixedView(private val base: TimelineView, override val snapshot: TimelineSnapshot) : TimelineView {
  override val filter: LibraryFilter get() = base.filter
  override val size: Int get() = base.size

  override fun snapshotIndex(viewIndex: Int): Int = base.snapshotIndex(viewIndex)

  override fun viewIndexOf(id: MediaId): Int = base.viewIndexOf(id)
}

/** L'indice vero con presenza, preferiti e video assegnati dall'istante di scatto (sempre gli stessi per lo stesso media). */
internal class MixedSnapshot(val base: TimelineSnapshot) : TimelineSnapshot {
  override val size: Int get() = base.size
  override val seq: Long get() = base.seq

  private fun mixAt(i: Int): Int {
    var z = base.sortTimeAt(i) * -0x61c8864680b583ebL
    z = z xor (z ushr 31)
    return (z and 0x7fffffff).toInt()
  }

  private fun presenceBits(m: Int): Int = when (m % 10) {
    0, 1, 2, 3 -> CellFlags.ON_PHONE or CellFlags.ON_NAS
    4, 5 -> CellFlags.ON_NAS
    6, 7 -> CellFlags.ON_PHONE
    8 -> CellFlags.ON_PHONE or CellFlags.ON_NAS or CellFlags.SAFE
    else -> CellFlags.ON_PHONE or CellFlags.ON_NAS or CellFlags.TO_VERIFY
  }

  /** "Da verificare" vale per i video (D21): quelli lo sono sempre; e un sesto degli altri. */
  private fun isVideo(m: Int): Boolean = m % 10 == 9 || (m / 10) % 6 == 0

  override fun idAt(i: Int): MediaId = base.idAt(i)

  override fun sortTimeAt(i: Int): Long = base.sortTimeAt(i)

  override fun localDayAt(i: Int): Int = base.localDayAt(i)

  override fun kindAt(i: Int): MediaKind = if (isVideo(mixAt(i))) MediaKind.VIDEO else base.kindAt(i)

  override fun sourceAt(i: Int): MediaSource = base.sourceAt(i)

  override fun flagsAt(i: Int): Int {
    val m = mixAt(i)
    val presence = CellFlags.ON_PHONE or CellFlags.ON_NAS or CellFlags.SAFE or CellFlags.TO_VERIFY
    var flags = (base.flagsAt(i) and presence.inv()) or presenceBits(m)
    if ((m / 60) % 5 == 0) flags = flags or CellFlags.FAVORITE
    return flags
  }

  override fun aspectMilliAt(i: Int): Int = base.aspectMilliAt(i)

  override fun durationMsAt(i: Int): Long = if (isVideo(mixAt(i))) ((mixAt(i) / 7) % 600 + 4) * 1_000L else base.durationMsAt(i)

  override fun placeAt(i: Int): Int = base.placeAt(i)

  override fun thumbHashAt(i: Int): ThumbHash? = base.thumbHashAt(i)

  override fun groupSizeAt(i: Int): Int = base.groupSizeAt(i)

  override fun indexOf(id: MediaId): Int = base.indexOf(id)

  override fun presenceAt(i: Int): PresenceBadge? = PresenceBadge.of(flagsAt(i))
}

/** L'indice a colonne sotto la lente (per le miniature e le chiavi di versione), se c'e'. */
internal fun TimelineSnapshot.columnar(): ColumnarTimelineSnapshot? = when (this) {
  is ColumnarTimelineSnapshot -> this
  is MixedSnapshot -> base as? ColumnarTimelineSnapshot
  else -> null
}

/**
 * SOLO debug: lo stato del NAS del piede, scelto da `adb` per fotografarne ogni forma (G2). Il file
 * `files/g2-nas-footer` (cartella dei file esterni dell'app) contiene una parola: `lettura`, `allineo`, `aggiornato`,
 * `fermo`, `irraggiungibile`, `comune`, `prova`; vuoto o assente = nessun NAS da dire.
 */
internal object DebugNasFooter {
  const val FILE: String = "g2-nas-footer"

  fun read(dir: File?): LibraryNasStatus {
    val word = dir?.let { File(it, FILE) }?.takeIf { it.exists() }?.let { runCatching { it.readText().trim() }.getOrNull() }
    val now = System.currentTimeMillis()
    return when (word) {
      "lettura" -> LibraryNasStatus.FirstScan(done = 7_412, total = 20_000, etaMillis = 4 * 60_000L)
      "allineo" -> LibraryNasStatus.Aligning(bytesDone = 640_000_000L, bytesTotal = 1_200_000_000L, etaMillis = 50_000L)
      "aggiornato" -> LibraryNasStatus.UpToDate(atNasMillis = now - 12 * 60_000L)
      "fermo" -> LibraryNasStatus.Frozen(FreezeReason.VANISHED, sinceNasMillis = now - 3 * 60 * 60_000L)
      "irraggiungibile" -> LibraryNasStatus.AreasUnavailable(setOf(NasArea.PRIVATE, NasArea.COMMON))
      "comune" -> LibraryNasStatus.AreasUnavailable(setOf(NasArea.COMMON))
      "prova" -> LibraryNasStatus.LocalOnly
      else -> LibraryNasStatus.Idle
    }
  }
}
