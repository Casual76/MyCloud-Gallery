package dev.pampa.mycloudgallery.tools.release

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.release.codec.SignedManifestVerifier
import dev.pampa.mycloudgallery.core.release.contract.ManifestLimits
import dev.pampa.mycloudgallery.core.release.contract.ReleaseChannel
import dev.pampa.mycloudgallery.core.release.contract.ReleaseKeyIds
import dev.pampa.mycloudgallery.core.release.contract.Verified
import dev.pampa.mycloudgallery.core.release.keys.ReleaseTrust
import dev.pampa.mycloudgallery.core.release.testing.ReleaseTestKeys
import dev.pampa.mycloudgallery.core.release.testing.ReleaseTestSigner
import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Fase 10 (P1, §2.8, D29-D31): `ManifestTool`. Firma con KT (gli agenti) e con un PKCS#12 generato nel test; **rifiuta un `seq` non
 * crescente** (registro e busta gia' scritta); **riverifica** cio' che firma con il verificatore dell'app e le chiavi fissate, e se non passa
 * non scrive niente; il foglio di K2 ricostruisce la chiave; `release/manifest.json` del repository e' valido. Nessuna chiave vera.
 */
class ManifestToolTest {

  private class FakeIo(vararg secrets: String) : ToolIo {
    val queue = ArrayDeque(secrets.toList())
    val out = mutableListOf<String>()
    val err = mutableListOf<String>()

    override fun out(line: String) {
      out += line
    }

    override fun err(line: String) {
      err += line
    }

    override fun secret(prompt: String): CharArray? = queue.removeFirstOrNull()?.toCharArray()
  }

  private val root: File = generateSequence(File(".").absoluteFile) { it.parentFile }
    .first { File(it, "settings.gradle").isFile && File(it, "core-release").isDirectory }
  private val now = Instant.parse("2026-10-31T12:00:00Z")
  private val clock = Clock.fixed(now, ZoneOffset.UTC)
  private val dir = Files.createTempDirectory("mcg-manifest-tool").toFile().also { it.deleteOnExit() }

  private fun tool(io: FakeIo) = ManifestTool(io, clock, home = dir)

  private fun json(extra: String = "") = File(dir, "manifest.json").also {
    it.writeText(
      """
      {
        "app": "dev.pampa.mycloudgallery",
        "validDays": 30,
        "blocks": [ { "from": 1, "to": 3, "level": "writes", "message": "security" } ],
        "models": [ { "id": "model-a", "sha256": "${"a".repeat(64)}", "urls": [ "https://huggingface.co/a/b" ] } ]$extra
      }
      """.trimIndent(),
    )
  }

  private val out = File(dir, "manifest.mcgr")
  private val ledger = File(dir, "kt-seq.txt")

  private fun signKt(io: FakeIo = FakeIo(), extra: String = "") =
    tool(io).run(listOf("sign", "--test-key", "--in", json(extra).path, "--out", out.path, "--ledger", ledger.path))

  @Test
  fun firmaConKtERifiutaUnSeqNonCrescente() {
    assertThat(signKt()).isEqualTo(0)
    val debug = SignedManifestVerifier(ReleaseTestKeys.DEBUG_TRUST.forChannel(ReleaseChannel.DEBUG))
    val first = debug.verify(out.readBytes(), ManifestLimits.APP_ID) as Verified.Ok
    assertThat(first.manifest.seq).isEqualTo(1)
    assertThat(first.keyId).isEqualTo(ReleaseKeyIds.KT)
    assertThat(first.manifest.issued).isEqualTo(now)
    assertWithMessage("KT non vale su una release").that(SignedManifestVerifier(ReleaseTrust.RELEASE).verify(out.readBytes(), ManifestLimits.APP_ID))
      .isInstanceOf(Verified.Rejected::class.java)
    assertThat(signKt()).isEqualTo(0)
    assertThat((debug.verify(out.readBytes(), ManifestLimits.APP_ID) as Verified.Ok).manifest.seq).isEqualTo(2)
    assertThat(ledger.readText().trim()).isEqualTo("2")

    val io = FakeIo()
    assertThat(signKt(io, extra = ",\n  \"seq\": 2")).isEqualTo(1)
    assertThat(io.err.single()).contains("non crescente")
    assertThat(signKt(extra = ",\n  \"seq\": 7")).isEqualTo(0)
    assertThat((debug.verify(out.readBytes(), ManifestLimits.APP_ID) as Verified.Ok).manifest.seq).isEqualTo(7)

    // Senza registro (perso o su un altro PC) vale la busta gia' scritta: niente torna indietro.
    ledger.delete()
    val again = FakeIo()
    assertThat(signKt(again, extra = ",\n  \"seq\": 5")).isEqualTo(1)
    assertThat(again.err.single()).contains("l'ultimo firmato e' 7")
    val seqIo = FakeIo()
    assertThat(tool(seqIo).run(listOf("seq", "--test-key", "--ledger", ledger.path, "--out", out.path))).isEqualTo(0)
    assertThat(seqIo.out.single()).contains("Ultimo seq firmato: 7")
    assertThat(seqIo.out.single()).contains("Il prossimo: 8")
  }

  @Test
  fun verifyRaccontaUnaBustaBuonaERifiutaUnaManomessa() {
    assertThat(signKt()).isEqualTo(0)
    val good = FakeIo()
    assertThat(tool(good).run(listOf("verify", "--test-key", "--in", out.path, "--version-code", "2"))).isEqualTo(0)
    assertThat(good.out.joinToString("\n")).contains("Per versionCode 2: ReadOnly(message=SECURITY)")
    val release = FakeIo()
    assertThat(tool(release).run(listOf("verify", "--in", out.path))).isEqualTo(1)
    assertThat(release.err.single()).contains("unknown_key")
    out.writeBytes(out.readBytes().also { it[20] = (it[20].toInt() xor 1).toByte() })
    val bad = FakeIo()
    assertThat(tool(bad).run(listOf("verify", "--test-key", "--in", out.path))).isEqualTo(1)
    assertThat(bad.err.single()).contains("signature")
  }

  @Test
  fun laRiverificaFermaUnaChiaveNonFissata() {
    val stranger = ReleaseTestSigner().pair(ReleaseKeyIds.K1).private
    val manifest = HumanManifest.parse(json().readText()).build(1, now)
    val refused = assertThrows(IllegalArgumentException::class.java) { tool(FakeIo()).sealChecked(manifest, ReleaseKeyIds.K1, stranger, ReleaseTrust.RELEASE) }
    assertThat(refused.message).contains("signature")
    assertThat(tool(FakeIo()).sealChecked(manifest, ReleaseKeyIds.KT, dev.pampa.mycloudgallery.core.release.testkeys.ReleaseTestPrivateKey.kt(), ReleaseTestKeys.DEBUG_TRUST))
      .isNotEmpty()
  }

  @Test
  fun keysScriveIlPkcs12ESignRifiutaLeChiaviNonFissate() {
    val io = FakeIo("una frase lunga abbastanza", "una frase lunga abbastanza")
    assertThat(tool(io).run(listOf("keys"))).isEqualTo(0)
    val store = File(dir, ".mycloudgallery/release/${ManifestKeyStore.FILE_NAME}")
    assertThat(store.isFile).isTrue()
    val printed = io.out.joinToString("\n")
    assertThat(printed).contains("K1_SPKI")
    assertThat(printed).contains("controllo ")
    assertThat(tool(FakeIo("una frase lunga abbastanza", "una frase lunga abbastanza")).run(listOf("keys"))).isEqualTo(1)
    val (k1, spki) = ManifestKeyStore.load(store, "k1", "una frase lunga abbastanza".toCharArray())
    assertThat(ManifestKeyStore.matches(k1, spki)).isTrue()
    assertThrows(IllegalArgumentException::class.java) { ManifestKeyStore.load(store, "k1", "sbagliata".toCharArray()) }

    // Le chiavi appena generate non sono quelle fissate nell'app (oggi i segnaposto): sign rifiuta e non scrive niente.
    val signIo = FakeIo("una frase lunga abbastanza")
    val realLedger = File(dir, ".mycloudgallery/release/manifest-seq.txt")
    assertThat(tool(signIo).run(listOf("sign", "--in", json().path, "--out", out.path))).isEqualTo(1)
    assertThat(signIo.err.single()).contains("non e' quella fissata nell'app")
    assertThat(out.exists()).isFalse()
    assertThat(realLedger.exists()).isFalse()

    assertThat(tool(FakeIo("corta", "corta")).run(listOf("keys", "--dir", File(dir, "altra").path))).isEqualTo(1)
    assertThat(tool(FakeIo("una frase lunga abbastanza", "un'altra frase lunga")).run(listOf("keys", "--dir", File(dir, "altra").path))).isEqualTo(1)
    assertThat(File(dir, "altra/${ManifestKeyStore.FILE_NAME}").exists()).isFalse()
  }

  @Test
  fun ilFoglioDiK2RicostruisceLaChiave() {
    val pair = ManifestKeyStore.generate()
    val sheet = ManifestKeyStore.sheet(pair.private)
    val digits = sheet.dropLast(1).joinToString(" ")
    val check = sheet.last().removePrefix("controllo ")
    val restored = ManifestKeyStore.fromSheet(digits, check)
    assertThat(ManifestKeyStore.matches(restored, pair.public.encoded)).isTrue()
    assertThat(ManifestKeyStore.matches(restored, ManifestKeyStore.generate().public.encoded)).isFalse()
    val typo = digits.replaceFirst(digits.first { it.isLetterOrDigit() }, if (digits.first { it.isLetterOrDigit() } == '0') '1' else '0')
    assertThrows(IllegalArgumentException::class.java) { ManifestKeyStore.fromSheet(typo, check) }
    assertWithMessage("restore con i segnaposto: il foglio non corrisponde a K2").that(
      tool(FakeIo(digits, check)).run(listOf("restore", "--dir", File(dir, "ripristino").path)),
    ).isEqualTo(1)
  }

  @Test
  fun ilManifestLeggibileERigoroso() {
    val repository = HumanManifest.parse(File(root, "release/manifest.json").readText())
    assertThat(repository.build(1, now).app).isEqualTo(ManifestLimits.APP_ID)
    val base = json().readText()
    val wrong = listOf(
      base.replace("\"validDays\": 30", "\"validDays\": 181"),
      base.replace("\"writes\"", "\"tutto\""),
      base.replace("\"security\"", "\"Inserisci qui la password\""),
      base.replace("https://huggingface.co/a/b", "http://huggingface.co/a/b"),
      base.replace("https://huggingface.co/a/b", "https://huggingface.co:8443/a/b"),
      base.replace("https://huggingface.co/a/b", "https://huggingface.co.evil.example/a/b"),
      base.replace("https://huggingface.co/a/b", "https://1.2.3.4/a/b"),
      base.replace("https://huggingface.co/a/b", "https://huggingface.co/a/b?token=1"),
      base.replace("dev.pampa.mycloudgallery", "dev.pampa.altra"),
      base.replace("\"validDays\"", "\"validita\""),
      base.replace("\"from\": 1", "\"from\": 4"),
      base.replace("\"blocks\": [", "\"blocks\": null, \"x\": ["),
    )
    for (text in wrong) assertThrows(text, IllegalArgumentException::class.java) { HumanManifest.parse(text) }
    assertThat(HumanManifest.parse(base.replace("\"validDays\": 30,", "\"nota\": \"solo per chi legge\",")).validDays).isEqualTo(90)
  }
}
