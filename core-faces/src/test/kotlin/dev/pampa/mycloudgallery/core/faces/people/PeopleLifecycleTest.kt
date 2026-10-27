package dev.pampa.mycloudgallery.core.faces.people

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.faces.contract.EditOutcome
import dev.pampa.mycloudgallery.core.faces.contract.FacesArchive
import dev.pampa.mycloudgallery.core.faces.contract.PeopleEdit
import dev.pampa.mycloudgallery.core.faces.contract.PeopleWorld
import dev.pampa.mycloudgallery.core.faces.contract.PersonTarget
import dev.pampa.mycloudgallery.core.faces.noop.NoFacesPublisher
import dev.pampa.mycloudgallery.core.faces.testing.FakeFacesClock
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.FaceTier
import dev.pampa.mycloudgallery.core.model.MediaId
import java.io.File
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * Fase 8 (P9, §4.3 "un test per pacchetto con la generazione che cambia a meta'", E-F19, D25, D29): archivio che si chiude, generazione che
 * cambia, copertine, volti del media aperto, ritaglio solo da media mostrabili. Niente di tutto questo e' un errore per chi usa le persone:
 * "Persone non e' attiva qui" (`NotActive`) o elenchi vuoti, mai un crollo.
 */
@RunWith(AndroidJUnit4::class)
class PeopleLifecycleTest {

  @get:Rule
  val folder = TemporaryFolder()

  private var count = 0

  private fun rig(world: PeopleWorld = PeopleWorld(), generation: FaceGeneration = FaceGeneration.fromBytes(ByteArray(16) { 9 })) =
    PeopleRig(File(folder.root, "banco-${count++}"), world, generation = generation)

  private fun PeopleRig.mediaOf(face: FaceLocator): MediaId = world.media.getValue(face.contentKey)

  /** L'archivio si chiude a meta' (chiave distrutta, generazione cambiata): niente eccezione, "Persone non e' attiva qui"; le voci gia' scritte restano. */
  @Test
  fun unArchivioCheSiChiudeNonFaCrollare() = runBlocking<Unit> {
    rig().use { r ->
      r.people.rename(r.world.b, "Anna")
      // La fonte continua a offrire l'archivio chiuso: la prima lettura dopo la chiusura lancia, e viene presa.
      val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
      val alwaysTheSame = ArchivePeople(
        archives = { r.store.archive }, cells = WorldCells(r.world), visibility = r.visibility, publisher = NoFacesPublisher,
        clock = FakeFacesClock(), author = r.device, scope = scope,
      )
      alwaysTheSame.refresh()
      assertThat(alwaysTheSame.list.value.named.map { it.name }).containsExactly("Anna")
      r.store.close()
      assertThat(alwaysTheSame.rename(r.world.b, "Bea")).isEqualTo(EditOutcome.NotActive)
      alwaysTheSame.refresh()
      assertThat(alwaysTheSame.list.value.named).isEmpty()
      assertThat(alwaysTheSame.dictionary()).isEmpty()
      assertThat(alwaysTheSame.person(r.world.b).first()).isNull()
      assertThat(alwaysTheSame.resolve(r.world.b)).isNull()
      // Nessun archivio, nessuna azione.
      assertThat(r.people.rename(r.world.b, "Bea")).isEqualTo(EditOutcome.NotActive)
      scope.cancel()
    }
  }

  /** La generazione cambia (disattiva e riattiva, E-F33): le persone leggono e scrivono solo sull'archivio della generazione di adesso. */
  @Test
  fun laGenerazioneCambiaAMeta() = runBlocking<Unit> {
    val first = rig()
    val second = rig(generation = FaceGeneration.fromBytes(ByteArray(16) { 7 }))
    val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob())
    try {
      var current: FacesArchive = first.archive
      val people = ArchivePeople(
        archives = { current }, cells = WorldCells(first.world), visibility = first.visibility, publisher = first.publisher,
        clock = FakeFacesClock(), author = first.device, scope = scope,
      )
      people.refresh()
      people.rename(first.world.b, "Anna")
      assertThat(first.records().filter { it.edit is PeopleEdit.Name }).hasSize(1)
      current = second.archive
      people.refresh()
      assertThat(people.list.value.named).isEmpty()
      // La scrittura di adesso va nella generazione nuova, non nella vecchia.
      assertThat(people.rename(first.world.b, "Bea")).isEqualTo(EditOutcome.Done)
      assertThat(second.records().filter { it.edit is PeopleEdit.Name }).hasSize(1)
      assertThat(first.records().filter { it.edit is PeopleEdit.Name }.map { (it.edit as PeopleEdit.Name).name }).containsExactly("Anna")
      assertThat(people.list.value.named.map { it.name }).containsExactly("Bea")
    } finally {
      scope.cancel()
      first.close()
      second.close()
    }
  }

  @Test
  fun laCopertinaAutomaticaPrediligeUnVoltoConfermatoEQuellaScelta() = runBlocking<Unit> {
    rig().use { r ->
      val b = r.world.b
      r.people.rename(b, "Anna")
      val faces = r.world.facesOf(b).map { it.locator }
      // Un volto confermato dall'utente e' sicuro: diventa la copertina.
      val confirmed = faces[3]
      r.people.assign(confirmed, PersonTarget.Existing(b))
      assertThat(r.people.list.value.named.single().cover!!.matches(confirmed)).isTrue()
      // La scelta dell'utente vince, se il volto e' della persona e di un media mostrabile.
      assertThat(r.people.setCover(b, faces[1])).isEqualTo(EditOutcome.Done)
      assertThat(r.people.list.value.named.single().cover!!.matches(faces[1])).isTrue()
      assertThat(r.publisher.edits().last()).isEqualTo(PeopleEdit.Cover(b, faces[1]))
      // Un volto di un'altra persona non e' una copertina valida; nemmeno uno di un media nascosto.
      assertThat(r.people.setCover(b, r.world.facesOf(r.world.a).first().locator)).isEqualTo(EditOutcome.Invalid)
      val hiddenFace = r.world.facesOf(r.world.a).last().locator
      r.people.setCover(r.world.a, hiddenFace)
      assertThat(r.publisher.edits().none { it is PeopleEdit.Cover && it.person == r.world.a }).isTrue()
      // Tornare all'automatica: la lapide.
      assertThat(r.people.setCover(b, null)).isEqualTo(EditOutcome.Done)
      assertThat(r.publisher.edits().last()).isEqualTo(PeopleEdit.Cover(b, null))
      assertThat(r.people.setCover(b, null)).isEqualTo(EditOutcome.Done)
      // La copertina scelta poi nascosta: torna l'automatica (E-H6).
      r.people.setCover(b, faces[2])
      r.world.visibility.hidden += r.mediaOf(faces[2])
      r.people.refresh()
      assertThat(r.people.list.value.named.single().cover!!.matches(faces[2])).isFalse()
    }
  }

  @Test
  fun iVoltiDiUnMediaPerLaRigaDelViewer() = runBlocking<Unit> {
    rig().use { r ->
      val a = r.world.a
      val first = r.world.facesOf(a).first().locator
      val media = r.mediaOf(first)
      r.people.rename(a, "Marco")
      val chips = r.people.facesOf(media)
      assertThat(chips.map { it.tier }).containsExactly(FaceTier.CORE, FaceTier.NONE).inOrder()
      assertThat(chips.first().person).isEqualTo(a)
      assertThat(chips.first().name).isEqualTo("Marco")
      assertThat(chips.last().person).isNull()
      // La riga si aggiorna da sola quando cambia un nome.
      val flow = r.people.faces(media)
      assertThat(flow.first().first().name).isEqualTo("Marco")
      r.people.rename(a, "Marco R.")
      assertThat(flow.first().first().name).isEqualTo("Marco R.")
      // Il riferimento del volto per il viewer e' un testo di al piu' 128 caratteri che torna uguale.
      val token = dev.pampa.mycloudgallery.core.faces.contract.FaceTokens.of(chips.first().face)
      assertThat(token.length).isAtMost(128)
      assertThat(dev.pampa.mycloudgallery.core.faces.contract.FaceTokens.parse(token)!!.matches(chips.first().face)).isTrue()
      assertThat(dev.pampa.mycloudgallery.core.faces.contract.FaceTokens.parse("non e' un volto")).isNull()
      // Un volto si ritaglia solo dal suo media.
      assertThat(r.people.belongsTo(media, chips.first().face)).isTrue()
      assertThat(r.people.belongsTo(r.mediaOf(r.world.facesOf(r.world.b).first().locator), chips.first().face)).isFalse()
      // Il foglio "Chi e'?" propone persone con un nome.
      assertThat(r.people.choices().map { it.id }).containsExactly(a)
    }
  }

  /** "Altri volti" -> "Aggiungi un nome": dare un nome fa passare la persona in Persone. */
  @Test
  fun daUnNomeUnGruppoSenzaNomePassaInPersone() = runBlocking<Unit> {
    rig().use { r ->
      assertThat(r.people.others().first().people.map { it.id }).containsExactly(r.world.b, r.world.a)
      r.people.rename(r.world.b, "Anna")
      assertThat(r.people.list.value.named.map { it.id }).containsExactly(r.world.b)
      assertThat(r.people.others().first().people.map { it.id }).containsExactly(r.world.a)
      assertThat(r.people.list.value.othersCount).isEqualTo(1)
    }
  }

  @Test
  fun lAvanzamentoDelPrimoCalcoloEsceNellaLista() = runBlocking<Unit> {
    rig().use { r ->
      assertThat(r.people.list.value.progressPercent).isNull()
      r.progress.value = 35
      // Il numero si aggiorna da solo, senza rifare le viste.
      kotlinx.coroutines.withTimeout(10_000) { r.people.list.first { it.progressPercent == 35 } }
      assertThat(r.people.list.value.progressPercent).isEqualTo(35)
    }
  }

  /**
   * Il primo `refresh` parte da solo nel blocco `init`: ogni proprieta' che usa deve essere pronta **prima** (una era dichiarata dopo e il primo giro andava in
   * `NullPointerException`, fuggito poi in un altro test come eccezione non presa; su un altro thread la corsa era rara, qui e' certa). Con una coroutine che parte
   * subito nel thread del costruttore e un archivio che non sospende, il giro finisce dentro il costruttore: nessuna eccezione deve restare.
   */
  @Test
  fun costruireLePersoneNonLasciaEccezioniNelPrimoGiro() = runBlocking<Unit> {
    val world = PeopleWorld()
    val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
    val scope = kotlinx.coroutines.CoroutineScope(
      kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined + kotlinx.coroutines.CoroutineExceptionHandler { _, e -> errors += e },
    )
    val archive = dev.pampa.mycloudgallery.core.faces.testing.InMemoryFacesArchive(FaceGeneration.fromBytes(ByteArray(16) { 3 }))
    val people = ArchivePeople(
      archives = { archive }, cells = WorldCells(world), visibility = PeopleVisibility(world.visibility, world.visibility), publisher = NoFacesPublisher,
      clock = FakeFacesClock(), author = world.author, scope = scope,
    )
    assertThat(errors).isEmpty()
    assertThat(people.list.value.named).isEmpty()
    scope.cancel()
  }
}
