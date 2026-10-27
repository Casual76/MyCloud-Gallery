package dev.pampa.mycloudgallery.core.faces.people

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.faces.contract.EditOutcome
import dev.pampa.mycloudgallery.core.faces.contract.PeopleEdit
import dev.pampa.mycloudgallery.core.faces.contract.PeopleState
import dev.pampa.mycloudgallery.core.faces.contract.PeopleWorld
import dev.pampa.mycloudgallery.core.faces.contract.PersonPair
import dev.pampa.mycloudgallery.core.faces.contract.PersonTarget
import dev.pampa.mycloudgallery.core.faces.contract.SplitOutcome
import dev.pampa.mycloudgallery.core.model.FaceTier
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * Fase 8 (P9, §2.6, D23, D24, E-F5, E-F20-E-F23, E-F27): **ogni azione scrive le voci giuste e ha effetto subito**, sull'archivio vero. Le
 * voci sono quelle accodate alla pubblicazione (nell'ordine) e quelle che l'archivio tiene (con o senza l'ora del NAS). Le tre lapidi che le
 * correzioni nuove si portano dietro ("Non e' …" su un volto confermato, "E' …" dopo un "Non e' …", "Ignora" su un volto confermato) sono
 * provate una per una: senza, la correzione nuova non basterebbe a cambiare il risultato.
 */
@RunWith(AndroidJUnit4::class)
class PeopleActionsTest {

  @get:Rule
  val folder = TemporaryFolder()

  private var count = 0

  private fun rig(nasNow: Long? = 1_800_000_000_000L, namedOnly: Boolean = true) =
    PeopleRig(File(folder.root, "banco-${count++}"), PeopleWorld(), nasNow = nasNow, namedOnly = namedOnly)

  @Test
  fun unaVoceDaDatareVaSubitoEUnaVoceNuovaSostituisceLaVecchiaDellaStessaChiave() = runBlocking<Unit> {
    rig(nasNow = null).use { r ->
      val a = r.world.a
      assertThat(r.people.rename(a, "Bea")).isEqualTo(EditOutcome.Done)
      assertThat(r.people.rename(a, "Anna")).isEqualTo(EditOutcome.Done)
      // Vale subito, senza l'ora del NAS; la seconda scelta ha preso il posto della prima, anche se "Bea" > "Anna" (l'ordine dei valori no).
      assertThat(r.people.list.value.named.map { it.name }).containsExactly("Anna")
      val names = r.records().filter { it.edit is PeopleEdit.Name }
      assertThat(names).hasSize(1)
      assertThat(names.single().atNas).isNull()
      assertThat(r.publisher.edits()).containsExactly(PeopleEdit.Name(a, "Bea"), PeopleEdit.Name(a, "Anna")).inOrder()
      // Nascondere e mostrare di nuovo: l'ultima scelta vince, qualunque sia il valore.
      r.people.setHidden(a, true)
      r.people.setHidden(a, false)
      assertThat(PeopleState.of(r.records()).hidden).isEmpty()
      assertThat(r.people.list.value.named.map { it.id }).containsExactly(a)
    }
  }

  @Test
  fun conLOraDelNasLaVoceNascePortandola() = runBlocking<Unit> {
    rig(nasNow = 1_900_000_000_000L).use { r ->
      r.people.rename(r.world.a, "Marco")
      val record = r.records().single { it.edit is PeopleEdit.Name }
      assertThat(record.atNas).isEqualTo(1_900_000_000_000L)
      assertThat(record.author).isEqualTo(r.world.author)
    }
  }

  @Test
  fun nomiNonAmmessi() = runBlocking<Unit> {
    rig().use { r ->
      val bad = listOf("", "   ", "x".repeat(65), "Mar\u0000co", "Ma‮rco", "Ma​rco", "Ma\nrco")
      for (name in bad) assertThat(r.people.rename(r.world.a, name)).isEqualTo(EditOutcome.InvalidName)
      assertThat(r.people.rename(r.world.a, "x".repeat(64))).isEqualTo(EditOutcome.Done)
      assertThat(r.people.rename(r.world.a, "L")).isEqualTo(EditOutcome.Done)
      assertThat(r.publisher.edits()).hasSize(2)
    }
  }

  /** "Non e' …" su un volto confermato: senza la lapide di `is`, "E' …" vincerebbe (avvertenza di P5). */
  @Test
  fun nonEsuUnVoltoConfermatoScriveAncheLaLapideDiIs() = runBlocking<Unit> {
    rig().use { r ->
      val a = r.world.a
      val face = r.world.facesOf(a).first().locator
      assertThat(r.people.assign(face, PersonTarget.Existing(a))).isEqualTo(EditOutcome.Done)
      assertThat(r.publisher.edits()).containsExactly(PeopleEdit.Is(face, a))
      assertThat(r.people.reject(face, a)).isEqualTo(EditOutcome.Done)
      assertThat(r.publisher.edits().drop(1)).containsExactly(PeopleEdit.Not(face, a, true), PeopleEdit.Is(face, null)).inOrder()
      // Il volto e' uscito e non ci torna: la conferma non c'e' piu'.
      assertThat(r.people.person(a).first()!!.card.photos).isEqualTo(PeopleWorld.PHOTOS - 2)
      assertThat(r.people.facesOf(r.world.media.getValue(face.contentKey)).single { it.tier != FaceTier.NONE }.person).isNull()
      // Detto due volte non scrive due volte.
      assertThat(r.people.reject(face, a)).isEqualTo(EditOutcome.Done)
      assertThat(r.publisher.edits()).hasSize(3)
    }
  }

  /** "E' …" dopo un "Non e' …": la lapide dell'esclusione, altrimenti i due vincoli si contraddicono. */
  @Test
  fun eDopoNonEToglieLEsclusione() = runBlocking<Unit> {
    rig().use { r ->
      val b = r.world.b
      val face = r.world.facesOf(b)[1].locator
      r.people.reject(face, b)
      assertThat(r.people.person(b).first()!!.card.photos).isEqualTo(PeopleWorld.PHOTOS - 1)
      assertThat(r.people.assign(face, PersonTarget.Existing(b))).isEqualTo(EditOutcome.Done)
      assertThat(r.publisher.edits().takeLast(2)).containsExactly(PeopleEdit.Is(face, b), PeopleEdit.Not(face, b, false)).inOrder()
      assertThat(PeopleState.of(r.records()).rejectedFor(face)).isEmpty()
      assertThat(r.people.person(b).first()!!.card.photos).isEqualTo(PeopleWorld.PHOTOS)
    }
  }

  @Test
  fun ignoraUnVoltoConfermatoTogliAncheLaConferma() = runBlocking<Unit> {
    rig().use { r ->
      val b = r.world.b
      val face = r.world.facesOf(b)[2].locator
      r.people.assign(face, PersonTarget.Existing(b))
      assertThat(r.people.ignore(face)).isEqualTo(EditOutcome.Done)
      assertThat(r.publisher.edits().drop(1)).containsExactly(PeopleEdit.Ignore(face, true), PeopleEdit.Is(face, null)).inOrder()
      assertThat(PeopleState.of(r.records()).fixedFor(face)).isNull()
      assertThat(r.people.person(b).first()!!.card.photos).isEqualTo(PeopleWorld.PHOTOS - 1)
      // Il volto ignorato non compare nemmeno nella riga delle info.
      assertThat(r.people.facesOf(r.world.media.getValue(face.contentKey)).filter { it.tier != FaceTier.NONE }).isEmpty()
      assertThat(r.people.ignore(face)).isEqualTo(EditOutcome.Done)
      assertThat(r.publisher.edits()).hasSize(3)
    }
  }

  @Test
  fun unVoltoNonRiconoscibileONonEsistenteNonSiCorregge() = runBlocking<Unit> {
    rig().use { r ->
      assertThat(r.people.assign(r.world.none, PersonTarget.Existing(r.world.a))).isEqualTo(EditOutcome.Invalid)
      assertThat(r.people.ignore(r.world.none)).isEqualTo(EditOutcome.Invalid)
      assertThat(r.people.reject(r.world.none, r.world.a)).isEqualTo(EditOutcome.Invalid)
      val nowhere = r.world.facesOf(r.world.a).first().locator.let { it.copy(frame = 5) }
      assertThat(r.people.ignore(nowhere)).isEqualTo(EditOutcome.NotFound)
      assertThat(r.people.assign(r.world.facesOf(r.world.a).first().locator, PersonTarget.Existing(dev.pampa.mycloudgallery.core.model.PersonId.random())))
        .isEqualTo(EditOutcome.NotFound)
      assertThat(r.publisher.edits()).isEmpty()
    }
  }

  /** E-F23: unire a mano toglie un "No" detto prima a una proposta; con un nome solo, resta quello. */
  @Test
  fun unisciTogliIlSeparatiETieneIlNome() = runBlocking<Unit> {
    rig().use { r ->
      val (a, b) = r.world.a to r.world.b
      r.people.answer(dev.pampa.mycloudgallery.core.faces.contract.MergeProposal(card(r, a), card(r, b)), same = false)
      assertThat(PeopleState.of(r.records()).apart).contains(PersonPair.of(a, b))
      r.people.rename(a, "Marco")
      assertThat(r.people.merge(listOf(a, b), null)).isEqualTo(EditOutcome.Done)
      assertThat(r.publisher.edits().takeLast(2).map { it::class }).containsExactly(PeopleEdit.Alias::class, PeopleEdit.Apart::class).inOrder()
      val state = PeopleState.of(r.records())
      assertThat(state.apart).isEmpty()
      // Il nome e' quello dell'unica persona che ce l'aveva: sopravvive lei, l'altra e' un alias.
      assertThat(state.aliases).containsExactly(b, a)
      assertThat(r.people.list.value.named.map { it.id to it.name }).containsExactly(a to "Marco")
    }
  }

  @Test
  fun rispondereSiAUnaPropostaConDueNomiChiedeQualeTenere() = runBlocking<Unit> {
    rig().use { r ->
      val (a, b) = r.world.a to r.world.b
      r.people.rename(a, "Marco")
      r.people.rename(b, "Anna")
      val proposal = dev.pampa.mycloudgallery.core.faces.contract.MergeProposal(card(r, a), card(r, b))
      assertThat(r.people.answer(proposal, same = true)).isEqualTo(EditOutcome.NeedsNameChoice)
      assertThat(r.people.merge(listOf(a, b), keepNameOf = b)).isEqualTo(EditOutcome.Done)
      assertThat(r.people.merge(listOf(a, b), keepNameOf = b)).isEqualTo(EditOutcome.Invalid)
    }
  }

  /** D25: una persona nata da "Dividi" con meno di tre foto ha la sua pagina ma non sta in "Altri volti"; con tre, si'. */
  @Test
  fun dividiLaPersonaNuovaHaPaginaMaNonCompareSottoTreFoto() = runBlocking<Unit> {
    rig().use { r ->
      val b = r.world.b
      val faces = r.world.facesOf(b).map { it.locator }
      val two = (r.people.split(b, faces.take(2)) as SplitOutcome.Split).newPerson
      assertThat(r.people.person(two).first()!!.card.photos).isEqualTo(2)
      assertThat(r.people.others().first().people.map { it.id }).doesNotContain(two)
      val three = (r.people.split(b, faces.drop(2).take(3)) as? SplitOutcome.Failed)
      // Restano 3 volti: dividerli tutti non e' dividere.
      assertThat((three as SplitOutcome.Failed).outcome).isEqualTo(EditOutcome.Invalid)
      val rest = (r.people.split(b, faces.drop(2).take(2)) as SplitOutcome.Split).newPerson
      assertThat(r.people.person(rest).first()!!.card.photos).isEqualTo(2)
      // La stessa persona non si divide con un volto che non e' suo.
      assertThat((r.people.split(b, listOf(r.world.facesOf(r.world.a).first().locator)) as SplitOutcome.Failed).outcome).isEqualTo(EditOutcome.Invalid)
      // Con un nome la persona nuova compare in Persone.
      r.people.rename(two, "Nina")
      assertThat(r.people.list.value.named.map { it.id }).containsExactly(two)
    }
  }

  @Test
  fun dimenticaTogliLePersoneEIVolti() = runBlocking<Unit> {
    rig().use { r ->
      val b = r.world.b
      val face = r.world.facesOf(b).first()
      r.people.forget(b)
      assertThat(r.people.person(b).first()).isNull()
      assertThat(r.people.others().first().people.map { it.id }).doesNotContain(b)
      // I suoi volti sono ignorati, non "senza nome": nemmeno nella riga del viewer.
      assertThat(r.people.facesOf(r.world.media.getValue(face.ref.contentKey)).filter { it.tier != FaceTier.NONE }).isEmpty()
      assertThat(r.people.forget(b)).isEqualTo(EditOutcome.NotFound)
    }
  }

  @Test
  fun lAzioneFinisceAncheSeLaPubblicazioneNonRiesce() = runBlocking<Unit> {
    rig().use { r ->
      r.publisher.failing = true
      assertThat(r.people.rename(r.world.a, "Marco")).isEqualTo(EditOutcome.Done)
      assertThat(r.people.list.value.named.map { it.name }).containsExactly("Marco")
      assertThat(r.records().filter { it.edit is PeopleEdit.Name }).hasSize(1)
      assertThat(r.publisher.edits()).isEmpty()
    }
  }

  private suspend fun card(r: PeopleRig, id: dev.pampa.mycloudgallery.core.model.PersonId) = r.people.person(id).first()!!.card
}
