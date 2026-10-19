package dev.pampa.mycloudgallery.core.nas

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.nas.breaker.BreakerKeys
import dev.pampa.mycloudgallery.core.nas.breaker.BreakerRecord
import dev.pampa.mycloudgallery.core.nas.contract.AuthBlockReason
import dev.pampa.mycloudgallery.core.nas.contract.BreakerState
import dev.pampa.mycloudgallery.core.nas.contract.CredentialLoad
import dev.pampa.mycloudgallery.core.nas.contract.InsecureKind
import dev.pampa.mycloudgallery.core.nas.contract.InvalidateReason
import dev.pampa.mycloudgallery.core.nas.contract.NasConnState
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.contract.SmbDialect
import dev.pampa.mycloudgallery.core.nas.contract.errorOrNull
import dev.pampa.mycloudgallery.core.nas.testing.GuestBehaviour
import dev.pampa.mycloudgallery.core.nas.testing.SimOp
import dev.pampa.mycloudgallery.core.network.contract.DenyReason
import dev.pampa.mycloudgallery.core.network.contract.Via
import dev.pampa.mycloudgallery.core.security.credentials.NasCredentials
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SessionTest {

  private fun TestScope.harness() = Harness(backgroundScope, virtualClock())

  private suspend fun Harness.touch(purpose: Purpose = Purpose.BACKGROUND) =
    manager.withShare(SHARE, purpose) { it.browser.list(null, 10) }

  // --- Ordine dei controlli: nessuna autenticazione nei casi negati -------------------------------------------

  @Test
  fun percorsoNegatoNessunaTcpNessunaPassword() = runTest {
    val h = harness()
    for (reason in DenyReason.entries) {
      h.gate.deny(reason)
      val error = h.touch().errorOrNull()
      assertThat(error).isAnyOf(NasError.NotAllowedHere(reason), NasError.LocalNetworkBlocked)
    }
    assertThat(h.backend.tcpAttempts.get()).isEqualTo(0)
    assertThat(h.credentials.loads).isEqualTo(0)
    assertThat(h.backend.authenticateCalls.get()).isEqualTo(0)
  }

  @Test
  fun guidDiversoNessunaPasswordENessunNuovoTentativoSullaStessaRete() = runTest {
    val h = harness()
    h.backend.serverGuid = "99999999-8888-7777-6666-555555555555"
    assertThat(h.touch().errorOrNull()).isEqualTo(NasError.IdentityMismatch)
    assertThat(h.touch().errorOrNull()).isEqualTo(NasError.IdentityMismatch)
    assertThat(h.backend.tcpAttempts.get()).isEqualTo(1)
    assertThat(h.credentials.loads).isEqualTo(0)
    assertThat(h.backend.authenticateCalls.get()).isEqualTo(0)
    assertThat(h.manager.nasClock.nowMillisOrNull()).isNull()

    // Su un'altra rete si riprova (sempre senza password).
    h.gate.allow(Via.VPN, h.backend.binding("vpn-1"))
    assertThat(h.touch().errorOrNull()).isEqualTo(NasError.IdentityMismatch)
    assertThat(h.backend.tcpAttempts.get()).isEqualTo(2)
    assertThat(h.backend.authenticateCalls.get()).isEqualTo(0)
  }

  @Test
  fun cifraturaNonOffertaODialettoDiversoNessunaPassword() = runTest {
    val noEncryption = harness().apply { backend.encryptionOffered = false }
    assertThat(noEncryption.touch().errorOrNull()).isEqualTo(NasError.InsecureSession(InsecureKind.NO_ENCRYPTION))
    assertThat(noEncryption.backend.authenticateCalls.get()).isEqualTo(0)
    assertThat(noEncryption.credentials.loads).isEqualTo(0)

    val oldDialect = harness().apply { backend.dialect = SmbDialect.SMB_3_0 }
    assertThat(oldDialect.touch().errorOrNull()).isEqualTo(NasError.InsecureSession(InsecureKind.DIALECT))
    assertThat(oldDialect.backend.authenticateCalls.get()).isEqualTo(0)
  }

  @Test
  fun interruttoreApertoNessunaTcpNessunaPassword() = runTest {
    val h = harness()
    h.store.update(BreakerKeys.of(checkNotNull(h.account?.pinnedGuid), USER)) {
      BreakerRecord(generation = 3, openReason = AuthBlockReason.ACCOUNT_LOCKED, openedAt = 5)
    }
    assertThat(h.touch().errorOrNull()).isEqualTo(NasError.AuthBlocked(AuthBlockReason.ACCOUNT_LOCKED))
    assertThat(h.manager.state.value).isEqualTo(NasConnState.AuthBlocked(AuthBlockReason.ACCOUNT_LOCKED))
    assertThat(h.backend.tcpAttempts.get()).isEqualTo(0)
    assertThat(h.backend.authenticateCalls.get()).isEqualTo(0)

    // Password nuova salvata: un tentativo, riuscito.
    h.breaker.onNewPasswordSaved()
    runCurrent()
    assertThat(h.manager.state.value).isEqualTo(NasConnState.Idle)
    assertThat(h.touch().errorOrNull()).isNull()
    assertThat(h.backend.authenticateCalls.get()).isEqualTo(1)
  }

  @Test
  fun senzaGuidMemorizzatoNonSiConnette() = runTest {
    val h = harness()
    h.account = h.account?.copy(pinnedGuid = null)
    assertThat(h.touch().errorOrNull()).isEqualTo(NasError.NotAllowedHere(DenyReason.NOT_CONFIGURED))
    h.account = null
    assertThat(h.touch().errorOrNull()).isEqualTo(NasError.NotAllowedHere(DenyReason.NOT_CONFIGURED))
    assertThat(h.backend.tcpAttempts.get()).isEqualTo(0)
  }

  @Test
  fun ilPercorsoSiRicontrollaPrimaDellaPassword() = runTest {
    val h = harness()
    // La VPN si spegne durante la NEGOTIATE: la password non parte.
    h.backend.beforeOp = { op, _ -> if (op == SimOp.CONNECT) h.gate.deny(DenyReason.VPN_OFF_AWAY) }
    assertThat(h.touch().errorOrNull()).isEqualTo(NasError.NotAllowedHere(DenyReason.VPN_OFF_AWAY))
    assertThat(h.backend.authenticateCalls.get()).isEqualTo(0)
    assertThat(h.credentials.loads).isEqualTo(0)
  }

  @Test
  fun reteCambiataDuranteLaNegotiateSiRifaSullaReteNuova() = runTest {
    val h = harness()
    var switched = false
    h.backend.beforeOp = { op, _ ->
      if (op == SimOp.CONNECT && !switched) {
        switched = true
        h.gate.allow(Via.HOME_LAN, h.backend.binding("wifi-casa-2"))
      }
    }
    assertThat(h.touch().errorOrNull()).isNull()
    assertThat(h.backend.connectedNetworks).containsExactly(HOME_WIFI, "wifi-casa-2").inOrder()
    assertThat(h.backend.authenticateCalls.get()).isEqualTo(1)
  }

  @Test
  fun dopoLAutenticazioneSiPretendeUnaSessioneSicura() = runTest {
    for (setup in listOf<(Harness) -> Unit>(
      { it.backend.guest = GuestBehaviour.SESSION_FLAG },
      { it.backend.anonymous = true },
      { it.backend.sessionEncrypted = false },
    )) {
      val h = harness()
      setup(h)
      assertThat(h.touch().errorOrNull()).isInstanceOf(NasError.InsecureSession::class.java)
      assertThat(h.backend.openConnections).isEqualTo(0)
      // Agganciato: nessun nuovo login automatico sulla stessa rete.
      h.touch()
      assertThat(h.backend.authenticateCalls.get()).isEqualTo(1)
      assertThat(h.breaker.current()).isInstanceOf(BreakerState.Closed::class.java)
    }
  }

  @Test
  fun credenzialiAssentiONonCoerentiNessunaAutenticazione() = runTest {
    val h = harness()
    h.credentials.next = { CredentialLoad.NeedsPassword }
    assertThat(h.touch().errorOrNull()).isEqualTo(NasError.CredentialsMissing)
    h.credentials.next = { CredentialLoad.Found(NasCredentials("qualcun-altro", GOOD_PASSWORD.toCharArray())) }
    assertThat(h.touch().errorOrNull()).isEqualTo(NasError.CredentialsMissing)
    assertThat(h.backend.authenticateCalls.get()).isEqualTo(0)
    assertThat(h.breaker.current()).isEqualTo(BreakerState.Closed(BreakerRecord.FIRST_GENERATION))
  }

  /**
   * Verifica funzionale, RE-9: con la chiave del Keystore invalidata (E-S9) ogni lavoro riapriva un socket e riprovava, e lo stato
   * diceva "NAS non raggiungibile". Ora la richiesta della password e' una sola e resta ferma: nessuna connessione, nessuna
   * autenticazione (mai una password sbagliata verso il NAS: l'account non rischia di bloccarsi) finche' una password nuova non c'e'.
   */
  @Test
  fun chiaveInvalidata_laPasswordSiChiedeUnaVoltaESenzaTentativiAutomatici() = runTest {
    val h = harness()
    h.credentials.next = { CredentialLoad.NeedsPassword }

    assertThat(h.touch().errorOrNull()).isEqualTo(NasError.CredentialsMissing)
    assertThat(h.manager.state.value).isEqualTo(NasConnState.Unavailable(NasError.CredentialsMissing))
    val connections = h.backend.tcpAttempts.get()

    // Cinque lavori dopo, anche su un'altra rete: nessun socket aperto, nessuna autenticazione, sempre lo stesso motivo.
    repeat(5) { assertThat(h.touch().errorOrNull()).isEqualTo(NasError.CredentialsMissing) }
    h.gate.allow(Via.VPN, h.backend.binding("vpn-1"))
    assertThat(h.touch().errorOrNull()).isEqualTo(NasError.CredentialsMissing)
    assertThat(h.backend.tcpAttempts.get()).isEqualTo(connections)
    assertThat(h.backend.authenticateCalls.get()).isEqualTo(0)
    assertThat(h.breaker.current()).isEqualTo(BreakerState.Closed(BreakerRecord.FIRST_GENERATION))

    // L'utente salva una password nuova (da qualunque pagina: la sessione se ne accorge leggendola): un tentativo, riuscito.
    h.credentials.next = { CredentialLoad.Found(NasCredentials(USER, GOOD_PASSWORD.toCharArray())) }
    assertThat(h.touch().errorOrNull()).isNull()
    assertThat(h.backend.authenticateCalls.get()).isEqualTo(1)
    assertThat(h.manager.state.value).isInstanceOf(NasConnState.Ready::class.java)
  }

  @Test
  fun chiaveInvalidata_unGuastoPasseggeroDelKeystoreNonEMaiServeLaPassword() = runTest {
    val h = harness()
    h.credentials.next = { CredentialLoad.NeedsPassword }
    assertThat(h.touch().errorOrNull()).isEqualTo(NasError.CredentialsMissing)

    // Telefono riavviato e non ancora sbloccato: il Keystore non risponde. Non e' "serve la password": si riprova come sempre.
    h.credentials.next = { CredentialLoad.Unavailable }
    assertThat(h.touch().errorOrNull()).isEqualTo(NasError.Unexpected(null))
    assertThat(h.backend.authenticateCalls.get()).isEqualTo(0)
  }

  @Test
  fun epermSullaConnessioneEReteLocaleNegataNonNasSpento() = runTest {
    val h = harness()
    h.backend.beforeOp = { op, _ ->
      if (op == SimOp.CONNECT) throw java.net.ConnectException("connect failed: EPERM (Operation not permitted)")
    }
    assertThat(h.touch().errorOrNull()).isEqualTo(NasError.LocalNetworkBlocked)
  }

  @Test
  fun sorgenteDiCredenzialiGuastaNessunTentativoNessunaEccezione() = runTest {
    val h = harness()
    h.credentials.next = { throw IllegalStateException("Keystore non disponibile") }
    assertThat(h.touch().errorOrNull()).isEqualTo(NasError.Unexpected(null))
    assertThat(h.backend.authenticateCalls.get()).isEqualTo(0)
    assertThat(h.breaker.current()).isEqualTo(BreakerState.Closed(BreakerRecord.FIRST_GENERATION))
  }

  @Test
  fun sessioneProntaConLeInformazioniPerLaDiagnostica() = runTest {
    val h = harness()
    h.backend.timeOffsetMs = -1500
    assertThat(h.touch().errorOrNull()).isNull()
    val ready = h.manager.state.value as NasConnState.Ready
    assertThat(ready.info.dialect).isEqualTo(SmbDialect.SMB_3_1_1)
    assertThat(ready.info.encrypted).isTrue()
    assertThat(ready.info.cipher).isEqualTo("AES_128_GCM")
    assertThat(ready.info.serverGuid).isEqualTo(h.backend.serverGuid)
    assertThat(ready.info.nasTimeOffsetMs).isEqualTo(-1500)
    assertThat(ready.info.via).isEqualTo(Via.HOME_LAN)
    assertThat(h.manager.lastNegotiation.value?.encryptionOffered).isTrue()
    assertThat(h.manager.lastDiskTouchAt.value).isNotNull()
  }

  // --- Una connessione per account ----------------------------------------------------------------------------

  @Test
  fun unaSolaConnessioneEUnaSolaAutenticazionePerMoltiChiamanti() = runTest {
    val h = harness()
    val results = (1..20).map { async { h.touch() } }.awaitAll()
    assertThat(results.map { it.errorOrNull() }.toSet()).containsExactly(null)
    assertThat(h.backend.tcpAttempts.get()).isEqualTo(1)
    assertThat(h.backend.authenticateCalls.get()).isEqualTo(1)
    assertThat(h.backend.treeConnects.get()).isEqualTo(1)
  }

  // --- Riconnessione ------------------------------------------------------------------------------------------

  @Test
  fun dopoUnaCadutaTraDueOperazioniSiRiconnetteDaSola() = runTest {
    val h = harness()
    h.backend.put(SHARE, "foto/a.jpg", byteArrayOf(1, 2))
    h.manager.browser(SHARE).list(SharePath.of("foto"), 10).value()
    h.backend.dropAllConnections()
    assertThat(h.manager.browser(SHARE).list(SharePath.of("foto"), 10).value().entries).hasSize(1)
    assertThat(h.backend.tcpAttempts.get()).isEqualTo(2)
    assertThat(h.backend.authenticateCalls.get()).isEqualTo(2)
    assertThat(h.manager.state.value).isInstanceOf(NasConnState.Ready::class.java)
  }

  @Test
  fun cadutaDuranteUnOperazioneIdempotenteSiRiprova() = runTest {
    val h = harness()
    h.backend.put(SHARE, "foto/a.jpg", byteArrayOf(1, 2))
    var dropped = false
    h.backend.beforeOp = { op, _ ->
      if (op == SimOp.OPEN_DIRECTORY && !dropped) {
        dropped = true
        h.backend.dropAllConnections()
      }
    }
    assertThat(h.manager.browser(SHARE).list(SharePath.of("foto"), 10).value().entries).hasSize(1)
    assertThat(h.backoffs).containsExactly(1.seconds)
    assertThat(h.backend.authenticateCalls.get()).isEqualTo(2)
  }

  @Test
  fun cadutePersistentiAlMassimoDueNuoviTentativi() = runTest {
    val h = harness()
    h.backend.beforeOp = { op, _ -> if (op == SimOp.OPEN_DIRECTORY) h.backend.dropAllConnections() }
    assertThat(h.manager.browser(SHARE).list(null, 10).errorOrNull()).isEqualTo(NasError.Timeout)
    assertThat(h.backoffs).containsExactly(1.seconds, 2.seconds).inOrder()
    assertThat(h.backend.tcpAttempts.get()).isEqualTo(3)
    // La password e' gia' verificata: le ricostruzioni non aprono l'interruttore.
    assertThat(h.breaker.current()).isInstanceOf(BreakerState.Closed::class.java)
  }

  // --- Invalidazione -----------------------------------------------------------------------------------------

  @Test
  fun cambioDiReteChiudeSubitoLaSessione() = runTest {
    val h = harness()
    h.touch()
    assertThat(h.backend.openConnections).isEqualTo(1)
    h.gate.allow(Via.VPN, h.backend.binding("vpn-1"))
    runCurrent()
    assertThat(h.backend.openConnections).isEqualTo(0)
    assertThat(h.manager.state.value).isEqualTo(NasConnState.Idle)

    h.touch()
    assertThat(h.backend.connectedNetworks).containsExactly(HOME_WIFI, "vpn-1").inOrder()
    assertThat((h.manager.state.value as NasConnState.Ready).info.via).isEqualTo(Via.VPN)
  }

  @Test
  fun percorsoNegatoChiudeLaSessioneENonRiconnette() = runTest {
    val h = harness()
    h.touch()
    h.gate.deny(DenyReason.FOREIGN_VPN)
    runCurrent()
    assertThat(h.backend.openConnections).isEqualTo(0)
    assertThat(h.touch().errorOrNull()).isEqualTo(NasError.NotAllowedHere(DenyReason.FOREIGN_VPN))
    assertThat(h.backend.tcpAttempts.get()).isEqualTo(1)
  }

  @Test
  fun ilCambioDiReteSiVedeAncheSenzaAspettareIlCallback() = runTest {
    val h = harness()
    h.touch()
    // Il collector del percorso non ha ancora girato: il controllo prima dell'operazione basta.
    h.gate.allow(Via.VPN, h.backend.binding("vpn-1"))
    h.touch()
    assertThat(h.backend.connectedNetworks).containsExactly(HOME_WIFI, "vpn-1").inOrder()
  }

  @Test
  fun invalidazioneEsplicitaEInattivita() = runTest {
    val h = harness()
    h.touch()
    h.manager.invalidate(InvalidateReason.USER_REQUEST)
    assertThat(h.backend.openConnections).isEqualTo(0)
    assertThat(h.manager.state.value).isEqualTo(NasConnState.Idle)

    h.touch()
    assertThat(h.backend.openConnections).isEqualTo(1)
    advanceTimeBy(4.minutes)
    runCurrent()
    assertThat(h.backend.openConnections).isEqualTo(1)
    advanceTimeBy(1.minutes + 1.seconds)
    runCurrent()
    assertThat(h.backend.openConnections).isEqualTo(0)
    assertThat(h.manager.state.value).isEqualTo(NasConnState.Idle)
  }
}
