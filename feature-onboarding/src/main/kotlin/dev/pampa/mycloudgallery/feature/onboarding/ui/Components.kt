package dev.pampa.mycloudgallery.feature.onboarding.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidButton
import dev.antigravity.fluidengine.ui.fluid.FluidButtonSize
import dev.antigravity.fluidengine.ui.fluid.FluidButtonStyle
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.FluidProgressBar
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.FluidSwitch
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.designsystem.design.GalleryInfoGlyph
import dev.pampa.mycloudgallery.feature.onboarding.R

// I pezzi che le schermate dell'onboarding condividono. Tutti i colori vengono da `MaterialTheme.colorScheme`
// (coppie contenitore/contenuto), tutti i raggi da `FluidRadius` con `ContinuousCornerShape`, tutto il testo da
// `MaterialTheme.typography`, tutte le misure da `OnboardingDimens`.

/** Il tono di una scheda o di una piastrella: da quale famiglia di ruoli prende i colori. */
internal enum class NoticeTone { Info, Success, Caution, Danger }

@Immutable
internal data class NoticeColors(val container: Color, val onContainer: Color, val tile: Color, val onTile: Color)

@Composable
internal fun noticeColors(tone: NoticeTone): NoticeColors {
  val s = MaterialTheme.colorScheme
  return when (tone) {
    NoticeTone.Info -> NoticeColors(s.surfaceContainerLow, s.onSurface, s.primaryContainer, s.onPrimaryContainer)
    NoticeTone.Success -> NoticeColors(s.primaryContainer, s.onPrimaryContainer, s.primary, s.onPrimary)
    NoticeTone.Caution -> NoticeColors(s.tertiaryContainer, s.onTertiaryContainer, s.tertiary, s.onTertiary)
    NoticeTone.Danger -> NoticeColors(s.errorContainer, s.onErrorContainer, s.error, s.onError)
  }
}

/** La piastrella con un'icona: il colore del tono sta qui e mai sullo sfondo di una riga. */
@Composable
internal fun GlyphTile(
  icon: ImageVector,
  tone: NoticeTone,
  modifier: Modifier = Modifier,
  size: Dp = OnboardingDimens.Tile,
  glyph: Dp = OnboardingDimens.TileGlyph,
) {
  val colors = noticeColors(tone)
  Box(
    modifier = modifier
      .size(size)
      .clip(ContinuousCornerShape(FluidRadius.Control))
      .background(colors.tile),
    contentAlignment = Alignment.Center,
  ) {
    Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(glyph), tint = colors.onTile)
  }
}

/**
 * Una scheda che dice una cosa: un avviso, un problema, una conferma.
 *
 * Il testo e' una regione "viva" (`liveRegion`): quando una scheda compare dopo un tocco (un problema, un
 * avviso), TalkBack la legge senza che l'utente debba cercarla. I bottoni restano fuori dalla regione.
 *
 * I bottoni nella scheda: il primo e' pieno (contrasto sicuro su ogni contenitore), gli altri sono tinta.
 */
@Composable
internal fun NoticeCard(
  tone: NoticeTone,
  icon: ImageVector,
  title: String,
  modifier: Modifier = Modifier,
  body: String? = null,
  compact: Boolean = false,
  onDismiss: (() -> Unit)? = null,
  bodyBehindInfo: Boolean = false,
  actions: @Composable ColumnScope.() -> Unit = {},
) {
  val colors = noticeColors(tone)
  // Fase I (CU-18, «l'app parla troppo»): una scheda che spiega (non che avverte) mostra il segno e il titolo; la frase sta dietro la «i» e si apre
  // con un tocco sul titolo. TalkBack la sente sempre. Gli avvertimenti (chiavi, recupero, gesti distruttivi) restano aperti: [bodyBehindInfo] falso.
  val folded = bodyBehindInfo && body != null
  // La regola del modulo vuole niente stato salvato nelle schermate (le password): la «i» aperta si richiude a una rotazione, e va bene.
  var open by remember(title) { mutableStateOf(false) }
  val padding = if (compact) OnboardingDimens.CompactPadding else OnboardingDimens.CardPadding
  Column(
    modifier = modifier
      .fillMaxWidth()
      .clip(ContinuousCornerShape(if (compact) FluidRadius.Card else FluidRadius.Group))
      .background(colors.container)
      .padding(padding),
    verticalArrangement = Arrangement.spacedBy(OnboardingDimens.CardGap),
  ) {
    Row(
      horizontalArrangement = Arrangement.spacedBy(OnboardingDimens.CardGap),
      verticalAlignment = Alignment.Top,
    ) {
      if (compact) {
        GlyphTile(icon = icon, tone = tone, size = OnboardingDimens.CompactTile, glyph = OnboardingDimens.CompactGlyph)
      } else {
        GlyphTile(icon = icon, tone = tone)
      }
      Column(
        modifier = Modifier
          .weight(1f)
          .then(if (folded) Modifier.fluidPressable(onClick = { open = !open }, role = Role.Button) else Modifier)
          .semantics(mergeDescendants = true) {
            liveRegion = LiveRegionMode.Polite
            if (folded) contentDescription = "$title. $body"
          },
        verticalArrangement = Arrangement.spacedBy(OnboardingDimens.TextGap),
      ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(OnboardingDimens.TextGap)) {
          Text(
            text = title,
            style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = colors.onContainer,
            modifier = Modifier.weight(1f, fill = false).then(if (folded) Modifier.semantics { hideFromAccessibility() } else Modifier),
          )
          if (folded) GalleryInfoGlyph(open = open, tint = colors.onContainer)
        }
        if (body != null && (!folded || open)) {
          Text(
            text = body,
            style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            color = colors.onContainer,
            modifier = if (folded) Modifier.semantics { hideFromAccessibility() } else Modifier,
          )
        }
      }
      if (onDismiss != null) {
        val dismissLabel = stringResource(R.string.common_dismiss)
        Box(
          modifier = Modifier
            .size(OnboardingDimens.TouchTarget)
            .semantics { contentDescription = dismissLabel }
            .fluidPressable(onClick = onDismiss, role = Role.Button),
          contentAlignment = Alignment.Center,
        ) {
          Icon(
            imageVector = Icons.Rounded.Close,
            contentDescription = null,
            modifier = Modifier.size(OnboardingDimens.MarkGlyph),
            tint = colors.onContainer,
          )
        }
      }
    }
    actions()
  }
}

/** Un bottone dentro una scheda: pieno se e' il principale, tinta altrimenti. */
@Composable
internal fun CardButton(
  text: String,
  onClick: () -> Unit,
  primary: Boolean,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  loading: Boolean = false,
) {
  FluidButton(
    text = text,
    onClick = onClick,
    modifier = modifier,
    style = if (primary) FluidButtonStyle.Filled else FluidButtonStyle.Tinted,
    size = FluidButtonSize.Large,
    enabled = enabled,
    loading = loading,
    fillWidth = true,
  )
}

/** Il segno di un'opzione scelta: un cerchio dell'accento con la spunta, o un anello vuoto. */
@Composable
internal fun SelectionMark(selected: Boolean, modifier: Modifier = Modifier) {
  val s = MaterialTheme.colorScheme
  Box(
    modifier = modifier
      .size(OnboardingDimens.Mark)
      .clip(FluidCapsuleShape)
      .then(
        if (selected) {
          Modifier.background(s.primary)
        } else {
          Modifier.border(width = OnboardingDimens.MarkRing, color = s.outline, shape = FluidCapsuleShape)
        },
      ),
    contentAlignment = Alignment.Center,
  ) {
    if (selected) {
      Icon(
        imageVector = Icons.Rounded.Check,
        contentDescription = null,
        modifier = Modifier.size(OnboardingDimens.MarkGlyph),
        tint = s.onPrimary,
      )
    }
  }
}

/** Una riga con un interruttore: la riga intera e' il bersaglio (48 dp) e dice di essere un interruttore. */
@Composable
internal fun ToggleRow(
  title: String,
  subtitle: String,
  icon: ImageVector,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  modifier: Modifier = Modifier,
  tone: FluidTone = FluidTone.Primary,
  enabled: Boolean = true,
) {
  FluidListRow(
    title = title,
    subtitle = subtitle,
    modifier = modifier.semantics(mergeDescendants = true) {
      role = Role.Switch
      toggleableState = ToggleableState(checked)
    },
    tone = tone,
    leading = { Icon(imageVector = icon, contentDescription = null) },
    badge = { FluidSwitch(checked = checked, onCheckedChange = null, enabled = enabled) },
    onClick = if (enabled) ({ onCheckedChange(!checked) }) else null,
    disclosure = false,
  )
}

/** Una riga che si sceglie fra altre (come un pulsante radio): evidenziata e con la spunta a destra. */
@Composable
internal fun ChoiceRow(
  title: String,
  subtitle: String,
  selected: Boolean,
  onSelect: () -> Unit,
  modifier: Modifier = Modifier,
  icon: ImageVector? = null,
  tone: FluidTone = FluidTone.Primary,
  enabled: Boolean = true,
  labels: (@Composable () -> Unit)? = null,
) {
  FluidListRow(
    title = title,
    subtitle = subtitle,
    modifier = modifier
      .alpha(if (enabled) 1f else DisabledAlpha)
      .semantics(mergeDescendants = true) {
        role = Role.RadioButton
        if (!enabled) disabled()
      },
    tone = tone,
    leading = icon?.let { glyph -> { Icon(imageVector = glyph, contentDescription = null) } },
    labels = labels,
    badge = if (enabled) ({ SelectionMark(selected = selected) }) else null,
    onClick = if (enabled) onSelect else null,
    selected = selected,
    disclosure = false,
  )
}

/** "Passo 3 di 14" e la barra: dice a che punto si e', senza prendere la scena al titolo. */
@Composable
internal fun StepProgressHeader(label: String, fraction: Float, modifier: Modifier = Modifier) {
  val animated by animateFloatAsState(
    targetValue = fraction,
    animationSpec = FluidMotion.smooth(),
    label = "onboarding progress",
  )
  Column(
    modifier = modifier
      .fillMaxWidth()
      .semantics(mergeDescendants = true) { contentDescription = label },
    verticalArrangement = Arrangement.spacedBy(OnboardingDimens.TextGap),
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelLarge,
      fontWeight = FontWeight.SemiBold,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FluidProgressBar(progress = { animated })
  }
}

/** Il segno di "fatto": una piastrella piena dell'accento con la spunta, che entra con la molla della casa. */
@Composable
internal fun SuccessMark(modifier: Modifier = Modifier) {
  val reduced = LocalFluidMotionPolicy.current.reducedMotion
  val scale = remember { Animatable(if (reduced) 1f else MarkEntryScale) }
  LaunchedEffect(Unit) {
    if (!reduced) scale.animateTo(1f, FluidMotion.fluid())
  }
  Box(
    modifier = modifier
      .size(OnboardingDimens.HeroTile)
      .graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
      }
      .clip(ContinuousCornerShape(FluidRadius.Card))
      .background(MaterialTheme.colorScheme.primary),
    contentAlignment = Alignment.Center,
  ) {
    Icon(
      imageVector = Icons.Rounded.Check,
      contentDescription = null,
      modifier = Modifier.size(OnboardingDimens.HeroGlyph),
      tint = MaterialTheme.colorScheme.onPrimary,
    )
  }
}

private const val MarkEntryScale = 0.6f

/** Una riga che non si puo' scegliere si vede, ma si legge piu' piano (come un bottone spento: sbiadisce, non diventa grigia). */
private const val DisabledAlpha = 0.5f

/**
 * Un codice in gruppi (di recupero o di approvazione): un riquadro per gruppo, nell'ordine di lettura.
 *
 * Ogni gruppo si legge a voce lettera per lettera ("Gruppo 2 di 7: K, 7, Q, D"): un codice pronunciato come
 * una parola e' un codice sbagliato. I caratteri non diventano mai un'unica stringa leggibile: il lettore di
 * schermo ha un'etichetta per gruppo.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CodeGroups(
  groups: List<String>,
  cellMinWidth: Dp,
  modifier: Modifier = Modifier,
  large: Boolean = false,
) {
  val style = if (large) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineSmall
  val shape = ContinuousCornerShape(FluidRadius.Control)
  FlowRow(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(OnboardingDimens.CodeGap, Alignment.CenterHorizontally),
    verticalArrangement = Arrangement.spacedBy(OnboardingDimens.CodeGap),
  ) {
    groups.forEachIndexed { index, group ->
      val spoken = stringResource(R.string.code_group_spoken, index + 1, groups.size, group.toList().joinToString(", "))
      Column(
        modifier = Modifier
          .defaultMinSize(minWidth = cellMinWidth)
          .clip(shape)
          .background(MaterialTheme.colorScheme.surfaceContainerLowest)
          .border(OnboardingDimens.Hairline, MaterialTheme.colorScheme.outlineVariant, shape)
          .padding(horizontal = OnboardingDimens.CardGap, vertical = OnboardingDimens.CardGap)
          .clearAndSetSemantics { contentDescription = spoken },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(OnboardingDimens.TextGap),
      ) {
        Text(
          text = (index + 1).toString(),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
          text = group,
          style = style.copy(fontFeatureSettings = TabularFigures),
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface,
          textAlign = TextAlign.Center,
        )
      }
    }
  }
}

private const val TabularFigures = "tnum"

/**
 * "Mostra" / "Nascondi" per un campo segreto: l'occhio e la parola, alti almeno 48 dp, con la descrizione per il lettore
 * di schermo. Sta sopra il campo, sulla riga dell'etichetta ([SecretFieldLabel]): dentro il campo (lo slot `trailing`
 * dell'engine e' di 18 dp) il bersaglio di tocco sarebbe troppo piccolo.
 */
@Composable
internal fun RevealToggle(revealed: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
  val description = stringResource(if (revealed) R.string.reveal_hide else R.string.reveal_show)
  Row(
    modifier = modifier
      .defaultMinSize(minWidth = OnboardingDimens.TouchTarget, minHeight = OnboardingDimens.TouchTarget)
      .semantics { contentDescription = description }
      .fluidPressable(onClick = onToggle, role = Role.Button),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(OnboardingDimens.TextGap, Alignment.End),
  ) {
    Icon(
      imageVector = if (revealed) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
      contentDescription = null,
      modifier = Modifier.size(OnboardingDimens.MarkGlyph),
      tint = MaterialTheme.colorScheme.primary,
    )
    Text(
      text = stringResource(if (revealed) R.string.reveal_hide_short else R.string.reveal_show_short),
      style = MaterialTheme.typography.labelLarge,
      fontWeight = FontWeight.SemiBold,
      color = MaterialTheme.colorScheme.primary,
    )
  }
}

/** L'etichetta di un campo segreto con "Mostra"/"Nascondi" a destra, nella stessa riga. */
@Composable
internal fun SecretFieldLabel(label: String, revealed: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
  Row(
    modifier = modifier.fillMaxWidth().padding(start = OnboardingDimens.TextGap),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    RevealToggle(revealed = revealed, onToggle = onToggle)
  }
}
