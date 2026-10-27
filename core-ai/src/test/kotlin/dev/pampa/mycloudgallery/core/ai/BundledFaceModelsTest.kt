package dev.pampa.mycloudgallery.core.ai

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.models.LocalModels
import dev.pampa.mycloudgallery.core.models.contract.BundledAssets
import dev.pampa.mycloudgallery.core.models.contract.EnsureResult
import dev.pampa.mycloudgallery.core.models.contract.ModelCatalog
import dev.pampa.mycloudgallery.core.models.contract.ModelSpec
import dev.pampa.mycloudgallery.core.models.contract.ModelTrigger
import dev.pampa.mycloudgallery.core.models.noop.ClosedModelNetworkGate
import dev.pampa.mycloudgallery.core.models.source.BundledModelFetcher
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Fase 8 (P1, D6-D8, D39, risposta Q1 (a)): i due modelli dei volti viaggiano nell'APK come asset di core-ai (`faces/`), con le impronte del
 * catalogo e le licenze e l'avviso accanto. Se qualcuno sostituisce un file senza aggiornare il catalogo (o il contrario), qui si vede; lo
 * stesso controllo, sull'APK di release, lo fa `tools/check.ps1`.
 */
class BundledFaceModelsTest {

  private val assets = File("src/main/assets")

  private fun sha256(file: File): Sha256 =
    Sha256(MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) })

  @Test
  fun gliAssetDeiVoltiSonoQuelliDelCatalogo() {
    for (spec in ModelCatalog.FACES) {
      val file = File(assets, spec.bundledAsset!!)
      assertThat(file.isFile).isTrue()
      assertThat(file.length()).isEqualTo(spec.sizeBytes)
      assertThat(sha256(file)).isEqualTo(spec.sha256)
    }
  }

  @Test
  fun licenzeEAvvisiStannoAccantoAiModelli() {
    val mit = File(assets, "faces/LICENSE-YuNet.txt").readText()
    assertThat(mit).contains("MIT License")
    assertThat(mit).contains("Copyright (c) 2020 Shiqi Yu")
    val apache = File(assets, "faces/LICENSE-SFace.txt").readText()
    assertThat(apache).contains("Apache License")
    assertThat(apache).contains("Version 2.0, January 2004")
    val notice = File(assets, "faces/NOTICE.txt").readText()
    for (spec in ModelCatalog.FACES) {
      assertThat(notice).contains(spec.sha256.hex)
      assertThat(notice).contains(spec.sizeBytes.toString())
      assertThat(notice).contains(spec.fileName)
    }
    // L'origine: OpenCV Zoo, il commit fissato e le impronte degli ONNX d'ingresso (le stesse di tools/models/faces/fingerprints.json).
    assertThat(notice).contains("48b10fa505aeb51fbd127fc60a5c660040e823b7")
    val fingerprints = File("../tools/models/faces/fingerprints.json").readText()
    for (onnx in Regex("""sha256.\s*:\s*.([0-9a-f]{64})""").findAll(fingerprints).map { it.groupValues[1] }.take(3)) {
      assertThat(notice).contains(onnx)
    }
    assertThat(notice).contains("MIT")
    assertThat(notice).contains("Apache 2.0")
    assertThat(notice).contains("CASIA-WebFace")
  }

  /** Nella cartella dei volti solo i due modelli, le due licenze e l'avviso: niente ONNX ne' altri modelli di nascosto. */
  @Test
  fun nellaCartellaDeiVoltiSoloIlNecessario() {
    val names = File(assets, "faces").listFiles().orEmpty().map { it.name }
    val expected = ModelCatalog.FACES.map(ModelSpec::fileName) + listOf("LICENSE-YuNet.txt", "LICENSE-SFace.txt", "NOTICE.txt")
    assertThat(names).containsExactlyElementsIn(expected)
  }

  /**
   * Da un capo all'altro con i file veri: il gestore dei modelli estrae ogni asset dei volti dalla cartella degli asset (come fa `:app` dall'APK,
   * senza rete), ne verifica dimensione e SHA-256 contro il catalogo e lo consegna con il file verificato in `models/<sha256>`.
   */
  @Test
  fun ilGestoreEstraeEVerificaIFileVeri() = runBlocking<Unit> {
    val root = Files.createTempDirectory("mcg-faces-models").toFile()
    try {
      val fetcher = BundledModelFetcher(BundledAssets { name -> File(assets, name).takeIf { it.isFile }?.inputStream() })
      val models = LocalModels(root = root, fetcher = fetcher, networkGate = ClosedModelNetworkGate)
      for (spec in ModelCatalog.FACES) {
        assertThat(models.manager.ensure(spec.id, ModelTrigger.USER_NOW, 60.seconds)).isEqualTo(EnsureResult.Ready)
        models.files.open(spec.id)!!.use { verified ->
          assertThat(verified.file.length()).isEqualTo(spec.sizeBytes)
          assertThat(verified.file.name).isEqualTo(spec.sha256.hex)
          assertThat(sha256(verified.file)).isEqualTo(spec.sha256)
        }
      }
      assertThat(models.manager.usage().bytesById.keys).containsExactlyElementsIn(ModelCatalog.FACES.map { it.id })
    } finally {
      root.deleteRecursively()
    }
  }
}
