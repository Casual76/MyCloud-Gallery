package dev.pampa.mycloudgallery.core.designsystem.a11y

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader

// Fase 10, P8 (D23 (2), §2.7): le intestazioni. Il titolo di una sezione e' un'**intestazione** per TalkBack (si salta da una all'altra con il
// gesto delle intestazioni). `FluidSectionHeader` dell'engine non lo dichiara e l'engine non si tocca da qui (e' un sotto-modulo: `check.ps1`
// vuole `engine/` pulito): la galleria usa questo al suo posto, con lo stesso aspetto.

/** Come [FluidSectionHeader], ma dichiarata come intestazione (titolo e dettaglio si leggono insieme). */
@Composable
fun GallerySectionHeader(
  title: String,
  modifier: Modifier = Modifier,
  detail: String? = null,
) {
  FluidSectionHeader(
    title = title,
    modifier = modifier.semantics(mergeDescendants = true) { heading() },
    detail = detail,
  )
}
