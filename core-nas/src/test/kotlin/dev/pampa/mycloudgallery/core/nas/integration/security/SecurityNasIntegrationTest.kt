package dev.pampa.mycloudgallery.core.nas.integration.security

import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.nas.integration.ItCredentials
import dev.pampa.mycloudgallery.core.nas.integration.NasIntegration
import dev.pampa.mycloudgallery.core.nas.integration.NasIntegrationConfig
import dev.pampa.mycloudgallery.core.nas.integration.TimedDirectBinding
import dev.pampa.mycloudgallery.core.nas.runBlockingBounded
import dev.pampa.mycloudgallery.core.nas.smb.SmbjTransport
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters

/**
 * Il livello **PC** della revisione finale (Fase 10 P11; docs/fase10-progetto.md §2.11, D36): S3 (b), S3 (c), S9 e S1 (b) sul **NAS di
 * prova**, con il banco esistente (`NasIntegration`, il file delle credenziali che il banco legge da solo, `TestAreaGuard`). Solo con
 * `-PnasIntegration` e il file; senza, si salta. Ogni prova ha il suo giro `mcg-it/<giro>/` nelle share di prova e alla fine lo toglie
 * (solo quello). Al **primo `AUTH_FAILED`** la prova e' rossa con il motivo e le successive si saltano: nessun nuovo tentativo. S1 (b)
 * chiede `user2`, `password2` e `share2` nel file: senza, si salta.
 *
 * Lo lancia `tools/security/run-all.ps1 -Livello pc`. `MCG_SECURITY_OUT`: dove vanno l'elenco di S9 (`s9-elenco.tsv`) e il resoconto di
 * soli numeri (`pc.txt`); `MCG_SECURITY_IMAGES`: la cartella delle immagini ostili di S3 (c) (scritte da core-metadata);
 * `MCG_SECURITY_S9_FILES`: quanti file per S9 (di norma 2.000).
 */
@NasIntegration
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class SecurityNasIntegrationTest {

  private val out: File get() = File(System.getenv("MCG_SECURITY_OUT") ?: "build/security/pc").apply { mkdirs() }

  /** Una prova: credenziali dal banco, guardia e autotest prima del socket, verifica una volta per account, la pulizia del solo giro. */
  private fun probe(name: String, needsSecond: Boolean = false, block: suspend (SecurityArea) -> String) {
    assumeTrue("una prova precedente si e' fermata ($stopped): nessun nuovo tentativo", stopped == null)
    val credentials: ItCredentials = NasIntegrationConfig.requireCredentials()
    if (needsSecond) assumeTrue("$name: user2/password2/share2 assenti nel file delle credenziali", credentials.user2 != null && credentials.password2 != null && credentials.share2 != null)
    val lines = ArrayList<String>()
    try {
      runBlockingBounded(name, 1_800) {
        val inner = SmbjTransport()
        val binding = TimedDirectBinding()
        val guid = credentials.guid ?: SecurityArea.negotiateGuid(credentials, inner, binding) ?: throw NasUnavailable("nessuna risposta alla NEGOTIATE")
        SecurityArea.open(credentials, inner, binding, guid).use { area ->
          var residue: List<String> = emptyList()
          try {
            area.verify(area.manager, credentials.user, credentials.share)
            if (needsSecond) area.verify(checkNotNull(area.manager2), credentials.user2.orEmpty(), credentials.privateShare2 ?: credentials.share2!!)
            lines += block(area)
          } finally {
            residue = area.cleanup()
            lines += "MCG-SEC $name pulizia residui=${residue.size} solo_il_giro_della_prova=1"
          }
          assertWithMessage("$name: residui dopo la pulizia").that(residue).isEmpty()
        }
      }
    } catch (stop: AuthStop) {
      stopped = "AUTH_FAILED"
      lines += "MCG-SEC $name fermata=AUTH_FAILED nuovi_tentativi=0"
      throw stop
    } catch (down: NasUnavailable) {
      stopped = "NAS_NON_RAGGIUNGIBILE"
      lines += "MCG-SEC $name fermata=NAS_NON_RAGGIUNGIBILE"
      throw down
    } finally {
      lines.forEach(::println)
      File(out, "pc.txt").appendText(lines.joinToString("\n", postfix = "\n"))
    }
  }

  @Test
  fun s3b_segmentiOstiliRilettiDalNasRifiutati() = probe("S3b") { area ->
    HostileSegmentsProbe.run(area).also {
      assertWithMessage("S3b").that(it.rejected).isEqualTo(it.hostile)
      assertWithMessage("S3b controlli").that(it.accepted).isEqualTo(it.controls)
      assertWithMessage("S3b crolli").that(it.crashes).isEqualTo(0)
      assertWithMessage("S3b riletti uguali").that(it.sameBytes).isEqualTo(it.hostile + it.controls)
    }.line()
  }

  @Test
  fun s3c_immaginiMalformateCaricateERiletteUguali() = probe("S3c") { area ->
    val images = System.getenv("MCG_SECURITY_IMAGES")?.let(::File)?.takeIf { it.isDirectory }
    HostileImagesProbe.run(area, images).also { assertWithMessage("S3c").that(it.sameBytes).isEqualTo(it.uploaded) }.line()
  }

  @Test
  fun s9_cambiamentoDiMassaSulNasDiProva() = probe("S9") { area ->
    val files = System.getenv("MCG_SECURITY_S9_FILES")?.toIntOrNull()?.coerceIn(300, 2_000) ?: 2_000
    MassChangeProbe.run(area, files, File(out, "s9-elenco.tsv")).also {
      assertWithMessage("S9 elenco prima").that(it.before).isEqualTo(files)
      assertWithMessage("S9 elenco dopo").that(it.after).isEqualTo(files)
    }.line()
  }

  @Test
  fun s1b_test2NonLeggeIDatiPrivatiDiTest() = probe("S1b", needsSecond = true) { area ->
    CrossAccountProbe.run(area).also {
      assertWithMessage("S1b: segni leggibili da test2").that(it.marksFound).isEqualTo(0)
      assertWithMessage("S1b: area privata di test negata a test2").that(it.privateDenied).isTrue()
    }.line()
  }

  companion object {
    /** Il motivo per cui le prove si sono fermate (il primo `AUTH_FAILED` o il NAS che non risponde): le successive si saltano. */
    @Volatile private var stopped: String? = null
  }
}
