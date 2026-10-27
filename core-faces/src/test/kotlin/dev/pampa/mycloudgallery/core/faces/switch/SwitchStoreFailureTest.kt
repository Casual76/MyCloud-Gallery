package dev.pampa.mycloudgallery.core.faces.switch

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.faces.contract.FacesLocal
import dev.pampa.mycloudgallery.core.faces.contract.FacesLocalStore
import dev.pampa.mycloudgallery.core.faces.contract.ReconcileOutcome
import dev.pampa.mycloudgallery.core.faces.testing.FakeFacesClock
import dev.pampa.mycloudgallery.core.faces.testing.FakeFacesRequirements
import dev.pampa.mycloudgallery.core.faces.testing.InMemoryFacesLocalStore
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.FacesSetting
import dev.pampa.mycloudgallery.core.personal.contract.FacesSettingCodec
import dev.pampa.mycloudgallery.core.personal.testing.FacesRegister
import dev.pampa.mycloudgallery.core.personal.testing.InMemoryFacesPersonalSettings
import java.io.IOException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

/**
 * O3 (Fase 8, correzione dopo il banco delle interruzioni): lo stato locale `faces-local` che non si riesce a scrivere (disco pieno, file bloccato: un `IOException`)
 * nel mezzo dell'allineamento dei dati con la voce dell'account. L'allineamento che segue ogni voce nuova gira in una coroutine dello scope dell'interruttore: in
 * `:app` una coroutine che lancia senza gestore chiude l'app, e un `collect` che lancia smette di seguire le voci. Deve **fallire chiuso con il suo codice** (i dati
 * restano ricordati, `ERASE_PENDING`, quel giro non pubblica) e **riprovare**, senza che niente esca.
 */
class SwitchStoreFailureTest {

  /** Uno stato locale che non scrive per le prossime [failUpdates] richieste. */
  private class FlakyLocalStore(private val inner: FacesLocalStore) : FacesLocalStore {
    override val local = inner.local
    var failUpdates = 0

    override suspend fun update(transform: (FacesLocal) -> FacesLocal): FacesLocal {
      if (failUpdates > 0) {
        failUpdates--
        throw IOException("disco pieno")
      }
      return inner.update(transform)
    }
  }

  private val g1: FaceGeneration = SwitchRig.generation(1)
  private val g2: FaceGeneration = SwitchRig.generation(2)

  /** Le eccezioni che escono da una coroutine dello scope dell'interruttore (il gestore dello scope le raccoglie invece di darle al processo). */
  private val escaped = mutableListOf<Throwable>()
  private val scope = CoroutineScope(Dispatchers.Unconfined + Job() + CoroutineExceptionHandler { _, e -> escaped += e })
  private val account = InMemoryFacesPersonalSettings(FacesRegister(), "A")
  private val local = FlakyLocalStore(InMemoryFacesLocalStore())
  private val eraser = RecordingEraser()

  @After
  fun chiudi() = scope.cancel()

  /** Un dispositivo con Persone attiva sulla generazione [g1] e i suoi dati locali; l'interruttore vero, con lo scope che raccoglie. */
  private suspend fun device(): DefaultFacesSwitch {
    account.set(FacesSetting.On(g1, 1))
    local.update { it.copy(noticedGeneration = g1, noticedTextVersion = 1, dataGeneration = g1) }
    eraser.data += g1
    return DefaultFacesSwitch(scope, account, local, FakeFacesRequirements(), eraser, FakeFacesClock())
  }

  @Test
  fun unGuastoDelloStatoLocaleNellAllineamentoNonEsceDallaCoroutine_ESiRiprovaAllaVoceDopo() = runTest {
    device()
    // La voce dice "spenta": i dati si tolgono, ma il segno "i dati non ci sono piu'" non si scrive.
    local.failUpdates = 1
    account.injectRemote(FacesSettingCodec.off(g1))

    assertThat(escaped).isEmpty()
    // Niente dimenticato senza che sia scritto: la generazione resta ricordata e il gancio dice "cancellazione in sospeso".
    assertThat(local.local.value.dataGeneration).isEqualTo(g1)

    // La coroutine ha continuato a seguire le voci: la voce dopo (un'altra generazione) riprova da sola e rimette in ordine.
    account.injectRemote(FacesSettingCodec.on(g2, 1))
    assertThat(escaped).isEmpty()
    assertThat(local.local.value.dataGeneration).isNull()
    assertThat(eraser.data).isEmpty()
  }

  @Test
  fun reconcileCheNonRiesceAScrivereLoStatoLocaleFallisceChiusoConIlSuoCodice() = runTest {
    val switch = device()
    eraser.succeeds = false
    account.injectRemote(FacesSettingCodec.off(g1))
    eraser.succeeds = true
    escaped.clear()

    local.failUpdates = 1
    val outcome = switch.reconcile(FacesSetting.Off(g1))
    assertThat(outcome).isEqualTo(ReconcileOutcome.Failed(DefaultFacesSwitch.ERASE_PENDING))
    assertThat(local.local.value.dataGeneration).isEqualTo(g1)

    // Si riprova: la cancellazione non trova piu' niente e il segno si scrive.
    assertThat(switch.reconcile(FacesSetting.Off(g1))).isEqualTo(ReconcileOutcome.Done)
    assertThat(local.local.value.dataGeneration).isNull()
    assertThat(escaped).isEmpty()
  }
}
