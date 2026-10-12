package dev.pampa.mycloudgallery

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.antigravity.fluidengine.ui.fluid.FluidScreen

/**
 * La schermata di partenza: una `FluidScreen` vuota con il titolo dell'app, a tema Ametista.
 *
 * [extraItems] e' il punto in cui la build debug appende il suo ingresso al catalogo dei componenti
 * (vedi src/debug/.../AppRoot.kt). In release e' vuoto e la schermata resta davvero vuota.
 */
@Composable
internal fun HomeScreen(extraItems: LazyListScope.() -> Unit = {}) {
  FluidScreen(title = stringResource(R.string.home_title)) {
    extraItems()
  }
}
