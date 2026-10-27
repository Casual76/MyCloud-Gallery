package dev.pampa.mycloudgallery.feature.library.utility

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryDeleteLocation
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryDeleteLocationKind
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryDeleteScope
import dev.pampa.mycloudgallery.core.designsystem.sheet.GalleryDeleteSheet
import dev.pampa.mycloudgallery.core.library.duplicates.CopyArea
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneMediaIntents
import dev.pampa.mycloudgallery.feature.library.R
import dev.pampa.mycloudgallery.feature.library.trash.SheetTap
import dev.pampa.mycloudgallery.feature.library.trash.UserTap

// Fase 7, P8 (D25, §2.1.1): il FOGLIO DI ELIMINAZIONE ESISTENTE (`GalleryDeleteSheet`, quello di "Elimina dal telefono" e del viewer) usato dalla
// pulizia dei doppioni. "Solo dal telefono" / "Ovunque" (una sola scelta per i doppioni identici), l'elenco dei posti con l'effetto di ogni scelta
// prima di premere, la nota dei 30 giorni nei cestini. Il tocco su "Elimina" e' la conferma; niente parte prima.

private fun CleanupScope.design(): GalleryDeleteScope = when (this) {
  CleanupScope.PHONE_ONLY -> GalleryDeleteScope.PhoneOnly
  CleanupScope.EVERYWHERE -> GalleryDeleteScope.Everywhere
}

private fun GalleryDeleteScope.cleanup(): CleanupScope = when (this) {
  GalleryDeleteScope.PhoneOnly -> CleanupScope.PHONE_ONLY
  GalleryDeleteScope.Everywhere -> CleanupScope.EVERYWHERE
}

/**
 * Il foglio, aperto quando [sheet] c'e'. [familyName] dice di chi e' un'area (E-U1).
 *
 * **Qui nasce la prova del tocco** (R18): una macchina nuova a ogni apertura ([SheetTap]), e un tocco su "Elimina" prima di 600 ms dalla comparsa non
 * conta. Il controllore riceve la prova ([onConfirm]), non se la fabbrica.
 */
@Composable
internal fun CleanupSheetUi(
  sheet: CleanupSheet?,
  familyName: (String) -> String?,
  onScope: (CleanupScope) -> Unit,
  onConfirm: (UserTap) -> Unit,
  onDismiss: () -> Unit,
  monotonicMs: () -> Long = SystemClock::elapsedRealtime,
) {
  val shown = sheet != null
  val tap = remember(shown) { SheetTap(monotonicMs()) }
  GalleryDeleteSheet(
    visible = shown,
    count = sheet?.count ?: 1,
    scope = (sheet?.chosen ?: CleanupScope.PHONE_ONLY).design(),
    onScopeChange = { onScope(it.cleanup()) },
    locations = if (sheet != null) cleanupLocations(sheet, familyName) else emptyList(),
    onConfirm = { tap.confirm(monotonicMs())?.let(onConfirm) },
    onDismissRequest = onDismiss,
    scopes = sheet?.scopes?.map { it.design() } ?: listOf(GalleryDeleteScope.PhoneOnly),
    // DP-6: "Eliminare 41 copie (3,2 GB)?", "Eliminare 4 scatti (12 MB)?": le copie non sono "foto" (4 scatti con 3 copie facevano "12 foto"), e ci sono i GB.
    title = sheet?.let { sheetTitle(it) },
  )
}

@Composable
private fun sheetTitle(sheet: CleanupSheet): String {
  val plan = sheet.plan
  val size = UtilityText.size(plan.bytes)
  return when (sheet.source) {
    CleanupSource.IDENTICAL -> pluralStringResource(R.plurals.duplicates_sheet_title_copies, plan.copies.coerceAtLeast(1), GalleryNumbers.format(plan.copies), size)
    CleanupSource.SIMILAR -> pluralStringResource(R.plurals.duplicates_sheet_title_shots, plan.groups.coerceAtLeast(1), GalleryNumbers.format(plan.groups), size)
  }
}

/**
 * I posti del foglio: il telefono, l'area privata, l'area comune, e le aree degli altri che non si toccano (E-U1, "Non hai i permessi"). I numeri sono
 * quelli del piano piu' largo; l'effetto di "Solo dal telefono" lo dice il foglio stesso ("Resta").
 */
@Composable
private fun cleanupLocations(sheet: CleanupSheet, familyName: (String) -> String?): List<GalleryDeleteLocation> {
  val plan = sheet.plans[CleanupScope.EVERYWHERE] ?: sheet.plan
  val out = ArrayList<GalleryDeleteLocation>()
  if (plan.phone.isNotEmpty()) {
    val count = plan.phone.size
    var detail = copiesDetail(count, plan.phone.sumOf { it.size })
    if (plan.unsafePhone > 0) detail += "\n" + pluralStringResource(R.plurals.duplicates_sheet_unsafe, plan.unsafePhone, GalleryNumbers.format(plan.unsafePhone))
    if (sheet.phoneLeft > 0) detail += "\n" + stringResource(R.string.duplicates_sheet_phone_left, GalleryNumbers.format(PhoneMediaIntents.MAX_REQUEST_URIS))
    out += GalleryDeleteLocation(stringResource(R.string.duplicates_sheet_phone), GalleryDeleteLocationKind.Phone, detail)
  }
  val privateCopies = plan.nas.filter { it.area == CopyArea.MY_PRIVATE }
  if (privateCopies.isNotEmpty()) {
    out += GalleryDeleteLocation(
      stringResource(R.string.duplicates_sheet_private), GalleryDeleteLocationKind.NasPrivate, copiesDetail(privateCopies.size, privateCopies.sumOf { it.size }),
    )
  }
  val commonCopies = plan.nas.filter { it.area == CopyArea.COMMON }
  if (commonCopies.isNotEmpty()) {
    // E-D8 (DP-2): un file dell'area comune lo vedono tutti i familiari; toglierlo lo toglie a tutti, e il foglio lo dice prima del tocco.
    val detail = copiesDetail(commonCopies.size, commonCopies.sumOf { it.size }) + "\n" + stringResource(R.string.duplicates_sheet_common_gone)
    out += GalleryDeleteLocation(stringResource(R.string.duplicates_sheet_common), GalleryDeleteLocationKind.NasShared, detail)
  }
  // E-U1: le copie nell'area di un altro account non si propongono (nessun permesso), e si dice di chi sono.
  sheet.othersAreas.groupBy { it }.forEach { (share, copies) ->
    val name = share?.let(familyName) ?: stringResource(R.string.duplicates_other_family)
    out += GalleryDeleteLocation(
      title = stringResource(R.string.duplicates_area_others, name),
      kind = GalleryDeleteLocationKind.NasShared,
      detail = pluralStringResource(R.plurals.duplicates_sheet_others, copies.size, GalleryNumbers.format(copies.size)),
      deletable = false,
    )
  }
  return out
}

@Composable
private fun copiesDetail(count: Int, bytes: Long): String =
  pluralStringResource(R.plurals.duplicates_sheet_copies, count, GalleryNumbers.format(count), UtilityText.size(bytes))
