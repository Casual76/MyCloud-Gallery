package dev.pampa.mycloudgallery.feature.onboarding.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProgress
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.feature.onboarding.R
import dev.pampa.mycloudgallery.feature.onboarding.titleRes

/** In che stato e' un passo nella barra laterale. */
internal enum class SidebarState { DONE, CURRENT, SKIPPED, TODO }

internal fun OnboardingProgress.sidebarState(step: OnboardingStep, current: OnboardingStep): SidebarState = when {
  step == current -> SidebarState.CURRENT
  step in skipped && step !in completed -> SidebarState.SKIPPED
  step in completed -> SidebarState.DONE
  else -> SidebarState.TODO
}

/**
 * La barra laterale dei passi, su uno schermo largo (tablet): il percorso intero a colpo d'occhio, con il passo di
 * adesso evidenziato, quelli fatti spuntati e quelli saltati con un orologio. Sta sul fondale della finestra (un
 * fondale solo, come in ogni pagina a due pannelli) e non e' interattiva: e' una mappa, non un menu.
 *
 * Per il lettore di schermo non esiste: ogni pagina dice gia' "Passo 3 di 14", e una seconda lista di passi in
 * testa all'ordine di lettura sarebbe solo rumore.
 */
@Composable
internal fun StepSidebar(
  progress: OnboardingProgress,
  currentStep: OnboardingStep?,
  modifier: Modifier = Modifier,
) {
  val steps = progress.plan
  Column(
    modifier = modifier
      .width(OnboardingDimens.SidebarWidth)
      .fillMaxHeight()
      .windowInsetsPadding(WindowInsets.safeDrawing)
      .padding(horizontal = OnboardingDimens.Gutter, vertical = OnboardingDimens.DockPadding)
      .clearAndSetSemantics { },
    verticalArrangement = Arrangement.spacedBy(OnboardingDimens.CardGap),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(OnboardingDimens.CardGap),
    ) {
      GlyphTile(icon = Icons.Rounded.PhotoLibrary, tone = NoticeTone.Success)
      Column {
        Text(
          text = stringResource(R.string.sidebar_title),
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
          text = stringResource(R.string.sidebar_subtitle),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .weight(1f)
        .verticalScroll(rememberScrollState())
        .padding(top = OnboardingDimens.CardGap),
    ) {
      steps.forEachIndexed { index, step ->
        SidebarRow(
          number = index + 1,
          title = stringResource(step.titleRes()),
          state = if (currentStep == null) SidebarState.TODO else progress.sidebarState(step, currentStep),
          last = index == steps.lastIndex,
        )
      }
    }
    Text(
      text = stringResource(R.string.sidebar_privacy),
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun SidebarRow(number: Int, title: String, state: SidebarState, last: Boolean) {
  val scheme = MaterialTheme.colorScheme
  Row(
    modifier = Modifier.height(IntrinsicSize.Min),
    horizontalArrangement = Arrangement.spacedBy(OnboardingDimens.CardGap),
  ) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      Box(
        modifier = Modifier
          .size(OnboardingDimens.StepDot)
          .clip(FluidCapsuleShape)
          .then(
            when (state) {
              SidebarState.DONE -> Modifier.background(scheme.primary)
              SidebarState.CURRENT -> Modifier.background(scheme.primaryContainer).border(OnboardingDimens.MarkRing, scheme.primary, FluidCapsuleShape)
              SidebarState.SKIPPED -> Modifier.background(scheme.tertiaryContainer)
              SidebarState.TODO -> Modifier.border(OnboardingDimens.MarkRing, scheme.outlineVariant, FluidCapsuleShape)
            },
          ),
        contentAlignment = Alignment.Center,
      ) {
        when (state) {
          SidebarState.DONE -> Icon(Icons.Rounded.Check, null, Modifier.size(OnboardingDimens.MarkGlyph), tint = scheme.onPrimary)
          SidebarState.SKIPPED -> Icon(Icons.Rounded.Schedule, null, Modifier.size(OnboardingDimens.MarkGlyph), tint = scheme.onTertiaryContainer)
          SidebarState.CURRENT -> Text(number.toString(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = scheme.onPrimaryContainer)
          SidebarState.TODO -> Text(number.toString(), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
        }
      }
      if (!last) {
        Box(
          modifier = Modifier
            .width(OnboardingDimens.StepRail)
            .weight(1f)
            .background(if (state == SidebarState.DONE) scheme.primary else scheme.outlineVariant),
        )
      }
    }
    Text(
      text = title,
      modifier = Modifier.padding(bottom = if (last) OnboardingDimens.None else OnboardingDimens.SidebarGap),
      style = if (state == SidebarState.CURRENT) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
      fontWeight = if (state == SidebarState.CURRENT) FontWeight.SemiBold else FontWeight.Normal,
      color = if (state == SidebarState.CURRENT) scheme.onSurface else scheme.onSurfaceVariant,
    )
  }
}
