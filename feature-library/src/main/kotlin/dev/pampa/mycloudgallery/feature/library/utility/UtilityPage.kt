package dev.pampa.mycloudgallery.feature.library.utility

import android.content.res.Resources
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.designsystem.state.PhoneTrash
import dev.pampa.mycloudgallery.core.designsystem.state.phoneTrashText
import dev.pampa.mycloudgallery.core.library.contract.LibraryFilter
import dev.pampa.mycloudgallery.feature.library.R
import dev.pampa.mycloudgallery.feature.library.collections.CollectionEntry
import dev.pampa.mycloudgallery.feature.library.collections.CollectionEntryGroup
import dev.pampa.mycloudgallery.feature.library.collections.CollectionSections
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers
import dev.pampa.mycloudgallery.feature.library.collections.CollectionItemsSource
import kotlinx.coroutines.flow.Flow
import dev.pampa.mycloudgallery.feature.library.countText

// Fase 7, P8 (D24, docs/fase7-progetto.md §2.1 "Utility"): le PAGINE DELL'UTILITY come dati. Modificate, Doppioni ed Eliminati di recente.
// H5 (fase H, 11/11): Screenshot, Video e WhatsApp sono usciti, erano doppioni della scheda Album (Screenshot, Video, Messaggistica: lo stesso filtro, o
// uno che lo contiene). Una rotta o una scelta del tablet salvata con il loro codice non si apre piu' (`fromCode` da' null) e torna indietro da sola. `CollectionsTab.kt` e' di P11: non lo tocco, gli consegno una voce per pagina (`CollectionEntry`) e la rotta; "Libera spazio"
// (Fase 5) resta la voce di `:app`.

/**
 * Una pagina dell'Utility.
 *
 * @property code il nome stabile nella rotta e nello stato salvato del pannello del tablet: non cambia mai.
 * @property filter il filtro della Libreria che la pagina mostra con lo stesso `MediaGrid` e lo stesso viewer; null per Doppioni ed Eliminati di recente.
 */
enum class UtilityPage(val code: String, val filter: LibraryFilter?) {
  EDITED("edited", LibraryFilter.EDITED),
  DUPLICATES("duplicates", null),
  RECENTLY_DELETED("recently_deleted", null),
  ;

  /** Il codice della voce nelle Raccolte: sta accanto a quelli delle altre voci di `:app`, quindi ha il suo prefisso. */
  val entryCode: String get() = ENTRY_PREFIX + code

  val isFilter: Boolean get() = filter != null

  companion object {
    private const val ENTRY_PREFIX = "utility/"

    /** Le pagine-filtro, nell'ordine dell'elenco. */
    val filters: List<UtilityPage> = entries.filter { it.isFilter }

    fun fromCode(code: String?): UtilityPage? = entries.firstOrNull { it.code == code }

    /** La pagina di una voce delle Raccolte (`CollectionEntry.code`); null se la voce non e' dell'Utility. */
    fun fromEntryCode(code: String?): UtilityPage? =
      code?.takeIf { it.startsWith(ENTRY_PREFIX) }?.let { fromCode(it.removePrefix(ENTRY_PREFIX)) }
  }
}

@get:StringRes
internal val UtilityPage.titleRes: Int
  get() = when (this) {
    UtilityPage.EDITED -> R.string.utility_edited
    UtilityPage.DUPLICATES -> R.string.utility_duplicates
    UtilityPage.RECENTLY_DELETED -> R.string.utility_recently_deleted
  }

internal val UtilityPage.icon: ImageVector
  get() = when (this) {
    UtilityPage.EDITED -> Icons.Rounded.Edit
    UtilityPage.DUPLICATES -> Icons.Rounded.ContentCopy
    UtilityPage.RECENTLY_DELETED -> Icons.Rounded.Delete
  }

/** Il tono della piastrella dell'icona (mai quello della riga: regola 2 del design system). */
internal val UtilityPage.tone: FluidTone
  get() = when (this) {
    UtilityPage.EDITED -> FluidTone.Warning
    UtilityPage.DUPLICATES -> FluidTone.Neutral
    UtilityPage.RECENTLY_DELETED -> FluidTone.Warning
  }

@get:StringRes
internal val UtilityPage.emptyTitleRes: Int
  get() = when (this) {
    UtilityPage.EDITED -> R.string.utility_edited_empty_title
    UtilityPage.DUPLICATES -> R.string.duplicates_identical_empty_title
    UtilityPage.RECENTLY_DELETED -> R.string.trash_empty_title
  }

@get:StringRes
internal val UtilityPage.emptyDetailRes: Int
  get() = when (this) {
    UtilityPage.EDITED -> R.string.utility_edited_empty_detail
    UtilityPage.DUPLICATES -> R.string.duplicates_identical_empty_detail
    UtilityPage.RECENTLY_DELETED -> R.string.trash_empty_detail
  }

/**
 * Le voci dell'Utility come **dati** per le Raccolte (`CollectionEntryGroup`, il meccanismo che le Raccolte gia' usano per le voci di `:app`): una per
 * pagina, con il titolo, la frase, il conteggio per le pagine-filtro e nessun conteggio su Doppioni ed Eliminati di recente (non si contano senza
 * leggere: dal di fuori non si deve capire quanti sono, E-H1). [counts] sono i conteggi vivi delle pagine-filtro; [canTrash] e' falso dove non c'e'
 * ne' il cestino di sistema ne' quello del NAS (la voce non c'e').
 *
 * "Libera spazio" non e' qui: e' la voce di `:app` (Fase 5) e chi compone le Raccolte la mette nello stesso gruppo.
 */
@Composable
fun utilityEntries(counts: Map<UtilityPage, Int>, canTrash: Boolean = true): List<CollectionEntry> = buildList {
  for (page in UtilityPage.entries) {
    if (page == UtilityPage.RECENTLY_DELETED && !canTrash) continue
    val subtitle = when (page) {
      UtilityPage.DUPLICATES -> stringResource(R.string.utility_duplicates_subtitle)
      UtilityPage.RECENTLY_DELETED ->
        stringResource(phoneTrashText(R.string.utility_recently_deleted_subtitle, R.string.utility_recently_deleted_subtitle_gallery))
      else -> counts[page]?.let { countText(R.plurals.collection_count, it) } ?: stringResource(R.string.collection_count_none)
    }
    add(CollectionEntry(code = page.entryCode, title = stringResource(page.titleRes), subtitle = subtitle, icon = page.icon, tone = page.tone))
  }
}

/** Il gruppo "Utility" delle Raccolte: titolo e voci. */
@Composable
fun utilityEntryGroup(counts: Map<UtilityPage, Int>, canTrash: Boolean = true): CollectionEntryGroup =
  CollectionEntryGroup(stringResource(R.string.utility_group_title), utilityEntries(counts, canTrash))

/**
 * Fase 7 (P13): le stesse voci di [utilityEntries], fuori da una composable: il fornitore della sezione "Utility" delle Raccolte (`:app`, un
 * `CollectionsSectionProvider` con un `StateFlow`) le costruisce con le risorse dell'app. [extra] sono le voci di `:app` dello stesso gruppo
 * ("Libera spazio", Fase 5); [phoneTrash] il cestino di questo telefono (D-3: su Samsung quello di Galleria). Il gruppo porta il suo codice
 * ([CollectionSections.UTILITY]).
 */
fun utilityEntryGroup(
  resources: Resources,
  counts: Map<UtilityPage, Int>,
  canTrash: Boolean,
  extra: List<CollectionEntry> = emptyList(),
  phoneTrash: PhoneTrash = PhoneTrash.ANDROID,
): CollectionEntryGroup {
  val entries = UtilityPage.entries.mapNotNull { page ->
    if (page == UtilityPage.RECENTLY_DELETED && !canTrash) return@mapNotNull null
    val subtitle = when (page) {
      UtilityPage.DUPLICATES -> resources.getString(R.string.utility_duplicates_subtitle)
      UtilityPage.RECENTLY_DELETED ->
        resources.getString(if (phoneTrash == PhoneTrash.GALLERY) R.string.utility_recently_deleted_subtitle_gallery else R.string.utility_recently_deleted_subtitle)
      else -> counts[page]?.let { resources.getQuantityString(R.plurals.collection_count, it, GalleryNumbers.format(it)) }
        ?: resources.getString(R.string.collection_count_none)
    }
    CollectionEntry(code = page.entryCode, title = resources.getString(page.titleRes), subtitle = subtitle, icon = page.icon, tone = page.tone)
  }
  return CollectionEntryGroup(resources.getString(R.string.utility_group_title), entries + extra, code = CollectionSections.UTILITY)
}

/** Fase 7 (P13): i conteggi vivi delle pagine-filtro, per il fornitore della sezione "Utility" (la stessa fonte delle Raccolte, senza nascosti). */
fun CollectionItemsSource.utilityCountsFlow(): Flow<Map<UtilityPage, Int>> = utilityCounts()
