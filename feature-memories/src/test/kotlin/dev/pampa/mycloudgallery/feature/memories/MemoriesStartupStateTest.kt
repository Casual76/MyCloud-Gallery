package dev.pampa.mycloudgallery.feature.memories

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.memories.contract.MemoryId
import dev.pampa.mycloudgallery.core.memories.contract.MemoryIds
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKey
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.memories.noop.NoMemoryClips
import dev.pampa.mycloudgallery.core.memories.testing.InMemoryMusicSettings
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesListState
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesSectionState
import dev.pampa.mycloudgallery.feature.memories.contract.MemoryPageState
import dev.pampa.mycloudgallery.feature.memories.contract.NoMemoryPlaceNames
import dev.pampa.mycloudgallery.feature.memories.list.AllMemoriesController
import dev.pampa.mycloudgallery.feature.memories.page.MemoryPageController
import dev.pampa.mycloudgallery.feature.memories.player.PlayerController
import dev.pampa.mycloudgallery.feature.memories.section.MemoriesEntryTarget
import dev.pampa.mycloudgallery.feature.memories.section.MemoriesSectionSources
import dev.pampa.mycloudgallery.feature.memories.testing.FakeMusicLibrary
import dev.pampa.mycloudgallery.feature.memories.testing.FakeMusicPlayback
import dev.pampa.mycloudgallery.feature.memories.testing.RecordingPlayerImages
import dev.pampa.mycloudgallery.feature.memories.ui.FinalFilter
import dev.pampa.mycloudgallery.feature.memories.ui.MemoryNames
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * **Lo stato prima che i ricordi siano pronti** (RP-1 e RP-2 della verifica funzionale della Fase 9, 31/10; "Avvio e pagine istantanei": mai uno stato sbagliato, solo l'ultimo
 * stato o lo scheletro). All'avvio a freddo la Libreria non ha ancora l'istantanea (e, in `:app`, i ricordi non sono ancora montati): `Memories.memory` **non dice niente**.
 * Le schermate aspettano:
 *  - la pagina del ricordo resta in caricamento, mai "Non trovo questo ricordo";
 *  - la presentazione resta in caricamento, mai "Questo ricordo non c'e' piu'";
 *  - la sezione e "Tutti i ricordi" restano sull'ultimo scaffale, mai "I ricordi arrivano quando ci sono abbastanza foto" con lo scaffale pieno;
 * e quando la Libreria arriva dicono la verita' (compreso "non trovo" per un ricordo che non c'e').
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MemoriesStartupStateTest {

  private val resources get() = ApplicationProvider.getApplicationContext<Application>().resources

  private val ghost: MemoryId = MemoryIds.of(MemoryKey.OnThisDay(LocalDate.of(2001, 1, 1)))

  private fun names(rig: MemoriesRig) = MemoryNames(rig.people, NoMemoryPlaceNames)

  /** Come all'avvio a freddo: scaffale pieno (scritto dalla notte), Libreria non ancora letta. Torna l'istantanea per rimetterla. */
  private suspend fun coldStart(rig: MemoriesRig): TimelineSnapshot {
    rig.refresh()
    val snapshot = checkNotNull(rig.lib.visibility.snapshot.value)
    rig.lib.visibility.snapshot.value = null
    return snapshot
  }

  @Test
  fun laPaginaDelRicordoAspetta_mai_NonTrovoQuestoRicordo() = runTest {
    val rig = MemoriesRig()
    val snapshot = coldStart(rig)
    val real = rig.card(MemoryKind.ON_THIS_DAY, 1).id
    val page = MemoryPageController(real, rig.memories, names(rig), FinalFilter(NothingHidden), backgroundScope)
    val missing = MemoryPageController(ghost, rig.memories, names(rig), FinalFilter(NothingHidden), backgroundScope)
    runCurrent()
    assertThat(page.ui.value.page).isEqualTo(MemoryPageState.Loading)
    assertThat(missing.ui.value.page).isEqualTo(MemoryPageState.Loading)
    // La Libreria arriva: il ricordo vero si apre, e quello che non c'e' dice la verita'.
    rig.lib.visibility.snapshot.value = snapshot
    runCurrent()
    assertThat(page.ui.value.page).isInstanceOf(MemoryPageState.Ready::class.java)
    assertThat(missing.ui.value.page).isEqualTo(MemoryPageState.NotFound)
  }

  @Test
  fun laPresentazioneAspetta_mai_QuestoRicordoNonCePiu() = runTest {
    val rig = MemoriesRig()
    val snapshot = coldStart(rig)
    val real = rig.card(MemoryKind.ON_THIS_DAY, 1).id
    fun player(id: MemoryId, scope: TestScope = this) = PlayerController(
      id, rig.memories, names(rig), FinalFilter(NothingHidden), NoMemoryClips, RecordingPlayerImages(), InMemoryMusicSettings(), FakeMusicLibrary(), FakeMusicPlayback(),
      scope.backgroundScope,
    )
    val first = player(real)
    val missing = player(ghost)
    runCurrent()
    for (p in listOf(first, missing)) {
      assertThat(p.ui.value.gone).isFalse()
      assertThat(p.ui.value.state).isNull()
    }
    rig.lib.visibility.snapshot.value = snapshot
    runCurrent()
    assertThat(first.ui.value.gone).isFalse()
    assertThat(first.ui.value.state).isNotNull()
    assertThat(missing.ui.value.gone).isTrue()
  }

  @Test
  fun laSezioneRestaSullUltimoScaffale_mai_IRicordiArrivanoQuandoCiSonoAbbastanzaFoto() = runTest {
    val rig = MemoriesRig()
    val snapshot = coldStart(rig)
    val sources = MemoriesSectionSources(resources, rig.memories, names(rig), FinalFilter(rig.lib.visibility), backgroundScope)
    runCurrent()
    val before = sources.sectionState.value
    assertThat(before).isInstanceOf(MemoriesSectionState.Ready::class.java)
    assertThat((before as MemoriesSectionState.Ready).cards).hasSize(3)
    assertThat(sources.section.value.entries.map { it.code }).doesNotContain(MemoriesEntryTarget.EMPTY)
    // L'istantanea arriva: il filtro finale rifa' conti e copertine, e lo scaffale pieno resta pieno.
    rig.lib.visibility.snapshot.value = snapshot
    runCurrent()
    val after = sources.sectionState.value as MemoriesSectionState.Ready
    assertThat(after.cards).hasSize(3)
    assertThat(sources.section.value.entries.map { it.code }).doesNotContain(MemoriesEntryTarget.EMPTY)
  }

  @Test
  fun tuttiIRicordiRestanoSuLoScaffale_senzaCopertine_poiLeCopertine() = runTest {
    val rig = MemoriesRig()
    val snapshot = coldStart(rig)
    val all = AllMemoriesController(rig.memories, names(rig), FinalFilter(rig.lib.visibility), backgroundScope)
    runCurrent()
    assertThat(all.ui.value.state).isInstanceOf(MemoriesListState.Ready::class.java)
    // Senza istantanea nessuna copertina: il filtro finale non ha niente di cui fidarsi, e non si mostra niente di non filtrato.
    assertThat(all.ui.value.snapshot).isNull()
    rig.lib.visibility.snapshot.value = snapshot
    runCurrent()
    assertThat(all.ui.value.state).isInstanceOf(MemoriesListState.Ready::class.java)
    assertThat(all.ui.value.snapshot).isNotNull()
  }
}
