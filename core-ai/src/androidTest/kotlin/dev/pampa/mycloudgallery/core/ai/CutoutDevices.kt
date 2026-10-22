package dev.pampa.mycloudgallery.core.ai

import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import dev.pampa.mycloudgallery.core.ai.contract.AlphaMask
import dev.pampa.mycloudgallery.core.ai.cutout.MagicTouchCutter
import dev.pampa.mycloudgallery.core.ai.cutout.MagicTouchTuning
import dev.pampa.mycloudgallery.core.ai.cutout.ModelBytes
import dev.pampa.mycloudgallery.core.ai.cutout.SessionOpener
import dev.pampa.mycloudgallery.core.ai.cutout.TaskBundle
import dev.pampa.mycloudgallery.core.ai.cutout.V1Session
import dev.pampa.mycloudgallery.core.ai.cutout.V2Session
import dev.pampa.mycloudgallery.core.ai.testing.CutoutFixture
import dev.pampa.mycloudgallery.core.models.contract.ModelCatalog
import java.io.File
import java.security.MessageDigest

/**
 * Gli arnesi dei test strumentati del ritaglio (Fase 6, P13): i due modelli MagicTouch sul dispositivo e le immagini dei risultati.
 *
 * **La v1** e' l'asset dell'APK (`cutout/magic_touch.tflite`, verificato contro il catalogo). **La v2** (il pacchetto `.task` da 30,5 MB,
 * scaricato dal sito dei modelli di MediaPipe solo per misurare) non sta nel repository ne' nell'APK: si copia sul dispositivo di prova
 * con `adb push interactive_segmentation.task /data/local/tmp/mcg-models/` (sul PC in `%USERPROFILE%\.mycloudgallery\models\magictouch-v2\`)
 * e [V2] lo porta nella cartella dei file dell'APK di prova, verificando dimensione e SHA-256.
 */
internal object CutoutDevices {

  private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
  private val context: Context get() = instrumentation.targetContext

  /** Il pacchetto v2 di MediaPipe: dimensione e SHA-256 del file scaricato (22/10/2026, MD5 uguale a quello del server). */
  const val V2_NAME: String = "interactive_segmentation.task"
  const val V2_SIZE: Long = 30_525_312L
  const val V2_SHA256: String = "38431bc66b883404e8397f74c3579404315b9b52b04a46c6346fe906a7309b03"

  /** L'impostazione da `-e mcgCfg "fit=stretch;r=0.04"`; senza, quella predefinita. */
  fun tuning(): MagicTouchTuning = InstrumentationRegistry.getArguments().getString("mcgCfg")?.let(MagicTouchTuning::parse) ?: MagicTouchTuning.DEFAULT

  fun threads(): Int = InstrumentationRegistry.getArguments().getString("mcgThreads")?.toIntOrNull() ?: MagicTouchCutter.DEFAULT_THREADS

  fun v1Opener(tuning: MagicTouchTuning = tuning(), threads: Int = threads()): SessionOpener = SessionOpener {
    val file = File(context.filesDir, "p13-v1.tflite")
    context.assets.open(ModelCatalog.CUTOUT_MAGICTOUCH.bundledAsset!!).use { input -> file.outputStream().use { input.copyTo(it) } }
    check(sha256(file) == ModelCatalog.CUTOUT_MAGICTOUCH.sha256.hex) { "SHA-256 della v1" }
    V1Session(ModelBytes.mapFile(file), threads, tuning)
  }

  fun v2Available(): Boolean = v2File() != null

  fun v2Opener(tuning: MagicTouchTuning = tuning(), threads: Int = threads()): SessionOpener = SessionOpener {
    val file = checkNotNull(v2File()) { "pacchetto v2 assente" }
    val parts = TaskBundle.parts(ModelBytes.mapFile(file))
    V2Session(parts.encoder, parts.decoder, threads, tuning)
  }

  fun cutter(version: String, tuning: MagicTouchTuning = tuning(), threads: Int = threads()): MagicTouchCutter =
    MagicTouchCutter.forTesting(if (version == "v2") v2Opener(tuning, threads) else v1Opener(tuning, threads), tuning)

  /** Il pacchetto v2 nella cartella dei file dell'APK di prova, verificato; null se non e' stato copiato sul dispositivo. */
  private fun v2File(): File? {
    val out = File(context.filesDir, "p13-v2.task")
    if (out.isFile && out.length() == V2_SIZE) return out
    val pfd = instrumentation.uiAutomation.executeShellCommand("cat /data/local/tmp/mcg-models/$V2_NAME")
    ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input -> out.outputStream().use { input.copyTo(it, 1 shl 20) } }
    if (out.length() != V2_SIZE) {
      out.delete()
      return null
    }
    check(sha256(out) == V2_SHA256) { "SHA-256 della v2" }
    return out
  }

  fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
      val buffer = ByteArray(1 shl 20)
      while (true) {
        val n = input.read(buffer)
        if (n < 0) break
        digest.update(buffer, 0, n)
      }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  /** La cartella dei risultati da guardare: `adb pull /sdcard/Android/data/<pacchetto di prova>/files/lab`. */
  fun labDir(): File = File(context.getExternalFilesDir(null), "lab").apply { mkdirs() }

  /** Un'immagine con l'originale, la maschera e il ritaglio su scacchiera, per guardare il risultato a occhio. */
  fun montage(fixture: CutoutFixture, mask: AlphaMask?, name: String) {
    val w = fixture.width
    val h = fixture.height
    val pixels = fixture.pixels()
    val alpha = mask?.alpha()
    val out = IntArray(w * 3 * h)
    for (y in 0 until h) {
      for (x in 0 until w) {
        val p = pixels[y * w + x]
        val a = alpha?.let { it[y * w + x].toInt() and 0xff } ?: 0
        out[y * w * 3 + x] = p
        out[y * w * 3 + w + x] = (0xff shl 24) or (a shl 16) or (a shl 8) or a
        val checker = if (((x / 16) + (y / 16)) % 2 == 0) 0xC8 else 0x78
        val r = (((p shr 16) and 0xff) * a + checker * (255 - a)) / 255
        val g = (((p shr 8) and 0xff) * a + checker * (255 - a)) / 255
        val b = ((p and 0xff) * a + checker * (255 - a)) / 255
        out[y * w * 3 + 2 * w + x] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
      }
    }
    val bitmap = Bitmap.createBitmap(out, w * 3, h, Bitmap.Config.ARGB_8888)
    File(labDir(), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    bitmap.recycle()
  }
}
