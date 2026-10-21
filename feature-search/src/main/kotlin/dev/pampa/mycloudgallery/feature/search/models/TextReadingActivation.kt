package dev.pampa.mycloudgallery.feature.search.models

import android.content.res.Resources
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.theme.FluidInlineMessage
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.ai.contract.TextReadingConsent
import dev.pampa.mycloudgallery.core.ai.contract.TextReadingSwitch
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.feature.search.R
import dev.pampa.mycloudgallery.feature.search.ui.SearchDimens

// LA PAGINA DI ATTIVAZIONE DELLA LETTURA DEL TESTO (Fase 6, P8, docs/fase6-progetto.md §2.6, D41; risposte Q4 e Q13). Dice in frasi brevi cosa fa ML Kit
// e cosa manda a Google (statistiche tecniche, **mai** foto ne' testo), poi "Attiva" e "Non ora" **con lo stesso peso**: nessuna spinta verso
// "Attiva" (e' un'eccezione alla regola "nessuna telemetria", D49). Il testo ha una versione: il consenso la porta con se' ([TextReadingConsent.textVersion])
// e, se il testo cambia, la funzione si richiede.
//
// **E' l'unico file che costruisce un `TextReadingConsent`** (Phase6ArchitectureTest): senza questa pagina la funzione non si accende.

/** Il testo della pagina, come dato: lo prova un test (le parole che devono esserci, quelle che non devono) senza comporre niente. */
object TextReadingActivationText {

  /** I quattro paragrafi, con in grassetto cio' che conta (§2.6). */
  fun paragraphs(resources: Resources): List<AnnotatedString> = listOf(
    withBold(resources, R.string.activation_what_pre, R.string.activation_what_bold, R.string.activation_what_post),
    withBold(resources, R.string.activation_sends_pre, R.string.activation_sends_bold, R.string.activation_sends_post),
    withBold(resources, R.string.activation_scope_pre, R.string.activation_scope_bold, R.string.activation_scope_post),
    AnnotatedString(resources.getString(R.string.activation_others)),
  )

  private fun withBold(resources: Resources, pre: Int, bold: Int, post: Int): AnnotatedString = buildAnnotatedString {
    append(resources.getString(pre))
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(resources.getString(bold)) }
    append(resources.getString(post))
  }
}

/**
 * La pagina. [versionChanged]: l'account l'aveva gia' attivata ma il testo e' cambiato: in testa una nota dice di rileggerlo. "Attiva" costruisce il
 * consenso con la **versione del testo di questa pagina** ([TextReadingSwitch.CURRENT_TEXT_VERSION]) e l'istante monotono in cui la pagina e' sullo
 * schermo; "Non ora" non fa niente e non spegne niente.
 */
@Composable
fun TextReadingActivationScreen(
  onActivate: (TextReadingConsent) -> Unit,
  onNotNow: () -> Unit,
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
  versionChanged: Boolean = false,
) {
  val resources = LocalContext.current.resources
  val paragraphs = remember(resources) { TextReadingActivationText.paragraphs(resources) }
  // L'istante (orologio monotono, D39) in cui la pagina e' sullo schermo: il consenso lo porta con se'.
  val shownAt = remember { System.nanoTime() }
  val enable = stringResource(R.string.activation_enable)
  val notNow = stringResource(R.string.activation_not_now)
  val enableDescription = stringResource(R.string.activation_enable_description)
  val notNowDescription = stringResource(R.string.activation_not_now_description)
  FluidScreen(title = stringResource(R.string.activation_title), modifier = modifier, onBack = onBack) {
    if (versionChanged) {
      item(key = "changed") {
        FluidInlineMessage(
          message = stringResource(R.string.activation_changed),
          title = stringResource(R.string.activation_title),
          tone = FluidTone.Warning,
          modifier = Modifier.padding(bottom = SearchDimens.BlockGap),
        )
      }
    }
    paragraphs.forEachIndexed { index, text ->
      item(key = "paragraph-$index") {
        Text(
          text = text,
          modifier = Modifier.widthIn(max = SearchDimens.ReadingWidth).padding(vertical = SearchDimens.NoticeInset),
          style = MaterialTheme.typography.bodyLarge,
          color = MaterialTheme.colorScheme.onSurface,
        )
      }
    }
    item(key = "buttons") {
      // Lo stesso peso, la stessa forma, la stessa larghezza: l'utente sceglie, la pagina non spinge.
      Row(
        modifier = Modifier.fillMaxWidth().padding(top = SearchDimens.BlockGap, bottom = GalleryDimens.Gutter),
        horizontalArrangement = Arrangement.spacedBy(SearchDimens.TightGap),
      ) {
        FluidButton(
          text = notNow,
          onClick = onNotNow,
          style = FluidButtonStyle.Tinted,
          size = FluidButtonSize.Large,
          modifier = Modifier.weight(1f).semantics { contentDescription = notNowDescription },
        )
        FluidButton(
          text = enable,
          onClick = { onActivate(TextReadingConsent(TextReadingSwitch.CURRENT_TEXT_VERSION, shownAt)) },
          style = FluidButtonStyle.Tinted,
          size = FluidButtonSize.Large,
          modifier = Modifier.weight(1f).semantics { contentDescription = enableDescription },
        )
      }
    }
  }
}
