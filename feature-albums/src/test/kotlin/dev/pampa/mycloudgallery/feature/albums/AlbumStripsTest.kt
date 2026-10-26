package dev.pampa.mycloudgallery.feature.albums

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.ShareFailure
import dev.pampa.mycloudgallery.core.albums.contract.ShareOpId
import dev.pampa.mycloudgallery.core.albums.contract.ShareOpState
import dev.pampa.mycloudgallery.core.albums.contract.SharePlanKind
import dev.pampa.mycloudgallery.core.albums.contract.ShareWait
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumBanner
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumPageState
import dev.pampa.mycloudgallery.feature.albums.contract.SuggestionsState
import dev.pampa.mycloudgallery.feature.albums.page.AlbumStrips
import dev.pampa.mycloudgallery.feature.albums.page.Strip
import dev.pampa.mycloudgallery.feature.albums.page.StripAction
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Le strisce in testa alla pagina di un album (Fase 7, P10; §1.5 `AlbumBanner`, `ShareProgress`, `PermissionInfo`; D9, D17, D39; E-SH13, E-SH16): l'ordine
 * (prima cio' che e' in corso, poi cio' che chiede un gesto, poi i permessi), le parole di ogni stato e cosa fa un tocco. Una funzione pura sopra `Resources`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class AlbumStripsTest {

  private val resources get() = ApplicationProvider.getApplicationContext<Application>().resources
  private val scenes = Scenes()

  private fun strips(
    banners: List<AlbumBanner>,
    id: dev.pampa.mycloudgallery.core.model.AlbumId = scenes.osloShared,
    suggested: Boolean = false,
    canAdopt: Boolean = true,
    canBringBack: Boolean = true,
  ): List<Strip> {
    val ui = scenes.pageUi(id, banners = banners, suggested = suggested)
    return AlbumStrips.of(resources, ui.page as AlbumPageState.Ready, ui.suggested, ui.creatorName, canAdopt, canBringBack)
  }

  private val op = ShareOpId("op-1")

  @Test
  fun unAlbumPersonaleSenzaNienteNonHaStrisce() {
    assertThat(strips(emptyList(), scenes.birthday)).isEmpty()
  }

  @Test
  fun unCondivisoHaSempreLaStrisciaDeiPermessiInFondoConLoStatoELaPersona() {
    val mine = strips(listOf(AlbumBanner.Incoming(2)))
    assertThat(mine.map { it.id }).containsExactly("incoming", "permissions").inOrder()
    assertThat(mine.last().title).isEqualTo("Gli altri aggiungono")
    assertThat(mine.last().detail).isEqualTo("Lo hai creato tu")
    assertThat(mine.last().action).isEqualTo(StripAction.OPEN_PERMISSIONS)
    val others = strips(emptyList(), scenes.christmas)
    assertThat(others.single().detail).isEqualTo("Creato da Marco")
    assertThat(strips(emptyList(), scenes.grandpa).single().title).isEqualTo("Gli altri guardano")
  }

  @Test
  fun primaCioCheEInCorsoPoiCioCheChiedeUnGestoPoiIPermessi() {
    val running = ShareOpState.Running(op, scenes.osloShared, SharePlanKind.SHARE, done = 7, total = 18)
    val list = strips(listOf(AlbumBanner.Sharing(running), AlbumBanner.Incoming(4), AlbumBanner.FilesFromPc(3)), suggested = false)
    assertThat(list.map { it.id }).containsExactly("sharing", "incoming", "files_from_pc", "permissions").inOrder()
    val smart = strips(listOf(AlbumBanner.NewItems(3)), scenes.oslo, suggested = true)
    assertThat(smart.map { it.id }).containsExactly("new", "suggested").inOrder()
  }

  @Test
  fun unaStrisciaDaDecidereApreLaSuaPaginaEUnaInformazioneNonFaNiente() {
    val list = strips(listOf(AlbumBanner.ToShare(4), AlbumBanner.NewItems(2), AlbumBanner.Incoming(1)), scenes.osloShared)
    assertThat(list.single { it.id == "to_share" }.action).isEqualTo(StripAction.OPEN_TO_SHARE)
    assertThat(list.single { it.id == "new" }.action).isEqualTo(StripAction.NONE)
    assertThat(list.single { it.id == "incoming" }.action).isEqualTo(StripAction.NONE)
    val smart = strips(emptyList(), scenes.oslo, suggested = true)
    assertThat(smart.single { it.id == "suggested" }.action).isEqualTo(StripAction.OPEN_SUGGESTED)
    assertThat(smart.single { it.id == "suggested" }.detail).contains("12")
  }

  @Test
  fun iFileDalPcEQuelliNonAggiuntiSonoAzioniSoloSeLaPortaHaQualcosaDaProporre() {
    val banners = listOf(AlbumBanner.FilesFromPc(3), AlbumBanner.NotAdded(2))
    val yes = strips(banners)
    assertThat(yes.single { it.id == "files_from_pc" }.action).isEqualTo(StripAction.ADOPT_FOLDER_FILES)
    assertThat(yes.single { it.id == "not_added" }.action).isEqualTo(StripAction.BRING_BACK_REJECTED)
    val no = strips(banners, canAdopt = false, canBringBack = false)
    assertThat(no.single { it.id == "files_from_pc" }.action).isEqualTo(StripAction.NONE)
    assertThat(no.single { it.id == "not_added" }.action).isEqualTo(StripAction.NONE)
  }

  @Test
  fun leCondivisioniInCorsoDiconoQuantiELeAttesePerche() {
    val running = strips(listOf(AlbumBanner.Sharing(ShareOpState.Running(op, scenes.osloShared, SharePlanKind.SHARE, 7, 18)))).first()
    assertThat(running.title).contains("7")
    assertThat(running.title).contains("18")
    val details = ShareWait.entries.map { wait ->
      strips(listOf(AlbumBanner.Sharing(ShareOpState.Paused(op, scenes.osloShared, SharePlanKind.SHARE, wait, 7, 18)))).first().detail
    }
    // Ogni motivo di attesa ha la sua frase, e nessuna e' vuota.
    assertThat(details.all { it.isNotBlank() }).isTrue()
    assertThat(details.toSet()).hasSize(ShareWait.entries.size)
    val bringBack = strips(listOf(AlbumBanner.Sharing(ShareOpState.Running(op, scenes.osloShared, SharePlanKind.BRING_BACK, 1, 3)))).first()
    assertThat(bringBack.title).isNotEqualTo(running.title)
  }

  @Test
  fun unaCondivisioneFinitaOParzialeDiceQuanteSonoArrivateEQuanteNo() {
    val partly = ShareOpState.PartlyDone(op, scenes.osloShared, SharePlanKind.SHARE, done = 15, failures = mapOf(ShareFailure.SOURCE_CHANGED to 2), cancelled = 1)
    val strip = strips(listOf(AlbumBanner.Sharing(partly))).first()
    assertThat(strip.detail).contains("15")
    assertThat(strip.detail).contains("3")
    val done = strips(listOf(AlbumBanner.Sharing(ShareOpState.Done(op, scenes.osloShared, SharePlanKind.SHARE, 18)))).first()
    assertThat(done.title).contains("18")
  }

  @Test
  fun leSuggerisonoNonCompaionoSeNonCeNeSono() {
    val none = AlbumStrips.of(
      resources, scenes.pageUi(scenes.oslo).page as AlbumPageState.Ready, SuggestionsState.None, null,
    )
    assertThat(none.none { it.id == "suggested" }).isTrue()
  }
}
