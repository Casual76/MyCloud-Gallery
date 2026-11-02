package dev.pampa.mycloudgallery.feature.settings.about

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.ImageVector
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionFootnote
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.designsystem.a11y.GallerySectionHeader
import dev.pampa.mycloudgallery.feature.settings.R
import dev.pampa.mycloudgallery.feature.settings.ui.RowGlyph

/**
 * "Novita' di questa versione" (Fase 10, P12; D33): Impostazioni -> Informazioni e licenze -> la riga con questo nome. Una pagina semplice, con
 * i componenti di tutte le altre (nessuna grafica nuova): un titolo per ogni voce del changelog e una riga per ogni frase. Il testo e' la sezione
 * della versione di `CHANGELOG.md`, impacchettata come risorsa: nessuna rete, niente da scaricare. E' solo lettura, senza ViewModel.
 */
@Composable
internal fun WhatsNewRoute(versionName: String?, onBack: () -> Unit) {
  val context = LocalContext.current
  val text = remember(context) { readChangelog(context) }
  val parsed = remember(text, versionName) {
    val exact = text?.let { ReleaseNotesParser.forVersion(it, versionName) }
    // Senza la sezione della versione si mostra la piu' recente e la pagina lo dice: mai una pagina vuota, mai una versione scambiata di nascosto.
    if (exact != null) exact to true else text?.let { ReleaseNotesParser.parse(it).firstOrNull() }?.let { it to false }
  }
  WhatsNewScreen(notes = parsed?.first, matchesVersion = parsed?.second ?: false, versionName = versionName, onBack = onBack)
}

/** La pagina vera, senza stato: [notes] e' la sezione da mostrare (o null se la risorsa manca), [matchesVersion] dice se e' quella dell'app. */
@Composable
internal fun WhatsNewScreen(notes: ReleaseNotes?, matchesVersion: Boolean, versionName: String?, onBack: () -> Unit) {
  FluidScreen(title = stringResource(R.string.whatsnew_title), onBack = onBack) {
    if (notes == null) {
      item { GallerySectionHeader(title = stringResource(R.string.whatsnew_empty_title)) }
      item { FluidSectionFootnote(text = stringResource(R.string.whatsnew_empty)) }
      return@FluidScreen
    }
    item {
      GallerySectionHeader(
        title = stringResource(R.string.whatsnew_version, notes.version),
        detail = if (matchesVersion) null else stringResource(R.string.whatsnew_other_version, versionName ?: "?"),
      )
    }
    notes.sections.forEach { section ->
      item { GallerySectionHeader(title = section.title) }
      item {
        SelectionContainer {
          FluidListGroup {
            section.items.forEachIndexed { index, sentence ->
              if (index > 0) FluidListDivider()
              FluidListRow(
                title = sentence,
                subtitle = "",
                tone = toneOf(section.title),
                leading = { RowGlyph(glyphOf(section.title)) },
                disclosure = false,
              )
            }
          }
        }
      }
    }
    item { FluidSectionFootnote(text = stringResource(R.string.whatsnew_footnote)) }
  }
}

/** Le quattro voci del changelog hanno un tono e un'icona; un titolo che non conosciamo resta neutro, con l'icona delle informazioni. */
private fun toneOf(title: String): FluidTone = when (title.lowercase()) {
  "novità", "novita'" -> FluidTone.Primary
  "correzioni" -> FluidTone.Neutral
  "sicurezza" -> FluidTone.Info
  else -> FluidTone.Neutral
}

private fun glyphOf(title: String): ImageVector = when (title.lowercase()) {
  "novità", "novita'" -> Icons.Rounded.AutoAwesome
  "correzioni" -> Icons.Rounded.Build
  "sicurezza" -> Icons.Rounded.Lock
  else -> Icons.Rounded.Info
}

/** Il testo del changelog dalla risorsa dell'app, o null se non si legge (la pagina allora lo dice). */
private fun readChangelog(context: android.content.Context): String? =
  try {
    context.resources.openRawResource(R.raw.changelog).bufferedReader(Charsets.UTF_8).use { it.readText() }.take(ReleaseNotesParser.MAX_CHARS)
  } catch (_: Exception) {
    null
  }
