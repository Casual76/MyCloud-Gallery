package dev.pampa.mycloudgallery.feature.settings.about

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Public
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.foundation.EngineBuild
import dev.antigravity.fluidengine.ui.fluid.FluidEngineLicenses
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.pampa.mycloudgallery.core.designsystem.a11y.GallerySectionHeader
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.feature.settings.R
import dev.pampa.mycloudgallery.feature.settings.ui.RowTile
import dev.pampa.mycloudgallery.feature.settings.ui.SettingsInfo

/**
 * "Informazioni e licenze" (Fase 3, P11; docs/fase3-progetto.md §1.3): cos'e' l'app, da dove vengono i nomi dei luoghi
 * (GeoNames, CC BY 4.0: l'attribuzione e' un obbligo della licenza), **tutte** le librerie di altri autori incluse
 * nell'APK di release con la loro licenza ([ABOUT_LIBRARIES], dalle dipendenze di runtime), le opere dentro il Fluid Engine
 * (`FluidEngineLicenses`) e il testo breve di ogni licenza con il suo indirizzo (revisione di sicurezza, I6). Dal Fluid Engine 3.0 la riga dell'app dice
 * anche la sua licenza, la GPL-3.0 (l'engine porta codice GPL-3.0 di LibrePods): sorgenti a chi riceve l'APK, nessuna garanzia, il testo nel file `LICENSE`.
 *
 * E' una pagina di sola lettura, senza ViewModel. Gli indirizzi delle licenze sono **testo selezionabile, mai un
 * collegamento**: la Fase 3 non fa traffico di rete e nessun tocco qui apre un browser (D18, D24).
 */
@Composable
fun AboutRoute(onBack: () -> Unit) {
  val context = LocalContext.current
  val versionName = remember(context) { appVersionName(context) }
  // Fase 10 (P12, D33): "Novita' di questa versione" e' una pagina di Informazioni e licenze. Resta dentro questa rotta (uno stato salvato, il
  // tasto Indietro torna qui) cosi' la navigazione delle Impostazioni non cambia.
  var showNews by rememberSaveable { mutableStateOf(false) }
  if (showNews) {
    BackHandler { showNews = false }
    WhatsNewRoute(versionName = versionName, onBack = { showNews = false })
  } else {
    AboutScreen(versionName = versionName, onBack = onBack, onOpenNews = { showNews = true })
  }
}

/**
 * La pagina vera, senza stato: [versionName] e' la versione dell'app, o null se il sistema non la dice (allora la
 * riga non compare). [onOpenNews] apre "Novita' di questa versione" (Fase 10, P12): senza, la riga non compare. La usano
 * `AboutRoute` e i test.
 */
@Composable
internal fun AboutScreen(versionName: String?, onBack: () -> Unit, onOpenNews: (() -> Unit)? = null) {
  FluidScreen(title = stringResource(R.string.about_title), onBack = onBack) {
    // --- L'app -----------------------------------------------------------------------------------------------
    item { GallerySectionHeader(title = stringResource(R.string.about_section_app)) }
    item {
      SelectionContainer {
        FluidListGroup {
          FluidListRow(
            title = stringResource(R.string.about_app_name),
            subtitle = stringResource(R.string.about_app_detail),
            meta = versionName?.let { stringResource(R.string.about_app_version, it) },
            tone = FluidTone.Primary,
            leading = { RowTile(Icons.Rounded.Info) },
            leadingTile = false,
            disclosure = false,
          )
          // Adozione del Fluid Engine 3.0: l'app e' GPL-3.0 (l'engine porta codice di LibrePods). Testo da leggere, nessun tocco.
          FluidListDivider()
          FluidListRow(
            eyebrow = stringResource(R.string.about_license_label),
            title = stringResource(R.string.about_app_license_title),
            subtitle = stringResource(R.string.about_app_license_detail),
            tone = FluidTone.Neutral,
            leading = { RowTile(Icons.Rounded.Description) },
            leadingTile = false,
            disclosure = false,
          )
        }
      }
    }

    // --- Novita' di questa versione (Fase 10, P12, D33) ------------------------------------------------------------
    if (onOpenNews != null) {
      item {
        FluidListGroup {
          FluidListRow(
            title = stringResource(R.string.whatsnew_row),
            subtitle = stringResource(R.string.whatsnew_row_detail),
            tone = FluidTone.Primary,
            leading = { RowTile(Icons.Rounded.AutoAwesome) },
            leadingTile = false,
            onClick = onOpenNews,
          )
        }
      }
    }

    // --- Luoghi offline: GeoNames, CC BY 4.0 -------------------------------------------------------------------
    item {
      GallerySectionHeader(title = stringResource(R.string.about_section_places))
    }
    item {
      SelectionContainer {
        FluidListGroup {
          FluidListRow(
            title = stringResource(R.string.about_geonames_title),
            subtitle = stringResource(R.string.about_geonames_attribution),
            tone = FluidTone.Info,
            leading = { RowTile(Icons.Rounded.Public) },
            leadingTile = false,
            disclosure = false,
          )
          FluidListDivider()
          FluidListRow(
            eyebrow = stringResource(R.string.about_license_label),
            title = stringResource(R.string.about_license_cc_by),
            subtitle = stringResource(R.string.about_license_cc_by_url),
            tone = FluidTone.Neutral,
            leading = { RowTile(Icons.Rounded.Description) },
            leadingTile = false,
            disclosure = false,
          )
        }
      }
    }
    item { FluidSectionFootnote(text = stringResource(R.string.about_geonames_note)) }
    // Giro 1 della fase I (CU-5): la frase sotto il titolo del gruppo sta dietro la «i»; le attribuzioni restano in pagina (licenze).
    item { SettingsInfo(label = stringResource(R.string.about_section_info), text = stringResource(R.string.about_section_places_detail)) }

    // --- La mappa (Fase 9, P2): OpenStreetMap e OpenMapTiles (online), Natural Earth e GeoNames (carta di base), stile e caratteri ---------
    item {
      GallerySectionHeader(title = stringResource(R.string.about_section_map))
    }
    item {
      SelectionContainer {
        FluidListGroup {
          FluidListRow(
            title = stringResource(R.string.about_map_online_title),
            subtitle = stringResource(R.string.about_map_online_attribution),
            tone = FluidTone.Info,
            leading = { RowTile(Icons.Rounded.Public) },
            leadingTile = false,
            disclosure = false,
          )
          FluidListDivider()
          FluidListRow(
            eyebrow = stringResource(R.string.about_license_label),
            title = stringResource(R.string.about_license_odbl),
            subtitle = stringResource(R.string.about_license_odbl_url),
            tone = FluidTone.Neutral,
            leading = { RowTile(Icons.Rounded.Description) },
            leadingTile = false,
            disclosure = false,
          )
          FluidListDivider()
          FluidListRow(
            title = stringResource(R.string.about_map_base_title),
            subtitle = stringResource(R.string.about_map_base_attribution),
            tone = FluidTone.Info,
            leading = { RowTile(Icons.Rounded.Public) },
            leadingTile = false,
            disclosure = false,
          )
          FluidListDivider()
          FluidListRow(
            title = stringResource(R.string.about_map_style_title),
            subtitle = stringResource(R.string.about_map_style_attribution),
            tone = FluidTone.Neutral,
            leading = { RowTile(Icons.Rounded.Code) },
            leadingTile = false,
            disclosure = false,
          )
        }
      }
    }
    item { FluidSectionFootnote(text = stringResource(R.string.about_map_note)) }
    item { SettingsInfo(label = stringResource(R.string.about_section_info), text = stringResource(R.string.about_section_map_detail)) }

    // --- Librerie (tutte quelle dell'APK di release) -----------------------------------------------------------------
    item {
      GallerySectionHeader(title = stringResource(R.string.about_section_libraries))
    }
    item {
      SelectionContainer {
        FluidListGroup {
          ABOUT_LIBRARIES.forEachIndexed { index, library ->
            if (index > 0) FluidListDivider()
            FluidListRow(
              title = library.name,
              subtitle = library.subtitle,
              meta = stringResource(library.license.shortName),
              tone = FluidTone.Neutral,
              leading = { RowTile(Icons.Rounded.Code) },
              leadingTile = false,
              disclosure = false,
            )
          }
        }
      }
    }
    item { FluidSectionFootnote(text = stringResource(R.string.about_libraries_footnote)) }
    item { SettingsInfo(label = stringResource(R.string.about_section_info), text = stringResource(R.string.about_section_libraries_detail)) }

    // --- Il Fluid Engine: le opere di altri che porta con se' -------------------------------------------------------------
    item {
      GallerySectionHeader(title = stringResource(R.string.about_section_engine))
    }
    item { SettingsInfo(label = stringResource(R.string.about_section_info), text = stringResource(R.string.about_section_engine_detail, EngineBuild.VERSION)) }
    item {
      SelectionContainer {
        FluidListGroup {
          FluidEngineLicenses.forEachIndexed { index, work ->
            if (index > 0) FluidListDivider()
            FluidListRow(
              title = work.name,
              // Autore, licenza e indirizzo del progetto: testo da leggere, mai un collegamento (FluidLicenseGroup apre il
              // browser al tocco; qui nessun tocco apre niente).
              subtitle = "${work.author} · ${work.licence}\n${work.url}",
              tone = FluidTone.Neutral,
              leading = { RowTile(Icons.Rounded.Code) },
              leadingTile = false,
              disclosure = false,
            )
          }
        }
      }
    }

    // --- Il testo breve delle licenze --------------------------------------------------------------------------------------
    item { GallerySectionHeader(title = stringResource(R.string.about_section_licenses)) }
    item {
      SelectionContainer {
        FluidListGroup {
          ABOUT_LICENSES_IN_USE.forEachIndexed { index, license ->
            if (index > 0) FluidListDivider()
            FluidListRow(
              eyebrow = stringResource(R.string.about_license_label),
              title = stringResource(license.fullName),
              subtitle = stringResource(license.url),
              tone = FluidTone.Neutral,
              leading = { RowTile(Icons.Rounded.Description) },
              leadingTile = false,
              disclosure = false,
            )
          }
        }
      }
    }
    item { FluidSectionFootnote(text = stringResource(R.string.about_licenses_footnote)) }
  }
}

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
