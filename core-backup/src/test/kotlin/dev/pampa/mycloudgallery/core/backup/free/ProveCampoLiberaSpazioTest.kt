package dev.pampa.mycloudgallery.core.backup.free

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceFilter
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceRules
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.LocationState
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * D-3 delle prove sul campo (`docs/prove-campo-esiti.md`): su One UI `createTrashRequest` manda i file nel Cestino di Galleria. Per l'app la
 * riga non c'e' piu' (ne' nella sua cartella ne' fra le cestinate) e l'utente puo' ripristinarla solo da Galleria. "Libera spazio" non deve
 * considerare fallito un cestino riuscito, ne' lasciare il catalogo incoerente quando il file torna prima che un esito sconosciuto si risolva.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProveCampoLiberaSpazioTest {

  private val bench = FreeSpaceBench()

  private fun stateOf(m: FreeSpaceBench.Made) = bench.diary.everything().map { it.second }.single { it.mediaId == m.id }.state

  /**
   * Il processo muore con il dialogo aperto; One UI ha gia' tolto il file (Cestino di Galleria) e l'utente lo ripristina da Galleria → Cestino
   * prima che l'app risolva l'esito. Il file torna come riga nuova e la lettura del telefono ricollega la posizione. L'elemento resta
   * "liberato" (il cestino c'e' stato: nessun errore) e la posizione resta **presente**: prima la segnava assente e la Libreria mostrava
   * "solo NAS" un file che era sul telefono.
   */
  @Test
  fun d3_unFileRipristinatoDaGalleriaPrimaDellEsitoRestaSulTelefonoNelCatalogo() = runTest {
    val a = bench.safe()
    val b = bench.safe()
    val run = bench.start()
    val batch = checkNotNull(bench.space.nextBatch(run))
    bench.dialog(batch)
    bench.restart()

    // Galleria → Cestino → Ripristina: il file di [a] torna con un `_ID` nuovo, la lettura del telefono lo ricollega alla stessa posizione.
    bench.phone.add(RESTORED_ID, NasContent.of(a.data.getValue(a.current)))
    bench.catalog.change(a.id) { media ->
      media.copy(
        locations = media.locations.map {
          if (it.id == a.phoneLocation) it.copy(location = (it.location as Location.Phone).copy(mediaStoreId = RESTORED_ID), state = LocationState.PRESENT) else it
        },
      )
    }
    bench.space.settle()

    assertThat(stateOf(a)).isEqualTo(FreeItemState.FREED)
    assertThat(stateOf(b)).isEqualTo(FreeItemState.FREED)
    assertThat(bench.catalog.location(checkNotNull(a.phoneLocation))!!.state).isEqualTo(LocationState.PRESENT)
    // [b] non e' tornato: la sua posizione diventa assente, come sempre.
    assertThat(bench.catalog.location(checkNotNull(b.phoneLocation))!!.state).isEqualTo(LocationState.ABSENT)
    assertThat(bench.catalog.freed).containsExactly(b.phoneLocation)
    assertThat(bench.trackingViolations(settled = true)).isEmpty()
  }

  /** La risposta del dialogo arriva (One UI dice "fatto" anche se il file va nel Cestino di Galleria): liberati, senza rileggere le righe. */
  @Test
  fun d3_unCestinoConfermatoELiberatoAncheSeLeRigheNonSiRitrovano() = runTest {
    val a = bench.safe()
    val run = bench.start()
    val batch = checkNotNull(bench.space.nextBatch(run))
    val after = bench.space.recordOutcome(batch, bench.dialog(batch))

    assertThat(after.freedItems).isEqualTo(1)
    assertThat(stateOf(a)).isEqualTo(FreeItemState.FREED)
    assertThat(bench.phone.ref(a.phoneId!!)).isNull()
    assertThat(bench.catalog.freed).containsExactly(a.phoneLocation)
    // Nessun nuovo tentativo: il lotto e' chiuso e non si ripropone.
    assertThat(bench.space.nextBatch(bench.refresh(after))).isNull()
  }

  // --- D-10 (ripresa con `test`): l'app uccisa con il dialogo di sistema aperto -------------------------------------------------

  private fun states() = bench.diary.everything().map { it.second.state }

  /**
   * D-10: sull'S25 "Libera ora" ha aperto il dialogo di sistema per 5 foto; con il dialogo aperto il processo e' stato ucciso ed e' ripartito,
   * e la sua risoluzione degli esiti ha visto le 5 righe ancora sul telefono: `KEPT`, che e' finale. Poi l'utente ha toccato "Consenti", One UI
   * ha tolto i file e il diario e' rimasto con 5 "rimasti" per righe che non c'erano piu' (invariante 14). Il dialogo non muore con l'app: finche'
   * puo' rispondere, una riga ancora sul telefono resta in dubbio, e la lettura del telefono dopo il "Consenti" la dice liberata.
   */
  @Test
  fun d10_conIlDialogoApertoDopoLaMorteDelProcessoUnConsentiTardivoLiberaNonLasciaRimasti() = runTest {
    repeat(5) { bench.safe() }
    val run = bench.start()
    val batch = checkNotNull(bench.space.nextBatch(run))
    // Il processo muore con il dialogo aperto; quello nuovo riparte (`atStart`, una lettura del telefono) prima della risposta.
    bench.restart()
    bench.space.settle()
    assertThat(states()).doesNotContain(FreeItemState.KEPT)
    assertThat(states()).containsExactlyElementsIn(List(5) { FreeItemState.IN_DOUBT })
    // Finche' il dialogo di allora puo' rispondere, le righe restano consegnate ad Android (nessun lavoro le riprende per un altro dialogo).
    assertThat(bench.diary.handedPhoneLocations()).hasSize(5)

    // L'utente tocca "Consenti": i file escono dal telefono. La lettura del telefono che segue risolve gli esiti.
    bench.dialog(batch)
    bench.space.settle()

    assertThat(states()).containsExactlyElementsIn(List(5) { FreeItemState.FREED })
    assertThat(bench.catalog.freed).hasSize(5)
    assertThat(bench.trackingViolations(settled = true)).isEmpty()
  }

  /**
   * D-10, l'altra strada: il dialogo di allora non risponde (l'utente lo chiude o tocca "Nega"). Le righe restano sul telefono; diventano
   * "rimaste" quando quel dialogo non puo' piu' contare: l'utente torna nella pagina e chiede un piano (il dialogo stava sopra l'app: se e' qui,
   * e' chiuso), la verifica del giro scade (6 ore) o il telefono si riavvia. Da li' un nuovo "Libera spazio" le puo' riproporre, sempre con una
   * verifica nuova.
   */
  @Test
  fun d10_seIlDialogoNonRispondeLeRigheDiventanoRimasteConUnPianoNuovoAVerificaScadutaONelRiavvio() = runTest {
    repeat(2) { bench.safe() }
    bench.space.nextBatch(bench.start())
    bench.restart()
    bench.space.settle()
    assertThat(states()).containsExactly(FreeItemState.IN_DOUBT, FreeItemState.IN_DOUBT)

    // L'utente torna nella pagina e chiede un piano: il dialogo di prima e' chiuso, le righe sono rimaste e si propongono di nuovo.
    val again = bench.space.plan(FreeSpaceFilter())
    assertThat(states()).containsExactly(FreeItemState.KEPT, FreeItemState.KEPT)
    assertThat(again.items).hasSize(2)
    assertThat(bench.trackingViolations(settled = true)).isEmpty()

    // Senza tornare nella pagina: a verifica scaduta.
    val expired = FreeSpaceBench()
    repeat(2) { expired.safe() }
    expired.space.nextBatch(expired.start())
    expired.restart()
    expired.space.settle()
    expired.clock.advance(FreeSpaceRules.VERIFICATION_VALID_MS + 60_000L)
    expired.space.settle()
    assertThat(expired.diary.everything().map { it.second.state }).containsExactly(FreeItemState.KEPT, FreeItemState.KEPT)
    assertThat(expired.trackingViolations(settled = true)).isEmpty()

    // Lo stesso dopo un riavvio del telefono (il conteggio dei riavvii cambia): il dialogo di prima non c'e' piu'.
    val other = FreeSpaceBench()
    repeat(2) { other.safe() }
    other.space.nextBatch(other.start())
    other.restart()
    other.space.settle()
    other.bootCount = other.bootCount?.plus(1)
    other.restart()
    other.space.settle()
    assertThat(other.diary.everything().map { it.second.state }).containsExactly(FreeItemState.KEPT, FreeItemState.KEPT)
  }

  private companion object {
    const val RESTORED_ID = 9_500L
  }
}
