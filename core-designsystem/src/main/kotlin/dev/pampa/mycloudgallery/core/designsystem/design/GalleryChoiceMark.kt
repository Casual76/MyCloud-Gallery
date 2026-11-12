package dev.pampa.mycloudgallery.core.designsystem.design

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Le misure del segno di una scelta. */
object GalleryChoiceMarkDefaults {
  val Size: Dp = 24.dp
}

/**
 * Il segno di una scelta fra piu' opzioni (giro 1 della fase I, CU-16; comune dal pacchetto U, UU-9: prima ogni pagina ne aveva una copia): il tondo
 * vuoto, e la spunta piena dell'accento sulla scelta di adesso. Decorativo: la riga dice a TalkBack che e' una scelta e se e' quella scelta.
 */
@Composable
fun GalleryChoiceMark(selected: Boolean, modifier: Modifier = Modifier) {
  Icon(
    imageVector = if (selected) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
    contentDescription = null,
    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
    modifier = modifier.size(GalleryChoiceMarkDefaults.Size),
  )
}
