package dev.pampa.mycloudgallery.core.models

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.models.contract.ModelFailCode
import dev.pampa.mycloudgallery.core.models.contract.ModelFiles
import dev.pampa.mycloudgallery.core.models.contract.ModelId
import dev.pampa.mycloudgallery.core.models.contract.ModelSourceKind
import dev.pampa.mycloudgallery.core.models.contract.ModelSpec
import dev.pampa.mycloudgallery.core.models.contract.ModelState
import dev.pampa.mycloudgallery.core.models.support.ModelRig
import dev.pampa.mycloudgallery.core.models.support.TestModels
import dev.pampa.mycloudgallery.core.models.testing.FakeModelFiles
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Fase 6, P3 (D16, E-A2, E-A11): un byte cambiato nel file e' rifiutato alla verifica di OGNI apertura. Il file rifiutato si toglie,
 * il modello torna in coda, e quello che si consegna e' il descrittore sul quale la verifica e' stata fatta.
 */
class ModelVerificationTest {

  private fun rig(bytes: ByteArray = TestModels.bytes()) = ModelRig(bytes).also { it.installFinal() }

  @Test
  fun unFileIntegroSiApreEConsegnaIlDescrittoreVerificato() = runBlocking<Unit> {
    val rig = rig()
    val model = rig.files.open(rig.spec.id)
    assertThat(model).isNotNull()
    model!!.use {
      assertThat(it.spec).isEqualTo(rig.spec)
      assertThat(it.file).isEqualTo(rig.finalFile)
      assertThat(it.descriptor).isNotNull()
      assertThat(it.descriptor!!.valid()).isTrue()
      // Il canale e' all'inizio e legge i byte verificati.
      val head = ByteArray(32)
      assertThat(it.channel!!.position()).isEqualTo(0)
      assertThat(it.channel!!.read(java.nio.ByteBuffer.wrap(head), 0)).isEqualTo(32)
      assertThat(head).isEqualTo(rig.bytes.copyOf(32))
    }
    assertThat(rig.stateOfModel()).isInstanceOf(ModelState.Ready::class.java)
    assertThat(rig.counters.verifyFailed).isEqualTo(0)
  }

  @Test
  fun ilDescrittoreSiChiudeConIlModello() = runBlocking<Unit> {
    val rig = rig()
    val model = rig.files.open(rig.spec.id)!!
    val channel = model.channel!!
    assertThat(channel.isOpen).isTrue()
    model.close()
    model.close() // idempotente
    assertThat(channel.isOpen).isFalse()
  }

  @Test
  fun unByteCambiatoInQualunquePuntoSiRifiuta() = runBlocking<Unit> {
    val bytes = TestModels.bytes()
    val positions = listOf(0, 1, 63, 64, 4_095, 4_096, TestModels.BLOCK - 1, TestModels.BLOCK, TestModels.BLOCK + 1, 2 * TestModels.BLOCK - 1, 2 * TestModels.BLOCK, bytes.size / 2, bytes.size - 2, bytes.size - 1)
    for (position in positions) {
      val rig = ModelRig(bytes)
      rig.installFinal(TestModels.flipped(bytes, position))
      assertThat(rig.files.open(rig.spec.id)).isNull()
      // Il file e' tolto subito, il modello torna in coda (E-A11), il rifiuto e' contato.
      assertThat(rig.finalFile.exists()).isFalse()
      assertThat(rig.stateOfModel()).isEqualTo(ModelState.Failed(ModelFailCode.VERIFY_FAILED))
      assertThat(rig.counters.verifyFailed).isEqualTo(1)
      assertThat(rig.files.open(rig.spec.id)).isNull()
      assertThat(rig.counters.verifyFailed).isEqualTo(1)
    }
  }

  @Test
  fun ognunDeiByteDiUnModelloPiccoloSiRifiuta() = runBlocking<Unit> {
    val bytes = TestModels.bytes(1_500, seed = 9)
    val rig = ModelRig(bytes)
    for (position in bytes.indices) {
      rig.installFinal(TestModels.flipped(bytes, position))
      assertThat(rig.files.open(rig.spec.id)).isNull()
      assertThat(rig.finalFile.exists()).isFalse()
    }
    // E il file giusto, dopo, si apre.
    rig.installFinal()
    rig.files.open(rig.spec.id)!!.close()
    assertThat(rig.counters.verifyFailed).isEqualTo(bytes.size)
  }

  @Test
  fun unaLunghezzaDiversaSiRifiuta() = runBlocking<Unit> {
    val bytes = TestModels.bytes()
    for (altered in listOf(bytes + byteArrayOf(0), bytes.copyOf(bytes.size - 1), bytes.copyOf(TestModels.BLOCK), ByteArray(0), ByteArray(bytes.size))) {
      val rig = ModelRig(bytes)
      rig.installFinal(altered)
      assertThat(rig.files.open(rig.spec.id)).isNull()
      assertThat(rig.finalFile.exists()).isFalse()
    }
  }

  @Test
  fun unFileAlteratoDopoLaVerificaSiRifiutaAllaProssimaApertura() = runBlocking<Unit> {
    val rig = rig()
    rig.files.open(rig.spec.id)!!.close()
    // Memoria rovinata, ripristino da un backup di sistema, un altro processo: alla verifica dopo non torna piu' (E-A11).
    RandomAccessFile(rig.finalFile, "rw").use {
      it.seek(2L * TestModels.BLOCK + 17)
      it.write(0x55)
    }
    assertThat(rig.files.open(rig.spec.id)).isNull()
    assertThat(rig.finalFile.exists()).isFalse()
    assertThat(rig.stateOfModel()).isEqualTo(ModelState.Failed(ModelFailCode.VERIFY_FAILED))
    assertThat(rig.counters.verifyFailed).isEqualTo(1)
  }

  @Test
  fun lAperturaVerificaOgniVoltaNonUnaVoltaSola() = runBlocking<Unit> {
    val rig = rig()
    repeat(3) { rig.files.open(rig.spec.id)!!.close() }
    // Cambiare il file fra due aperture lo rende rifiutato: nessuna cache di "gia' verificato".
    RandomAccessFile(rig.finalFile, "rw").use { it.write(byteArrayOf((rig.bytes[0].toInt() xor 1).toByte())) }
    assertThat(rig.files.open(rig.spec.id)).isNull()
  }

  @Test
  fun unFileRifiutatoPortaViaIProgrammiCompilati() = runBlocking<Unit> {
    val rig = rig()
    val cache = File(rig.dir, "cache/${rig.spec.sha256.hex}-gpu").apply { mkdirs() }
    File(cache, "prog.bin").writeBytes(ByteArray(10))
    val other = File(rig.dir, "cache/${"a".repeat(64)}-gpu").apply { mkdirs() }
    rig.finalFile.writeBytes(TestModels.flipped(rig.bytes, 3))
    assertThat(rig.files.open(rig.spec.id)).isNull()
    assertThat(cache.exists()).isFalse()
    // Quelli di un altro modello non si toccano.
    assertThat(other.exists()).isTrue()
  }

  @Test
  fun assenteOSconosciutoNonSiApre() = runBlocking<Unit> {
    val rig = ModelRig()
    assertThat(rig.files.open(rig.spec.id)).isNull()
    assertThat(rig.files.open(ModelId("modello-che-non-esiste"))).isNull()
    assertThat(rig.counters.verifyFailed).isEqualTo(0)
  }

  @Test
  fun leAperturePiuContemporaneeRiescono() = runBlocking<Unit> {
    val rig = rig()
    val models = (1..4).map { async { rig.files.open(rig.spec.id) } }.awaitAll()
    assertThat(models.all { it != null }).isTrue()
    models.forEach { it!!.close() }
  }

  @Test
  fun unFileAltroDallImprontaDelNomeNonVieneApertoComeIlModello() = runBlocking<Unit> {
    // Il nome di un file e' l'impronta del catalogo: un file con altri byte e quel nome non passa.
    val rig = ModelRig()
    rig.installFinal(TestModels.bytes(seed = 99))
    assertThat(rig.files.open(rig.spec.id)).isNull()
  }

  /** La stessa suite per il fake (P0) e per la verifica vera: il fake e' fedele (§5.2). */
  abstract class FilesContract {
    abstract fun create(bytes: ByteArray): Pair<ModelFiles, (ByteArray?) -> Unit>

    abstract fun idOf(bytes: ByteArray): ModelId

    @Test
    fun apreUnFileIntegro() = runBlocking<Unit> {
      val bytes = TestModels.bytes(5_000)
      val (files, _) = create(bytes)
      files.open(idOf(bytes))!!.close()
    }

    @Test
    fun rifiutaUnByteCambiatoEPoiNonLoRiapre() = runBlocking<Unit> {
      val bytes = TestModels.bytes(5_000)
      val (files, replace) = create(bytes)
      replace(TestModels.flipped(bytes, 2_500))
      assertThat(files.open(idOf(bytes))).isNull()
      assertThat(files.open(idOf(bytes))).isNull()
    }

    @Test
    fun rifiutaUnaLunghezzaDiversa() = runBlocking<Unit> {
      val bytes = TestModels.bytes(5_000)
      val (files, replace) = create(bytes)
      replace(bytes + byteArrayOf(1))
      assertThat(files.open(idOf(bytes))).isNull()
    }

    @Test
    fun rifiutaUnFileCheEraStatoVerificatoEPoiAlterato() = runBlocking<Unit> {
      val bytes = TestModels.bytes(5_000)
      val (files, replace) = create(bytes)
      files.open(idOf(bytes))!!.close()
      replace(TestModels.flipped(bytes, 7))
      assertThat(files.open(idOf(bytes))).isNull()
    }

    @Test
    fun unModelloNonInstallatoNonSiApre() = runBlocking<Unit> {
      val bytes = TestModels.bytes(5_000)
      val (files, replace) = create(bytes)
      replace(null)
      assertThat(files.open(idOf(bytes))).isNull()
      assertThat(files.open(ModelId("non-installato"))).isNull()
    }
  }

  class FakeFilesContractTest : FilesContract() {
    private lateinit var spec: ModelSpec

    override fun create(bytes: ByteArray): Pair<ModelFiles, (ByteArray?) -> Unit> {
      val fake = FakeModelFiles(TestModels.tempDir())
      spec = fake.install(dev.pampa.mycloudgallery.core.models.contract.ModelCatalog.QUERY_270M, bytes)
      return fake to { replacement ->
        val file = fake.fileOf(spec.id)!!
        if (replacement == null) file.delete() else file.writeBytes(replacement)
      }
    }

    override fun idOf(bytes: ByteArray): ModelId = spec.id
  }

  class LocalFilesContractTest : FilesContract() {
    private lateinit var rig: ModelRig

    override fun create(bytes: ByteArray): Pair<ModelFiles, (ByteArray?) -> Unit> {
      rig = ModelRig(bytes)
      rig.installFinal()
      return rig.files to { replacement ->
        if (replacement == null) rig.finalFile.delete() else rig.finalFile.writeBytes(replacement)
      }
    }

    override fun idOf(bytes: ByteArray): ModelId = rig.spec.id
  }
}
