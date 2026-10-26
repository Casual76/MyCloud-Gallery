package dev.pampa.mycloudgallery.core.library.visibility

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.database.entity.MediaAliasEntity
import dev.pampa.mycloudgallery.core.database.entity.MediaPersonalEntity
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.library.data.CatalogTimelineSource
import dev.pampa.mycloudgallery.core.library.similar.Fx
import dev.pampa.mycloudgallery.core.library.similar.SimilarCatalogTestBase
import dev.pampa.mycloudgallery.core.model.MediaGroupKind
import dev.pampa.mycloudgallery.core.model.MediaId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Fase 7 (P9, D22, E-H1, E-L11): le implementazioni sul catalogo vero (Room in memoria) di `HiddenMask`, `VisibleMedia` e `FamilyExposure`. Il
 * nascosto si legge da `media_personal`; il nascosto e' contagioso nei gruppi anche quando il dato e' arrivato a meta' (una sola copia
 * nascosta); un id unito a un media nascosto lo e'; un id sconosciuto o un errore di lettura falliscono chiusi.
 */
@RunWith(RobolectricTestRunner::class)
class CatalogVisibilityTest : SimilarCatalogTestBase() {

  private val unknown = MediaId("00000000-0000-4000-8000-0000000000ff")

  private fun mask() = CatalogHiddenMask(db)

  private fun visibleMedia(snapshot: MutableStateFlow<TimelineSnapshot?> = MutableStateFlow(null)) = CatalogVisibleMedia(db, snapshot)

  private suspend fun hide(ids: Collection<MediaId>) = catalog.setPersonalFlags(ids) { it.copy(hidden = true) }

  private suspend fun hide(id: MediaId) = hide(listOf(id))

  /** Scrive il segno senza passare da `setPersonalFlags` (che lo propagherebbe al gruppo): un dato arrivato a meta'. */
  private suspend fun hideOnlyThis(id: MediaId) = db.personalDao().upsert(MediaPersonalEntity(id.value, favorite = false, hidden = true, updatedAt = now))

  @Test
  fun ilNascostoSiLeggeDalleScelteDellUtenteEUnIdSconosciutoFallisceChiuso() = runBlocking<Unit> {
    val a = phoneShot()
    val b = phoneShot()
    val c = phoneShot()
    hide(b)

    assertThat(mask().hiddenAmong(listOf(a, b, c, unknown))).containsExactly(b, unknown)
    assertThat(visibleMedia().visible(listOf(a, b, c, unknown))).containsExactly(a, c)
    assertThat(mask().hiddenAmong(emptyList())).isEmpty()
  }

  @Test
  fun unGruppoConUnSoloNascostoNasconde_TuttiIMembri_E_Lo_Dice_AncheSeIlDatoArrivaAMeta() = runBlocking<Unit> {
    val a = phoneShot()
    val b = phoneShot()
    val c = phoneShot()
    val outside = phoneShot()
    catalog.group(MediaGroupKind.BURST, listOf(a, b, c), cover = b)
    hideOnlyThis(a)

    assertThat(mask().hiddenAmong(listOf(a, b, c, outside))).containsExactly(a, b, c)
    assertThat(visibleMedia().visible(listOf(a, b, c, outside))).containsExactly(outside)
  }

  @Test
  fun unIdUnitoAUnMediaNascostoELoStesso() = runBlocking<Unit> {
    val survivor = phoneShot()
    val other = phoneShot()
    val absorbed = MediaId("00000000-0000-4000-8000-0000000000aa")
    // L'unione di due identita' lascia un alias dal vecchio id al sopravvissuto (`MergeDao`): qui lo si scrive a mano.
    db.mediaDao().insertAlias(MediaAliasEntity(absorbed.value, survivor.value, now))
    hide(survivor)

    // Sia il sopravvissuto sia l'id assorbito sono nascosti; nessuno dei due e' visibile, l'altro media si'.
    assertThat(mask().hiddenAmong(listOf(survivor, absorbed, other))).containsExactly(survivor, absorbed)
    assertThat(visibleMedia().visible(listOf(survivor, absorbed, other))).containsExactly(other)
  }

  @Test
  fun unMediaSparitoDaOgniPostoNonEVisibileMaNonEnascosto() = runBlocking<Unit> {
    val shown = phoneShot()
    val gone = nasShot("Foto/via.jpg")
    catalog.markVanished(catalog.media(gone)!!.locations.single().location.key)

    assertThat(visibleMedia().visible(listOf(shown, gone))).containsExactly(shown)
    assertThat(mask().hiddenAmong(listOf(shown, gone))).isEmpty()
  }

  @Test
  fun unErroreDiLetturaRendeNascostoTuttoENonVisibileNiente() = runBlocking<Unit> {
    val a = phoneShot()
    val b = phoneShot()
    db.close()

    assertThat(mask().hiddenAmong(listOf(a, b))).containsExactly(a, b)
    assertThat(visibleMedia().visible(listOf(a, b))).isEmpty()
  }

  @Test
  fun moltiIdSiLeggonoABlocchi() = runBlocking<Unit> {
    val ids = (0 until 1_200).map { phoneShot() }
    val hidden = ids.filterIndexed { i, _ -> i % 7 == 0 }
    hide(hidden)

    assertThat(mask().hiddenAmong(ids)).containsExactlyElementsIn(hidden)
    assertThat(visibleMedia().visible(ids)).containsExactlyElementsIn(ids - hidden.toSet())
  }

  @Test
  fun iFamiliariVedonoSoloCioCheStaNellAreaComune() = runBlocking<Unit> {
    val onlyPhone = phoneShot()
    val privateNas = nasShot("Privata/a.jpg", share = "alessio")
    val common = nasShot("Famiglia/b.jpg", share = "NAS")
    val exposure = CatalogFamilyExposure(db)

    assertThat(exposure.seenByFamily(listOf(onlyPhone, privateNas, common, unknown))).containsExactly(common)
    assertThat(exposure.seenByFamily(emptyList())).isEmpty()
  }

  @Test
  fun unMediaSiaSulTelefonoSiaNellAreaComuneLoVedonoIFamiliari() = runBlocking<Unit> {
    val common = nasShot("Famiglia/c.jpg", share = "NAS")

    // La copia dell'area comune non sparisce perche' il media e' nascosto qui: nascondere vale solo dentro l'app.
    hide(common)
    assertThat(CatalogFamilyExposure(db).seenByFamily(listOf(common))).containsExactly(common)
  }

  @Test
  fun ilWidgetNonDaMaiUnNascostoNeppureSeNascostoDopoL_Istantanea() = runBlocking<Unit> {
    val ids = (0 until 20).map { phoneShot(takenAt = Fx.SECOND0 - it * 60_000L) }
    val snapshot = MutableStateFlow<TimelineSnapshot?>(CatalogTimelineSource(db, catalog).buildIndex())
    val feed = RotatingWidgetFeed(CatalogVisibleMedia(db, snapshot), CatalogHiddenMask(db))
    val hiddenAfter = ids.take(5).toSet()
    hide(hiddenAfter) // dopo l'istantanea: questa le ha ancora

    val seen = HashSet<MediaId>()
    repeat(10) { seen += feed.next(8) }

    assertThat(seen).containsNoneIn(hiddenAfter)
    assertThat(seen).containsExactlyElementsIn(ids - hiddenAfter)
  }
}
