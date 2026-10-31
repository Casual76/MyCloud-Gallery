package dev.pampa.mycloudgallery.core.nas.integration.security

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.nas.WAIT_SECONDS
import dev.pampa.mycloudgallery.core.nas.fs.smb
import dev.pampa.mycloudgallery.core.nas.integration.ItCredentials
import dev.pampa.mycloudgallery.core.nas.integration.SmbAccess
import dev.pampa.mycloudgallery.core.nas.integration.TestAreaGuard
import dev.pampa.mycloudgallery.core.nas.integration.TestAreaViolation
import dev.pampa.mycloudgallery.core.nas.runBlockingBounded
import dev.pampa.mycloudgallery.core.nas.testing.InMemorySmbBackend
import dev.pampa.mycloudgallery.core.nas.testing.SimAccess
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.io.File
import java.util.Properties
import kotlin.random.Random
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Gli strumenti della revisione finale dal PC (S1 (b), S3 (b), S3 (c), S9; Fase 10 P11, C447) **provati contro un NAS finto**
 * (`InMemorySmbBackend` con i permessi per account delle share di prova, piu' una share di famiglia `NAS` che non va mai toccata). Gira
 * sempre in `check.ps1`. Dimostra che: ogni scrittura sta nelle share di prova e sotto `mcg-it/<giro>/` (guardia con test); la pulizia toglie
 * il giro della prova e **solo** quello; la prova fra account trova i segni se il NAS li lascia leggere (non e' cieca) e zero se no; il primo
 * `AUTH_FAILED` ferma la prova senza nuovi tentativi.
 */
class SecurityProbesFakeNasTest {

  private val password = "Prova-Sicurezza-P11-1"
  private val password2 = "Prova-Sicurezza-P11-2"
  private val otherRun = "20200101-000000-aaaaaaaa"

  private fun backend(leaky: Boolean = false): InMemorySmbBackend = InMemorySmbBackend().apply {
    addUser("test", password)
    addUser("test2", password2)
    addShare("test", mapOf("test" to SimAccess.READ_WRITE) + if (leaky) mapOf("test2" to SimAccess.READ) else emptyMap())
    addShare("test-comune", mapOf("test" to SimAccess.READ_WRITE, "test2" to SimAccess.READ_WRITE))
    addShare("test2", mapOf("test2" to SimAccess.READ_WRITE))
    addShare("NAS", mapOf("test" to SimAccess.READ_WRITE, "test2" to SimAccess.READ_WRITE))
    // Un giro di qualcun altro, gia' li': la pulizia non deve toccarlo.
    put("test-comune", "${TestAreaGuard.ROOT}/$otherRun/di-un-altro.jpg", byteArrayOf(1, 2, 3))
    put("NAS", "famiglia.jpg", byteArrayOf(4, 5, 6))
  }

  private fun credentials(user1Password: String = password): ItCredentials = checkNotNull(
    ItCredentials.parse(
      Properties().apply {
        setProperty("user", "test"); setProperty("password", user1Password); setProperty("share", "test"); setProperty("share2", "test-comune")
        setProperty("user2", "test2"); setProperty("password2", password2); setProperty("privateShare2", "test2"); setProperty("host", "192.168.178.58")
      },
    ),
  )

  private fun open(backend: InMemorySmbBackend, creds: ItCredentials = credentials()) =
    SecurityArea.open(creds, backend, backend.binding("wifi-casa"), backend.serverGuid)

  /** Ogni file di [share] fuori dal giro [run] (esclusi quelli messi apposta prima della prova). */
  private fun outside(backend: InMemorySmbBackend, share: String, run: String, before: Set<String>): List<String> {
    val out = ArrayList<String>()
    fun walk(dir: String) {
      for (name in backend.children(share, dir)) {
        val path = if (dir.isEmpty()) name else "$dir/$name"
        if (backend.bytes(share, path) == null) walk(path) else if (path !in before && !path.startsWith("${TestAreaGuard.ROOT}/$run/")) out += path
      }
    }
    walk("")
    return out
  }

  @Test
  fun leProveScrivonoSoloNelGiroDelleShareDiProvaEPulisconoSoloIlLoro() {
    runBlockingBounded("le prove sul NAS finto", WAIT_SECONDS * 10) {
      val backend = backend()
      val before = setOf("${TestAreaGuard.ROOT}/$otherRun/di-un-altro.jpg", "famiglia.jpg")
      val listing = File("build/security/fake/s9-elenco.tsv")
      open(backend).use { area ->
        area.verify(area.manager, "test", "test")
        area.verify(checkNotNull(area.manager2), "test2", "test2")
        val s1 = CrossAccountProbe.run(area, Random(1))
        val s3b = HostileSegmentsProbe.run(area)
        val s3c = HostileImagesProbe.run(area, null)
        val s9 = MassChangeProbe.run(area, files = 300, listing = listing)
        for (share in listOf("test", "test-comune", "test2", "NAS")) {
          assertWithMessage("$share: file fuori dal giro").that(outside(backend, share, area.guard.runId, before)).isEmpty()
        }
        assertWithMessage("la share di famiglia non e' toccata").that(backend.fileCount("NAS")).isEqualTo(1)

        assertWithMessage("S1b: segni letti da test2").that(s1.marksFound).isEqualTo(0)
        assertWithMessage("S1b: test2 ha letto l'area comune").that(s1.readByOther).isAtLeast(5)
        assertThat(s1.privateDenied).isTrue()
        assertWithMessage("S3b: ostili rifiutati").that(s3b.rejected).isEqualTo(s3b.hostile)
        assertWithMessage("S3b: controlli accettati").that(s3b.accepted).isEqualTo(s3b.controls)
        assertThat(s3b.sameBytes).isEqualTo(s3b.hostile + s3b.controls)
        assertThat(s3b.crashes).isEqualTo(0)
        assertThat(s3c.sameBytes).isEqualTo(s3c.uploaded)
        assertThat(s9.before).isEqualTo(300)
        assertThat(s9.after).isEqualTo(300)
        assertWithMessage("rinomine e riscritture conservano il fileId").that(s9.keptIds).isEqualTo(300)
        assertThat(listing.readLines().count { it.startsWith("dopo\t") && it.endsWith("\tcifrato") }).isEqualTo(50)

        assertWithMessage("residui").that(area.cleanup()).isEmpty()
        println(listOf(s1.line(), s3b.line(), s3c.line(), s9.line()).joinToString("\n") { "$it banco=finto" })
      }
      // Dopo la pulizia: il giro di un altro c'e' ancora, quindi anche mcg-it; nient'altro della prova.
      assertThat(backend.exists("test-comune", "${TestAreaGuard.ROOT}/$otherRun/di-un-altro.jpg")).isTrue()
      for (share in listOf("test", "test-comune", "test2")) {
        assertWithMessage("$share: resta qualcosa della prova").that(outside(backend, share, "nessuno", before)).isEmpty()
      }
      assertThat(backend.exists("test", TestAreaGuard.ROOT)).isFalse()
    }
  }

  @Test
  fun laGuardiaRifiutaOgniScritturaFuoriDalGiroPrimaDellaChiamata() {
    runBlockingBounded("la guardia delle prove", WAIT_SECONDS) {
      val backend = backend()
      open(backend).use { area ->
        area.verify(area.manager, "test", "test")
        val forbidden = listOf(
          "test" to SharePath.of("fuori.jpg"),
          "test" to SharePath.of(TestAreaGuard.ROOT, "fuori.jpg"),
          "test-comune" to SharePath.of(TestAreaGuard.ROOT, otherRun, "x.jpg"),
          "test-comune" to SharePath.of(".mycloudgallery", "registry", "1"),
        )
        for ((share, path) in forbidden) {
          val error = runCatching { area.op(area.manager, share) { ctx -> ctx.share.createExclusive(path.smb()).close() } }.exceptionOrNull()
          assertWithMessage("$share/${path.value}").that(error).isNotNull()
          assertWithMessage("$share/${path.value}: creato").that(backend.exists(share, path.value)).isFalse()
          // La stessa decisione al livello del trasporto, senza nessuna chiamata: la guardia lancia prima.
          assertThrows(TestAreaViolation::class.java) { area.guard.checkSmb(share, path.smb(), SmbAccess.CREATE) }
        }
        // La share di famiglia: rifiutata dalla guardia prima di ogni connessione, in ogni forma.
        for (family in listOf("NAS", "Nas", "Public", "alessio")) {
          assertThrows(TestAreaViolation::class.java) { area.guard.checkShare(family) }
        }
        assertThat(area.cleanup()).isEmpty()
      }
      // Un file delle credenziali che nomina una share di famiglia non apre nemmeno l'area.
      val bad = Properties().apply { setProperty("user", "test"); setProperty("password", password); setProperty("share", "NAS") }
      assertThrows(TestAreaViolation::class.java) { SecurityArea.open(ItCredentials.parse(bad)!!, backend, backend.binding("wifi"), null) }
    }
  }

  @Test
  fun suUnNasCheLasciaLeggereLAreaPrivataLaProvaTrovaISegni() {
    runBlockingBounded("la prova fra account non e' cieca", WAIT_SECONDS * 4) {
      val backend = backend(leaky = true)
      open(backend).use { area ->
        val s1 = CrossAccountProbe.run(area, Random(2))
        assertWithMessage("un NAS che sbaglia i permessi deve far trovare i segni").that(s1.marksFound).isGreaterThan(0)
        assertThat(s1.privateDenied).isFalse()
        assertThat(area.cleanup()).isEmpty()
      }
    }
  }

  @Test
  fun ilPrimoAuthFailedFermaLaProvaSenzaNuoviTentativi() {
    runBlockingBounded("AUTH_FAILED", WAIT_SECONDS) {
      val backend = backend()
      open(backend, credentials(user1Password = "Sbagliata-Davvero-P11")).use { area ->
        val stop = runCatching { area.verify(area.manager, "test", "test") }.exceptionOrNull()
        assertThat(stop).isInstanceOf(AuthStop::class.java)
        // La sessione non riprova da sola: l'interruttore e' aperto e una seconda richiesta non arriva al NAS.
        area.manager.withShare("test", dev.pampa.mycloudgallery.core.nas.contract.Purpose.INTERACTIVE) { }
        assertThat(backend.authenticateCalls.get()).isEqualTo(1)
      }
    }
  }
}
