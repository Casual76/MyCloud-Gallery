package dev.pampa.mycloudgallery.feature.settings.about

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Public
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.feature.settings.R
import dev.pampa.mycloudgallery.feature.settings.ui.RowGlyph

/**
 * "Informazioni e licenze" (Fase 3, P11; docs/fase3-progetto.md §1.3): cos'e' l'app, da dove vengono i nomi dei luoghi
 * (GeoNames, CC BY 4.0: l'attribuzione e' un obbligo della licenza) e quali librerie di altri autori usa.
 *
 * E' una pagina di sola lettura, senza ViewModel. Gli indirizzi delle licenze sono **testo selezionabile, mai un
 * collegamento**: la Fase 3 non fa traffico di rete e nessun tocco qui apre un browser (D18, D24).
 */
@Composable
fun AboutRoute(onBack: () -> Unit) {
  val context = LocalContext.current
  val versionName = remember(context) { appVersionName(context) }
  AboutScreen(versionName = versionName, onBack = onBack)
}

/**
 * La pagina vera, senza stato: [versionName] e' la versione dell'app, o null se il sistema non la dice (allora la
 * riga non compare). La usano `AboutRoute` e i test.
 */
@Composable
internal fun AboutScreen(versionName: String?, onBack: () -> Unit) {
  FluidScreen(title = stringResource(R.string.about_title), onBack = onBack) {
    // --- L'app -----------------------------------------------------------------------------------------------
    item { FluidSectionHeader(title = stringResource(R.string.about_section_app)) }
    item {
      SelectionContainer {
        FluidListGroup {
          FluidListRow(
            title = stringResource(R.string.about_app_name),
            subtitle = stringResource(R.string.about_app_detail),
            meta = versionName?.let { stringResource(R.string.about_app_version, it) },
            tone = FluidTone.Primary,
            leading = { RowGlyph(Icons.Rounded.Info) },
            disclosure = false,
          )
        }
      }
    }

    // --- Luoghi offline: GeoNames, CC BY 4.0 -------------------------------------------------------------------
    item {
      FluidSectionHeader(
        title = stringResource(R.string.about_section_places),
        detail = stringResource(R.string.about_section_places_detail),
      )
    }
    item {
      SelectionContainer {
        FluidListGroup {
          FluidListRow(
            title = stringResource(R.string.about_geonames_title),
            subtitle = stringResource(R.string.about_geonames_attribution),
            tone = FluidTone.Info,
            leading = { RowGlyph(Icons.Rounded.Public) },
            disclosure = false,
          )
          FluidListDivider()
          FluidListRow(
            eyebrow = stringResource(R.string.about_license_label),
            title = stringResource(R.string.about_license_cc_by),
            subtitle = stringResource(R.string.about_license_cc_by_url),
            tone = FluidTone.Neutral,
            leading = { RowGlyph(Icons.Rounded.Description) },
            disclosure = false,
          )
        }
      }
    }
    item { FluidSectionFootnote(text = stringResource(R.string.about_geonames_note)) }

    // --- Librerie -----------------------------------------------------------------------------------------------
    item {
      FluidSectionHeader(
        title = stringResource(R.string.about_section_libraries),
        detail = stringResource(R.string.about_section_libraries_detail),
      )
    }
    item {
      SelectionContainer {
        FluidListGroup {
          LIBRARIES.forEach { library ->
            FluidListRow(
              title = stringResource(library.title),
              subtitle = stringResource(library.detail),
              meta = stringResource(R.string.about_license_apache),
              tone = FluidTone.Neutral,
              leading = { RowGlyph(Icons.Rounded.Code) },
              disclosure = false,
            )
            FluidListDivider()
          }
          FluidListRow(
            eyebrow = stringResource(R.string.about_license_label),
            title = stringResource(R.string.about_license_apache_full),
            subtitle = stringResource(R.string.about_license_apache_url),
            tone = FluidTone.Neutral,
            leading = { RowGlyph(Icons.Rounded.Description) },
            disclosure = false,
          )
        }
      }
    }
    item { FluidSectionFootnote(text = stringResource(R.string.about_libraries_footnote)) }
  }
}

/** Una libreria di altri autori: il nome e chi/quale versione, entrambi testi della pagina. Le licenze sono tutte Apache 2.0. */
private class LibraryCredit(val title: Int, val detail: Int)

private val LIBRARIES: List<LibraryCredit> = listOf(
  LibraryCredit(R.string.about_lib_telephoto_title, R.string.about_lib_telephoto_detail),
  LibraryCredit(R.string.about_lib_media3_title, R.string.about_lib_media3_detail),
  LibraryCredit(R.string.about_lib_exif_title, R.string.about_lib_exif_detail),
)

/** La versione dell'app dal gestore dei pacchetti, o null se non si legge (la riga allora non compare). */
internal fun appVersionName(context: Context): String? =
  try {
    val packages = context.packageManager
    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      packages.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
    } else {
      @Suppress("DEPRECATION")
      packages.getPackageInfo(context.packageName, 0)
    }
    info.versionName?.takeIf { it.isNotBlank() }
  } catch (_: PackageManager.NameNotFoundException) {
    null
  }
