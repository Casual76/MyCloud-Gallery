package dev.pampa.mycloudgallery.core.family.onboarding

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.family.contract.OnboardingPath
import dev.pampa.mycloudgallery.core.family.contract.OnboardingProblem
import dev.pampa.mycloudgallery.core.family.contract.OnboardingStep
import dev.pampa.mycloudgallery.core.family.contract.StepInput
import dev.pampa.mycloudgallery.core.family.contract.StepResult
import dev.pampa.mycloudgallery.core.family.support.OnboardingFixture
import dev.pampa.mycloudgallery.core.family.support.Reports
import dev.pampa.mycloudgallery.core.family.support.TEST_GUID
import dev.pampa.mycloudgallery.core.family.support.TEST_IP
import dev.pampa.mycloudgallery.core.family.support.TEST_ULA
import dev.pampa.mycloudgallery.core.family.support.TamperedFileSystem
import dev.pampa.mycloudgallery.core.family.support.TamperingSessions
import dev.pampa.mycloudgallery.core.family.support.TestFamily
import dev.pampa.mycloudgallery.core.family.support.expectAdvanced
import dev.pampa.mycloudgallery.core.family.support.runFounderUntil
import dev.pampa.mycloudgallery.core.family.support.shownCode
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NegotiationFacts
import dev.pampa.mycloudgallery.core.nas.contract.SmbDialect
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.crypto.RecoveryCode
import dev.pampa.mycloudgallery.core.trust.crypto.RecoveryKit
import dev.pampa.mycloudgallery.core.trust.crypto.VaultRefusal
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.NasRead
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import dev.pampa.mycloudgallery.core.trust.registry.RegistryVerifier
import dev.pampa.mycloudgallery.core.trust.registry.TrustAnchor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Il passo 12 (chiave di famiglia e kit) e il passo 14: l'ordine fisso (kit riletto e riaperto col codice digitato
 * PRIMA della fondazione), le scritture idempotenti, la ripresa a meta' e "famiglia gia' esistente".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FamilyKeyStepTest {

  private fun problem(p: OnboardingProblem) = StepResult.Problem(p)

  private suspend fun OnboardingFixture.atFamilyKey(): DefaultOnboardingCoordinator {
    val c = coordinator()
    runFounderUntil(c, OnboardingStep.FAMILY_KEY)
    return c
  }

  private fun OnboardingFixture.commonFs() = env.nas.share("NAS")

  private fun OnboardingFixture.kitBytes(): ByteArray {
    val record = store.snapshot.foundation!!
    val fingerprint = env.familyKeys.stored!!.publicKey.fingerprint
    val read = env.nas.share("alessio").read(NasLayout.recoveryKitFile(record.familyId, fingerprint), 4096)
    return (read as NasRead.Data).bytes
  }

  @Test
  fun ilPasso12SiFaNellOrdineFissoELaFamigliaNasceDopoIlKit() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.atFamilyKey()
    f.env.nas.calls.clear()

    // (a) e (b): la chiave e' nella cassaforte e il codice e' pronto, ma sul NAS non e' ancora successo niente.
    val code = c.shownCode()
    assertThat(f.env.familyKeys.stored).isNotNull()
    assertThat(f.env.nas.calls).isEmpty()
    assertThat(code.split('-')).hasSize(7)
    assertThat(code.split('-').all { it.length == 4 }).isTrue()

    // (c), (d), (e): l'utente riscrive il codice per intero.
    val typed = StepInput.RecoveryCodeTyped(code.toCharArray())
    val result = expectAdvanced(c.submit(typed))
    assertThat(result.next).isEqualTo(OnboardingStep.SECURITY_NOTICE)
    assertThat(typed.code.all { it == '\u0000' }).isTrue()

    // Prima l'area privata (kit scritto, riletto), poi quella comune (fondazione): mai il contrario.
    assertThat(f.env.nas.calls).containsExactly("alessio" to Purpose.ONBOARDING, "NAS" to Purpose.ONBOARDING).inOrder()

    // Il kit sul NAS si apre con il codice digitato e restituisce la stessa chiave di famiglia.
    val record = f.store.snapshot.foundation!!
    val key = f.env.familyKeys.stored!!
    val opened = RecoveryKit.open(f.kitBytes(), RecoveryCode.parse(code)!!, record.familyId, key.publicKey.fingerprint)
    assertThat(opened).isInstanceOf(Verdict.Accepted::class.java)
    assertThat((opened as Verdict.Accepted).value.familyKey.publicKey).isEqualTo(key.publicKey)

    // Il registro versione 1 e' firmato dalla chiave di famiglia e si verifica con l'ancora (id, impronta, versione).
    val registryBytes = (f.commonFs().read(NasLayout.registryFile(1), 100_000) as NasRead.Data).bytes
    val anchor = TrustAnchor.create(record.familyId, key.publicKey.fingerprint, 1)!!
    val state = (RegistryVerifier.accept(registryBytes, null, anchor) as Verdict.Accepted).value
    val founder = state.registry.devices.single()
    assertThat(founder.admin).isTrue()
    assertThat(founder.status).isEqualTo(DeviceStatus.APPROVED)
    assertThat(founder.nasUser).isEqualTo("alessio")
    assertThat(founder.name).isEqualTo("Telefono di Alessio")
    assertThat(founder.key).isEqualTo(f.env.deviceKeys.deviceSigner().publicKey)
    assertThat(state.registry.issuedAt).isEqualTo(f.env.nasNow)

    // La configurazione di famiglia firmata (passo 12(e)): IP e nome del NAS, GUID, ULA, area comune, esclusioni,
    // alimentazione, preset e qualita' di default.
    val config = state.registry.config
    assertThat(config.nasHost).isEqualTo(TEST_IP)
    assertThat(config.nasName).isEqualTo("MyCloudEX2Ultra.fritz.box")
    assertThat(config.nasGuid).isEqualTo(TEST_GUID)
    assertThat(config.homeUla).containsExactly(TEST_ULA)
    assertThat(config.commonShare).isEqualTo("NAS")
    assertThat(config.commonExclusions).containsExactly("Film/Serie", "Musica").inOrder()
    assertThat(config.nasPower).isEqualTo("always")
    assertThat(config.defaults.preset).isEqualTo("balanced")
    assertThat(config.defaults.quality).isEqualTo("standard")

    // E' stato salvato lo stato fidato (ancora) e il registro locale e' stato riletto.
    val trust = f.env.trustState.snapshot!!
    assertThat(trust.registryBytes).isEqualTo(registryBytes)
    assertThat(trust.familyKeyFingerprint).isEqualTo(key.publicKey.fingerprint)
    assertThat(trust.minRegistryVersion).isEqualTo(1L)
    assertThat(f.env.registry.refreshes).containsExactly(Purpose.ONBOARDING)
    assertThat(record.stage).isEqualTo(FamilyKeyStage.FOUNDED)
  }

  @Test
  fun ilCodiceSiPuoRimostrareEIGruppiSiAzzeranoAllaChiusura() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.atFamilyKey()
    val first = c.recoveryCode()
    val shown = first.groups.joinToString("-") { String(it) }
    // Revisione 2, F3: il foglio porta anche l'impronta della chiave di famiglia appena generata.
    assertThat(first.familyFingerprint).isEqualTo(f.env.familyKeys.stored!!.publicKey.fingerprint)
    first.close()
    assertThat(first.groups.all { group -> group.all { it == '\u0000' } }).isTrue()
    // Riaprendo la schermata il codice e' lo stesso (la chiave non cambia), con gruppi nuovi.
    assertThat(c.shownCode()).isEqualTo(shown)
    assertThat(f.env.familyKeys.stores).isEqualTo(1)
    assertThat(first.toString()).doesNotContain(shown.take(4))
  }

  @Test
  fun unCodiceSbagliatoNonTocaIlNasEPermetteDiRiprovare() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.atFamilyKey()
    val code = c.shownCode()
    f.env.nas.calls.clear()

    val last = code.last()
    val wrong = code.dropLast(1) + (if (last == 'A') 'B' else 'A')
    assertThat(c.submit(StepInput.RecoveryCodeTyped(wrong.toCharArray()))).isEqualTo(problem(OnboardingProblem.RECOVERY_CODE_MISMATCH))
    assertThat(c.submit(StepInput.RecoveryCodeTyped(code.take(20).toCharArray()))).isEqualTo(problem(OnboardingProblem.RECOVERY_CODE_MISMATCH))
    assertThat(c.submit(StepInput.RecoveryCodeTyped(CharArray(0)))).isEqualTo(problem(OnboardingProblem.RECOVERY_CODE_MISMATCH))
    assertThat(c.submit(StepInput.RecoveryCodeTyped("????-????-????".toCharArray()))).isEqualTo(problem(OnboardingProblem.RECOVERY_CODE_MISMATCH))
    assertThat(f.env.nas.calls).isEmpty()
    assertThat(f.store.snapshot.foundation).isNull()
    assertThat(f.current).isEqualTo(OnboardingStep.FAMILY_KEY)

    // Minuscole, spazi invece dei trattini: e' lo stesso codice scritto a mano.
    expectAdvanced(c.submit(StepInput.RecoveryCodeTyped(code.lowercase().replace('-', ' ').toCharArray())))
  }

  @Test
  fun seIlKitNonSiRiapreLaFamigliaNonNasce() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.atFamilyKey()
    val code = c.shownCode()
    // Il NAS restituisce, al posto del kit appena scritto, un file qualunque.
    f.env.sessions = TamperingSessions(f.env.nas) { share, fs ->
      if (share == "alessio") TamperedFileSystem(fs, onRead = { _, _ -> NasRead.Data(ByteArray(200) { 1 }) }) else fs
    }
    assertThat(c.submit(StepInput.RecoveryCodeTyped(code.toCharArray()))).isEqualTo(problem(OnboardingProblem.RECOVERY_KIT_UNVERIFIED))
    assertThat(f.commonFs().exists(NasLayout.registryFile(1))).isFalse()
    assertThat(f.commonFs().fileCount()).isEqualTo(0)
    assertThat(f.store.snapshot.foundation).isNull()
    assertThat(f.env.trustState.snapshot).isNull()

    // Un kit che si apre solo con un altro codice (messo da altri, o di un tentativo precedente) vale lo stesso come "non verificato".
    f.env.sessions = TamperingSessions(f.env.nas) { share, fs ->
      if (share == "alessio") {
        TamperedFileSystem(
          fs,
          onRead = { path, _ ->
            val key = f.env.familyKeys.stored!!
            NasRead.Data(RecoveryKit.seal(key, path.components[2], RecoveryCode.generate(), f.env.nasNow))
          },
        )
      } else {
        fs
      }
    }
    assertThat(c.submit(StepInput.RecoveryCodeTyped(code.toCharArray()))).isEqualTo(problem(OnboardingProblem.RECOVERY_KIT_UNVERIFIED))
    assertThat(f.commonFs().fileCount()).isEqualTo(0)

    // Il kit sparito subito dopo la scrittura.
    f.env.sessions = TamperingSessions(f.env.nas) { share, fs ->
      if (share == "alessio") TamperedFileSystem(fs, onRead = { _, _ -> NasRead.NotFound }) else fs
    }
    assertThat(c.submit(StepInput.RecoveryCodeTyped(code.toCharArray()))).isEqualTo(problem(OnboardingProblem.RECOVERY_KIT_UNVERIFIED))
    assertThat(f.commonFs().fileCount()).isEqualTo(0)

    // Tornato tutto normale il codice digitato basta: il kit gia' scritto si riapre ed e' lo stesso (stessi byte).
    f.env.sessions = f.env.nas
    expectAdvanced(c.submit(StepInput.RecoveryCodeTyped(code.toCharArray())))
    assertThat(f.commonFs().exists(NasLayout.registryFile(1))).isTrue()
  }

  @Test
  fun daQuiCeGiaUnaFamigliaSiPassaARecuperaLAdminOAEntraComeFamiliare() = runTest(UnconfinedTestDispatcher()) {
    // Trovato sul campo (19/10): i due tasti del pannello non facevano niente, perche' il cambio di percorso era accettato solo al passo 1.
    for (path in listOf(OnboardingPath.RECOVER_ADMIN, OnboardingPath.JOIN_QR)) {
      val f = OnboardingFixture(this)
      val c = f.coordinator()
      f.runFounderUntil(c, OnboardingStep.FAMILY_KEY)
      TestFamily(f.commonFs())
      assertThat(c.submit(StepInput.RecoveryCodeTyped(c.shownCode().toCharArray()))).isEqualTo(problem(OnboardingProblem.FAMILY_ALREADY_EXISTS))

      expectAdvanced(c.submit(StepInput.ChoosePath(path)))
      assertThat(f.store.snapshot.progress.path).isEqualTo(path)
      assertThat(f.current).isNotEqualTo(OnboardingStep.FAMILY_KEY)
      // La chiave della famiglia mai fondata non resta nella cassaforte.
      assertThat(f.env.familyKeys.stored).isNull()
    }
  }

  @Test
  fun unaFamigliaGiaEsistenteInFondazioneSiRiconosceESiTornaAlPasso4() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.coordinator()
    f.runFounderUntil(c, OnboardingStep.FAMILY_KEY)
    // Dopo il passo 4, qualcun altro fonda una famiglia nella stessa area.
    val other = TestFamily(f.commonFs())
    val otherBytes = (f.commonFs().read(NasLayout.registryFile(1), 100_000) as NasRead.Data).bytes
    val code = c.shownCode()

    assertThat(c.submit(StepInput.RecoveryCodeTyped(code.toCharArray()))).isEqualTo(problem(OnboardingProblem.FAMILY_ALREADY_EXISTS))
    // Il registro dell'altra famiglia non e' stato toccato e il nostro dispositivo non si crede configurato.
    assertThat((f.commonFs().read(NasLayout.registryFile(1), 100_000) as NasRead.Data).bytes).isEqualTo(otherBytes)
    assertThat(f.env.trustState.snapshot).isNull()
    assertThat(f.current).isEqualTo(OnboardingStep.FAMILY_KEY)
    // Il kit, invece, c'e' gia' (viene prima della fondazione) e la chiave e' al sicuro.
    assertThat(f.store.snapshot.foundation!!.stage).isEqualTo(FamilyKeyStage.KIT_VERIFIED)
    assertThat(f.env.familyKeys.stored).isNotNull()
    assertThat(other.familyId).isNotEqualTo(f.store.snapshot.foundation!!.familyId)

    // "Si torna al passo 4": un'altra area comune, e il kit gia' verificato non si rifa.
    while (f.current != OnboardingStep.AREAS) c.back()
    assertThat(expectAdvanced(c.submit(StepInput.Areas("Public", "alessio"))).next).isEqualTo(OnboardingStep.FAMILY_KEY)
    assertThat(c.recoveryCode().groups).isEmpty()
    expectAdvanced(c.submit(StepInput.RecoveryCodeTyped(CharArray(0))))
    assertThat(f.env.nas.share("Public").exists(NasLayout.registryFile(1))).isTrue()
    // La fondazione dice la nuova area comune, non quella del primo tentativo.
    val bytes = (f.env.nas.share("Public").read(NasLayout.registryFile(1), 100_000) as NasRead.Data).bytes
    val record = f.store.snapshot.foundation!!
    val anchor = TrustAnchor.create(record.familyId, f.env.familyKeys.stored!!.publicKey.fingerprint, 1)!!
    val state = (RegistryVerifier.accept(bytes, null, anchor) as Verdict.Accepted).value
    assertThat(state.registry.config.commonShare).isEqualTo("Public")
    assertThat(f.env.nas.share("alessio").publishAttempts).isEqualTo(1) // il kit e' stato scritto una volta sola
  }

  @Test
  fun dopoFamigliaGiaEsistenteSiPuoEntrareComeFamiliareEIlKitOrfanoNonRestaNellaCassaforte() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.coordinator()
    f.runFounderUntil(c, OnboardingStep.FAMILY_KEY)
    TestFamily(f.commonFs())
    c.submit(StepInput.RecoveryCodeTyped(c.shownCode().toCharArray()))
    assertThat(f.env.familyKeys.stored).isNotNull()

    while (f.current != OnboardingStep.WELCOME) c.back()
    expectAdvanced(c.submit(StepInput.ChoosePath(OnboardingPath.JOIN_QR)))
    assertThat(f.env.familyKeys.stored).isNull()
    assertThat(f.store.snapshot.foundation).isNull()
    assertThat(f.store.snapshot.progress.path).isEqualTo(OnboardingPath.JOIN_QR)
  }

  @Test
  fun laScritturaDellaFondazioneEIdempotenteSeLaRispostaSiPerde() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.atFamilyKey()
    val code = c.shownCode()
    // Il NAS scrive il registro ma la risposta non arriva (timeout subito dopo la scrittura).
    var failures = 1
    f.env.sessions = TamperingSessions(f.env.nas) { share, fs ->
      if (share == "NAS") TamperedFileSystem(fs, failReadsAfterPublish = failures-- > 0) else fs
    }
    assertThat(c.submit(StepInput.RecoveryCodeTyped(code.toCharArray()))).isEqualTo(problem(OnboardingProblem.NAS_WRITE_FAILED))
    assertThat(f.commonFs().exists(NasLayout.registryFile(1))).isTrue()
    assertThat(f.store.snapshot.foundation!!.stage).isEqualTo(FamilyKeyStage.KIT_VERIFIED)
    val written = (f.commonFs().read(NasLayout.registryFile(1), 100_000) as NasRead.Data).bytes

    // Si riprova: stessi byte, ALREADY_EXISTS vale come successo e non si scrive niente di nuovo.
    expectAdvanced(c.submit(StepInput.RecoveryCodeTyped(CharArray(0))))
    assertThat((f.commonFs().read(NasLayout.registryFile(1), 100_000) as NasRead.Data).bytes).isEqualTo(written)
    assertThat(f.store.snapshot.foundation!!.stage).isEqualTo(FamilyKeyStage.FOUNDED)
    assertThat(f.env.nas.share("alessio").publishAttempts).isEqualTo(1)
    assertThat(f.env.trustState.snapshot!!.registryBytes).isEqualTo(written)
  }

  @Test
  fun unaFondazioneGiaScrittaDaMeEFirmataDiNuovoDopoUnRiavvioVaBene() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.atFamilyKey()
    val code = c.shownCode()
    // Il NAS scrive il registro ma la risposta si perde; poi il processo muore (il registro firmato in memoria sparisce).
    var failures = 1
    f.env.sessions = TamperingSessions(f.env.nas) { share, fs ->
      if (share == "NAS") TamperedFileSystem(fs, failReadsAfterPublish = failures-- > 0) else fs
    }
    assertThat(c.submit(StepInput.RecoveryCodeTyped(code.toCharArray()))).isEqualTo(problem(OnboardingProblem.NAS_WRITE_FAILED))
    val written = (f.commonFs().read(NasLayout.registryFile(1), 100_000) as NasRead.Data).bytes

    // Un coordinatore nuovo firma la fondazione da capo (un'ora diversa, un'altra firma): non e' lo stesso file, ma e' roba nostra.
    f.env.nasNow += 5 * 60 * 1000L
    val restarted = f.coordinator()
    expectAdvanced(restarted.submit(StepInput.RecoveryCodeTyped(CharArray(0))))
    assertThat((f.commonFs().read(NasLayout.registryFile(1), 100_000) as NasRead.Data).bytes).isEqualTo(written)
    assertThat(f.env.trustState.snapshot!!.registryBytes).isEqualTo(written)
    assertThat(f.store.snapshot.foundation!!.stage).isEqualTo(FamilyKeyStage.FOUNDED)
  }

  @Test
  fun seSiInterrompeFraKitEFondazioneSiRiparteDaLaFondazione_E_O9() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.atFamilyKey()
    val code = c.shownCode()
    f.commonFs().failWith = NasError.Timeout
    assertThat(c.submit(StepInput.RecoveryCodeTyped(code.toCharArray()))).isEqualTo(problem(OnboardingProblem.NAS_WRITE_FAILED))
    val record = f.store.snapshot.foundation!!
    assertThat(record.stage).isEqualTo(FamilyKeyStage.KIT_VERIFIED)

    // Il processo muore. Il NAS torna: un coordinatore nuovo trova il kit gia' verificato.
    f.commonFs().failWith = null
    val restarted = f.coordinator()
    assertThat(restarted.progress.value.current).isEqualTo(OnboardingStep.FAMILY_KEY)
    assertThat(restarted.recoveryCode().groups).isEmpty() // niente piu' da mostrare
    expectAdvanced(restarted.submit(StepInput.RecoveryCodeTyped(CharArray(0))))

    assertThat(f.store.snapshot.foundation).isEqualTo(FoundationRecord(FamilyKeyStage.FOUNDED, record.familyId, record.keyPublic))
    assertThat(f.env.familyKeys.stores).isEqualTo(1) // la chiave non e' stata rigenerata
    assertThat(f.env.nas.share("alessio").publishAttempts).isEqualTo(1) // il kit non e' stato riscritto
    assertThat(f.commonFs().exists(NasLayout.registryFile(1))).isTrue()
  }

  @Test
  fun seSiInterrompeFraIlCodiceELaRiscritturaSiRicominciaConUnaChiaveNuova() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.atFamilyKey()
    val oldCode = c.shownCode()
    val oldKey = f.env.familyKeys.stored!!.publicKey

    val restarted = f.coordinator()
    // Il codice sul foglio non apre niente: non e' mai stato scritto un kit con quel codice.
    assertThat(restarted.submit(StepInput.RecoveryCodeTyped(oldCode.toCharArray()))).isEqualTo(problem(OnboardingProblem.RECOVERY_CODE_MISMATCH))
    val newCode = restarted.shownCode()
    assertThat(newCode).isNotEqualTo(oldCode)
    assertThat(f.env.familyKeys.stored!!.publicKey).isNotEqualTo(oldKey)
    assertThat(f.env.familyKeys.stores).isEqualTo(2)
    expectAdvanced(restarted.submit(StepInput.RecoveryCodeTyped(newCode.toCharArray())))
  }

  @Test
  fun senzaBloccoSchermoLaChiaveDiFamigliaNonSiConserva_E_S3() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.atFamilyKey()
    f.env.familyKeys.secure = false
    assertThat(c.recoveryCode().groups).isEmpty()
    assertThat(c.submit(StepInput.RecoveryCodeTyped("ABCD-EFGH".toCharArray()))).isEqualTo(problem(OnboardingProblem.CREDENTIALS_NOT_SAVED))
    assertThat(f.env.nas.calls.none { it.first == "alessio" }).isTrue()
    // Messo il blocco schermo, si riparte.
    f.env.familyKeys.secure = true
    val code = c.shownCode()
    assertThat(code).isNotEmpty()
    expectAdvanced(c.submit(StepInput.RecoveryCodeTyped(code.toCharArray())))
  }

  /**
   * Fase 10, M3 (decisione 2(a)): "Crea la chiave" chiede l'impronta o il PIN per custodirla. Annullato: nessuna chiave, niente sul NAS,
   * e la pagina riceve il perche' (non "passo non disponibile"); al tasto di nuovo si riprova.
   */
  @Test
  fun ilFoglioAnnullatoNonCustodisceNiente_laPaginaSaPerche_eSiRiprova() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.atFamilyKey()
    f.env.familyKeys.sheet = VaultRefusal.CANCELLED

    val refused = c.recoveryCode()

    assertThat(refused.groups).isEmpty()
    assertThat(refused.problem).isEqualTo(OnboardingProblem.FAMILY_KEY_NOT_CONFIRMED)
    assertThat(f.env.familyKeys.stored).isNull()
    assertThat(c.submit(StepInput.RecoveryCodeTyped("ABCD-EFGH".toCharArray()))).isEqualTo(problem(OnboardingProblem.FAMILY_KEY_NOT_CONFIRMED))
    assertThat(f.env.nas.calls.none { it.first == "alessio" }).isTrue()

    f.env.familyKeys.sheet = null
    val code = c.shownCode()
    assertThat(code).isNotEmpty()
    expectAdvanced(c.submit(StepInput.RecoveryCodeTyped(code.toCharArray())))
    // Un foglio per tentativo di custodia; la fondazione firma con la chiave appena creata, ancora in memoria (nessun foglio in piu').
    assertThat(f.env.familyKeys.sheets).isEqualTo(2)
  }

  @Test
  fun unaFamigliaGiaFondataNonSiRifaSeSiTornaIndietro() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.atFamilyKey()
    expectAdvanced(c.submit(StepInput.RecoveryCodeTyped(c.shownCode().toCharArray())))
    val registry = (f.commonFs().read(NasLayout.registryFile(1), 100_000) as NasRead.Data).bytes
    f.env.nas.calls.clear()

    c.back()
    assertThat(f.current).isEqualTo(OnboardingStep.FAMILY_KEY)
    assertThat(c.recoveryCode().groups).isEmpty()
    expectAdvanced(c.submit(StepInput.RecoveryCodeTyped(CharArray(0))))
    assertThat(f.env.nas.calls).isEmpty()
    assertThat(f.env.familyKeys.stores).isEqualTo(1)
    assertThat((f.commonFs().read(NasLayout.registryFile(1), 100_000) as NasRead.Data).bytes).isEqualTo(registry)
    // E dopo la fondazione il percorso non si cambia piu' (e indietro non si torna oltre le cartelle: aree, account e
    // indirizzo sono nella configurazione firmata).
    repeat(20) { c.back() }
    assertThat(f.current).isEqualTo(OnboardingStep.FOLDERS)
    assertThat(c.submit(StepInput.ChoosePath(OnboardingPath.JOIN_QR))).isEqualTo(problem(OnboardingProblem.STEP_NOT_AVAILABLE))
  }

  @Test
  fun inNomiDelDispositivoImpossibiliSiRipieganoSuTestiValidi() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    f.env.deviceDescription = DeviceDescription(model = "", defaultName = "x".repeat(100))
    val c = f.atFamilyKey()
    expectAdvanced(c.submit(StepInput.RecoveryCodeTyped(c.shownCode().toCharArray())))
    val registryBytes = (f.commonFs().read(NasLayout.registryFile(1), 100_000) as NasRead.Data).bytes
    val record = f.store.snapshot.foundation!!
    val anchor = TrustAnchor.create(record.familyId, f.env.familyKeys.stored!!.publicKey.fingerprint, 1)!!
    val founder = (RegistryVerifier.accept(registryBytes, null, anchor) as Verdict.Accepted).value.registry.devices.single()
    assertThat(founder.name).isEqualTo("Dispositivo admin")
    assertThat(founder.model).isEqualTo("Android")
  }

  @Test
  fun fuoriDalPasso12NonCEIlCodice() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.coordinator()
    assertThat(c.recoveryCode().groups).isEmpty() // benvenuto
    expectAdvanced(c.submit(StepInput.ChoosePath(OnboardingPath.JOIN_QR)))
    assertThat(c.recoveryCode().groups).isEmpty()
    assertThat(f.env.familyKeys.stored).isNull()
  }

  @Test
  fun unGuidInUnAltraFormaNonFaFallireLaFondazioneEUnoMaiuscoloSiNormalizza() = runTest(UnconfinedTestDispatcher()) {
    for ((raw, expected) in listOf("NON-UN-UUID" to null, TEST_GUID.uppercase() to TEST_GUID)) {
      val f = OnboardingFixture(this)
      f.env.tester.handler = { _, _, stages ->
        Reports.ok(stages, NegotiationFacts(SmbDialect.SMB_3_1_1, encryptionOffered = true, serverGuid = raw, timeOffsetMs = 0L))
      }
      val c = f.coordinator()
      f.runFounderUntil(c, OnboardingStep.FAMILY_KEY)
      expectAdvanced(c.submit(StepInput.RecoveryCodeTyped(c.shownCode().toCharArray())))
      val record = f.store.snapshot.foundation!!
      val anchor = TrustAnchor.create(record.familyId, f.env.familyKeys.stored!!.publicKey.fingerprint, 1)!!
      val bytes = (f.commonFs().read(NasLayout.registryFile(1), 100_000) as NasRead.Data).bytes
      val config = (RegistryVerifier.accept(bytes, null, anchor) as Verdict.Accepted).value.registry.config
      assertThat(config.nasGuid).isEqualTo(expected)
      // Il GUID memorizzato in locale resta quello della NEGOTIATE, com'e'.
      assertThat(f.store.snapshot.nas!!.guid).isEqualTo(raw)
    }
  }

  @Test
  fun cambiandoLeScelteDopoIlKitLaFondazioneSiFirmaDiNuovo() = runTest(UnconfinedTestDispatcher()) {
    val f = OnboardingFixture(this)
    val c = f.atFamilyKey()
    val code = c.shownCode()
    f.commonFs().failWith = NasError.Timeout
    assertThat(c.submit(StepInput.RecoveryCodeTyped(code.toCharArray()))).isEqualTo(problem(OnboardingProblem.NAS_WRITE_FAILED))
    f.commonFs().failWith = null
    // L'utente torna alle cartelle e cambia le esclusioni: il kit e' gia' a posto, la fondazione porta le scelte nuove.
    while (f.current != OnboardingStep.FOLDERS) c.back()
    expectAdvanced(c.submit(StepInput.Folders(setOf("Archivio/Vecchio"))))
    assertThat(f.current).isEqualTo(OnboardingStep.FAMILY_KEY)
    expectAdvanced(c.submit(StepInput.RecoveryCodeTyped(CharArray(0))))
    val record = f.store.snapshot.foundation!!
    val anchor = TrustAnchor.create(record.familyId, f.env.familyKeys.stored!!.publicKey.fingerprint, 1)!!
    val bytes = (f.commonFs().read(NasLayout.registryFile(1), 100_000) as NasRead.Data).bytes
    val config = (RegistryVerifier.accept(bytes, null, anchor) as Verdict.Accepted).value.registry.config
    assertThat(config.commonExclusions).containsExactly("Archivio/Vecchio")
  }

  @Test
  fun unIndirizzoIpv6EntraNelRegistroTraParentesiQuadre() {
    val nas = NasFacts("fd12:3456:789a::58", null, 445, "guid")
    assertThat(FamilyConfigFactory.hostOf(nas)).isEqualTo("[fd12:3456:789a::58]")
    assertThat(FamilyConfigFactory.ipOf("[fd12:3456:789a::58]")).isEqualTo("fd12:3456:789a::58")
    assertThat(FamilyConfigFactory.hostOf(NasFacts(TEST_IP, null, 445, "guid"))).isEqualTo(TEST_IP)
    assertThat(FamilyConfigFactory.create(OnboardingSnapshot.EMPTY, nas, "NAS").nasHost).isEqualTo("[fd12:3456:789a::58]")
  }
}
