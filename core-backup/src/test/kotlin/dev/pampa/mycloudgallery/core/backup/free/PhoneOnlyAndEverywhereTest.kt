package dev.pampa.mycloudgallery.core.backup.free

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.BackupGateState
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceExclusion
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceRunState
import dev.pampa.mycloudgallery.core.backup.contract.GuardScope
import dev.pampa.mycloudgallery.core.backup.contract.NewBackupItem
import dev.pampa.mycloudgallery.core.backup.contract.UploadKind
import dev.pampa.mycloudgallery.core.backup.contract.VersionRole
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * P9, D22: "Elimina solo dal telefono" (E-D1, E-D14, E-B14) e l'elenco di "Ovunque" con le copie di tutte le versioni (E-D15). Il NAS non si
 * tocca mai da qui: la copia del telefono se ne va solo come in "Libera spazio" (guardiano, verifica sul momento, timbro, dialogo di
 * sistema); le copie del NAS di "Ovunque" le toglie il piano `Trash` della 4B (core-nasops), con la sua conferma.
 */
class PhoneOnlyAndEverywhereTest {

  private val bench = FreeSpaceBench()

  @Test
  fun eD14_unMediaNonAlSicuro_avvisoEPrimaMettiloAlSicuroPreselezionata() = runTest {
    val safe = bench.safe()
    val unverified = bench.safe(verified = false)
    val noCopy = bench.safe(privateCopy = false)
    val common = bench.safe(privateCopy = false, commonCopy = true)
    val uploading = bench.safe(privateCopy = false)
    val nasOnly = bench.safe(onPhone = false)
    bench.store.create(
      listOf(NewBackupItem(UploadKind.NEW_COPY, uploading.id, uploading.current, 4_096, uploading.phoneRef, null, FreeSpaceBench.PRIVATE, SharePath.parse("Backup/x.jpg"))),
    )
    val ids = setOf(safe.id, unverified.id, noCopy.id, common.id, uploading.id, nasOnly.id)
    val assessment = bench.space.assessPhoneOnly(ids)
    assertThat(assessment.advice).containsExactly(
      safe.id, PhoneOnlyAdvice.FREE,
      unverified.id, PhoneOnlyAdvice.SAFE_FIRST,
      noCopy.id, PhoneOnlyAdvice.SAFE_FIRST,
      common.id, PhoneOnlyAdvice.GUARD_FIRST,
      uploading.id, PhoneOnlyAdvice.WAIT,
      nasOnly.id, PhoneOnlyAdvice.NOT_ON_PHONE,
    )
    assertThat(assessment.preselectSafeFirst).isTrue()
    assertThat(assessment.toSafeguard).containsExactly(unverified.id, noCopy.id)
    // Il piano della parte liberabile: solo quei media, nessun giorno escluso (li ha scelti l'utente).
    val filter = assessment.freeFilter!!
    assertThat(filter.excludeRecentDays).isEqualTo(0)
    assertThat(filter.selection).containsExactly(safe.id, common.id)
    assertThat(bench.space.assessPhoneOnly(setOf(safe.id)).preselectSafeFirst).isFalse()
  }

  @Test
  fun eD14_primaMettiloAlSicuro_poiIlCestinoDiSistema() = runTest {
    val photo = bench.safe(verified = false, localDay = 20_261_018) // di ieri: "Libera spazio" di serie non lo proporrebbe
    assertThat(bench.space.assessPhoneOnly(setOf(photo.id)).advice[photo.id]).isEqualTo(PhoneOnlyAdvice.SAFE_FIRST)
    // "Prima mettilo al sicuro": il backup lo verifica (qui lo fa il catalogo come dopo la rilettura del verificatore).
    bench.catalog.change(photo.id) { media ->
      media.copy(locations = media.locations.map { if (it.isNas) it.copy(verifiedAtMillis = bench.clock.nasNow) else it })
    }
    val assessment = bench.space.assessPhoneOnly(setOf(photo.id))
    assertThat(assessment.advice[photo.id]).isEqualTo(PhoneOnlyAdvice.FREE)
    // Poi come "Libera spazio": verifica sul momento, timbro, dialogo di sistema.
    val done = bench.freeAll(bench.start(assessment.freeFilter!!))
    assertThat(done.freedItems).isEqualTo(1)
    assertThat(bench.removed.map { it.mediaStoreId }).containsExactly(photo.phoneId)
    // Il NAS non e' stato toccato: la copia privata e' ancora li', con gli stessi byte.
    assertThat(bench.nas.bytes(FreeSpaceBench.PRIVATE, photo.privatePaths.getValue(photo.current))).isEqualTo(photo.data.getValue(photo.current))
  }

  @Test
  fun eB14_conLaSolaCopiaComune_primaIlGuardiano_poiSiLibera() = runTest {
    val common = bench.safe(privateCopy = false, commonCopy = true)
    val assessment = bench.space.assessPhoneOnly(setOf(common.id))
    assertThat(assessment.advice[common.id]).isEqualTo(PhoneOnlyAdvice.GUARD_FIRST)
    val run = bench.start(assessment.freeFilter!!)
    assertThat(bench.guardian.scopes).containsExactly(GuardScope.Media(setOf(common.id)))
    assertThat(run.guardedTotal).isEqualTo(1)
    assertThat(run.guardedDone).isEqualTo(1)
    bench.freeAll(run)
    assertThat(bench.removed.map { it.mediaStoreId }).containsExactly(common.phoneId)
    // La copia privata creata dal guardiano c'e' ed e' integra; la copia comune e' rimasta dov'era.
    assertThat(bench.nas.bytes(FreeSpaceBench.PRIVATE, "Backup/Area comune/2024/01/${common.name}")).isEqualTo(common.data.getValue(common.current))
    assertThat(bench.nas.exists(FreeSpaceBench.COMMON, common.commonPaths.getValue(common.current))).isTrue()
  }

  @Test
  fun eB14_seIlGuardianoNonRiesce_ilMediaRestaSulTelefono() = runTest {
    val common = bench.safe(privateCopy = false, commonCopy = true)
    val ok = bench.safe()
    bench.guardian.mode = BenchGuardian.Mode.FAIL
    val done = bench.freeAll(bench.start())
    assertThat(done.dropped).containsExactly(FreeSpaceExclusion.NOT_VERIFIED, 1)
    assertThat(bench.removed.map { it.mediaStoreId }).containsExactly(ok.phoneId)
    assertThat(bench.phone.ref(common.phoneId!!)).isNotNull()

    // Il NAS che cade durante il guardiano ferma il giro: niente si libera.
    val other = FreeSpaceBench()
    other.safe(privateCopy = false, commonCopy = true)
    other.guardian.mode = BenchGuardian.Mode.NAS_DOWN
    val blocked = other.start()
    assertThat(blocked.state).isEqualTo(FreeSpaceRunState.BLOCKED)
    assertThat(other.space.nextBatch(blocked)).isNull()

    // Una tappa finita durante il guardiano: il giro riprende dal guardiano alla tappa dopo.
    val third = FreeSpaceBench()
    third.safe(privateCopy = false, commonCopy = true)
    third.guardian.mode = BenchGuardian.Mode.BUDGET_ONCE
    val waiting = third.start()
    assertThat(waiting.state).isEqualTo(FreeSpaceRunState.GUARDING)
    val resumed = third.space.resume(waiting, kotlin.time.Duration.parse("1h"))
    assertThat(resumed.state).isEqualTo(FreeSpaceRunState.READY)
    third.freeAll(resumed)
    assertThat(third.removed).hasSize(1)
  }

  @Test
  fun eD14_areaCongelata_backupNonAttivo_recinzione() = runTest {
    val safe = bench.safe()
    bench.gate.default = BackupGateState.FROZEN
    assertThat(bench.space.assessPhoneOnly(setOf(safe.id)).advice[safe.id]).isEqualTo(PhoneOnlyAdvice.FROZEN)
    bench.gate.default = BackupGateState.TEST_ONLY
    assertThat(bench.space.assessPhoneOnly(setOf(safe.id)).advice[safe.id]).isEqualTo(PhoneOnlyAdvice.UNAVAILABLE)
    bench.gate.default = BackupGateState.OPEN
    bench.fence = FreeSpacePlanTest.TestFence
    bench.restart()
    assertThat(bench.space.assessPhoneOnly(setOf(safe.id)).advice[safe.id]).isEqualTo(PhoneOnlyAdvice.OUTSIDE_TEST_FENCE)
    assertThat(bench.space.assessPhoneOnly(setOf(MediaId.random())).advice.values.single()).isEqualTo(PhoneOnlyAdvice.NOT_ON_PHONE)
  }

  // --- "Ovunque" (E-D15) -------------------------------------------------------------------------------------------------------

  @Test
  fun eD15_ovunqueElencaLeCopieDiTutteLeVersioni() = runTest {
    val edited = bench.edited()
    // Anche una copia dell'attuale nell'area comune (condivisa con la famiglia) e una in un'area di un altro account (non si tocca).
    bench.addLocation(edited.id, bench.nasCopy(edited.current, FreeSpaceBench.COMMON, "Foto condivise/${edited.name}", edited.data.getValue(edited.current), verifiedAt = null))
    val foreign = bench.nasCopy(edited.current, FreeSpaceBench.OTHER_PRIVATE, "Foto/${edited.name}", edited.data.getValue(edited.current), verifiedAt = null)
    bench.addLocation(
      edited.id,
      foreign.copy(location = (foreign.location as dev.pampa.mycloudgallery.core.model.Location.Nas).copy(account = AccountId("test2"))),
    )
    val plan = EverywhereCopies(bench.catalog, { bench.me }, { bench.areas }).list(edited.id)
    assertThat(plan.blocked).isNull()
    assertThat(plan.nasCopies).hasSize(3)
    assertThat(plan.versions).isEqualTo(2)
    assertThat(plan.previousVersions).isEqualTo(1)
    // L'attuale prima (privata, poi comune), poi l'originale in Versioni/.
    val first = plan.nasCopies[0]
    assertThat(first.isCurrent).isTrue()
    assertThat(first.area).isEqualTo(EverywhereArea.PRIVATE)
    assertThat(first.inVersionsFolder).isFalse()
    assertThat(plan.nasCopies[1].area).isEqualTo(EverywhereArea.COMMON)
    val original = plan.nasCopies[2]
    assertThat(original.role).isEqualTo(VersionRole.ORIGINAL)
    assertThat(original.isCurrent).isFalse()
    assertThat(original.inVersionsFolder).isTrue()
    assertThat(original.path.value).isEqualTo(edited.privatePaths.getValue(edited.original))
    // Il timbro e' quello del catalogo: il piano Trash della 4B lo ricontrolla prima di spostare (non si sposta un file cambiato).
    assertThat(original.stamp.size).isEqualTo(edited.data.getValue(edited.original).size.toLong())
    assertThat(plan.phoneCopies).containsExactly(edited.phoneRef)
    // Nessuna lettura del NAS per fare l'elenco.
    assertThat(bench.nas.opens.get()).isEqualTo(0)
  }

  @Test
  fun eD15_ovunqueConLaRecinzione_eUnMediaCheNonCe() = runTest {
    val outside = bench.edited(folder = "DCIM/Camera/")
    val inside = bench.edited(folder = "DCIM/mcg-prova/")
    val copies = EverywhereCopies(bench.catalog, { bench.me }, { bench.areas }, FreeSpacePlanTest.TestFence)
    val plans = copies.list(listOf(outside.id, inside.id, MediaId.random()))
    assertThat(plans.map { it.blocked }).containsExactly(EverywhereBlock.OUTSIDE_TEST_FENCE, null, EverywhereBlock.NOT_FOUND).inOrder()
    assertThat(plans[0].nasCopies).isEmpty()
    assertThat(plans[1].nasCopies).hasSize(2)
    assertThat(EverywhereCopies(bench.catalog, { null }, { bench.areas }).list(inside.id).blocked).isEqualTo(EverywhereBlock.NOT_AVAILABLE)
  }
}
