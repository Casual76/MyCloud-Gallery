package dev.pampa.mycloudgallery.feature.albums

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.CreateOutcome
import dev.pampa.mycloudgallery.core.albums.contract.PeopleRule
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.AlbumKind
import dev.pampa.mycloudgallery.core.model.PeopleMode
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.feature.albums.contract.AccountNamesAsIs
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumBanner
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumMembers
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumPageState
import dev.pampa.mycloudgallery.feature.albums.contract.NoAlbumPending
import dev.pampa.mycloudgallery.feature.albums.contract.PersonChoice
import dev.pampa.mycloudgallery.feature.albums.page.AlbumPageController
import dev.pampa.mycloudgallery.feature.albums.testing.FakePeopleChoices
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * La pagina di uno smart con le persone (Fase 8, P10; E-F37, E-F27): una striscia dice che qui i volti non ci sono (l'album non cambia) o che una persona della regola non e'
 * piu' riconosciuta; la striscia segue la regola e i volti che si accendono; un album senza persone non ne ha mai. Solo nomi inventati.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PeopleAlbumBannerTest {
  private fun person(n: Int) = PersonId.fromBytes(ByteArray(PersonId.BYTES) { (n * 13 + it).toByte() })

  private val marco = person(1)
  private val anna = person(2)

  private fun TestScope.page(world: AlbumsTestWorld, id: AlbumId, people: FakePeopleChoices) =
    AlbumPageController(
      id, world.albums, world.sharedAlbums, world.albums, world.runner, AccountNamesAsIs, AlbumMembers { _, _ -> null }, NoAlbumPending, world.screens, backgroundScope, people,
    )

  private fun AlbumPageController.banners(): List<AlbumBanner> = (ui.value.page as AlbumPageState.Ready).banners

  private suspend fun AlbumsTestWorld.smart(rules: SmartRules): AlbumId = (albums.create("Persone", AlbumKind.SMART, rules) as CreateOutcome.Created).id

  private val both = SmartRules(null, null, null, people = PeopleRule(listOf(marco, anna), PeopleMode.ALL))

  @Test
  fun senzaVoltiLaPaginaDiceCheLePersoneNonSonoAttive_E_F37() = runTest(UnconfinedTestDispatcher()) {
    val world = AlbumsTestWorld()
    val people = FakePeopleChoices(listOf(PersonChoice(marco, "Marco", 3), PersonChoice(anna, "Anna", 2)), available = false)
    val controller = page(world, world.smart(both), people)
    assertThat(controller.banners()).contains(AlbumBanner.PeopleOff)
    // Quando i volti si accendono la striscia sparisce da sola.
    people.setAvailable(true)
    assertThat(controller.banners()).doesNotContain(AlbumBanner.PeopleOff)
    assertThat(controller.banners().filterIsInstance<AlbumBanner.PeopleLost>()).isEmpty()
  }

  @Test
  fun unaPersonaPerdutaSiDiceConIlNumero_E_F27() = runTest(UnconfinedTestDispatcher()) {
    val world = AlbumsTestWorld()
    val people = FakePeopleChoices(listOf(PersonChoice(marco, "Marco", 3), PersonChoice(anna, "Anna", 2)), lost = setOf(anna))
    val controller = page(world, world.smart(both), people)
    assertThat(controller.banners()).contains(AlbumBanner.PeopleLost(1))
    assertThat(controller.banners()).doesNotContain(AlbumBanner.PeopleOff)
  }

  @Test
  fun unaPersonaNascostaNonFaNessunAvviso_E_F28() = runTest(UnconfinedTestDispatcher()) {
    val world = AlbumsTestWorld()
    val people = FakePeopleChoices(listOf(PersonChoice(marco, "Marco", 3), PersonChoice(anna, "Anna", 2)), hidden = setOf(anna))
    val controller = page(world, world.smart(both), people)
    assertThat(controller.banners().filterIsInstance<AlbumBanner.PeopleLost>()).isEmpty()
    assertThat(controller.banners()).doesNotContain(AlbumBanner.PeopleOff)
  }

  @Test
  fun unAlbumSenzaPersoneNonHaMaiLaStriscia() = runTest(UnconfinedTestDispatcher()) {
    val world = AlbumsTestWorld()
    val people = FakePeopleChoices(emptyList(), available = false)
    val controller = page(world, world.smart(SmartRules(null, null, "mare")), people)
    assertThat(controller.banners().filter { it is AlbumBanner.PeopleOff || it is AlbumBanner.PeopleLost }).isEmpty()
  }

  @Test
  fun seLaPortaSbagliaLaPaginaNonSiRompe_AS7() = runTest(UnconfinedTestDispatcher()) {
    val world = AlbumsTestWorld()
    val people = FakePeopleChoices(listOf(PersonChoice(marco, "Marco", 3))).also { it.failing = true }
    val controller = page(world, world.smart(both), people)
    assertThat(controller.ui.value.page).isInstanceOf(AlbumPageState.Ready::class.java)
    assertThat(controller.banners().filterIsInstance<AlbumBanner.PeopleLost>()).isEmpty()
  }
}
