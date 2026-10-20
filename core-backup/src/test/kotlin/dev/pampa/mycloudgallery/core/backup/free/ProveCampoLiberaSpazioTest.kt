package dev.pampa.mycloudgallery.core.backup.free

import com.google.common.truth.Truth.assertThat
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

  private companion object {
    const val RESTORED_ID = 9_500L
  }
}
