package dev.pampa.mycloudgallery.core.backup.versions

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.BackupGateState
import dev.pampa.mycloudgallery.core.backup.contract.PhoneConfirmationCheck
import dev.pampa.mycloudgallery.core.backup.contract.PhoneWriteGrant
import dev.pampa.mycloudgallery.core.backup.contract.RestoreAvailability
import dev.pampa.mycloudgallery.core.backup.contract.RestoreOutcome
import dev.pampa.mycloudgallery.core.backup.contract.UploadKind
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.testing.TestConfirmations
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Fase 5B, P8 (D18, E-E2, E-E10, §2.4 `SWAP`): "Ripristina originale" dall'app ([DefaultVersionKeeper]). La conferma vale per il piano
 * ricalcolato adesso; lo scambio sul NAS sono due rinomine senza byte caricati; la copia del telefono modificata si riscrive solo con il
 * permesso del dialogo di sistema e con l'originale **riletto dal NAS e verificato**; negato il dialogo, il NAS si scambia e il telefono resta
 * com'era. Se l'originale non si puo' riportare, l'attuale torna al suo posto (mai un solito percorso vuoto a lavoro finito).
 */
class VersionKeeperTest {

  @get:Rule val folder = TemporaryFolder()

  private val share = VersionWorld.PRIVATE_SHARE
  private val versions = "Versioni/Backup/S25 di prova/2026/10"

  private class Setup(val w: VersionWorld, val seed: VersionWorld.Seeded, val v1Bytes: Sha256, val rewriter: PhoneRowRewriter, val keeper: DefaultVersionKeeper, val engine: NasVersionEngine)

  /** Un media modificato sul telefono e gia' salvato come versione: v1 al solito percorso, l'originale in Versioni/. */
  private suspend fun edited(ops: (VersionWorld) -> dev.pampa.mycloudgallery.core.nas.ops.backup.NasBackupOps = { it.ops }): Setup {
    val w = VersionWorld(folder.newFolder())
    val seed = w.seedSafe()
    w.editOnPhone(seed)
    val v1Bytes = w.phone.sha256(seed.phoneId!!)!!
    w.engine.planPhoneVersions(listOf(w.candidate(seed.mediaId, seed.phoneId)))
    w.runUntilQuiet()
    val engine = w.newEngine(ops = ops(w))
    val rewriter = PhoneRowRewriter(w.phone)
    val keeper = DefaultVersionKeeper(w.share, w.catalog, w.journal, engine, w.gate, w.clock, w.nas.reader, rewriter, w.staged)
    return Setup(w, seed, v1Bytes, rewriter, keeper, engine)
  }

  @Test
  fun senzaIlPermessoDelDialogoSiChiedeENienteCambia() = runTest {
    val s = edited()
    val plan = s.keeper.plan(s.seed.mediaId)
    assertThat(plan.restore).isEqualTo(RestoreAvailability.AVAILABLE)
    val before = s.w.files()
    val outcome = s.keeper.restoreOriginal(s.seed.mediaId, TestConfirmations.confirm(plan, s.w.clock.monotonic), writeGrant = null)
    assertThat(outcome).isEqualTo(RestoreOutcome.NeedsPhoneWrite(listOf(s.w.phone.ref(s.seed.phoneId!!)!!)))
    assertThat(s.w.files()).isEqualTo(before)
    assertThat(s.w.journal.all().none { it.kind == UploadKind.SWAP }).isTrue()
    assertThat(s.w.catalog.currentOf(s.seed.mediaId)).isNotEqualTo(s.seed.original)
  }

  @Test
  fun conIlPermessoSiScambiaSulNasESiRiscriveIlTelefonoConLOriginaleRiletto() = runTest {
    val s = edited()
    val before = s.w.snapshot()
    val plan = s.keeper.plan(s.seed.mediaId)
    val ref = s.w.phone.ref(s.seed.phoneId!!)!!
    s.rewriter.granted += s.seed.phoneId
    val outcome = s.keeper.restoreOriginal(s.seed.mediaId, TestConfirmations.confirm(plan, s.w.clock.monotonic), PhoneWriteGrant(setOf(ref)))
    assertThat(outcome).isEqualTo(RestoreOutcome.Queued)
    assertThat(s.w.journal.all().single { it.kind == UploadKind.SWAP }.state).isEqualTo(UploadState.DONE)
    // NAS: l'originale al solito percorso, v1 in Versioni/; telefono: l'originale.
    assertThat(s.w.sha(share, s.seed.usualPath.value)).isEqualTo(Sha256.of(s.seed.content))
    assertThat(s.w.sha(share, "$versions/IMG_1234 (versione del 2026-10-16 02.00).jpg")).isEqualTo(s.v1Bytes)
    assertThat(s.w.phone.sha256(s.seed.phoneId)).isEqualTo(Sha256.of(s.seed.content))
    assertThat(s.rewriter.replaced).containsExactly(s.seed.phoneId)
    // Catalogo: l'originale e' l'attuale.
    assertThat(s.w.catalog.currentOf(s.seed.mediaId)).isEqualTo(s.seed.original)
    assertThat(s.w.catalog.restores).isEqualTo(1)
    assertThat(s.w.violations(before, final = true)).isEmpty()
    assertThat(s.keeper.plan(s.seed.mediaId).restore).isEqualTo(RestoreAvailability.ALREADY_ORIGINAL)
  }

  @Test
  fun negatoIlDialogoSiScambiaSoloSulNasEIlTelefonoTieneLaModifica() = runTest {
    val s = edited()
    val plan = s.keeper.plan(s.seed.mediaId)
    // L'utente ha detto no al dialogo di sistema: un permesso che non copre la riga.
    val outcome = s.keeper.restoreOriginal(s.seed.mediaId, TestConfirmations.confirm(plan, s.w.clock.monotonic), PhoneWriteGrant(emptySet()))
    assertThat(outcome).isEqualTo(RestoreOutcome.Queued)
    assertThat(s.w.sha(share, s.seed.usualPath.value)).isEqualTo(Sha256.of(s.seed.content))
    assertThat(s.w.phone.sha256(s.seed.phoneId!!)).isEqualTo(s.v1Bytes)
    assertThat(s.rewriter.replaced).isEmpty()
    // "L'app dice quale copia ha cosa": il telefono ha la versione modificata, il NAS l'originale al solito percorso.
    val after = s.keeper.plan(s.seed.mediaId)
    assertThat(after.current!!.versionId).isEqualTo(s.seed.original)
    assertThat(after.versions.single { it.versionId != s.seed.original }.places)
      .containsExactly(dev.pampa.mycloudgallery.core.backup.contract.VersionPlace.PHONE, dev.pampa.mycloudgallery.core.backup.contract.VersionPlace.NAS_VERSIONS_FOLDER)
  }

  @Test
  fun laConfermaValePerIlPianoDiAdessoEPerPocoTempo() = runTest {
    val s = edited()
    val plan = s.keeper.plan(s.seed.mediaId)
    val confirmation = TestConfirmations.confirm(plan, s.w.clock.monotonic)
    // Troppo vecchia.
    s.w.clock.advance(11 * 60_000L)
    assertThat(s.keeper.restoreOriginal(s.seed.mediaId, confirmation, PhoneWriteGrant(emptySet())))
      .isEqualTo(RestoreOutcome.Rejected(PhoneConfirmationCheck.Expired))
    // Un'altra modifica dopo il foglio: il piano non e' piu' quello.
    val fresh = TestConfirmations.confirm(s.keeper.plan(s.seed.mediaId), s.w.clock.monotonic)
    s.w.editOnPhone(s.seed)
    assertThat(s.keeper.restoreOriginal(s.seed.mediaId, fresh, PhoneWriteGrant(emptySet())))
      .isEqualTo(RestoreOutcome.Rejected(PhoneConfirmationCheck.WrongPlan))
    assertThat(s.w.journal.all().none { it.kind == UploadKind.SWAP }).isTrue()
  }

  @Test
  fun quandoRipristinareNonSiPuo() = runTest {
    val w = VersionWorld(folder.newFolder())
    val keeper = DefaultVersionKeeper(w.share, w.catalog, w.journal, w.engine, w.gate, w.clock, w.nas.reader, PhoneRowRewriter(w.phone), w.staged)
    val only = w.seedSafe(name = "Uno.jpg")
    assertThat(keeper.plan(only.mediaId).restore).isEqualTo(RestoreAvailability.ONLY_ONE_VERSION)
    // Non e' un media proprio.
    val (other, version) = w.catalog.addMedia(size = 10, fullHash = null, mine = false)
    w.catalog.addVersion(other, dev.pampa.mycloudgallery.core.model.VersionKind.EDITED, 11, null)
    assertThat(keeper.plan(other).restore).isEqualTo(RestoreAvailability.NOT_MINE)
    assertThat(version).isNotNull()
    // Congelata, scritture ferme, sola lettura.
    val s = edited()
    val k = s.keeper
    s.w.gate.default = BackupGateState.FROZEN
    assertThat(k.plan(s.seed.mediaId).restore).isEqualTo(RestoreAvailability.FROZEN)
    s.w.gate.default = BackupGateState.WRITES_STOPPED
    assertThat(k.plan(s.seed.mediaId).restore).isEqualTo(RestoreAvailability.FROZEN)
    s.w.gate.default = BackupGateState.READ_ONLY_AREA
    assertThat(k.plan(s.seed.mediaId).restore).isEqualTo(RestoreAvailability.READ_ONLY_AREA)
    s.w.gate.default = BackupGateState.OPEN
    val plan = k.plan(s.seed.mediaId)
    s.w.gate.default = BackupGateState.FROZEN
    assertThat(k.restoreOriginal(s.seed.mediaId, TestConfirmations.confirm(plan, s.w.clock.monotonic), PhoneWriteGrant(emptySet())))
      .isEqualTo(RestoreOutcome.Unavailable(RestoreAvailability.FROZEN))
    s.w.gate.default = BackupGateState.OPEN
    // L'originale in Versioni/ non verificato: non si sposta un file di cui non si e' sicuri.
    val w2 = VersionWorld(folder.newFolder())
    val seed2 = w2.seedSafe()
    w2.editOnPhone(seed2)
    w2.engine.planPhoneVersions(listOf(w2.candidate(seed2.mediaId, seed2.phoneId!!)))
    w2.runUntilQuiet()
    w2.catalog.unverify(seed2.mediaId, seed2.original)
    val k2 = DefaultVersionKeeper(w2.share, w2.catalog, w2.journal, w2.engine, w2.gate, w2.clock, w2.nas.reader, PhoneRowRewriter(w2.phone), w2.staged)
    assertThat(k2.plan(seed2.mediaId).restore).isEqualTo(RestoreAvailability.ORIGINAL_NOT_SAFE)
    assertThat(w2.engine.planSwap(seed2.mediaId)).isNull()
  }

  /** L'originale in Versioni/ cambiato dal PC mentre lo si riporta: l'attuale torna al suo posto, lo scambio non e' fatto. */
  @Test
  fun lOriginaleCambiatoDalPcRiportaLAttualeAlSuoPosto() = runTest {
    var hooked: HookedOps? = null
    val s = edited { w -> HookedOps(w.ops).also { hooked = it } }
    val archivedOriginal = "$versions/IMG_1234 (originale).jpg"
    val fromPc = s.w.bytes(9_000)
    s.w.knownContents += Sha256.of(fromPc)
    hooked!!.beforeRestore = {
      hooked!!.beforeRestore = {}
      s.w.nas.modify(share, archivedOriginal, NasContent.of(fromPc))
    }
    val v1FileId = s.w.files().getValue(s.seed.usualPath.value).second
    val plan = s.keeper.plan(s.seed.mediaId)
    s.keeper.restoreOriginal(s.seed.mediaId, TestConfirmations.confirm(plan, s.w.clock.monotonic), PhoneWriteGrant(emptySet()))
    s.w.runUntilQuietWith(s.engine)
    val swap = s.w.journal.all().single { it.kind == UploadKind.SWAP }
    assertThat(swap.state).isEqualTo(UploadState.FAILED)
    assertThat(swap.hold).isEqualTo(VersionHold.ROLLED_BACK)
    // v1 di nuovo al solito percorso (lo stesso file), l'originale del PC in Versioni/ intatto; il catalogo non ha ripristinato niente.
    assertThat(s.w.sha(share, s.seed.usualPath.value)).isEqualTo(s.v1Bytes)
    assertThat(s.w.files().getValue(s.seed.usualPath.value).second).isEqualTo(v1FileId)
    assertThat(s.w.sha(share, archivedOriginal)).isEqualTo(Sha256.of(fromPc))
    assertThat(s.w.catalog.restores).isEqualTo(0)
    assertThat(s.w.catalog.nasCopiesOf(s.seed.mediaId, s.w.catalog.currentOf(s.seed.mediaId)).single().second).isEqualTo(s.seed.usualPath)
  }

  /** L'attuale cambiato dal PC prima dello scambio: niente si sposta e lo scambio finisce (il piano confermato non vale piu'). */
  @Test
  fun lAttualeCambiatoDalPcNonSiSpostaELoScambioFinisce() = runTest {
    var hooked: HookedOps? = null
    val s = edited { w -> HookedOps(w.ops).also { hooked = it } }
    val fromPc = s.w.bytes(9_500)
    hooked!!.beforeArchive = {
      hooked!!.beforeArchive = {}
      s.w.nas.modify(share, s.seed.usualPath.value, NasContent.of(fromPc))
    }
    val before = s.w.files()
    val plan = s.keeper.plan(s.seed.mediaId)
    s.keeper.restoreOriginal(s.seed.mediaId, TestConfirmations.confirm(plan, s.w.clock.monotonic), PhoneWriteGrant(emptySet()))
    val swap = s.w.journal.all().single { it.kind == UploadKind.SWAP }
    assertThat(swap.state).isEqualTo(UploadState.FAILED)
    assertThat(swap.hold).isEqualTo(VersionHold.SOURCE_CHANGED)
    // Solo il PC ha cambiato qualcosa: stessi nomi, stesse identita'.
    assertThat(s.w.files().keys).isEqualTo(before.keys)
    assertThat(s.w.sha(share, "$versions/IMG_1234 (originale).jpg")).isEqualTo(Sha256.of(s.seed.content))
  }
}
