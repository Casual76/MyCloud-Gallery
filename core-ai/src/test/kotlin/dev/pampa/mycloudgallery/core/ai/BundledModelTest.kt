package dev.pampa.mycloudgallery.core.ai

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.models.contract.ModelCatalog
import java.io.File
import java.security.MessageDigest
import org.junit.Test

/**
 * Fase 6 (P0, risposta Q11 (a), D44): il modello del ritaglio viaggia nell'APK come asset di core-ai, con l'impronta fissata nel
 * catalogo e la licenza accanto. Se qualcuno sostituisce il file senza aggiornare il catalogo (o il contrario), qui si vede.
 */
class BundledModelTest {

  private val assets = File("src/main/assets")

  @Test
  fun lAssetDelRitaglioEQuelloDelCatalogo() {
    val spec = ModelCatalog.CUTOUT_MAGICTOUCH
    val file = File(assets, spec.bundledAsset!!)
    assertThat(file.isFile).isTrue()
    assertThat(file.length()).isEqualTo(spec.sizeBytes)
    val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
    assertThat(Sha256(digest.joinToString("") { "%02x".format(it) })).isEqualTo(spec.sha256)
  }

  @Test
  fun licenzaEAvvisiStannoAccantoAlModello() {
    val license = File(assets, "cutout/LICENSE-Apache-2.0.txt").readText()
    assertThat(license).contains("Apache License")
    assertThat(license).contains("Version 2.0, January 2004")
    val notice = File(assets, "cutout/NOTICE.txt").readText()
    assertThat(notice).contains(ModelCatalog.CUTOUT_MAGICTOUCH.sha256.hex)
    assertThat(notice).contains("Apache 2.0")
  }

  /**
   * Nessun altro modello entra nell'APK di nascosto: il ritaglio (Fase 6) e i due dei volti (Fase 8, `BundledFaceModelsTest`), tutti nel
   * catalogo con il loro percorso.
   */
  @Test
  fun nessunAltroModelloNegliAsset() {
    val models = assets.walkTopDown().filter { it.isFile && it.extension in setOf("tflite", "litertlm", "task", "onnx", "bin") }.toList()
    val expected = (listOf(ModelCatalog.CUTOUT_MAGICTOUCH) + ModelCatalog.FACES).map { it.bundledAsset!! }
    assertThat(models.map { it.relativeTo(assets).invariantSeparatorsPath }).containsExactlyElementsIn(expected)
  }
}
