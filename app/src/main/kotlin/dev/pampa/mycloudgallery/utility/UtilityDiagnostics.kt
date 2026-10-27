package dev.pampa.mycloudgallery.utility

import dev.pampa.mycloudgallery.core.albums.contract.AlbumDiag
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagItem
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSeverity
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagnosticsContributor
import dev.pampa.mycloudgallery.core.common.diagnostics.ExportValue
import dev.pampa.mycloudgallery.core.library.duplicates.NightlyReport
import dev.pampa.mycloudgallery.core.library.duplicates.SigningReport
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCatalog

/**
 * La manutenzione dell'Utility nella Diagnostica (Fase 7, revisione di fine fase, DG-2). `UtilityGraph.lastNightly` e `lastSigning` si scrivevano e non li
 * leggeva nessuno: "Controlla di notte i video del NAS" e la firma dei Doppioni simili giravano senza lasciare traccia, e la prova F76 ("righe FULL_HASH in
 * coda e ritirate") non aveva dove guardare. **Solo numeri e segni** (D37): quanti video guardati, in coda, ritirati; quanti scatti firmati, in attesa, saltati.
 * Stanno nella sezione degli album, accanto al resto della Fase 7.
 */
class UtilityDiagnostics(private val reports: () -> Pair<NightlyReport?, SigningReport?>) : DiagnosticsContributor {

  constructor(utility: UtilityGraph) : this({ utility.lastNightly to utility.lastSigning })

  override val section: DiagSection = DiagSection.ALBUMS

  override suspend fun collect(): List<DiagItem> = reports().let { (nightly, signing) -> render(nightly, signing) }

  internal fun render(nightly: NightlyReport?, signing: SigningReport?): List<DiagItem> {
    val items = ArrayList<DiagItem>()
    if (nightly != null) {
      items += item(
        AlbumDiag.UTILITY_FULL_HASH,
        mapOf(
          "enabled" to ExportValue.Flag(nightly.enabled),
          "scanned" to ExportValue.Num(nightly.scanned.toLong()),
          "queued" to ExportValue.Num(nightly.queued.toLong()),
          "withdrawn" to ExportValue.Num(nightly.withdrawn.toLong()),
          "finished" to ExportValue.Flag(nightly.finished),
        ),
      )
    }
    if (signing != null) {
      items += item(
        AlbumDiag.UTILITY_SIGNING,
        mapOf(
          "signed" to ExportValue.Num(signing.signed.toLong()),
          "waiting" to ExportValue.Num(signing.waiting.toLong()),
          "skipped" to ExportValue.Num(signing.skipped.toLong()),
          "finished" to ExportValue.Flag(signing.finished),
        ),
      )
    }
    return items
  }

  private fun item(code: DiagCode, export: Map<String, ExportValue>) =
    DiagItem(code = code, section = section, severity = DiagCatalog.entry(code)?.severity ?: DiagSeverity.INFO, titleRes = DiagCatalog.titleOf(code), export = export)
}
