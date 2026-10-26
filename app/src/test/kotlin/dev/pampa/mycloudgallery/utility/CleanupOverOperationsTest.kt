package dev.pampa.mycloudgallery.utility

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.duplicates.CopyArea
import dev.pampa.mycloudgallery.core.library.duplicates.NasTrashRef
import dev.pampa.mycloudgallery.core.nasops.contract.ConfirmationIssuer
import dev.pampa.mycloudgallery.core.nasops.contract.NasItem
import dev.pampa.mycloudgallery.core.nasops.contract.NasOperations
import dev.pampa.mycloudgallery.core.nasops.contract.NasTrashState
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.nasops.contract.OpRequest
import dev.pampa.mycloudgallery.core.nasops.contract.PlanOutcome
import dev.pampa.mycloudgallery.core.nasops.contract.StartOutcome
import dev.pampa.mycloudgallery.core.nasops.testing.NasOpsTestKit
import dev.pampa.mycloudgallery.core.nasops.contract.GateStatus
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import dev.pampa.mycloudgallery.feature.library.trash.NasTrashOutcome
import dev.pampa.mycloudgallery.feature.library.trash.UserTap
import dev.pampa.mycloudgallery.feature.library.trash.testing.fakeSingleTap
import dev.pampa.mycloudgallery.feature.library.utility.CleanupNasPort
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Fase 7 (P8, D25, §2.1.1): **la pulizia dei doppioni passa da `NasOperations`**. `feature-library` non vede `core-nasops` (test d'architettura): la
 * pulizia esce dalla porta [CleanupNasPort] con le copie da togliere e la prova del tocco dell'utente, e chi la lega (`:app`, P13) costruisce il piano
 * `OpRequest.Trash`, lo fa verificare e lo avvia con la conferma. Qui sotto c'e' **l'adattatore di riferimento** ([OperationsCleanupNas]) sopra il NAS
 * finto del kit di `core-nasops` (le vere operazioni, il vero diario, lo stesso confronto dei timbri): P13 lo sposta in `:app` com'e'. La prova che il
 * piano dei Doppioni e' fatto nel modo che `NasOperations` si aspetta:
 *  - un file con il timbro visto dal catalogo (dimensione, data, `fileId`) va nel cestino del NAS con **un** tocco (mai in modo definitivo);
 *  - se il file e' cambiato dopo che il catalogo l'ha visto (E-D13) non si tocca;
 *  - con le scritture ferme (congelamento, emergenza) non parte e lo dice (`BLOCKED`);
 *  - e' sempre e solo cio' che l'utente ha scelto: un elenco vuoto non pianifica niente.
 */
class CleanupOverOperationsTest {

  /** L'adattatore di riferimento di `CleanupNasPort`: piano di `Trash`, conferma dopo il tocco, avvio. Mai altro che il cestino. */
  private class OperationsCleanupNas(private val operations: NasOperations, private val monotonicMs: () -> Long) : CleanupNasPort {
    override suspend fun trash(copies: List<NasTrashRef>, tap: UserTap): NasTrashOutcome {
      if (copies.isEmpty()) return NasTrashOutcome.REJECTED
      val items = copies.map { copy ->
        NasItem(copy.share, SharePath.parse(copy.path), isFolder = false, size = copy.size, stamp = NasStamp(copy.size, copy.modifiedAt, copy.fileId))
      }
      val plan = (operations.plan(OpRequest.Trash(items)) as? PlanOutcome.Ready)?.plan ?: return NasTrashOutcome.REJECTED
      // Un piano senza elementi (tutti esclusi: share in sola lettura, file spariti) non avvia nessuna operazione.
      if (plan.items.isEmpty()) return NasTrashOutcome.REJECTED
      // Il tocco c'e' gia' stato (sul foglio): la conferma si emette adesso, con i tocchi che la prova dice, e il piano ne chiede uno.
      if (tap.taps < plan.requiredConfirmations) return NasTrashOutcome.REJECTED
      return when (operations.start(plan, ConfirmationIssuer.issue(plan, tap.taps, monotonicMs()))) {
        is StartOutcome.Started, is StartOutcome.AlreadyStarted -> NasTrashOutcome.STARTED
        StartOutcome.WritesBlocked -> NasTrashOutcome.BLOCKED
        else -> NasTrashOutcome.REJECTED
      }
    }
  }

  private val kit = NasOpsTestKit()
  private val port: CleanupNasPort get() = OperationsCleanupNas(kit.operations) { kit.clocks.monotonicMs() }

  /** Quello che il catalogo ricorda di una copia: share, percorso e il timbro che ha visto. */
  private fun copyAt(path: String, bytes: ByteArray, share: String = NasOpsTestKit.PRIVATE_SHARE, area: CopyArea = CopyArea.MY_PRIVATE): NasTrashRef {
    val stamp = kit.putFile(share, path, bytes)
    return NasTrashRef(share, path, stamp.size, stamp.modifiedAtMillis, stamp.fileId, area)
  }

  @Test
  fun unaCopiaVaNelCestinoDelNasConUnSoloTocco() = runBlocking {
    val copy = copyAt("Backup/Pixel 9/2026/10/IMG_0002.jpg", ByteArray(2_000) { 3 })
    val keep = copyAt("Foto/Oslo/IMG_0002.jpg", ByteArray(2_000) { 3 })

    val outcome = port.trash(listOf(copy), fakeSingleTap())

    assertThat(outcome).isEqualTo(NasTrashOutcome.STARTED)
    assertThat(kit.exists(NasOpsTestKit.PRIVATE_SHARE, copy.path)).isFalse()
    // Quella da tenere non si tocca, e la copia tolta e' nel cestino del NAS (30 giorni), non cancellata.
    assertThat(kit.exists(NasOpsTestKit.PRIVATE_SHARE, keep.path)).isTrue()
    kit.trash.refresh()
    val trashed = (kit.trash.items.value as NasTrashState.Ready).items.single()
    assertThat(trashed.daysLeft).isEqualTo(30)
    assertThat(trashed.deletableNow).isTrue()
  }

  @Test
  fun unaCopiaCambiataDopoCheIlCatalogoLaVideNonSiTocca() = runBlocking {
    val copy = copyAt("Backup/Pixel 9/2026/10/IMG_0003.jpg", ByteArray(2_000) { 4 })
    // Qualcuno la sostituisce dal PC: stesso nome, byte e data nuovi.
    kit.advance(60_000)
    kit.modify(NasOpsTestKit.PRIVATE_SHARE, copy.path, ByteArray(2_500) { 9 })

    port.trash(listOf(copy), fakeSingleTap())

    // Il piano porta il timbro che il catalogo ha visto: l'esecuzione si ferma sul file cambiato (E-D13). Il file nuovo resta dov'e', niente nel cestino.
    assertThat(kit.exists(NasOpsTestKit.PRIVATE_SHARE, copy.path)).isTrue()
    assertThat(kit.bytes(NasOpsTestKit.PRIVATE_SHARE, copy.path)).hasLength(2_500)
    kit.trash.refresh()
    assertThat((kit.trash.items.value as NasTrashState.Ready).items).isEmpty()
  }

  @Test
  fun conLeScrittureFermeNonPartiNiente_ELoDice() = runBlocking {
    val copy = copyAt("Backup/Pixel 9/2026/10/IMG_0004.jpg", ByteArray(1_000) { 5 })
    kit.gate.byShare[NasOpsTestKit.PRIVATE_SHARE] = GateStatus.FROZEN

    val outcome = port.trash(listOf(copy), fakeSingleTap())

    assertThat(outcome).isIn(listOf(NasTrashOutcome.BLOCKED, NasTrashOutcome.REJECTED))
    assertThat(kit.exists(NasOpsTestKit.PRIVATE_SHARE, copy.path)).isTrue()
  }

  @Test
  fun unaCopiaCheNonCePiuNonSiProponeNientePerQuelloCheNonCe() = runBlocking {
    val gone = NasTrashRef(NasOpsTestKit.PRIVATE_SHARE, "Backup/Pixel 9/2026/10/IMG_0005.jpg", 1_000, 1_000, 77, CopyArea.MY_PRIVATE)

    val outcome = port.trash(listOf(gone), fakeSingleTap())

    assertThat(outcome).isNotEqualTo(NasTrashOutcome.STARTED)
  }

  @Test
  fun senzaCopieNonSiPianificaNiente() = runBlocking {
    assertThat(port.trash(emptyList(), fakeSingleTap())).isEqualTo(NasTrashOutcome.REJECTED)
    assertThat(kit.operations.operations.value).isEmpty()
  }
}
