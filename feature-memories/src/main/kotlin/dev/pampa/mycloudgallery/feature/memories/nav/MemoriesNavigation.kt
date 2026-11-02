package dev.pampa.mycloudgallery.feature.memories.nav

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import dev.antigravity.fluidengine.ui.navigation.composable
import dev.antigravity.fluidengine.ui.page.FluidBackSwipe
import dev.pampa.mycloudgallery.core.memories.contract.MemoryId
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesActions
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesThumbnails
import dev.pampa.mycloudgallery.feature.memories.contract.NoPlayerClipSurface
import dev.pampa.mycloudgallery.feature.memories.contract.PlayerClipSurface
import dev.pampa.mycloudgallery.feature.memories.list.AllMemoriesRoute
import dev.pampa.mycloudgallery.feature.memories.page.MemoryPageExits
import dev.pampa.mycloudgallery.feature.memories.page.MemoryPageRoute
import dev.pampa.mycloudgallery.feature.memories.player.MemoryPlayerRoute
import dev.pampa.mycloudgallery.feature.memories.section.MemoriesEntryTarget
import dev.pampa.mycloudgallery.feature.memories.settings.MemoriesSettingsRoute
import kotlinx.serialization.Serializable

// LE ROTTE DEI RICORDI (Fase 9, P9): un grafo da agganciare, come `peopleGraph` e `albumsGraph`. **Non** tocca `MainScaffold` ne' `AppNavHost` (sono
// di P13): P13 chiama [memoriesGraph] dentro il `NavHost`, apre le rotte con le funzioni `open...` di sotto e porta qui le voci della sezione Ricordi
// delle Raccolte ([MemoriesEntryTarget.parse]) e l'azione `OPEN_MEMORIES` della notifica (P10: la pagina Ricordi di oggi). Il viewer non e' una rotta:
// [MemoriesActions.openViewer] manda la richiesta a `:app`, che lo ospita sopra la pagina corrente.

/** "Tutti i ricordi". */
@Serializable
data object AllMemoriesDestination

/** La pagina di un ricordo. [id] e' il `MemoryId` come testo (un hash: mai un titolo, un luogo o un nome). */
@Serializable
data class MemoryDestination(val id: String) {
  /** D33: mai l'id del ricordo. */
  override fun toString(): String = "MemoryDestination"
}

/** La presentazione di un ricordo (schermo intero). */
@Serializable
data class MemoryPlayerDestination(val id: String) {
  /** D33: mai l'id del ricordo. */
  override fun toString(): String = "MemoryPlayerDestination"
}

/** Impostazioni -> Ricordi e notifiche. */
@Serializable
data object MemoriesSettingsDestination

fun NavController.openAllMemories() = navigate(AllMemoriesDestination)

fun NavController.openMemory(id: MemoryId) = navigate(MemoryDestination(id.value))

fun NavController.openMemoryPlayer(id: MemoryId) = navigate(MemoryPlayerDestination(id.value))

fun NavController.openMemoriesSettings() = navigate(MemoriesSettingsDestination)

/**
 * Apre la destinazione di una voce della sezione ([MemoriesEntryTarget.parse]): "Tutti i ricordi", o la pagina del ricordo che [resolve] trova per il
 * codice (null = uscito dallo scaffale mentre si toccava: non si apre niente). Le righe che dicono come stanno le cose non portano da nessuna parte.
 */
fun NavController.openMemoriesEntry(target: MemoriesEntryTarget, resolve: (String) -> MemoryId?) {
  when (target) {
    MemoriesEntryTarget.All -> openAllMemories()
    is MemoriesEntryTarget.Memory -> resolve(target.code)?.let { openMemory(it) }
    MemoriesEntryTarget.Inert -> Unit
  }
}

/**
 * Le pagine dei ricordi nel grafo di navigazione: "Tutti i ricordi", la pagina di un ricordo, la presentazione e Impostazioni -> Ricordi e notifiche.
 * [actions] sono le uscite verso `:app` (viewer, album, permesso, canale, selettore della canzone); [thumbnails] le miniature locali della Libreria e
 * l'anteprima locale; [clips] il clip muto dei video del telefono (senza legame: il fotogramma di copertina).
 */
fun NavGraphBuilder.memoriesGraph(
  nav: NavController,
  actions: MemoriesActions,
  thumbnails: MemoriesThumbnails,
  clips: PlayerClipSurface = NoPlayerClipSurface,
) {
  val back: () -> Unit = { nav.popBackStack() }

  composable<AllMemoriesDestination> { AllMemoriesRoute(thumbnails = thumbnails, onOpenMemory = nav::openMemory, onBack = back) }
  composable<MemoryDestination> {
    MemoryPageRoute(actions = actions, thumbnails = thumbnails, exits = MemoryPageExits(onBack = back, onPlay = nav::openMemoryPlayer))
  }
  composable<MemoryPlayerDestination>(backSwipe = FluidBackSwipe.Off) { MemoryPlayerRoute(thumbnails = thumbnails, onExit = back, clips = clips) }
  composable<MemoriesSettingsDestination> { MemoriesSettingsRoute(actions = actions, onBack = back) }
}
