package dev.pampa.mycloudgallery.feature.backup.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import dev.antigravity.fluidengine.ui.fluid.GlassBackdropState
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryTopChrome
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryTopEdgeScrim
import dev.pampa.mycloudgallery.core.designsystem.design.galleryTopChromeHeight
import dev.pampa.mycloudgallery.feature.backup.R

// La chrome delle schermate del backup: il linguaggio G2, direzione C "vetro adattivo" (docs/grafica-progetto.md §11, §12), lo stesso
// delle Raccolte e delle pagine della 4B (`NasPage` di feature-nas, di cui questa e' la versione senza barra in fondo). In alto UNA riga
// sotto la barra di stato: (indietro) | etichetta | capsula dei comandi, pezzi di vetro adattivo su una sfumatura del colore della pagina;
// sotto, la pagina che scorre. Un solo fondale di vetro per pagina: lo registra [BackupPage]. D30: nessuno stile nuovo.
//
// Il contenuto che passa sotto la chrome qui e' pagina e testo (non foto con ThumbHash): si garantisce il caso peggiore assoluto (nero e
// bianco pieni) sotto la sfumatura, come fanno gli Eliminati di recente e le pagine della 4B.

/** Che cosa dice l'etichetta in alto: il titolo e, sotto, il luogo o il riepilogo. */
@Immutable
internal data class BackupLabel(val title: String, val subtitle: String? = null)

/** Lo spazio che la chrome toglie al contenuto: sopra la riga in alto, sotto il bordo (la pillola delle schede, se c'e'). */
@Immutable
internal data class BackupInsets(val top: Dp, val bottom: Dp)

/**
 * Una pagina del backup: lo sfondo, il fondale del vetro, la sfumatura in alto, la riga di chrome e il contenuto.
 *
 * @param onBack il tasto indietro; null nel pannello di dettaglio del tablet e dove la pagina e' ospitata (non e' una rotta).
 * @param commands i comandi nella capsula a destra; null = niente capsula.
 * @param hostPadding lo spazio che la pillola delle schede occupa in basso (come `contentPadding` delle Raccolte): zero a schermo
 *   intero, il suo ingombro quando la pagina sta nel pannello di dettaglio di un tablet.
 */
@Composable
internal fun BackupPage(
  label: BackupLabel,
  modifier: Modifier = Modifier,
  onBack: (() -> Unit)? = null,
  backdropOut: ((GlassBackdropState) -> Unit)? = null,
  commands: (@Composable RowScope.() -> Unit)? = null,
  hostPadding: PaddingValues = PaddingValues(),
  content: @Composable BoxScope.(BackupInsets) -> Unit,
) {
  val backdrop = rememberGlassBackdrop()
  if (backdropOut != null) SideEffect { backdropOut(backdrop) }
  val topChrome = galleryTopChromeHeight()
  val bottomInset = maxOf(hostPadding.calculateBottomPadding(), WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding())

  Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    Box(modifier = Modifier.fillMaxSize().glassBackdropSource(backdrop)) {
      content(BackupInsets(top = topChrome, bottom = bottomInset))
      GalleryTopEdgeScrim(topChrome, Modifier.align(Alignment.TopCenter))
    }
    GalleryTopChrome(
      content = null,
      backdrop = backdrop,
      title = label.title,
      subtitle = label.subtitle,
      topChrome = topChrome,
      modifier = Modifier.align(Alignment.TopStart),
      onBack = onBack,
      backDescription = stringResource(R.string.backup_back),
      showCommands = commands != null,
      commands = commands ?: {},
    )
  }
}
