package dev.pampa.mycloudgallery.faces

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.EditOutcome
import dev.pampa.mycloudgallery.core.albums.contract.PeopleRule
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.faces.contract.EraseReport
import dev.pampa.mycloudgallery.core.faces.contract.EraseStep
import dev.pampa.mycloudgallery.core.faces.contract.EraseWait
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.HereState
import dev.pampa.mycloudgallery.core.faces.contract.PeopleAlbumsChoice
import dev.pampa.mycloudgallery.core.faces.erase.FacesEraseFence
import dev.pampa.mycloudgallery.core.faces.sync.FacesArchives
import dev.pampa.mycloudgallery.core.faces.testing.FakeFacesSwitch
import dev.pampa.mycloudgallery.core.faces.testing.InMemoryFacesArchive
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.PeopleMode
import dev.pampa.mycloudgallery.core.model.PersonId
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Correzioni dopo le revisioni della Fase 8, pacchetto A, nella parte di `:app`:
 * - **R13**: un giro fermo su un file tenuto aperto dal NAS allunga l'attesa (2, 4, 8, 16, poi 30 minuti) invece di ripartire ogni minuto;
 * - **CE-4**: gli album smart con le persone che non si sciolgono fanno fallire il tentativo del diario (prima il passo si segnava "fatto" con un album ancora
 *   con la regola);
 * - **R11**: l'ultimo archivio aperto si dimentica.
 */
class FacesCorrectionsATest {

  // --- R13 -----------------------------------------------------------------------------------------------------------------------------

  private fun report(waiting: EraseWait?, done: Boolean = false) = EraseReport(if (done) EraseStep.DONE else EraseStep.LOCAL_DONE, 0, waiting, done)

  @Test
  fun r13_lAttesaPerUnFileApertoCresceEResta30MinutiAlMassimo() {
    val waits = (1..8).map { FacesGraph.heldDelay(it) }
    assertThat(waits.take(5)).containsExactly(2.minutes, 4.minutes, 8.minutes, 16.minutes, 30.minutes).inOrder()
    assertThat(waits.drop(5).toSet()).containsExactly(FacesGraph.ERASE_LATER)
    // Mai piu' corta di due minuti (non un giro al minuto), mai oltre il tetto degli altri motivi di attesa.
    for (wait in waits) assertThat(wait >= 2.minutes && wait <= FacesGraph.ERASE_LATER).isTrue()
    // Fuori dall'intervallo (zero giri, o un conteggio enorme) non si rompe.
    assertThat(FacesGraph.heldDelay(0)).isEqualTo(2.minutes)
    assertThat(FacesGraph.heldDelay(1_000)).isEqualTo(FacesGraph.ERASE_LATER)
  }

  @Test
  fun r13_ilFileApertoUsaLAttesaCrescente_GliAltriMotiviRestanoCosiCome() {
    for (rounds in 1..6) assertThat(FacesGraph.eraseDelay(report(EraseWait.FILE_OPEN), rounds)).isEqualTo(FacesGraph.heldDelay(rounds))
    // Il resto non cambia: finita e le attese che riprendono i giri dell'indice (CE-2) non servono al lavoro, nessun motivo = presto, gli altri motivi = piu' tardi.
    assertThat(FacesGraph.eraseDelay(report(null, done = true), 3)).isNull()
    assertThat(FacesGraph.eraseDelay(report(EraseWait.DEVICES), 3)).isNull()
    assertThat(FacesGraph.eraseDelay(report(EraseWait.RECYCLE_BIN), 3)).isNull()
    assertThat(FacesGraph.eraseDelay(report(null), 3)).isEqualTo(FacesGraph.ERASE_SOON)
    for (wait in listOf(EraseWait.NAS, EraseWait.FROZEN, EraseWait.CLOCK_JUMP, EraseWait.READ_ONLY, EraseWait.EMERGENCY_STOP, EraseWait.NOT_TRUSTED)) {
      assertThat(FacesGraph.eraseDelay(report(wait), 3)).isEqualTo(FacesGraph.ERASE_LATER)
    }
  }

  // --- CE-4 ----------------------------------------------------------------------------------------------------------------------------

  private fun album(n: Int, frozen: Boolean = false) = SmartWithPeople(
    AlbumId("00000000-0000-4000-8000-%012d".format(n)),
    SmartRules(period = null, place = null, text = null, people = PeopleRule(listOf(PersonId.random()), PeopleMode.ALL)),
    frozen,
  )

  private class FakeAlbums(val albums: List<SmartWithPeople>) : PeopleAlbumsAccess {
    val frozen = mutableListOf<AlbumId>()
    val released = mutableListOf<AlbumId>()
    val failRules = mutableSetOf<AlbumId>()
    val throwRules = mutableSetOf<AlbumId>()
    val failFreeze = mutableSetOf<AlbumId>()

    override fun withPeople(): List<SmartWithPeople> = albums.filter { it.id !in released }

    override suspend fun freeze(id: AlbumId): EditOutcome {
      if (id in failFreeze) return EditOutcome.NotFound
      frozen += id
      return EditOutcome.Done
    }

    override suspend fun setRules(id: AlbumId, rules: SmartRules): EditOutcome {
      if (id in throwRules) throw IllegalStateException("guasto")
      if (id in failRules) return EditOutcome.NotFound
      check(rules.people == null)
      released += id
      return EditOutcome.Done
    }
  }

  @Test
  fun ce4_glialbumNonMontatiFannoFallireIlTentativo_EContatiSonoZero() = runBlocking<Unit> {
    val port = AppSmartAlbumsPeople { null }
    assertThat(port.albumsWithPeople()).isEqualTo(0)
    assertThrows(IllegalStateException::class.java) { runBlocking { port.release(PeopleAlbumsChoice.KEEP_PHOTOS) } }
  }

  @Test
  fun ce4_unAlbumCheNonSiScioglieFaFallireIlTentativoMaGliAltriSiSciolgono_IlTentativoDopoFinisce() = runBlocking<Unit> {
    val a = album(1)
    val b = album(2)
    val c = album(3)
    val fake = FakeAlbums(listOf(a, b, c)).also { it.failRules += b.id }
    val port = AppSmartAlbumsPeople { fake }
    assertThat(port.albumsWithPeople()).isEqualTo(3)
    assertThrows(IllegalStateException::class.java) { runBlocking { port.release(PeopleAlbumsChoice.KEEP_PHOTOS) } }
    // Ne ha sciolti due su tre, e il terzo e' ancora li' con la sua regola: non e' "fatto".
    assertThat(fake.released).containsExactly(a.id, c.id)
    assertThat(fake.withPeople().map { it.id }).containsExactly(b.id)
    // Il problema passa: il tentativo dopo scioglie l'ultimo (e non rifa' quelli gia' sciolti: idempotente) e risponde quanti.
    fake.failRules.clear()
    assertThat(port.release(PeopleAlbumsChoice.KEEP_PHOTOS)).isEqualTo(1)
    assertThat(fake.withPeople()).isEmpty()
  }

  @Test
  fun ce4_ancheUnaEccezioneDiUnAlbumFaFallireIlTentativo() = runBlocking<Unit> {
    val a = album(1)
    val b = album(2)
    val fake = FakeAlbums(listOf(a, b)).also { it.throwRules += a.id }
    val port = AppSmartAlbumsPeople { fake }
    assertThrows(IllegalStateException::class.java) { runBlocking { port.release(PeopleAlbumsChoice.DROP_RULE) } }
    assertThat(fake.released).containsExactly(b.id)
    // E un album che non si congela non perde la regola: "Tieni le foto" non si fa a meta'.
    val c = album(3)
    val frozenFail = FakeAlbums(listOf(c)).also { it.failFreeze += c.id }
    assertThrows(IllegalStateException::class.java) { runBlocking { AppSmartAlbumsPeople { frozenFail }.release(PeopleAlbumsChoice.KEEP_PHOTOS) } }
    assertThat(frozenFail.released).isEmpty()
  }

  @Test
  fun ce4_laSceltaGuidaLeMosse() = runBlocking<Unit> {
    val fresh = album(1)
    val already = album(2, frozen = true)
    val keep = FakeAlbums(listOf(fresh, already))
    assertThat(AppSmartAlbumsPeople { keep }.release(PeopleAlbumsChoice.KEEP_PHOTOS)).isEqualTo(2)
    // "Tieni le foto": congela solo chi non lo e' gia', poi toglie la regola a tutti.
    assertThat(keep.frozen).containsExactly(fresh.id)
    assertThat(keep.released).containsExactly(fresh.id, already.id)
    val drop = FakeAlbums(listOf(fresh))
    assertThat(AppSmartAlbumsPeople { drop }.release(PeopleAlbumsChoice.DROP_RULE)).isEqualTo(1)
    assertThat(drop.frozen).isEmpty()
  }

  // --- R11 -----------------------------------------------------------------------------------------------------------------------------

  @Test
  fun r11_lUltimoArchivioApertoSiDimenticaConLaCancellazione() = runBlocking<Unit> {
    val generation = FaceGeneration.fromBytes(ByteArray(16) { 12 })
    val fence = FacesEraseFence(FakeFacesSwitch(initial = FacesState.On(generation, HereState.ACTIVE)))
    val archives = FencedArchives(fence, FacesArchives { InMemoryFacesArchive(it) })
    assertThat(archives.open(generation)).isNotNull()
    assertThat(archives.opened()).isNotNull()
    archives.forget()
    assertThat(archives.opened()).isNull()
  }
}
