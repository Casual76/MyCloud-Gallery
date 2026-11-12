package dev.pampa.mycloudgallery.core.faces.people

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.faces.contract.PeopleVariant
import dev.pampa.mycloudgallery.core.faces.contract.PeopleWorld
import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.model.FaceTier
import dev.pampa.mycloudgallery.core.model.MediaId
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * Fase 8 (P9, D25, D28, E-F38, E-F39, §2.9): **nascosti mai** e **copertine solo da media mostrabili**. Il mondo delle prove ha una persona
 * (`c`) con soli media nascosti e un media nascosto per un'altra (`a`). Si prova che il filtro e' quello di `VisibleMedia` e `HiddenMask`
 * (anche un nascosto dell'ultimo momento, dopo l'istantanea), che senza istantanea della Libreria non si mostra niente, e che la copertina
 * non viene mai da un media che non si puo' mostrare. La proprieta' sui mondi casuali sta nel registro dei Nascosti di `:app`.
 */
@RunWith(AndroidJUnit4::class)
class PeopleVisibilityTest {

  @get:Rule
  val folder = TemporaryFolder()

  private var count = 0

  private fun rig(namedOnly: Boolean = true) = PeopleRig(File(folder.root, "banco-${count++}"), PeopleWorld(), namedOnly = namedOnly)

  private fun PeopleRig.mediaOf(face: dev.pampa.mycloudgallery.core.model.FaceLocator): MediaId = world.media.getValue(face.contentKey)

  /** I6 (review CG-I1, 4a): con Persone non attiva qui il cancello dei ritagli rifiuta tutto, anche un volto chiesto con un indirizzo tenuto. */
  @Test
  fun conPersoneNonAttiva_ilCancelloDeiRitagliRifiutaTutto() = runBlocking<Unit> {
    rig().use { r ->
      val face = r.world.facesOf(r.world.a).first().locator
      assertThat(r.people.mayShow(face)).isTrue()
      assertThat(r.people.shownCellsOf(face)).isNotEmpty()
      r.available.value = false
      assertThat(r.people.mayShow(face)).isFalse()
      assertThat(r.people.shownCellsOf(face)).isEmpty()
      r.available.value = true
      assertThat(r.people.mayShow(face)).isTrue()
    }
  }

  @Test
  fun unaPersonaConSoliMediaNascostiNonCompareDaNessunaParte() = runBlocking<Unit> {
    rig().use { r ->
      val c = r.world.c
      r.people.rename(c, "Solo nascosti")
      assertThat(r.people.list.value.named).isEmpty()
      assertThat(r.people.list.value.othersCount).isEqualTo(2)
      assertThat(r.people.person(c).first()).isNull()
      assertThat(r.people.others().first().people.map { it.id }).doesNotContain(c)
      assertThat(r.people.dictionary()).isEmpty()
      assertThat(r.people.hidden().first()).isEmpty()
      assertThat(r.people.reviewFaces(c)).isEmpty()
      // Il nome c'e' (e' dell'utente), ma non si mostra. Un media tornato visibile compare solo quando la Libreria ha la sua cella (l'istantanea).
      r.world.visibility.hidden.removeAll(r.world.facesOf(c).map { r.mediaOf(it.locator) }.toSet())
      r.people.refresh()
      assertThat(r.people.list.value.named).isEmpty()
    }
  }

  /** Un media nascosto un attimo fa, **dopo** l'istantanea: la Libreria non l'ha ancora tolto, il filtro finale si': conteggi, copertina, pagina. */
  @Test
  fun unNascostoDellUltimoMomentoNonEsce() = runBlocking<Unit> {
    rig().use { r ->
      val b = r.world.b
      r.people.rename(b, "Anna")
      val faces = r.world.facesOf(b).map { it.locator }
      assertThat(r.people.list.value.named.single().photos).isEqualTo(PeopleWorld.PHOTOS)
      val cover = r.people.list.value.named.single().cover!!
      // Si nasconde il media della copertina senza rifare l'istantanea.
      r.world.visibility.hidden += r.mediaOf(cover)
      r.people.refresh()
      val card = r.people.list.value.named.single()
      assertThat(card.photos).isEqualTo(PeopleWorld.PHOTOS - 1)
      assertThat(card.cover).isNotNull()
      assertThat(card.cover!!.matches(cover)).isFalse()
      val page = r.people.person(b).first()!!
      val shown = (0 until page.view.size).map { page.view.snapshot.idAt(page.view.snapshotIndex(it)) }
      assertThat(shown).doesNotContain(r.mediaOf(cover))
      assertThat(r.people.reviewFaces(b).map { it.media }).doesNotContain(r.mediaOf(cover))
      assertThat(r.visibility.mayShow(r.mediaOf(cover))).isFalse()
      assertThat(r.people.mayShow(cover)).isFalse()
      assertThat(r.people.mayShow(faces.first { !it.matches(cover) })).isTrue()
    }
  }

  @Test
  fun ilFiltroFinaleEUltimoAncheSeLaFonteEdLIstantaneaDiconoSi() = runBlocking<Unit> {
    // La fonte dei visibili dice si' a tutto e l'istantanea ha la cella, ma la maschera nasconde quel media dopo: non esce.
    val world = PeopleWorld()
    val everything = object : dev.pampa.mycloudgallery.core.library.contract.VisibleMedia {
      override val snapshot = world.visibility.snapshot
      override suspend fun visible(ids: Collection<MediaId>): Set<MediaId> = ids.toSet()
    }
    val late = world.media.values.first { it !in world.hidden }
    val withMask = PeopleVisibility(everything, HiddenMask { ids -> ids.filter { it == late }.toSet() })
    val withoutMask = PeopleVisibility(everything, HiddenMask { emptySet() })
    val candidates = world.media.values.toList()
    assertThat(withMask.shown(candidates)).doesNotContain(late)
    // Controllo positivo: senza il filtro finale quel media c'e' (e' nell'istantanea), i nascosti di prima no (non hanno la cella).
    assertThat(withoutMask.shown(candidates)).contains(late)
    assertThat(withoutMask.shown(candidates)).containsNoneIn(world.hidden)
  }

  @Test
  fun senzaIstantaneaDellaLibreriaNonSiMostraNiente() = runBlocking<Unit> {
    rig().use { r ->
      r.people.rename(r.world.b, "Anna")
      r.world.visibility.snapshot.value = null
      r.people.refresh()
      assertThat(r.people.list.value.named).isEmpty()
      assertThat(r.people.person(r.world.b).first()).isNull()
      assertThat(r.people.dictionary()).isEmpty()
    }
  }

  /** E-F39: un media che sparisce esce dai conteggi; se torna (stessa chiave) la persona ritrova la foto. */
  @Test
  fun unMediaCheSparisceEscoDaiConteggiEQuandoTornaRientra() = runBlocking<Unit> {
    rig().use { r ->
      val b = r.world.b
      r.people.rename(b, "Anna")
      val media = r.mediaOf(r.world.facesOf(b).first().locator)
      r.world.visibility.present.remove(media)
      r.people.refresh()
      assertThat(r.people.list.value.named.single().photos).isEqualTo(PeopleWorld.PHOTOS - 1)
      r.world.visibility.present += media
      r.people.refresh()
      assertThat(r.people.list.value.named.single().photos).isEqualTo(PeopleWorld.PHOTOS)
    }
  }

  /** Il media aperto dai Nascosti: la riga "Persone" c'e', ma le pagine che ne nascono non mostrano mai altri nascosti. */
  @Test
  fun dalMediaNascostoLaRigaCeMaLePagineNo() = runBlocking<Unit> {
    rig().use { r ->
      val a = r.world.a
      r.people.rename(a, "Marco")
      val hiddenFace = r.world.facesOf(a).last()
      val hiddenMedia = r.mediaOf(hiddenFace.locator)
      assertThat(hiddenMedia).isIn(r.world.hidden)
      val chips = r.people.facesOf(hiddenMedia)
      assertThat(chips.filter { it.tier != FaceTier.NONE }.map { it.person to it.name }).containsExactly(a to "Marco")
      val page = r.people.person(a).first()!!
      val shown = (0 until page.view.size).map { page.view.snapshot.idAt(page.view.snapshotIndex(it)) }
      assertThat(shown).doesNotContain(hiddenMedia)
      assertThat(r.people.mayShow(hiddenFace.locator)).isFalse()
    }
  }

  @Test
  fun unaPersonaNascostaNonCompareInPersoneNeNellaRigaMaSiRitrova() = runBlocking<Unit> {
    rig().use { r ->
      val b = r.world.b
      r.people.rename(b, "Anna")
      r.people.setHidden(b, true)
      assertThat(r.people.list.value.named).isEmpty()
      assertThat(r.people.list.value.hiddenCount).isEqualTo(1)
      assertThat(r.people.hidden().first().map { it.id to it.name }).containsExactly(b to "Anna")
      val media = r.mediaOf(r.world.facesOf(b).first().locator)
      assertThat(r.people.facesOf(media).filter { it.tier != FaceTier.NONE }).isEmpty()
      r.people.setHidden(b, false)
      assertThat(r.people.hidden().first()).isEmpty()
      assertThat(r.people.list.value.named.map { it.id }).containsExactly(b)
    }
  }

  @Test
  fun lOpzioneDiQ2CambiaLaVistaSenzaTogliereNessunGruppo() = runBlocking<Unit> {
    rig(namedOnly = true).use { r ->
      assertThat(r.people.list.value.unnamed).isEmpty()
      assertThat(r.people.list.value.othersCount).isEqualTo(2)
      r.variant.value = PeopleVariant.ALL_GROUPS
      r.people.refresh()
      assertThat(r.people.list.value.unnamed.map { it.id }).containsExactly(r.world.b, r.world.a).inOrder()
      assertThat(r.people.list.value.othersCount).isEqualTo(0)
      assertThat(r.people.others().first().people).isEmpty()
      r.variant.value = PeopleVariant.NAMED_FIRST
      r.people.refresh()
      assertThat(r.people.others().first().people.map { it.id }).containsExactly(r.world.b, r.world.a).inOrder()
    }
  }

  @Test
  fun personeNonAttivaQuiNienteDaMostrareEOgniAzioneNonAttiva() = runBlocking<Unit> {
    rig().use { r ->
      r.people.rename(r.world.b, "Anna")
      r.available.value = false
      r.people.refresh()
      assertThat(r.people.list.value.named).isEmpty()
      assertThat(r.people.dictionary()).isEmpty()
      assertThat(r.people.person(r.world.b).first()).isNull()
      assertThat(r.people.rename(r.world.b, "Bea")).isEqualTo(dev.pampa.mycloudgallery.core.faces.contract.EditOutcome.NotActive)
      assertThat(r.people.facesOf(r.mediaOf(r.world.facesOf(r.world.b).first().locator))).isEmpty()
      assertThat(r.people.available.value).isFalse()
    }
  }
}
