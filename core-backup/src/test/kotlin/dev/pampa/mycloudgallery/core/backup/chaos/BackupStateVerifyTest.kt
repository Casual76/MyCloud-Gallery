package dev.pampa.mycloudgallery.core.backup.chaos

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Il verificatore degli invarianti che gira DA SOLO su uno stato salvato (`MCG_BACKUP_STATE`): lo stato che il banco scrive in
 * `build/chaos/backup-state-<giro>.txt` quando un giro e' rosso. Niente motore, niente NAS, niente telefono: giudica solo cio' che c'e' nel file.
 * Si lancia con `tools\chaos\area.ps1 -Mode backup -Stage state -Manifest <file>` (con `-NotSettled` per i soli invarianti che valgono a meta').
 * Senza la variabile il test e' saltato. Il rapporto va in `build/chaos/backup-state-verify.txt`.
 */
class BackupStateVerifyTest {

  @Test
  fun verificaUnoStatoSalvatoDaSolo() {
    val path = System.getenv("MCG_BACKUP_STATE")?.takeIf { it.isNotBlank() }
    assumeTrue("serve MCG_BACKUP_STATE (un file scritto dal banco)", path != null)
    val state = BackupStateFile.read(File(path!!))
    val phase = if (System.getenv("MCG_BACKUP_SETTLED")?.lowercase() == "false") Phase.MID else Phase.FINAL
    val violations = BackupVerifier.check(state, phase)
    val text = buildString {
      appendLine("=== Verifica di uno stato salvato (${if (phase == Phase.FINAL) "a lavoro finito" else "a meta'"}) ===")
      appendLine("file: ${state.files.size}; elementi del diario: ${state.items.size}; media: ${state.media.size}")
      if (violations.isEmpty()) appendLine("TUTTI GLI INVARIANTI RISPETTATI") else violations.forEach { appendLine("VIOLAZIONE $it") }
    }
    println(text)
    File("build/chaos/backup-state-verify.txt").also { it.parentFile?.mkdirs() }.writeText(text)
    assertWithMessage(text).that(violations.filter { it.invariant != Invariant.KNOWN_GAP }).isEmpty()
  }
}
