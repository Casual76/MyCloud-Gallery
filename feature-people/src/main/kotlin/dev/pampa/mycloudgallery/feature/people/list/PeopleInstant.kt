package dev.pampa.mycloudgallery.feature.people.list

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.pampa.mycloudgallery.core.designsystem.instant.InstantBlock
import dev.pampa.mycloudgallery.core.designsystem.instant.InstantShape
import dev.pampa.mycloudgallery.core.designsystem.instant.InstantShapeMemory
import dev.pampa.mycloudgallery.feature.people.contract.PeopleListState
import dev.pampa.mycloudgallery.feature.people.contract.PeopleListUi
import kotlinx.coroutines.delay

/**
 * Persone al primo ingresso (Avvio istantaneo, 28/10). L'elenco arriva vuoto finche' Persone non e' montata e l'archivio non e' letto: senza
 * questo, la pagina diceva "Nessuna persona" per un attimo e poi mostrava la griglia (un lampo). Se l'ultima volta c'erano delle persone (una
 * forma ricordata), un elenco vuoto nei primi [PATIENCE_MS] vale "non ancora letto": si disegna lo scheletro con la forma dell'ultima volta. Poi
 * il vuoto e' vero e si dice. La forma e' fatta solo di numeri (quante piastrelle, quante colonne): nessun nome, nessun volto.
 */
internal object PeopleInstant {

  const val CODE: String = "persone"

  /** Quanto un elenco vuoto vale "non ancora letto" al primo ingresso. */
  const val PATIENCE_MS: Long = 1_500L

  /** La forma della griglia per lo scheletro: le persone con un nome, poi l'intestazione e le altre. */
  fun shapeOf(ready: PeopleListState.Ready, columns: Int): InstantShape {
    val blocks = ArrayList<InstantBlock>()
    if (ready.named.isNotEmpty()) blocks += InstantBlock.Tiles(ready.named.size, columns)
    if (ready.unnamed.isNotEmpty()) {
      blocks += InstantBlock.Header
      blocks += InstantBlock.Tiles(ready.unnamed.size, columns)
    }
    return InstantShape.of(*blocks.toTypedArray())
  }

  /** Lo scheletro da disegnare adesso, o null (lo stato com'e'). */
  @Composable
  fun rememberSkeleton(ui: PeopleListUi): InstantShape? {
    val remembered = remember { InstantShapeMemory.rememberedOrNull(CODE) } ?: return null
    var patient by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
      delay(PATIENCE_MS)
      patient = false
    }
    return remembered.takeIf { patient && ui.state == PeopleListState.Empty }
  }
}
