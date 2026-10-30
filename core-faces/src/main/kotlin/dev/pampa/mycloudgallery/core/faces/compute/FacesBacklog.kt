package dev.pampa.mycloudgallery.core.faces.compute

import dev.pampa.mycloudgallery.core.faces.contract.FacesArchive
import dev.pampa.mycloudgallery.core.faces.contract.FacesArea
import dev.pampa.mycloudgallery.core.faces.contract.FacesCatalog
import dev.pampa.mycloudgallery.core.faces.contract.FacesComputeLimits
import dev.pampa.mycloudgallery.core.faces.contract.FacesRequirements
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.FacesSwitch
import dev.pampa.mycloudgallery.core.faces.contract.HereState
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaKind
import kotlin.math.ceil
import kotlin.math.max
import kotlinx.coroutines.CancellationException

// LA STIMA DAL LAVORO VERO (docs/fase8-progetto.md §2.3, §2.12, D10; docs/regole-attivita.md "Persone"): quanti media restano da analizzare,
// contati con le **stesse porte e le stesse regole** del calcolo (catalogo, archivio, cosa si analizza), e quanto costa ognuno, misurato sul
// lavoro che il calcolo ha fatto davvero su questo dispositivo. "Analizzare le tue 12.000 foto richiede circa mezz'ora, di notte in carica" nasce
// da qui; le ipotesi di partenza ([FacesRates.DEFAULT]) valgono solo finche' non c'e' una misura.

/** Il lavoro che resta, per tipo (un contenuto in piu' aree o mesi si conta una volta, D23). Solo numeri. */
data class FacesWork(val photos: Long, val videos: Long) {
  val total: Long get() = photos + videos

  companion object {
    val NONE = FacesWork(0, 0)
  }
}

/**
 * Quanto costa ogni media, in secondi: [secondsPerPhoto] la prima passata di una foto, [hqShare] la parte che vuole anche la seconda (D17) e
 * [secondsPerHq] cio' che questa aggiunge, [secondsPerVideo] un video (copertina e fotogrammi).
 */
data class FacesRates(val secondsPerPhoto: Double, val hqShare: Double, val secondsPerHq: Double, val secondsPerVideo: Double) {
  companion object {
    /**
     * I ritmi di un dispositivo che fa [photosPerSecond] foto al secondo (decodifica, rilevatore, allineamento ed embedding: `DeviceMeasures.facesPerSecond`):
     * una foto costa il suo inverso, un video la copertina e i suoi fotogrammi (D18), il 20% delle foto vuole la seconda passata (D17) che sull'S25 costa
     * [HQ_PASS_SECONDS]. Il numero lo sceglie chi lega le porte (`:app`): questo modulo non conosce le misure delle regole.
     */
    fun forPhotosPerSecond(photosPerSecond: Double): FacesRates {
      require(photosPerSecond > 0.0 && photosPerSecond.isFinite()) { "Le foto al secondo devono essere positive." }
      val perPhoto = 1.0 / photosPerSecond
      return FacesRates(
        secondsPerPhoto = perPhoto,
        hqShare = HQ_SHARE,
        secondsPerHq = HQ_PASS_SECONDS,
        secondsPerVideo = (1 + FacesComputeLimits.FRAMES_PER_VIDEO) * perPhoto,
      )
    }

    /** La quota di foto con la seconda passata (D17): un'ipotesi che la misura sul lavoro vero (`FacesRateMeter`) sostituisce. */
    const val HQ_SHARE: Double = 0.2

    /** La seconda passata a 2048 px (YuNet a 4 tasselli): 344 ms sull'S25, p50 della prova strumentata del 27/10 (F104). */
    const val HQ_PASS_SECONDS: Double = 0.344

    /** Le misure sul campo dell'S25 (F104, 27/10): 8,8 foto al secondo. Valgono finche' il calcolo non ha misurato il dispositivo vero. */
    val DEFAULT: FacesRates = forPhotosPerSecond(8.8)
  }
}

object FacesEstimate {
  /** Il tempo di calcolo di [work] ai ritmi [rates]. */
  fun seconds(work: FacesWork, rates: FacesRates): Double =
    work.photos * (rates.secondsPerPhoto + rates.hqShare * rates.secondsPerHq) + work.videos * rates.secondsPerVideo

  /** Minuti interi, almeno 1 se c'e' lavoro, 0 se non c'e'. */
  fun minutes(work: FacesWork, rates: FacesRates): Int {
    if (work.total == 0L) return 0
    // Il margine assorbe il rumore dei decimali (14 minuti esatti non diventano 15).
    return max(1, ceil(seconds(work, rates) / 60.0 - 1e-6).toInt())
  }
}

/**
 * La misura del lavoro vero: ad ogni media calcolato il calcolo ci dice di che tipo era, se ha avuto la seconda passata e quanto ha
 * impiegato (orologio monotono). Una media mobile esponenziale per voce; la prima misura sostituisce l'ipotesi. Mai su disco: dopo un riavvio
 * si riparte dalle ipotesi finche' il calcolo non lavora di nuovo (`DeviceMeasures.facesPerSecond` lo scrive P13 dalle misure di P2).
 */
class FacesRateMeter(initial: FacesRates = FacesRates.DEFAULT, private val alpha: Double = 0.2) {
  private var photo: Double? = null
  private var hqExtra: Double? = null
  private var video: Double? = null
  private var photos = 0L
  private var hqPhotos = 0L
  private val base = initial

  init {
    require(alpha > 0.0 && alpha <= 1.0) { "Peso della media mobile fuori intervallo." }
  }

  @Synchronized
  fun record(kind: MediaKind, hqUsed: Boolean, nanos: Long) {
    if (nanos < 0) return
    val seconds = nanos / 1e9
    if (kind == MediaKind.VIDEO) {
      video = blend(video, seconds)
      return
    }
    photos++
    if (hqUsed) {
      hqPhotos++
      hqExtra = blend(hqExtra, max(0.0, seconds - (photo ?: base.secondsPerPhoto)))
    } else {
      photo = blend(photo, seconds)
    }
  }

  /** I ritmi di adesso: misurati dove c'e' una misura, le ipotesi altrove. */
  @Synchronized
  fun rates(): FacesRates = FacesRates(
    secondsPerPhoto = photo ?: base.secondsPerPhoto,
    hqShare = if (photos > 0) hqPhotos.toDouble() / photos else base.hqShare,
    secondsPerHq = hqExtra ?: base.secondsPerHq,
    secondsPerVideo = video ?: base.secondsPerVideo,
  )

  /** Media misurati finora (foto e video). */
  @Synchronized
  fun samples(): Long = photos + (if (video != null) 1 else 0)

  private fun blend(old: Double?, sample: Double): Double = if (old == null) sample else old + alpha * (sample - old)

  override fun toString(): String = "FacesRateMeter(${photos} foto)"
}

/**
 * La libreria contata una volta: i media gia' analizzati ([done], "non supportati" compresi) e quelli che restano ([work]). [total] e' la libreria intera
 * (CA-1: il progresso e' della libreria, non dell'unita' da 200 media). Solo numeri.
 */
data class FacesTally(val done: Long, val work: FacesWork) {
  val total: Long get() = done + work.total

  companion object {
    val NONE = FacesTally(0, FacesWork.NONE)
  }
}

/**
 * Il lavoro che resta su questo dispositivo, contato con le porte del calcolo: i media di ogni area (privata, comune, solo telefono) che si
 * analizzano (D18) e che non sono gia' fatti (`FacesArchive.analyzed` con la ricetta corrente, "non supportati" compresi). Zero se Persone non
 * e' attiva qui o il dispositivo non e' un indicizzatore (non lavora lui). [archive] e' quello della generazione corrente (null = nessuno).
 *
 * **Costa una lettura del catalogo** (i mesi di ogni area e i loro media, L242): si conta una volta per tappa del calcolo (CA-1) o quando l'utente guarda la
 * stima (CA-2), mai sul percorso dell'avvio e mai sul thread principale.
 */
class FacesBacklog(
  private val switch: FacesSwitch,
  private val requirements: FacesRequirements,
  private val catalog: FacesCatalog,
  private val archive: suspend () -> FacesArchive?,
  private val recipe: Int = FacesComputeLimits.RECIPE,
) {
  /** Cio' che resta da analizzare. */
  suspend fun count(): FacesWork = tally().work

  /** La libreria contata: quanto e' fatto e quanto resta. [FacesTally.NONE] se Persone non lavora qui (o l'archivio non c'e'). */
  suspend fun tally(): FacesTally {
    val state = switch.currentState()
    if (state !is FacesState.On || state.here == HereState.EXCLUDED) return FacesTally.NONE
    if (!requirements.indexers().thisDevice) return FacesTally.NONE
    val store = archive() ?: return FacesTally.NONE
    val counted = HashSet<ContentKey>()
    var done = 0L
    var photos = 0L
    var videos = 0L
    for (area in FacesArea.entries) {
      for (month in catalog.months(area).distinct()) {
        val byKey = catalog.items(area, month).groupBy { it.contentKey }
        val wanted = byKey.filter { (_, items) -> items.any(FaceScopeRules::analyzable) }.keys.filter { it !in counted }
        if (wanted.isEmpty()) continue
        val finished = try {
          store.analyzed(wanted, recipe)
        } catch (e: CancellationException) {
          throw e
        } catch (e: IllegalStateException) {
          // Archivio chiuso (generazione cambiata, chiave distrutta): niente da contare.
          return FacesTally.NONE
        }
        for (key in wanted) {
          counted += key
          if (key in finished) {
            done++
            continue
          }
          val item = byKey.getValue(key).first(FaceScopeRules::analyzable)
          if (item.kind == MediaKind.VIDEO) videos++ else photos++
        }
      }
    }
    return FacesTally(done, FacesWork(photos, videos))
  }
}
