package dev.pampa.mycloudgallery.core.qr.scanner

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Size
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.qr.R
import dev.pampa.mycloudgallery.core.qr.codec.QrRead
import dev.pampa.mycloudgallery.core.qr.codec.ZxingQrCodec
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation

/**
 * L'analizzatore dei fotogrammi: dal piano di luminanza al testo di un QR, a poche letture al secondo.
 *
 * **Nessuna immagine viene tenuta.** Il fotogramma si copia (il quadrato centrale, in un array che si
 * riusa), si decodifica e si restituisce a CameraX subito; non esiste un `ImageCapture`, un file, un
 * `Bitmap`. Il risultato (solo il testo, e solo se c'e' un QR) torna sul thread principale.
 *
 * Gira al piu' ogni [MIN_INTERVAL_MS]: a 30 fotogrammi al secondo decodificarli tutti scalderebbe il
 * telefono per niente, e dieci tentativi al secondo bastano a leggere un QR messo a fuoco.
 */
internal class LumaAnalyzer(
  private val deliver: (QrRead) -> Unit,
  private val read: (ByteArray, Int, Int) -> QrRead = ZxingQrCodec::read,
  private val nowMs: () -> Long = SystemClock::elapsedRealtime,
) : ImageAnalysis.Analyzer {

  private val active = AtomicBoolean(true)
  private val main = Handler(Looper.getMainLooper())
  private var lastAnalyzedAt = Long.MIN_VALUE / 2
  private var buffer: ByteArray? = null

  /** Dopo la chiusura nessun risultato torna piu' alla schermata (fotogrammi ancora in volo). */
  fun close() {
    active.set(false)
  }

  override fun analyze(image: ImageProxy) {
    try {
      if (!active.get()) return
      val now = nowMs()
      if (now - lastAnalyzedAt < MIN_INTERVAL_MS) return
      lastAnalyzedAt = now

      val plane = image.planes[0]
      val side = FrameLuma.squareSide(image.width, image.height)
      val luma = FrameLuma.centerSquare(
        plane = plane.buffer,
        rowStride = plane.rowStride,
        pixelStride = plane.pixelStride,
        width = image.width,
        height = image.height,
        reuse = buffer,
      )
      buffer = luma
      val result = read(luma, side, side)
      if (result !is QrRead.None) {
        main.post { if (active.get()) deliver(result) }
      }
    } catch (e: Exception) {
      // Un fotogramma storto non ferma lo scanner: si perde e si passa al prossimo. Nessun dettaglio nel log.
      GalleryLog.w(TAG) { "fotogramma scartato: ${e.javaClass.simpleName}" }
    } finally {
      image.close()
    }
  }

  companion object {
    const val MIN_INTERVAL_MS: Long = 100L
    private const val TAG = "LumaAnalyzer"
  }
}

/**
 * La fotocamera dentro l'app (CameraX): anteprima nel mirino e analisi dei fotogrammi, legate al ciclo di vita
 * della schermata (si spengono con lei e quando l'app va in secondo piano).
 *
 * Usa la fotocamera posteriore, o l'anteriore se e' l'unica (un tablet). Se non parte (nessuna fotocamera,
 * occupata da un'altra app, errore del sistema) chiama [onFailure] e lo scanner ripiega sull'inserimento a mano.
 *
 * L'anteprima e l'analisi chiedono lo stesso rapporto 4:3, cosi' il quadrato centrale che l'utente vede nel
 * mirino e quello che si decodifica sono la stessa porzione di immagine.
 *
 * @param torch lo stato voluto della torcia (si riapplica da solo se la fotocamera riparte)
 * @param onTorchAvailable dice se la fotocamera in uso ha una torcia
 * @param onRead consegna, sul thread principale, ogni lettura che non sia "nessun QR"
 * @param onFailure la fotocamera non si avvia o si e' interrotta
 */
@Composable
internal fun CameraFeed(
  torch: Boolean,
  onTorchAvailable: (Boolean) -> Unit,
  onRead: (QrRead) -> Unit,
  onFailure: () -> Unit,
) {
  val context = LocalContext.current.applicationContext
  val lifecycleOwner = LocalLifecycleOwner.current
  val currentOnRead by rememberUpdatedState(onRead)
  val currentOnFailure by rememberUpdatedState(onFailure)
  val currentOnTorchAvailable by rememberUpdatedState(onTorchAvailable)

  var surfaceRequest by remember { mutableStateOf<SurfaceRequest?>(null) }
  var camera by remember { mutableStateOf<Camera?>(null) }

  val preview = remember {
    Preview.Builder()
      .setResolutionSelector(
        ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build(),
      )
      .build()
      .apply { setSurfaceProvider { request -> surfaceRequest = request } }
  }
  val executor = remember { Executors.newSingleThreadExecutor() }
  val analyzer = remember { LumaAnalyzer(deliver = { currentOnRead(it) }) }
  val analysis = remember {
    ImageAnalysis.Builder()
      .setResolutionSelector(
        ResolutionSelector.Builder()
          .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
          // Un QR da 600 caratteri ha un centinaio di moduli per lato: a 640x480 ne verrebbero tre pixel
          // l'uno e troppo pochi. 1280x960 e' il compromesso fra leggibilita' e costo della decodifica.
          .setResolutionStrategy(
            ResolutionStrategy(Size(ANALYSIS_WIDTH, ANALYSIS_HEIGHT), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
          )
          .build(),
      )
      .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
      .build()
      .apply { setAnalyzer(executor, analyzer) }
  }

  DisposableEffect(Unit) {
    onDispose {
      analyzer.close()
      analysis.clearAnalyzer()
      executor.shutdown()
    }
  }

  LaunchedEffect(lifecycleOwner) {
    try {
      val provider = ProcessCameraProvider.awaitInstance(context)
      val selector = when {
        provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
        provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
        else -> throw IllegalStateException("nessuna fotocamera")
      }
      val bound = provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
      camera = bound
      currentOnTorchAvailable(bound.cameraInfo.hasFlashUnit())
      try {
        awaitCancellation()
      } finally {
        // Solo i nostri casi d'uso: la fotocamera potrebbe servire a qualcun altro nel processo.
        provider.unbind(preview, analysis)
        camera = null
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      GalleryLog.w(TAG) { "fotocamera non avviata: ${e.javaClass.simpleName}" }
      currentOnFailure()
    }
  }

  LaunchedEffect(camera, torch) {
    val bound = camera ?: return@LaunchedEffect
    if (bound.cameraInfo.hasFlashUnit()) bound.cameraControl.enableTorch(torch)
  }

  val request = surfaceRequest
  if (request == null) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Text(
        text = stringResource(R.string.qr_viewfinder_starting),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  } else {
    CameraXViewfinder(
      surfaceRequest = request,
      modifier = Modifier.fillMaxSize(),
      contentScale = ContentScale.Crop,
    )
  }
}

private const val TAG = "CameraFeed"
private const val ANALYSIS_WIDTH = 1280
private const val ANALYSIS_HEIGHT = 960
