package dev.pampa.mycloudgallery.core.albums.chaos

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.nas.smb.DirAccess
import dev.pampa.mycloudgallery.core.nas.testing.InMemorySmbBackend
import dev.pampa.mycloudgallery.core.nas.testing.SimAccess
import dev.pampa.mycloudgallery.core.network.contract.NasAddress
import java.security.MessageDigest
import kotlin.time.Duration
import org.junit.Test

/**
 * Il verificatore dell'area del telefono (`s25.ps1 -Mode share`) e il lettore in sola lettura, sul simulatore SMB (nessuna rete):
 *  - il manifesto che scrive lo script si legge, e un manifesto ostile no (share non di prova, cartelle con `..`, impronte sbagliate);
 *  - il lettore non scrive mai e non legge fuori dalle cartelle del manifesto, con zero chiamate al vero per ogni rifiuto;
 *  - il verificatore dice l'invariante giusto per ogni manomissione (un file a meta', un doppione, una perdita, un temporaneo rimasto).
 */
class ShareManifestVerifierTest {

  private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

  private val a = ByteArray(40) { (it * 3).toByte() }
  private val b = ByteArray(55) { (it * 5 + 1).toByte() }
  private val c = ByteArray(70) { (it * 7 + 2).toByte() }
  private val shaA = sha(a)
  private val shaB = sha(b)
  private val shaC = sha(c)

  private fun manifestText(freed: List<String> = emptyList(), extra: List<String> = emptyList()) = buildList {
    add("# mcg-share-manifest 1")
    add("run=20261026-101500-1a2b3c4d")
    add("device=")
    add("private=test|mcg-campo")
    add("common=test-comune|Album condivisi")
    add("phone=$shaA|40|a.jpg")
    add("phone=$shaB|55|b.jpg")
    add("phone=$shaC|70|c.jpg")
    add("notmedia=${sha(ByteArray(3))}|3|testo.jpg")
    freed.forEach { add("freed=$it|x") }
    addAll(extra)
  }

  private fun manifest(freed: List<String> = emptyList()) = ShareManifest.parse(manifestText(freed))

  private class Rig(val backend: InMemorySmbBackend, val area: ReadOnlyArea, val counter: DelegateCounter)

  private fun rig(manifest: ShareManifest, block: (Rig) -> Unit) {
    val backend = InMemorySmbBackend().apply {
      addUser("test", "pw-del-banco")
      listOf("test", "test-comune", "test2", "NAS").forEach { addShare(it, mapOf("test" to SimAccess.READ_WRITE)) }
    }
    val credentials = ShareAreaCredentials("192.168.178.58", "test", "pw-del-banco".toCharArray(), "test", "test-comune", backend.serverGuid)
    val guard = ShareAreaGuard.of(credentials, manifest.runId)
    val latch = AreaAuthLatch()
    val counter = DelegateCounter()
    val transport = ReadOnlyAreaTransport(backend, guard, ShareManifestVerifier.readable(manifest), latch, counter)
    val area = ReadOnlyArea(transport, ChaosConnection("test", "pw-del-banco".toCharArray(), "192.168.178.58", backend.serverGuid, backend.binding("pc")), latch).open()
    try {
      block(Rig(backend, area, counter))
    } finally {
      area.close()
    }
  }

  private fun verify(m: ShareManifest, settled: Boolean, setup: (InMemorySmbBackend) -> Unit): ManifestVerification {
    var result: ManifestVerification? = null
    rig(m) { r ->
      setup(r.backend)
      result = ShareManifestVerifier.verify(r.area, m, settled)
    }
    return result!!
  }

  private fun invariants(v: ManifestVerification) = v.violations.map { it.invariant }.toSet()

  /** Una condivisione finita: a e b nell'album, c rimasto nell'area privata; le sorgenti di a e b nel cestino privato. */
  private fun healthy(backend: InMemorySmbBackend) {
    backend.put("test-comune", "Album condivisi/Gita/a.jpg", a)
    backend.put("test-comune", "Album condivisi/Gita/b.jpg", b)
    backend.put("test", "mcg-campo/Backup/dev/2026/10/c.jpg", c)
    backend.put("test", ".mycloudgallery/trash/dev/files/id1", a)
    backend.put("test", ".mycloudgallery/trash/dev/files/id2", b)
  }

  @Test
  fun ilManifestoDelloScriptSiLegge() {
    val m = ShareManifest.parse(manifestText())
    assertThat(m.runId).isEqualTo("20261026-101500-1a2b3c4d")
    assertThat(m.privateShare to m.privateDir).isEqualTo("test" to "mcg-campo")
    assertThat(m.commonShare to m.commonDir).isEqualTo("test-comune" to "Album condivisi")
    assertThat(m.media.keys).containsExactly(shaA, shaB, shaC)
    assertThat(m.notMedia).hasSize(1)
  }

  @Test
  fun unManifestoOstileNonSiLegge() {
    val good = manifestText()
    fun refused(lines: List<String>) = runCatching { ShareManifest.parse(lines) }.isFailure
    assertThat(refused(good.filterNot { it.startsWith("#") })).isTrue()
    assertThat(refused(good.map { if (it.startsWith("private=")) "private=NAS|mcg-campo" else it })).isTrue()
    assertThat(refused(good.map { if (it.startsWith("common=")) "common=test2|Album condivisi" else it })).isTrue()
    assertThat(refused(good.map { if (it.startsWith("private=")) "private=test|../alessio" else it })).isTrue()
    assertThat(refused(good.map { if (it.startsWith("common=")) "common=test-comune|a/../b" else it })).isTrue()
    assertThat(refused(good.map { if (it.startsWith("private=")) "private=test|/assoluta" else it })).isTrue()
    assertThat(refused(good + "phone=zzzz|1|x")).isTrue()
    assertThat(refused(good + "altro=1")).isTrue()
    assertThat(refused(good.map { if (it.startsWith("run=")) "run=non-un-giro" else it })).isTrue()
    assertThat(refused(good.filterNot { it.startsWith("common=") })).isTrue()
  }

  @Test
  fun unaCondivisioneFinitaESanaPassaATuttiGliInvarianti() {
    val v = verify(manifest(listOf(shaA)), settled = true, setup = ::healthy)
    println(v.text())
    assertWithMessage(v.text()).that(v.ok).isTrue()
    assertThat(v.visible).isEqualTo(3)
    assertThat(v.trash).isEqualTo(2)
    assertThat(v.filesHashed).isAtLeast(5)
  }

  @Test
  fun unFileConAltriByteEUnFileAMeta() {
    val v = verify(manifest(), settled = true) { backend ->
      healthy(backend)
      backend.put("test-comune", "Album condivisi/Gita/mezzo.jpg", ByteArray(20))
    }
    assertThat(invariants(v)).contains(ShareInvariant.NO_HALF_FILE)
  }

  @Test
  fun unFixtureCheNonEUnMediaNonStaSulNas() {
    val v = verify(manifest(), settled = true) { backend ->
      healthy(backend)
      backend.put("test", "mcg-campo/Backup/dev/2026/10/testo.jpg", ByteArray(3))
    }
    assertThat(invariants(v)).contains(ShareInvariant.NO_HALF_FILE)
  }

  @Test
  fun lStessoMediaDueVolteNellAreaComuneEUnDoppione() {
    val v = verify(manifest(), settled = false) { backend ->
      healthy(backend)
      backend.put("test-comune", "Album condivisi/Gita/a (2).jpg", a)
    }
    assertThat(invariants(v)).contains(ShareInvariant.NO_DUPLICATE)
  }

  @Test
  fun unaCopiaNellAreaPrivataEUnaNellAComuneSonoAmmesseSoloAMeta() {
    val setup = { backend: InMemorySmbBackend ->
      healthy(backend)
      backend.put("test", "mcg-campo/Backup/dev/2026/10/a.jpg", a)
    }
    assertThat(invariants(verify(manifest(), settled = false, setup = setup))).doesNotContain(ShareInvariant.NO_DUPLICATE)
    assertThat(invariants(verify(manifest(), settled = true, setup = setup))).contains(ShareInvariant.NO_DUPLICATE)
  }

  @Test
  fun unMediaChiIlTelefonoNonHaPiuENonStaSulNasEUnaPerdita() {
    val v = verify(manifest(listOf(shaB)), settled = true) { backend ->
      backend.put("test-comune", "Album condivisi/Gita/a.jpg", a)
      backend.put("test", ".mycloudgallery/trash/dev/files/id2", b)
    }
    assertThat(invariants(v)).contains(ShareInvariant.NO_LOSS)
    val nowhere = verify(manifest(listOf(shaC)), settled = true) { backend ->
      backend.put("test-comune", "Album condivisi/Gita/a.jpg", a)
    }
    assertThat(invariants(nowhere)).contains(ShareInvariant.NO_LOSS)
  }

  @Test
  fun unTemporaneoRimastoAProvaFermaEUnViolazioneAMetaSoloSeNonEPropio() {
    val setup = { backend: InMemorySmbBackend ->
      healthy(backend)
      backend.put("test-comune", ".mycloudgallery/tmp/dev-op-abc-0", ByteArray(4))
    }
    assertThat(invariants(verify(manifest(), settled = true, setup = setup))).contains(ShareInvariant.NO_HALF_FILE)
    assertThat(verify(manifest(), settled = false, setup = setup).ok).isTrue()
    val foreign = verify(manifest(), settled = false) { backend ->
      healthy(backend)
      backend.put("test-comune", ".mycloudgallery/tmp/qualcosa-di-altro", ByteArray(4))
    }
    assertThat(invariants(foreign)).contains(ShareInvariant.NO_HALF_FILE)
  }

  @Test
  fun ilTrasportoDiSolaLetturaRifiutaOgniScritturaConZeroChiamateAlVero() {
    val m = manifest()
    val backend = InMemorySmbBackend().apply {
      addUser("test", "pw-del-banco")
      listOf("test", "test-comune").forEach { addShare(it, mapOf("test" to SimAccess.READ_WRITE)) }
    }
    val credentials = ShareAreaCredentials("192.168.178.58", "test", "x".toCharArray(), "test", "test-comune", null)
    val counter = DelegateCounter()
    val transport = ReadOnlyAreaTransport(backend, ShareAreaGuard.of(credentials, m.runId), ShareManifestVerifier.readable(m), AreaAuthLatch(), counter)
    val share = transport.connect(backend.binding("pc"), NasAddress("192.168.178.58"), Duration.ZERO).authenticate("test", "pw-del-banco".toCharArray()).connectShare("test")
    val before = counter.calls.get()
    fun refused(what: String, block: () -> Unit) {
      try {
        block()
        error("accettato: $what")
      } catch (_: AreaViolation) {
        // atteso
      }
    }
    refused("creare un file") { share.createExclusive("mcg-campo/x") }
    refused("creare una cartella") { share.mkdir("mcg-campo/y") }
    refused("togliere una cartella") { share.removeEmptyDirectory("mcg-campo/y") }
    refused("cancellare un file") { share.deleteFile("mcg-campo/x") }
    refused("aprire per cambiare") { share.openForChange("mcg-campo/x", exclusive = true) }
    refused("riaprire in scrittura") { share.openExistingForWrite("mcg-campo/x") }
    refused("aprire una cartella per aggiungere file") { share.openDirectory("mcg-campo", DirAccess.ADD_FILE) }
    assertWithMessage("chiamate arrivate al vero per i rifiuti").that(counter.calls.get()).isEqualTo(before)
  }

  @Test
  fun ilLettoreNonScriveMaiENonLeggeFuoriDalleCartelleDelManifesto() {
    val m = manifest()
    rig(m) { r ->
      healthy(r.backend)
      r.backend.put("test", "alessio-privato.txt", ByteArray(2))
      val before = r.counter.calls.get()
      val share = r.area
      fun refused(what: String, block: () -> Unit) {
        try {
          block()
          error("accettato: $what")
        } catch (_: AreaViolation) {
          // atteso
        }
      }
      refused("elenco della radice di una share") { share.entries("test", "") }
      refused("elenco di una cartella fuori dal manifesto") { share.entries("test", "Altro") }
      refused("lettura fuori dal manifesto") { share.read("test", "alessio-privato.txt") }
      refused("lettura con ..") { share.read("test", "mcg-campo/../alessio-privato.txt") }
      refused("lettura assoluta") { share.read("test", "/mcg-campo/x") }
      refused("share di famiglia") { share.entries("NAS", "mcg-campo") }
      refused("altra share di prova") { share.entries("test2", "mcg-campo") }
      refused("un prefisso che somiglia") { share.entries("test", "mcg-campo2") }
      assertWithMessage("chiamate arrivate al vero per i rifiuti").that(r.counter.calls.get()).isEqualTo(before)
      // Le cartelle del manifesto si leggono.
      assertThat(share.entries("test", "mcg-campo")).isNotEmpty()
      assertThat(share.read("test", "mcg-campo/Backup/dev/2026/10/c.jpg")).isEqualTo(c)
    }
  }
}
