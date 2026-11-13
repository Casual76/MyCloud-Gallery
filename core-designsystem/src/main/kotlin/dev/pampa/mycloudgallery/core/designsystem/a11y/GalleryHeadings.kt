package dev.pampa.mycloudgallery.core.designsystem.a11y

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidSectionHeader

// Fase 10, P8 (D23 (2), §2.7): le intestazioni. Il titolo di una sezione e' un'**intestazione** per TalkBack (si salta da una all'altra con il
// gesto delle intestazioni). `FluidSectionHeader` dell'engine non lo dichiara e l'engine non si tocca da qui (e' un sotto-modulo: `check.ps1`
// vuole `engine/` pulito): la galleria usa questo al suo posto, con lo stesso aspetto.

/**
 * Il rientro a sinistra del titolo di [GallerySectionHeader] (il titolo dell'engine sta a 4 dp dal bordo della colonna): chi allinea una nota al titolo di una sezione lo prende da qui e non
 * scrive un numero suo (BU2-9). Una prova confronta questo valore con la posizione del titolo vero, cosi' se l'engine lo cambia lo si vede.
 */
object GallerySectionHeaderDefaults {
  val TitleInset: Dp = 4.dp
}

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
