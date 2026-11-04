package dev.pampa.mycloudgallery.core.nas.integration.security

import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.nas.integration.NasIntegration
import dev.pampa.mycloudgallery.core.nas.integration.NasIntegrationConfig
import dev.pampa.mycloudgallery.core.nas.integration.TimedDirectBinding
import dev.pampa.mycloudgallery.core.nas.runBlockingBounded
import dev.pampa.mycloudgallery.core.nas.smb.SmbjTransport
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * I passi dal PC della seconda parte di P11 sull'**area della famiglia di prova** del NAS (C470): un passo per comando, scelto da
 * `MCG_FAMIGLIA_PASSO`, con lo stato del giro in `MCG_FAMIGLIA_DIR` (sotto `build/security/`, mai nel repository). Solo con
 * `-PnasIntegration` e il file delle credenziali del banco (letto dal banco, mai stampato); senza, si salta. Al primo `AUTH_FAILED` si ferma.
 * Lo lanciano `tools/security/famiglia-di-prova.ps1` e i passi di `run-all.ps1 -Livello emulatore|s25`; il NAS finto lo prova
 * `FamilyAreaFakeNasTest`.
 *
 * Passi: `fotografia` (con `MCG_FAMIGLIA_ETICHETTA`), `archivia`, `confronta` (`prima` contro `dopo`), `leggi`, `richieste`, `semina-s2`,
 * `semina-s3`, `immagini` (`MCG_SECURITY_IMAGES`), `manifest` (`MCG_FAMIGLIA_BUSTA`), `pulizia`.
 */
@NasIntegration
class FamilyAreaNasIntegrationTest {

  @Test
  fun passo() {
    val step = System.getenv("MCG_FAMIGLIA_PASSO")
    assumeTrue("nessun passo dell'area della famiglia (MCG_FAMIGLIA_PASSO)", !step.isNullOrBlank())
    val dir = File(checkNotNull(System.getenv("MCG_FAMIGLIA_DIR")) { "MCG_FAMIGLIA_DIR assente" })
    val credentials = NasIntegrationConfig.requireCredentials()
    val state = FamilyRunState.loadOrCreate(dir) { SecurityArea.newRunId() }
    val lines = ArrayList<String>()
    try {
      runBlockingBounded("famiglia: $step", 1_800) {
        val inner = SmbjTransport()
        val binding = TimedDirectBinding()
        val guid = credentials.guid ?: SecurityArea.negotiateGuid(credentials, inner, binding) ?: throw NasUnavailable("nessuna risposta alla NEGOTIATE")
        if (step!!.startsWith("massa-")) {
          // S9 nell'app: il giro di mcg-it e' quello dello stato, con la guardia di sempre (TestAreaGuard, solo mcg-it/<giro>/).
          SecurityArea.open(credentials, inner, binding, guid, runId = state.runId).use { area ->
            area.verify(area.manager, credentials.user, credentials.share)
            lines += mass(step, area)
          }
        } else {
          FamilyArea.open(credentials, inner, binding, guid, state).use { area ->
            area.verify()
            lines += execute(step, area, dir)
          }
        }
      }
    } catch (stop: AuthStop) {
      lines += "MCG-SEC famiglia passo=$step fermata=AUTH_FAILED nuovi_tentativi=0"
      throw stop
    } catch (down: NasUnavailable) {
      lines += "MCG-SEC famiglia passo=$step fermata=NAS_NON_RAGGIUNGIBILE"
      throw down
    } finally {
      lines.forEach(::println)
      File(dir, "famiglia-nas.txt").appendText(lines.joinToString("\n", postfix = "\n"))
    }
  }

  private suspend fun mass(step: String, area: SecurityArea): List<String> = when (step) {
    "massa-crea" -> {
      val files = System.getenv("MCG_SECURITY_S9_FILES")?.toIntOrNull()?.coerceIn(300, 2_000) ?: 2_000
      listOf("MCG-SEC S9app_crea file=${FamilyAreaProbes.massCreate(area, files)}")
    }
    "massa-cambia" -> {
      val (renamed, changed, locked) = FamilyAreaProbes.massChange(area)
      listOf("MCG-SEC S9app_cambia rinominati=$renamed cambiati=$changed cifrati=$locked")
    }
    "massa-pulizia" -> {
      val residue = area.cleanup()
      assertWithMessage("residui del giro di S9").that(residue).isEmpty()
      listOf("MCG-SEC S9app_pulizia residui=${residue.size} solo_il_giro_della_prova=1")
    }
    else -> error("passo sconosciuto: $step")
  }

  private suspend fun execute(step: String, area: FamilyArea, dir: File): List<String> {
    val now = System.currentTimeMillis()
    return when (step) {
      "semina-s1" -> {
        val info = checkNotNull(FamilyAreaProbes.readFamily(area)) { "famiglia di prova non trovata" }
        listOf(FamilyAreaProbes.seedPrivateMarks(area, info, now, File(dir, "segno-s1.txt")).line(), info.line())
      }
      "fotografia" -> {
        val label = System.getenv("MCG_FAMIGLIA_ETICHETTA")?.takeIf { it.matches(Regex("^[a-z0-9-]{1,20}$")) } ?: "prima"
        val entries = area.snapshot(area.privateShare) + area.snapshot(area.commonShare)
        File(dir, "fotografia-$label.tsv").writeText("# share, percorso, byte, data ms, cartella\n" + entries.joinToString("\n", postfix = "\n") { it.line() })
        listOf("MCG-SEC famiglia fotografia=$label voci=${entries.size} cartelle=${entries.count { it.dir }}")
      }
      "archivia" -> listOf("MCG-SEC famiglia archiviata=${if (FamilyAreaProbes.archiveFamily(area)) 1 else 0}")
      "confronta" -> {
        fun load(label: String) = File(dir, "fotografia-$label.tsv").readLines().mapNotNull(SnapshotEntry::parse)
        val diff = FamilyAreaProbes.compare(load("prima"), load("dopo"), area.guard.familyArchive)
        assertWithMessage(diff.line()).that(diff.clean).isTrue()
        listOf(diff.line())
      }
      "leggi" -> listOf(FamilyAreaProbes.readFamily(area)?.line() ?: "MCG-SEC famiglia trovata=0")
      "richieste" -> {
        val info = checkNotNull(FamilyAreaProbes.readFamily(area)) { "famiglia di prova non trovata" }
        // MCG_FAMIGLIA_RUOLI (facoltativo): solo questi dispositivi di prova rifanno la richiesta (un foglio dell'impronta scaduto).
        val roles = System.getenv("MCG_FAMIGLIA_RUOLI").orEmpty().split(',').map { it.trim() }.filter { it in listOf("approvato", "revocato") }.ifEmpty { listOf("approvato", "revocato") }
        listOf("MCG-SEC famiglia richieste=${FamilyAreaProbes.requests(area, info, now, File(dir, "codici.txt"), roles)}", info.line())
      }
      "semina-s2" -> {
        val info = checkNotNull(FamilyAreaProbes.readFamily(area)) { "famiglia di prova non trovata" }
        listOf(FamilyAreaProbes.seedRogue(area, info, now).line(), info.line())
      }
      "semina-s3" -> {
        val info = checkNotNull(FamilyAreaProbes.readFamily(area)) { "famiglia di prova non trovata" }
        listOf(FamilyAreaProbes.seedHostileSegments(area, info, now).line(), info.line())
      }
      "semina-s7a" -> {
        val shas = System.getenv("MCG_S7A_SHA").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        listOf(FamilyAreaProbes.seedTamperedModels(area, shas).line())
      }
      "immagini" -> listOf(FamilyAreaProbes.seedImages(area, System.getenv("MCG_SECURITY_IMAGES")?.let(::File)?.takeIf { it.isDirectory }).line())
      "manifest" -> {
        val envelope = File(checkNotNull(System.getenv("MCG_FAMIGLIA_BUSTA")) { "MCG_FAMIGLIA_BUSTA assente" })
        val ok = FamilyAreaProbes.placeManifest(area, envelope.readBytes())
        listOf("MCG-SEC S7app busta_messa=${if (ok) 1 else 0} byte=${envelope.length()}")
      }
      "pulizia" -> {
        val residue = FamilyAreaProbes.cleanup(area)
        assertWithMessage("residui dopo la pulizia").that(residue).isEmpty()
        listOf("MCG-SEC famiglia pulizia residui=${residue.size} registro=${area.guard.ledger().size}")
      }
      else -> error("passo sconosciuto: $step")
    }
  }
}
