package dev.pampa.mycloudgallery.core.ai.faces

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineError
import dev.pampa.mycloudgallery.core.ai.contract.AiEngineException
import dev.pampa.mycloudgallery.core.ai.contract.AlignedFace
import dev.pampa.mycloudgallery.core.ai.contract.DetectPass
import dev.pampa.mycloudgallery.core.ai.contract.FaceEngineLimits
import dev.pampa.mycloudgallery.core.ai.contract.FaceImage
import dev.pampa.mycloudgallery.core.model.FaceSpaceCode
import dev.pampa.mycloudgallery.core.models.LocalModels
import dev.pampa.mycloudgallery.core.models.contract.BundledAssets
import dev.pampa.mycloudgallery.core.models.contract.EnsureResult
import dev.pampa.mycloudgallery.core.models.contract.ModelCatalog
import dev.pampa.mycloudgallery.core.models.contract.ModelFiles
import dev.pampa.mycloudgallery.core.models.contract.ModelId
import dev.pampa.mycloudgallery.core.models.contract.ModelTrigger
import dev.pampa.mycloudgallery.core.models.contract.VerifiedModel
import dev.pampa.mycloudgallery.core.models.noop.ClosedModelNetworkGate
import dev.pampa.mycloudgallery.core.models.source.BundledModelFetcher
import dev.pampa.mycloudgallery.core.rules.run.ThreadPriorities
import dev.pampa.mycloudgallery.core.rules.run.YieldToApp
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test

/**
 * Fase 8 (P2): il montaggio dei motori veri sopra `ModelFiles` (`FaceEnginesFactory`, `FaceModelOpeners`), con i file veri dell'APK estratti dal gestore dei
 * modelli come fa l'app (`BundledFaceModelsTest` di P1 prova l'estrazione). Sulla JVM non ci sono Android ne' la libreria nativa di LiteRT: la mappatura del
 * descrittore e la sessione vera sono i due punti dove la prova mette i suoi. Si provano tutto cio' che sta intorno all'interprete: il modello che non c'e'
 * e' `MODEL_UNAVAILABLE`; ogni apertura verifica il file (`ModelFiles.open`), legge i byte del file verificato, passa 4 thread; il modello verificato si chiude
 * con la sessione e anche quando la sessione non si apre.
 */
class FaceEnginesFactoryTest {

  private val assets = File("src/main/assets")

  private fun models(root: File) = LocalModels(
    root = root,
    fetcher = BundledModelFetcher(BundledAssets { name -> File(assets, name).takeIf { it.isFile }?.inputStream() }),
    networkGate = ClosedModelNetworkGate,
  )

  private fun image() = FaceImage(IntArray(64 * 64) { 0xFF404040.toInt() }, 64, 64)

  /** La mappatura della prova: i byte del file verificato in ordine nativo (sul dispositivo e' `ModelBytes.map` sul descrittore). */
  private val mapFile: (VerifiedModel) -> ByteBuffer = { model ->
    FileInputStream(model.file).use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, model.file.length()) }.order(ByteOrder.nativeOrder())
  }

  private class NoRun : DetectorSession {
    var closed = 0

    override fun run(frame: FloatArray): YuNetOutputs = YuNetOutputs.allocate()

    override fun close() {
      closed++
    }
  }

  @Test
  fun senzaIModelliIMotoriNonPartono_MODEL_UNAVAILABLE() = runTest {
    val root = Files.createTempDirectory("mcg-faces-factory").toFile()
    try {
      val engines = FaceEnginesFactory.create(models(root).files)
      assertThat(engines.embedder.space).isEqualTo(FaceSpaceCode.CURRENT)
      assertThat(engines.aligner).isSameInstanceAs(FaceAligner)
      assertThat(engines.quality).isSameInstanceAs(FaceQuality)
      val detect = runCatching { engines.detector.detect(image(), DetectPass.PREVIEW) }.exceptionOrNull() as AiEngineException
      assertThat(detect.error).isEqualTo(AiEngineError.MODEL_UNAVAILABLE)
      val embed = runCatching { engines.embedder.embed(listOf(AlignedFace(IntArray(112 * 112)))) }.exceptionOrNull() as AiEngineException
      assertThat(embed.error).isEqualTo(AiEngineError.MODEL_UNAVAILABLE)
      engines.release()
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun ognuApertura_VerificaIlFile_LeggeIByteVerificati_PassaQuattroThread_EIlModelloSiChiudeConLaSessione() = runTest {
    val root = Files.createTempDirectory("mcg-faces-factory").toFile()
    try {
      val models = models(root)
      for (spec in ModelCatalog.FACES) assertThat(models.manager.ensure(spec.id, ModelTrigger.USER_NOW, 60.seconds)).isEqualTo(EnsureResult.Ready)
      val opened = ArrayList<VerifiedModel>()
      val counting = object : ModelFiles {
        override suspend fun open(id: ModelId): VerifiedModel? = models.files.open(id)?.also { opened += it }
      }
      var seen: ByteBuffer? = null
      var threads = -1
      val session = NoRun()
      val opener = FaceModelOpeners.detector(counting, ModelCatalog.FACE_YUNET, FaceEngineLimits.CPU_THREADS, mapFile) { bytes, t ->
        seen = bytes
        threads = t
        session
      }
      val owned = opener.open()
      assertThat(opened).hasSize(1)
      assertThat(opened.single().spec.id).isEqualTo(ModelCatalog.FACE_YUNET.id)
      assertThat(threads).isEqualTo(4)
      val bytes = seen!!
      assertThat(bytes.isDirect).isTrue()
      assertThat(bytes.order()).isEqualTo(ByteOrder.nativeOrder())
      assertThat(bytes.capacity().toLong()).isEqualTo(ModelCatalog.FACE_YUNET.sizeBytes)
      // Sono i byte del file dell'APK (il formato TFLite: "TFL3" al byte 4).
      val head = ByteArray(64).also { bytes.duplicate().get(it) }
      assertThat(head).isEqualTo(File(assets, ModelCatalog.FACE_YUNET.bundledAsset!!).inputStream().use { it.readNBytes(64) })
      assertThat(String(head, 4, 4, Charsets.US_ASCII)).isEqualTo("TFL3")
      // Il modello verificato resta aperto finche' la sessione vive e si chiude con lei (per ultimo).
      assertThat(opened.single().descriptor!!.valid()).isTrue()
      owned.close()
      assertThat(session.closed).isEqualTo(1)
      assertThat(opened.single().descriptor!!.valid()).isFalse()
      // Una seconda apertura verifica di nuovo.
      opener.open().close()
      assertThat(opened).hasSize(2)
    } finally {
      root.deleteRecursively()
    }
  }

  private class NoEmbed : EmbedderSession {
    override fun run(rgb: FloatArray): FloatArray = FloatArray(FaceSpaceCode.CURRENT.dimension)

    override fun close() = Unit
  }

  @Test
  fun dentroUnLavoroDiFondoConLAppInUso_IMotoriSiApronoConDueThread_AltrimentiConQuattro() = runTest {
    // Review generale, OB-3: i thread di un interprete si scelgono all'apertura. Dentro un lavoro di fondo che si fa da parte (`YieldToApp`,
    // l'app in uso) sono 2; in secondo piano, e per cio' che l'utente aspetta (nessun elemento nel contesto), restano i 4 di sempre.
    val root = Files.createTempDirectory("mcg-faces-factory").toFile()
    try {
      val models = models(root)
      for (spec in ModelCatalog.FACES) assertThat(models.manager.ensure(spec.id, ModelTrigger.USER_NOW, 60.seconds)).isEqualTo(EnsureResult.Ready)
      val threads = ArrayList<Int>()
      val detector = FaceModelOpeners.detector(models.files, ModelCatalog.FACE_YUNET, FaceEngineLimits.CPU_THREADS, mapFile) { _, t ->
        threads += t
        NoRun()
      }
      val embedder = FaceModelOpeners.embedder(models.files, ModelCatalog.FACE_SFACE, FaceEngineLimits.CPU_THREADS, mapFile) { _, t ->
        threads += t
        NoEmbed()
      }
      val inUse = AtomicBoolean(true)
      val untouched = object : ThreadPriorities {
        override fun current(): Int? = null

        override fun set(priority: Int) = Unit
      }
      val work = YieldToApp({ inUse.get() }, untouched)
      withContext(work) {
        detector.open().close()
        embedder.open().close()
      }
      inUse.set(false)
      withContext(work) {
        detector.open().close()
        embedder.open().close()
      }
      inUse.set(true)
      detector.open().close()
      assertThat(threads).containsExactly(2, 2, 4, 4, 4).inOrder()
      assertThat(YieldToApp.YIELDING_INFERENCE_THREADS).isEqualTo(2)
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun seLaSessioneNonSiApreOLaMappaturaFallisce_IlModelloVerificatoSiChiudeLoStesso() = runTest {
    val root = Files.createTempDirectory("mcg-faces-factory").toFile()
    try {
      val models = models(root)
      for (spec in ModelCatalog.FACES) assertThat(models.manager.ensure(spec.id, ModelTrigger.USER_NOW, 60.seconds)).isEqualTo(EnsureResult.Ready)
      val opened = ArrayList<VerifiedModel>()
      val counting = object : ModelFiles {
        override suspend fun open(id: ModelId): VerifiedModel? = models.files.open(id)?.also { opened += it }
      }
      val noSession = FaceModelOpeners.embedder(counting, ModelCatalog.FACE_SFACE, 4, mapFile) { _, _ -> throw AiEngineException(AiEngineError.MODEL_UNAVAILABLE) }
      assertThat((runCatching { noSession.open() }.exceptionOrNull() as AiEngineException).error).isEqualTo(AiEngineError.MODEL_UNAVAILABLE)
      assertThat(opened.single().descriptor!!.valid()).isFalse()
      val noMapping = FaceModelOpeners.detector(counting, ModelCatalog.FACE_YUNET, 4, { throw IllegalStateException("mappatura") }) { _, _ -> NoRun() }
      assertThat(runCatching { noMapping.open() }.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
      assertThat(opened).hasSize(2)
      assertThat(opened.last().descriptor!!.valid()).isFalse()
      // E un motore che si apre in un modo che la libreria non ammette arriva a chi chiama come codice, non come eccezione della libreria.
      val detector = YuNetDetector(FaceModelOpeners.detector(counting, ModelCatalog.FACE_YUNET, 4, mapFile) { _, _ -> throw IllegalStateException("la libreria") })
      assertThat((runCatching { detector.detect(image(), DetectPass.PREVIEW) }.exceptionOrNull() as AiEngineException).error).isEqualTo(AiEngineError.INIT_FAILED)
      assertThat(opened.last().descriptor!!.valid()).isFalse()
    } finally {
      root.deleteRecursively()
    }
  }

  @Test
  fun iNumeriDeiDueModelliSonoQuelliDelCatalogo() {
    assertThat(ModelCatalog.FACE_YUNET.sizeBytes).isEqualTo(239_700L)
    assertThat(ModelCatalog.FACE_SFACE.sizeBytes).isEqualTo(19_296_140L)
    assertThat(FaceEngineLimits.CPU_THREADS).isEqualTo(4)
    assertThat(ModelCatalog.FACE_SFACE.space).isEqualTo(FaceSpaceCode.CURRENT.space)
  }
}
