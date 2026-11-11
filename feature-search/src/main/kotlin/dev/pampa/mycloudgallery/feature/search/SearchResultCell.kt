package dev.pampa.mycloudgallery.feature.search

import java.time.ZoneId
import dev.pampa.mycloudgallery.core.describe.rules.MediaDescribers
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryGridCell
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryGridCellActions
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryGridCellBadges
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryGridCellStyle
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryGridInteractionState
import dev.pampa.mycloudgallery.core.designsystem.grid.GalleryPresence
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbBucket
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbKey
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbRequest
import dev.pampa.mycloudgallery.core.library.contract.OrderedTimelineView
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.index.ColumnarTimelineSnapshot
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.model.PresenceBadge
import dev.pampa.mycloudgallery.feature.search.contract.SearchThumbnails
import java.util.Locale

// La CELLA dei risultati (Fase 6, P8, D28; H4): la stessa della Libreria (`GalleryGridCell` del design system), con le sole miniature LOCALI. Il NAS non
// si legge mai nella ricerca ([SearchThumbnails.refs] scarta ogni `MediaRef.Nas`): un media senza sorgente locale mostra il segnaposto sfocato.

/** La versione della miniatura quando l'istantanea non la dice (la stessa chiave della Libreria, cosi' la cache e' una sola). */
internal const val DEFAULT_VERSION_KEY: Long = 1L

private val NoImage: State<ImageBitmap?> = mutableStateOf(null)

/** La chiave della miniatura della cella [index]: la stessa della Libreria, cosi' la cache e' una sola (e il viewer parte dalla stessa bitmap). */
internal fun thumbKeyOf(snapshot: TimelineSnapshot, index: Int): ThumbKey {
  val version = if (snapshot is ColumnarTimelineSnapshot) snapshot.versionKeyAt(index) else DEFAULT_VERSION_KEY
  return ThumbKey(snapshot.idAt(index), version, ThumbBucket.M)
}

/** La richiesta di miniatura di una cella, o null se il media non ha una sorgente locale (D28). */
internal fun thumbRequestOf(thumbnails: SearchThumbnails, snapshot: TimelineSnapshot, index: Int): ThumbRequest? {
  val ref = thumbnails.refs.refAt(snapshot, index) ?: return null
  return ThumbRequest(thumbKeyOf(snapshot, index), ref)
}

/**
 * La finestra del caricatore per le richieste di Cerca (CE-5): quelle con una sorgente del telefono sono "visibili"; quelle di un media solo sul NAS "in
 * arrivo", cosi' la sorgente del NAS non legge mai l'originale (lo fa solo per il contenuto visibile, D17) e risponde con la replica locale o la miniatura
 * provvisoria. Cerca non apre mai il NAS (D33).
 */
internal fun windowOf(requests: List<ThumbRequest>): Pair<List<ThumbRequest>, List<ThumbRequest>> =
  requests.partition { it.ref !is MediaRef.Nas }

/** I segni sulla cella, come in Libreria: preferito, modificata, durata, illeggibile e dove sta. */
internal fun badgesOf(snapshot: TimelineSnapshot, index: Int): GalleryGridCellBadges {
  val flags = snapshot.flagsAt(index)
  val video = snapshot.kindAt(index) == MediaKind.VIDEO
  return GalleryGridCellBadges(
    favorite = CellFlags.has(flags, CellFlags.FAVORITE),
    edited = CellFlags.has(flags, CellFlags.EDITED),
    duration = if (video) duration(snapshot.durationMsAt(index)) else null,
    unreadable = CellFlags.has(flags, CellFlags.UNREADABLE),
    presence = snapshot.presenceAt(index)?.let(::presenceOf),
  )
}

private fun presenceOf(badge: PresenceBadge): GalleryPresence = when (badge) {
  PresenceBadge.PHONE_ONLY -> GalleryPresence.PhoneOnly
  PresenceBadge.NAS_ONLY -> GalleryPresence.NasOnly
  PresenceBadge.BOTH -> GalleryPresence.Both
  PresenceBadge.SAFE -> GalleryPresence.Safe
  PresenceBadge.TO_VERIFY -> GalleryPresence.ToVerify
  PresenceBadge.PROBLEM -> GalleryPresence.Problem
}

/** `m:ss` (o `h:mm:ss`) come nella Libreria. */
internal fun duration(ms: Long): String {
  val total = (ms / MILLIS).coerceAtLeast(0)
  val h = total / SECONDS_PER_HOUR
  val m = total % SECONDS_PER_HOUR / SECONDS_PER_MINUTE
  val s = total % SECONDS_PER_MINUTE
  return if (h > 0) "%d:%02d:%02d".format(Locale.ROOT, h, m, s) else "%d:%02d".format(Locale.ROOT, m, s)
}

/** La frase che TalkBack legge per una cella: tipo e giorno dello scatto, mai il nome del file. */
internal fun cellDescription(resources: Resources, snapshot: TimelineSnapshot, index: Int): String {
  // Fase 10 (P7, D22): la frase la scrive il descrittore unico (tipo, data, ora, stato; persone e testo letto solo se :app ha legato le porte).
  return MediaDescribers.describeCell(MediaDescribers.shared(), snapshot, index, ZoneId.systemDefault()) ?: resources.getString(R.string.search_cell_undated)
}

/**
 * Una cella dei risultati: la stessa della Libreria (`GalleryGridCell`), con la stessa cache delle miniature. I tocchi li gestisce la griglia
 * ([SearchResultsGrid]); qui ci sono solo il disegno e, per TalkBack, le due azioni (aprire, o in selezione scegliere; scegliere).
 */
@Composable
internal fun SearchResultCell(
  view: OrderedTimelineView,
  viewIndex: Int,
  thumbnails: SearchThumbnails,
  style: GalleryGridCellStyle,
  interaction: GalleryGridInteractionState,
  actions: GalleryGridCellActions,
  modifier: Modifier = Modifier,
) {
  val snapshot = view.snapshot
  val index = view.snapshotIndex(viewIndex)
  val request = remember(snapshot, index) { thumbRequestOf(thumbnails, snapshot, index) }
  val image = if (request != null) thumbnails.loader.image(request.key, request.ref) else NoImage
  val placeholder = remember(snapshot, index) { snapshot.thumbHashAt(index)?.let { thumbnails.placeholders.image(it) } }
  GalleryGridCell(
    key = snapshot.idAt(index),
    image = image,
    badges = badgesOf(snapshot, index),
    style = style,
    interaction = interaction,
    actions = actions,
    modifier = modifier,
    placeholder = placeholder,
  )
}

/**
 * Le azioni di una cella dei risultati per TalkBack e Switch Access (Fase 10 P8, D23): la frase della cella (tipo, data, stato; mai il nome del file), il
 * tocco e la pressione lunga, che fanno quello che fanno col dito.
 */
internal class ResultCellActions(
  private val resources: Resources,
  private val describeAt: (Any) -> String?,
  override val clickLabel: String,
  override val longClickLabel: String,
  private val onClick: (Any) -> Unit,
  private val onLongClick: (Any) -> Unit,
) : GalleryGridCellActions {
  override fun describe(key: Any): String = describeAt(key) ?: resources.getString(R.string.search_cell_undated)

  override fun click(key: Any) = onClick(key)

  override fun longClick(key: Any) = onLongClick(key)
}

private const val MILLIS = 1_000L
private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3_600L
