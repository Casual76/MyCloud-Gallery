package dev.pampa.mycloudgallery.core.ai.runtime

import android.graphics.Bitmap
import dev.pampa.mycloudgallery.core.ai.contract.AiRecipe
import dev.pampa.mycloudgallery.core.ai.contract.DeviceProbe
import dev.pampa.mycloudgallery.core.ai.contract.ImageEmbedder
import dev.pampa.mycloudgallery.core.ai.contract.ModelImage
import dev.pampa.mycloudgallery.core.ai.contract.ProbeResult
import java.io.ByteArrayOutputStream

/**
 * Le 20 immagini sintetiche di [ProbeImages] come ingressi dei motori: bitmap -> JPEG alla qualita' della ricetta, come li fa
 * [ModelInputs] (stessa lunghezza del lato lungo: le scene sono gia' 768 x 576). Pochi millisecondi in tutto.
 */
internal object ProbeImageSet {

  /** Le [ProbeImages.COUNT] immagini, nell'ordine delle scene. */
  fun modelImages(recipe: AiRecipe = AiRecipe.CURRENT): List<ModelImage> = List(ProbeImages.COUNT) { modelImage(it, recipe) }

  fun modelImage(index: Int, recipe: AiRecipe = AiRecipe.CURRENT): ModelImage {
    val bitmap = Bitmap.createBitmap(ProbeImages.pixels(index), ProbeImages.WIDTH, ProbeImages.HEIGHT, Bitmap.Config.ARGB_8888)
    try {
      val out = ByteArrayOutputStream(96 * 1024)
      check(bitmap.compress(Bitmap.CompressFormat.JPEG, recipe.jpegQuality, out)) { "JPEG della scena $index non codificato." }
      return ModelImage(out.toByteArray(), ProbeImages.WIDTH, ProbeImages.HEIGHT)
    } finally {
      bitmap.recycle()
    }
  }
}

/**
 * La **misura breve** del dispositivo (docs/fase6-progetto.md D15, E-A4): quando l'utente accende il ruolo Indicizzatore si fanno
 * girare le 20 immagini sintetiche sul motore delle immagini e si dice quante ne fa al secondo, su che backend e quanto ha impiegato
 * ad avviarsi; con questo la pagina stima "Analizzare 18.000 media richiede circa 2 notti" ([ProbeResult.nightsFor]).
 *
 * - **Avvio** ([ProbeResult.coldStartMs]): la prima immagine da sola, comprese la verifica del modello e l'inizializzazione se il motore
 *   non era gia' caldo (se lo era, e' la latenza di un'immagine: il numero e' comunque quello che l'utente aspetta al primo uso).
 * - **Velocita'**: le 20 immagini in una chiamata, ripetute [rounds] volte; vale il giro piu' veloce (un giro isolato sotto il carico
 *   di un altro processo non dice com'e' il dispositivo).
 * - Il motore **si rilascia a fine misura** ([releaseAfter], IX-5 della verifica funzionale della Fase 6): il 440M su GPU pesa circa 800 MB
 *   di PSS e restava nel processo della galleria in primo piano fino all'azzeramento. Chi lo usa dopo lo riapre da solo (2 secondi a caldo).
 *   Nessuna rete, nessun dato personale.
 * - Un guasto del motore (`AiEngineException`) esce com'e': la pagina dice "dispositivo lento" o "modello assente".
 *
 * @param nanos orologio monotono (D39).
 * @param images le immagini della misura (le 20 sintetiche; i test ne passano altre).
 */
class RealDeviceProbe(
  private val embedder: ImageEmbedder,
  private val nanos: () -> Long = System::nanoTime,
  private val rounds: Int = 2,
  private val images: () -> List<ModelImage> = { ProbeImageSet.modelImages() },
  private val releaseAfter: Boolean = true,
) : DeviceProbe {

  init {
    require(rounds >= 1) { "Almeno un giro." }
  }

  override suspend fun measure(): ProbeResult {
    val images = images()
    require(images.isNotEmpty()) { "Nessuna immagine da misurare." }
    try {
      val coldStart = nanos()
      embedder.embed(listOf(images.first()))
      val coldStartMs = (nanos() - coldStart) / 1_000_000

      var best = Long.MAX_VALUE
      repeat(rounds) {
        val start = nanos()
        embedder.embed(images)
        best = minOf(best, nanos() - start)
      }
      val seconds = best.coerceAtLeast(1L) / 1e9
      return ProbeResult(imagesPerSecond = images.size / seconds, backend = embedder.backend.value, coldStartMs = coldStartMs)
    } finally {
      // Anche se la misura e' fallita o annullata: il motore non resta in memoria per nessuno.
      if (releaseAfter) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { embedder.release() }
    }
  }
}
