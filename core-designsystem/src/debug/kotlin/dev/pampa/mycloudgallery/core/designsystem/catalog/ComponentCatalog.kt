package dev.pampa.mycloudgallery.core.designsystem.catalog

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidColumnsDefaults
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalHost
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.FluidScreen
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader
import dev.antigravity.fluidengine.ui.fluid.LocalFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.antigravity.fluidengine.ui.fluid.fluidGlassModalObscured
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.rememberFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.mycloudgallery.core.designsystem.R

/** Il tag dell'elemento che i test degli screenshot catturano: la pagina di un gruppo, intera. */
const val CATALOG_SHOT_TAG: String = "catalog-shot"

/**
 * Il "Catalogo componenti" interno (piano 1.2): una pagina che mostra ogni componente della galleria in
 * chiaro e scuro, con le sue varianti e i suoi stati, per la revisione grafica. Si apre solo dalla
 * build debug.
 *
 * Due livelli: l'indice dei gruppi (celle, badge, barre, menu, fogli, stati, stato globale,
 * intestazioni, movimento, tema) e la pagina di un gruppo. Il passaggio fra i due e' laterale e opaco,
 * come ogni rotta (regola 3: una pagina in arrivo non sfuma mai).
 *
 * Alla radice vive un `FluidGlassModalHost`, come in un'app vera: e' quello che fa aprire i fogli di
 * conferma e il menu ⋮ del catalogo "per davvero", con il vetro che rifrange la pagina.
 */
@Composable
fun ComponentCatalog(
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var current by rememberSaveable { mutableStateOf<CatalogGroup?>(null) }
  val modalHost = rememberFluidGlassModalHostState()
  val backdrop = rememberGlassBackdrop()
  val reducedMotion = LocalFluidMotionPolicy.current.reducedMotion

  // Il piu' interno vince: dentro un gruppo "indietro" torna all'indice, e solo li' esce dal catalogo.
  BackHandler(enabled = current != null) { current = null }

  CompositionLocalProvider(LocalFluidGlassModalHostState provides modalHost) {
    Box(modifier = modifier.fillMaxSize()) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .glassBackdropSource(backdrop)
          .fluidGlassModalObscured(),
      ) {
        AnimatedContent(
          targetState = current,
          modifier = Modifier.fillMaxSize(),
          transitionSpec = { catalogTransition(forward = targetState != null, reducedMotion = reducedMotion) },
          label = "catalog page",
        ) { group ->
          Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            if (group == null) {
              CatalogIndex(onBack = onBack, onOpen = { current = it })
            } else {
              CatalogGroupScreen(group = group, onBack = { current = null })
            }
          }
        }
      }
      FluidGlassModalHost(state = modalHost, backdrop = backdrop)
    }
  }
}

/**
 * Il passaggio fra l'indice e un gruppo: laterale, **opaco**. La pagina che arriva scorre sopra
 * (all'andata) e quella che se ne va scorre via sopra (al ritorno); quella coperta si sposta di un
 * quarto di larghezza, piu' piano, come nelle rotte di pari livello dell'engine. Nessuna dissolvenza.
 */
private fun catalogTransition(forward: Boolean, reducedMotion: Boolean): ContentTransform {
  if (reducedMotion) return (EnterTransition.None togetherWith ExitTransition.None)
  val spec = tween<androidx.compose.ui.unit.IntOffset>(
    durationMillis = if (forward) FluidMotion.DurationExpand else FluidMotion.DurationCollapse,
    easing = FluidMotion.EaseEmphasized,
  )
  val enter = if (forward) slideInHorizontally(spec) { it } else slideInHorizontally(spec) { -it / CoveredPageShare }
  val exit = if (forward) slideOutHorizontally(spec) { -it / CoveredPageShare } else slideOutHorizontally(spec) { it }
  return ContentTransform(
    targetContentEnter = enter,
    initialContentExit = exit,
    targetContentZIndex = if (forward) 1f else -1f,
    sizeTransform = SizeTransform(clip = false),
  )
}

@Composable
private fun CatalogIndex(onBack: () -> Unit, onOpen: (CatalogGroup) -> Unit) {
  FluidScreen(
    title = stringResource(R.string.catalog_title),
    subtitle = stringResource(R.string.catalog_subtitle),
    onBack = onBack,
  ) {
    item { FluidSectionHeader(title = stringResource(R.string.catalog_section_components)) }
    item {
      FluidListGroup {
        CatalogGroup.entries.forEachIndexed { index, group ->
          if (index > 0) FluidListDivider()
          FluidListRow(
            title = stringResource(group.title),
            subtitle = stringResource(group.summary),
            leading = {
              Icon(imageVector = group.icon, contentDescription = null, modifier = Modifier.size(19.dp))
            },
            onClick = { onOpen(group) },
          )
        }
      }
    }
  }
}

@Composable
private fun CatalogGroupScreen(group: CatalogGroup, onBack: () -> Unit) {
  FluidScreen(
    title = stringResource(group.title),
    subtitle = stringResource(group.summary),
    onBack = onBack,
    contentMaxWidth = FluidColumnsDefaults.WideContentMaxWidth,
  ) {
    item { CatalogSectionList(sections = catalogSections(group)) }
  }
}

/**
 * La pagina di un gruppo, intera e non scorrevole: e' quello che i test catturano per ogni gruppo,
 * in chiaro e scuro, su telefono e tablet. Stesso contenuto della pagina viva (le stesse
 * [catalogSections]), senza la barra e senza lo scorrimento: l'altezza e' quella del contenuto, e
 * `CATALOG_SHOT_TAG` la rende catturabile da sola.
 */
@Composable
fun CatalogGroupPage(
  group: CatalogGroup,
  modifier: Modifier = Modifier,
) {
  val scheme = MaterialTheme.colorScheme
  CompositionLocalProvider(LocalContentColor provides scheme.onBackground) {
  Box(
    modifier = modifier
      .testTag(CATALOG_SHOT_TAG)
      .fillMaxWidth()
      .background(scheme.background),
    contentAlignment = Alignment.TopCenter,
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .widthIn(max = FluidColumnsDefaults.WideContentMaxWidth)
        .padding(horizontal = PageGutter, vertical = PageGutter),
    ) {
      Text(
        text = stringResource(group.title),
        style = MaterialTheme.typography.headlineLarge,
        color = scheme.onSurface,
      )
      Text(
        text = stringResource(group.summary),
        modifier = Modifier.padding(top = 2.dp, bottom = 20.dp),
        style = MaterialTheme.typography.bodyLarge,
        color = scheme.onSurfaceVariant,
      )
      CatalogSectionList(sections = catalogSections(group))
    }
  }
  }
}

/**
 * Il vecchio nome del catalogo, tenuto perche' `:app` (build debug) lo chiama ancora e `:app` non e'
 * di questa fase. Fa quello che fa [ComponentCatalog].
 */
@Composable
fun ComponentCatalogPlaceholder(
  onBack: () -> Unit,
  modifier: Modifier = Modifier,
) {
  ComponentCatalog(onBack = onBack, modifier = modifier)
}

private val PageGutter = 20.dp
private const val CoveredPageShare = 4
