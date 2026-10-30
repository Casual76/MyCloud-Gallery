package dev.pampa.mycloudgallery.feature.memories.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import dev.antigravity.fluidengine.ui.fluid.FluidGlassModalHost
import dev.antigravity.fluidengine.ui.fluid.LocalFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.fluidGlassModalObscured
import dev.antigravity.fluidengine.ui.fluid.glassBackdropSource
import dev.antigravity.fluidengine.ui.fluid.rememberFluidGlassModalHostState
import dev.antigravity.fluidengine.ui.fluid.rememberGlassBackdrop

/**
 * Il punto in cui i fogli delle schermate dei ricordi ("Mostra meno", "Non ricordarmi questo periodo", il selettore dell'ora) si aprono davvero:
 * i fogli del design system sono pop-up di vetro che vivono in un `FluidGlassModalHost` alla radice, e senza un host nello scope **non si
 * aprono** (e non vanno in crash). Se un host c'e' gia' (la radice di `:app`) lo si usa, altrimenti se ne mette uno qui, sopra la sola pagina
 * corrente. Lo stesso modo di album, persone, Cerca e Impostazioni.
 */
@Composable
fun MemoriesHost(content: @Composable () -> Unit) {
  if (LocalFluidGlassModalHostState.current != null) {
    content()
    return
  }
  val host = rememberFluidGlassModalHostState()
  val backdrop = rememberGlassBackdrop()
  CompositionLocalProvider(LocalFluidGlassModalHostState provides host) {
    Box(modifier = Modifier.fillMaxSize()) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .glassBackdropSource(backdrop)
          .fluidGlassModalObscured(),
      ) {
        content()
      }
      FluidGlassModalHost(state = host, backdrop = backdrop)
    }
  }
}
