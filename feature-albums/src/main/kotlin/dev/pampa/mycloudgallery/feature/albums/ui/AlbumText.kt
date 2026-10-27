package dev.pampa.mycloudgallery.feature.albums.ui

import android.content.Context
import android.content.res.Resources
import android.text.format.Formatter
import androidx.annotation.StringRes
import dev.pampa.mycloudgallery.core.albums.contract.ShareExclusion
import dev.pampa.mycloudgallery.core.albums.contract.SharePlan
import dev.pampa.mycloudgallery.core.albums.contract.SharePlanKind
import dev.pampa.mycloudgallery.core.albums.contract.TitleProblem
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers
import dev.pampa.mycloudgallery.core.model.AlbumSort
import dev.pampa.mycloudgallery.core.model.ShareLevel
import dev.pampa.mycloudgallery.feature.albums.R
import dev.pampa.mycloudgallery.feature.albums.contract.WhyBlocked
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// I TESTI DELLE SCHERMATE DEGLI ALBUM che si compongono con dei numeri (Fase 7, P10): funzioni pure sopra `Resources`, cosi' le prova un test
// senza Compose e le usano tutte le schermate. Mai un titolo, un nome o un percorso in un log (D37): qui i nomi arrivano gia' scritti.

internal object AlbumText {

  /** "42 elementi". */
  fun items(resources: Resources, n: Int): String = resources.getQuantityString(R.plurals.albums_items, n, GalleryNumbers.format(n))

  /** "12 foto e 3 video (1,2 GB)", o solo le foto, o solo i video; la dimensione solo se qualcosa si sposta. */
  fun planCounts(context: Context, plan: SharePlan): String {
    val resources = context.resources
    val photos = plan.photos
    val videos = plan.videos
    val parts = buildList {
      if (photos > 0) add(resources.getQuantityString(R.plurals.share_photos, photos, photos))
      if (videos > 0) add(resources.getQuantityString(R.plurals.share_videos, videos, videos))
    }
    val counts = if (parts.size == 2) resources.getString(R.string.share_counts_both, parts[0], parts[1]) else parts.firstOrNull().orEmpty()
    val bytes = plan.bytes
    return if (bytes > 0) resources.getString(R.string.share_counts_with_size, counts, Formatter.formatFileSize(context, bytes)) else counts
  }

  /** La frase del foglio, secondo il genere di piano e secondo che qualcosa si sposti o sia tutto gia' nell'area comune. */
  fun planMessage(context: Context, plan: SharePlan): String {
    val resources = context.resources
    val counts = planCounts(context, plan)
    return when (plan.kind) {
      SharePlanKind.SHARE ->
        if (plan.bytes == 0L) resources.getString(R.string.share_message_share_reference, counts, plan.folderLabel) else resources.getString(R.string.share_message_share, counts, plan.folderLabel)
      SharePlanKind.BRING_BACK -> resources.getString(R.string.share_message_bring_back, counts)
      SharePlanKind.ADOPT -> resources.getString(R.string.share_message_adopt, counts)
    }
  }

  @StringRes
  fun blocked(why: WhyBlocked): Int = when (why) {
    WhyBlocked.AFTER_TESTS -> R.string.album_blocked_after_tests
    WhyBlocked.READ_ONLY -> R.string.album_blocked_read_only
    WhyBlocked.WRITES_PAUSED -> R.string.album_blocked_paused
    WhyBlocked.NO_NAS -> R.string.album_blocked_no_nas
    WhyBlocked.TOO_MANY_ITEMS -> R.string.album_blocked_too_many
    WhyBlocked.NOT_ACTIVE -> R.string.album_blocked_not_active
  }

  @StringRes
  fun titleProblem(problem: TitleProblem): Int = when (problem) {
    TitleProblem.EMPTY -> R.string.albums_title_empty
    TitleProblem.TOO_LONG -> R.string.albums_title_too_long
    TitleProblem.FORBIDDEN_CHARS -> R.string.albums_title_forbidden
  }

  @StringRes
  fun level(level: ShareLevel): Int = when (level) {
    ShareLevel.ALL -> R.string.album_level_all
    ShareLevel.ADD -> R.string.album_level_add
    ShareLevel.VIEW -> R.string.album_level_view
  }

  @StringRes
  fun levelDetail(level: ShareLevel): Int = when (level) {
    ShareLevel.ALL -> R.string.album_level_all_detail
    ShareLevel.ADD -> R.string.album_level_add_detail
    ShareLevel.VIEW -> R.string.album_level_view_detail
  }

  @StringRes
  fun sort(sort: AlbumSort): Int = when (sort) {
    AlbumSort.OLDEST_FIRST -> R.string.album_sort_oldest
    AlbumSort.NEWEST_FIRST -> R.string.album_sort_newest
    AlbumSort.CUSTOM -> R.string.album_sort_custom
  }

  /** Una riga per ogni motivo per cui dei media restano fuori, con il loro numero: "3 già nell'album". */
  fun excluded(resources: Resources, reason: ShareExclusion, n: Int): String = resources.getString(
    when (reason) {
      ShareExclusion.OTHERS_AREA -> R.string.share_excluded_others_area
      ShareExclusion.HIDDEN_LEFT_OUT -> R.string.share_excluded_hidden
      ShareExclusion.ALREADY_IN_ALBUM -> R.string.share_excluded_already
      ShareExclusion.UNAVAILABLE -> R.string.share_excluded_unavailable
      ShareExclusion.NOT_MINE -> R.string.share_excluded_not_mine
      ShareExclusion.NOT_IN_COMMON -> R.string.share_excluded_not_in_common
      // Revisione B (SG-1, I4): il caricamento dal telefono non partirebbe; il foglio lo dice invece di prometterlo.
      ShareExclusion.UPLOAD_BLOCKED -> R.string.share_excluded_upload_blocked
    },
    n,
  )

  private val DayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ITALIAN)
  private val ShortDayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ITALIAN)

  /** "14 marzo 2026". */
  fun day(day: LocalDate): String = day.format(DayFormat)

  /** "14 mar 2026": nelle righe strette. */
  fun shortDay(day: LocalDate): String = day.format(ShortDayFormat)

  /** Il giorno di una cella dell'istantanea (`aaaammgg`), o null se la cella non ha data. */
  fun dayOf(yyyymmdd: Int): LocalDate? {
    if (yyyymmdd <= 0) return null
    return try {
      LocalDate.of(yyyymmdd / 10_000, yyyymmdd / 100 % 100, yyyymmdd % 100)
    } catch (_: java.time.DateTimeException) {
      null
    }
  }
}
