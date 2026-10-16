package dev.pampa.mycloudgallery.core.backup

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.backup.contract.UploadFlow
import dev.pampa.mycloudgallery.core.backup.contract.UploadKind
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.contract.UploadStateMachine
import org.junit.Test

/**
 * La macchina a stati del caricamento come dati (§2.2, D3): i cammini della 5A completi, quelli della 5B dichiarati, i ritorni e le
 * uscite ammessi e solo quelli. I codici sono stabili (finiscono in `backup_item`).
 */
class UploadStateMachineTest {

  @Test
  fun iCamminiDellaQuintaASonoQuelliDelProgetto() {
    assertThat(UploadStateMachine.flowOf(UploadKind.NEW_COPY).steps.map { it.code }).containsExactly(
      "planned", "hashed", "uploading", "uploaded", "verified", "publishing", "published", "linked", "done",
    ).inOrder()
    assertThat(UploadStateMachine.flowOf(UploadKind.GUARD_COPY).steps.map { it.code }).containsExactly(
      "planned", "copying", "copied", "verified", "publishing", "published", "linked", "done",
    ).inOrder()
    // 5B: dichiarati, con i passi del §2.4.
    assertThat(UploadStateMachine.flowOf(UploadKind.VERSION).steps).containsAtLeast(UploadState.ARCHIVING, UploadState.ARCHIVED)
    assertThat(UploadStateMachine.flowOf(UploadKind.VERSION, archivesCurrent = false)).isEqualTo(UploadFlow.VERSION_DIRECT)
    assertThat(UploadStateMachine.flowOf(UploadKind.SWAP).steps).containsAtLeast(UploadState.RESTORING, UploadState.RESTORED)
    assertThat(UploadKind.entries.filter { it.phase5B }).containsExactly(UploadKind.VERSION, UploadKind.SWAP, UploadKind.INCOMING)
  }

  @Test
  fun leTransizioniAmmesseSonoSoloQuelleDellaTabella() {
    val flow = UploadFlow.NEW_COPY
    val allowed = UploadState.entries.flatMap { from -> UploadState.entries.map { to -> from to to } }
      .filter { (from, to) -> from != to && UploadStateMachine.canTransition(flow, from, to) }
      .map { (from, to) -> "${from.code}>${to.code}" }
      .toSet()
    val forward = flow.steps.zipWithNext { a, b -> "${a.code}>${b.code}" }
    val failures = flow.steps.filterNot { it.terminal }.map { "${it.code}>failed" }
    val expected = forward + failures + setOf(
      // Ritorni (D4, E-B15, risposta persa, finale toccato dal PC).
      "uploaded>uploading", "verified>uploading", "publishing>uploading", "published>uploading", "uploading>hashed",
      // Uscite: deduplica e nome occupato da un file identico (D8, E-B3); versione non piu' sul telefono (E-B4, E-B15).
      "hashed>linked_existing", "publishing>linked_existing",
      "planned>gone", "hashed>gone", "uploading>gone",
    )
    assertThat(allowed).containsExactlyElementsIn(expected)
  }

  @Test
  fun unoStatoTerminaleNonSiLasciaMaiESiRiscriveUguale() {
    for (flow in UploadFlow.entries) {
      for (terminal in UploadState.entries.filter { it.terminal }) {
        assertWithMessage("${flow.code} ${terminal.code}").that(UploadStateMachine.canTransition(flow, terminal, terminal)).isTrue()
        for (to in UploadState.entries.filter { it != terminal }) {
          assertWithMessage("${flow.code} ${terminal.code}>${to.code}").that(UploadStateMachine.canTransition(flow, terminal, to)).isFalse()
        }
      }
    }
    // Il temporaneo completo non si butta per una riga sparita: dopo UPLOADED niente GONE.
    for (from in listOf(UploadState.UPLOADED, UploadState.VERIFIED, UploadState.PUBLISHING, UploadState.PUBLISHED, UploadState.LINKED)) {
      assertThat(UploadStateMachine.canTransition(UploadFlow.NEW_COPY, from, UploadState.GONE)).isFalse()
    }
    assertThat(UploadStateMachine.resumable).containsNoneOf(UploadState.DONE, UploadState.LINKED_EXISTING, UploadState.GONE, UploadState.FAILED)
    assertThat(UploadStateMachine.MAX_VERIFY_ATTEMPTS).isEqualTo(3)
  }

  @Test
  fun iCodiciSonoUniciEStabili() {
    for (codes in listOf(UploadState.entries.map { it.code }, UploadKind.entries.map { it.code }, UploadFlow.entries.map { it.code })) {
      assertThat(codes.toSet()).hasSize(codes.size)
      codes.forEach { assertThat(it).matches("^[a-z0-9_]{1,32}$") }
    }
  }
}
