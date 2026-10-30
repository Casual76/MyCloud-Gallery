package dev.pampa.mycloudgallery.feature.memories.player

import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.memories.contract.ClipSource
import dev.pampa.mycloudgallery.core.memories.contract.Memories
import dev.pampa.mycloudgallery.core.memories.contract.Memory
import dev.pampa.mycloudgallery.core.memories.contract.MemoryClips
import dev.pampa.mycloudgallery.core.memories.contract.MemoryId
import dev.pampa.mycloudgallery.core.memories.contract.MemoryView
import dev.pampa.mycloudgallery.core.memories.contract.MusicChoice
import dev.pampa.mycloudgallery.core.memories.contract.MusicLibrary
import dev.pampa.mycloudgallery.core.memories.contract.MusicSettings
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.feature.memories.contract.MemoryHeader
import dev.pampa.mycloudgallery.feature.memories.contract.MusicEvent
import dev.pampa.mycloudgallery.feature.memories.contract.MusicPlayback
import dev.pampa.mycloudgallery.feature.memories.contract.PlayerEnvironment
import dev.pampa.mycloudgallery.feature.memories.contract.PlayerImages
import dev.pampa.mycloudgallery.feature.memories.contract.PlayerIntro
import dev.pampa.mycloudgallery.feature.memories.contract.PlayerItem
import dev.pampa.mycloudgallery.feature.memories.contract.PlayerMusic
import dev.pampa.mycloudgallery.feature.memories.contract.PlayerState
import dev.pampa.mycloudgallery.feature.memories.contract.PlayerUi
import dev.pampa.mycloudgallery.feature.memories.ui.FinalFilter
import dev.pampa.mycloudgallery.feature.memories.ui.MemoryNames
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** I tempi della presentazione (docs/fase9-progetto.md §2.4): l'orologio e' quello del `CoroutineScope` (nei test virtuale), mai quello del telefono. */
object PlayerTimings {
  /** Il titolo sopra la copertina all'inizio. */
  const val INTRO_MS: Long = 2_000

  /** Una foto, e un video senza clip (il fotogramma di copertina). */
  const val PHOTO_MS: Long = 4_000

  /** La dissolvenza fra due elementi (un token di `FluidMotion` con questa durata) e quella breve con le animazioni ridotte. */
  const val FADE_MS: Int = 600
  const val REDUCED_FADE_MS: Int = 120

  /** Quanto si aspetta il filtro finale dei Nascosti e il clip di un video prima di andare avanti (mai un'attesa lunga). */
  const val MASK_WAIT_MS: Long = 1_000
  const val CLIP_WAIT_MS: Long = 500
}

/**
 * Il passo della presentazione: di serie quello del §2.4 ([PlayerTimings]); le prove che girano sul catalogo vero, dove i tempi non sono virtuali
 * (`HiddenSurfaces`, riga `MEMORIES`), lo accorciano. Mai una scelta dell'utente.
 */
class PlayerPace(
  val intro: Long = PlayerTimings.INTRO_MS,
  val photo: Long = PlayerTimings.PHOTO_MS,
  val maskWait: Long = PlayerTimings.MASK_WAIT_MS,
  val clipWait: Long = PlayerTimings.CLIP_WAIT_MS,
)

/**
 * LA PRESENTAZIONE (`MemoryPlayer`; Fase 9, P9; docs/fase9-progetto.md §1.5 `PlayerState`, §2.4, D13, D31, Q3, E-MEM14, E-MEM15, E-MEM17, E-MEM19, E-H8):
 * una macchina a stati senza Compose, con il tempo dello [scope] (nei test virtuale).
 *
 *  - **Inizio**: il titolo sopra la copertina per 2 s ([PlayerUi.intro]), poi la prima foto. **Una foto 4 s** (un video senza clip, il suo
 *    fotogramma; con un clip muto del telefono la durata del clip, al piu' 8 s). Poi la **carta finale** ([PlayerState.Ended]) con "Rivedi".
 *  - **Mai un'attesa sul NAS**: le immagini si chiedono a [PlayerImages] **senza aspettare** (l'elemento di adesso e i due successivi); il tempo
 *    corre lo stesso, e una sorgente lenta o spenta lascia la miniatura o il segnaposto. Niente di quello che e' qui sotto sospende su un'immagine.
 *  - **Gesti**: tocco = pausa (e i comandi), di nuovo = riprende; [next] e [previous] = scorrimento; l'uscita la fa la rotta ([exit]).
 *  - **Nascosti** (D31): un elemento nascosto durante la presentazione **si salta al prossimo passaggio** (il filtro finale guarda i prossimi
 *    prima di ogni passaggio) e quello sullo schermo esce subito (la vista e' viva: passa al successivo); sotto 3 elementi "Questo ricordo non
 *    c'e' piu'" ([PlayerUi.gone]).
 *  - **Animazioni ridotte**: nessun movimento (lo dice [PlayerState.reducedMotion], la schermata non muove); **TalkBack**: niente avanzamento
 *    automatico, niente musica, comincia in [PlayerState.Paused] e i gesti scorrono le foto.
 *  - **Musica (Q3)**: muta di serie; la canzone propria o il brano libero partono con la presentazione; **focus audio**: una chiamata
 *    ([MusicEvent.FOCUS_LOST]) ferma presentazione e musica e al ritorno si riparte; il file sparito ([MusicEvent.MISSING], o il brano che non si
 *    apre) lascia la presentazione muta con un avviso ([PlayerUi.musicMissing]).
 *  - **Visto**: la presentazione segna il ricordo come visto una volta, quando parte (D10).
 *
 * Si usa dal thread principale (i comandi e il tempo girano sullo stesso scope: nessuna corsa). Niente nei log: mai titoli, luoghi, id o nomi.
 */
class PlayerController(
  private val id: MemoryId,
  private val memories: Memories,
  private val names: MemoryNames,
  private val filter: FinalFilter,
  private val clips: MemoryClips,
  private val images: PlayerImages,
  private val musicSettings: MusicSettings,
  private val library: MusicLibrary,
  private val playback: MusicPlayback,
  private val scope: CoroutineScope,
  environment: PlayerEnvironment = PlayerEnvironment(),
  private val pace: PlayerPace = PlayerPace(),
) {
  private val environment = MutableStateFlow(environment)
  private val state = MutableStateFlow(PlayerUi())

  /** Lo stato di adesso: [PlayerUi.state] null finche' non si e' caricato. */
  val ui: StateFlow<PlayerUi> = state.asStateFlow()

  private var items: List<MediaId> = emptyList()
  private var snapshot: TimelineSnapshot? = null
  private var header: MemoryHeader? = null
  private var cover: MediaId? = null
  private var index = 0
  private var loaded = false
  private var introActive = false
  private var userPaused = false
  private var focusPaused = false
  private var lostForGood = false
  private var ended = false
  private var controlsShown = false
  private var music = PlayerMusic.MUTED
  private var musicMissing = false
  private var wanted: Triple<TimelineSnapshot, MediaId, List<MediaId>>? = null
  private var ticker: Job? = null
  private val clipCache = HashMap<MediaId, ClipSource?>()

  private val talkBack: Boolean get() = environment.value.talkBack

  init {
    scope.launch { memories.memory(id).collectLatest { view -> onView(view) } }
    scope.launch { playback.events.collect { event -> onMusic(event) } }
    scope.launch { this@PlayerController.environment.collect { _ -> onEnvironment() } }
  }

  /** Come sta il dispositivo (animazioni ridotte, TalkBack): la rotta lo rilegge a ogni cambio. */
  fun setEnvironment(value: PlayerEnvironment) {
    environment.value = value
  }

  // --- Il ricordo --------------------------------------------------------------------------------------------------------------------

  private suspend fun onView(view: MemoryView?) {
    if (view == null) {
      gone()
      return
    }
    val shown = filter.shown(view.view)
    if (shown.size < Memory.MIN_MEMBERS) {
      gone()
      return
    }
    val personName = names.person(view.memory.title.person) ?: view.personName
    snapshot = view.view.snapshot
    header = MemoryHeader(id, view.memory.kind, view.memory.title, personName, names.places(view.memory.title.placeIds))
    // La copertina del titolo e' un elemento che si puo' mostrare adesso (quella del servizio puo' essere stata nascosta un attimo fa, D31).
    cover = view.cover.takeIf { it in shown } ?: shown.first()
    if (!loaded) begin(shown) else reconcile(shown)
  }

  private fun begin(shown: List<MediaId>) {
    loaded = true
    items = shown
    index = 0
    ended = false
    focusPaused = false
    introActive = true
    // Con TalkBack il titolo si legge e non parte niente da solo.
    userPaused = talkBack
    controlsShown = talkBack
    scope.launch { guarded { memories.markSeen(id) } }
    startMusic()
    publish()
    restartTimer()
  }

  /** La vista e' cambiata (un nascosto e' uscito, un elemento e' stato tolto): quello sullo schermo, se c'e' ancora, resta; se no si passa al successivo. */
  private fun reconcile(shown: List<MediaId>) {
    val before = items
    val current = before.getOrNull(index)
    items = shown
    if (ended) {
      publish()
      return
    }
    if (current != null && current in shown) {
      index = shown.indexOf(current)
    } else {
      val after = before.drop(index + 1).firstOrNull { it in shown }
      if (after == null) {
        finish()
        return
      }
      index = shown.indexOf(after)
      introActive = false
      restartTimer()
    }
    publish()
  }

  private fun gone() {
    ticker?.cancel()
    items = emptyList()
    ended = false
    loaded = false
    playback.stop()
    images.clear()
    wanted = null
    state.value = PlayerUi(gone = true)
  }

  // --- Il tempo ----------------------------------------------------------------------------------------------------------------------

  private fun isPaused(): Boolean = userPaused || focusPaused

  private fun restartTimer() {
    ticker?.cancel()
    if (!loaded || ended || isPaused() || talkBack) return
    ticker = scope.launch {
      if (introActive) {
        delay(pace.intro)
        introActive = false
        publish()
      }
      while (true) {
        val current = items.getOrNull(index) ?: break
        ensureClip(current)
        delay(durationOf(current))
        if (!advance()) break
      }
    }
  }

  /** Quanto resta in vista [media]: 4 s, o la durata del clip muto di un video del telefono (al piu' 8 s). */
  private fun durationOf(media: MediaId): Long {
    val clip = clipCache[media]
    return if (clip != null && kindOf(media) == MediaKind.VIDEO) clip.durationMs.coerceIn(pace.photo, ClipSource.MAX_MS) else pace.photo
  }

  private suspend fun ensureClip(media: MediaId) {
    if (kindOf(media) != MediaKind.VIDEO || clipCache.containsKey(media)) return
    val clip = withTimeoutOrNull(pace.clipWait) { guardedValue { clips.clip(media) } }
    clipCache[media] = clip
    publish()
  }

  /**
   * Il passaggio al prossimo elemento. **Il filtro finale dei Nascosti** guarda i prossimi prima di muoversi: chi e' nascosto si toglie e non si mostra
   * piu' (D31, E-H8); un filtro lento o guasto vale "nascosto" (fallisce chiuso) e non fa aspettare. Falso se la presentazione e' finita.
   */
  private suspend fun advance(): Boolean {
    val ahead = items.drop(index + 1).take(PlayerState.Playing.PRELOAD + 1)
    if (ahead.isNotEmpty()) {
      val hidden = withTimeoutOrNull(pace.maskWait) { filter.hiddenAmong(ahead) } ?: ahead.toSet()
      if (hidden.isNotEmpty()) items = items.filter { it !in hidden }
    }
    if (index + 1 >= items.size) {
      finish()
      return false
    }
    index++
    publish()
    return true
  }

  private fun finish() {
    ticker?.cancel()
    ended = true
    controlsShown = true
    playback.stop()
    publish()
  }

  // --- I comandi ---------------------------------------------------------------------------------------------------------------------

  /** Tocco: salta il titolo; poi pausa e comandi; di nuovo riprende. Con TalkBack i comandi ci sono sempre e il tocco non fa partire niente. */
  fun tap() {
    if (!loaded || ended) return
    if (talkBack) return
    if (introActive) {
      introActive = false
      restartTimer()
      publish()
      return
    }
    if (isPaused()) resume() else pause()
  }

  fun pause() {
    if (!loaded || ended) return
    userPaused = true
    controlsShown = true
    ticker?.cancel()
    playback.pause()
    publish()
  }

  /** Riprende (mai con TalkBack: li' si avanza solo con i gesti). Riparte dall'inizio dell'elemento di adesso. */
  fun resume() {
    if (!loaded || ended || talkBack) return
    userPaused = false
    focusPaused = false
    lostForGood = false
    controlsShown = false
    if (music == PlayerMusic.PLAYING) playback.resume()
    restartTimer()
    publish()
  }

  /** Scorrimento avanti (o il tasto "Foto successiva"). Dopo l'ultimo elemento: la carta finale. */
  fun next() {
    if (!loaded || ended) return
    introActive = false
    scope.launch {
      if (advance()) restartTimer() else Unit
    }
  }

  /** Scorrimento indietro (o "Foto precedente"); dalla carta finale si torna all'ultimo elemento. */
  fun previous() {
    if (!loaded) return
    introActive = false
    if (ended) {
      ended = false
      index = items.lastIndex
      controlsShown = isPaused() || talkBack
    } else if (index > 0) {
      index--
    } else {
      return
    }
    restartTimer()
    publish()
  }

  /** "Rivedi": dall'inizio, senza il titolo; con TalkBack si resta fermi. */
  fun restart() {
    if (!loaded) return
    index = 0
    ended = false
    introActive = false
    focusPaused = false
    lostForGood = false
    userPaused = talkBack
    controlsShown = talkBack
    startMusic()
    restartTimer()
    publish()
  }

  /** L'uscita (trascinamento in basso, indietro, la X): si ferma tutto, musica compresa. */
  fun exit() {
    ticker?.cancel()
    playback.stop()
    images.clear()
    wanted = null
  }

  // --- Ambiente e musica ------------------------------------------------------------------------------------------------------------

  private fun onEnvironment() {
    if (!loaded) return
    if (talkBack) {
      // TalkBack acceso mentre si guarda: si ferma tutto e comanda l'utente (niente avanzamento, niente musica).
      ticker?.cancel()
      userPaused = true
      controlsShown = true
      playback.stop()
      music = PlayerMusic.MUTED
    }
    publish()
  }

  private fun startMusic() {
    if (talkBack) {
      music = PlayerMusic.MUTED
      return
    }
    scope.launch {
      val choice = musicSettings.choice.value
      if (choice == MusicChoice.None) {
        music = PlayerMusic.MUTED
        publish()
        return@launch
      }
      val source = guardedValue { library.open(choice) }
      if (source == null) {
        music = PlayerMusic.MISSING
        musicMissing = true
      } else {
        playback.start(source)
        music = PlayerMusic.PLAYING
        musicMissing = false
      }
      publish()
    }
  }

  private fun onMusic(event: MusicEvent) {
    if (!loaded) return
    when (event) {
      MusicEvent.FOCUS_LOST -> if (!ended && !focusPaused) {
        focusPaused = true
        ticker?.cancel()
        playback.pause()
        controlsShown = true
        publish()
      }
      MusicEvent.FOCUS_GAINED -> if (focusPaused && !lostForGood && !userPaused && !ended) {
        focusPaused = false
        controlsShown = false
        playback.resume()
        restartTimer()
        publish()
      }
      MusicEvent.FOCUS_LOST_FOR_GOOD -> {
        // Un'altra app ha preso l'audio per sempre: la musica si ferma e la presentazione aspetta l'utente, muta.
        lostForGood = true
        focusPaused = true
        ticker?.cancel()
        playback.stop()
        music = PlayerMusic.MUTED
        controlsShown = true
        publish()
      }
      MusicEvent.MISSING -> {
        music = PlayerMusic.MISSING
        musicMissing = true
        publish()
      }
    }
  }

  // --- Lo stato per la schermata ------------------------------------------------------------------------------------------------------

  private fun kindOf(media: MediaId): MediaKind {
    val snap = snapshot ?: return MediaKind.PHOTO
    val at = snap.indexOf(media)
    return if (at >= 0) snap.kindAt(at) else MediaKind.PHOTO
  }

  private fun itemOf(media: MediaId): PlayerItem = if (kindOf(media) == MediaKind.VIDEO) PlayerItem.Video(media, clipCache[media]) else PlayerItem.Photo(media)

  private fun publish() {
    val snap = snapshot ?: return
    val current = items.getOrNull(index) ?: return
    val head = header ?: return
    val nextIds = items.drop(index + 1).take(PlayerState.Playing.PRELOAD)
    want(snap, current, nextIds)
    val env = environment.value
    val item = itemOf(current)
    val playerState: PlayerState = when {
      ended -> PlayerState.Ended(items.size, env.reducedMotion, env.talkBack)
      isPaused() || env.talkBack -> PlayerState.Paused(index, item, env.reducedMotion, env.talkBack)
      else -> PlayerState.Playing(index, item, nextIds.map(::itemOf), music, env.reducedMotion, env.talkBack)
    }
    val introCover = cover?.let(::itemOf) ?: item
    state.value = PlayerUi(
      state = playerState,
      header = head,
      intro = if (introActive) PlayerIntro(head, introCover) else null,
      items = items.map(::itemOf),
      snapshot = snap,
      controls = controlsShown || isPaused() || ended || env.talkBack,
      musicMissing = musicMissing,
    )
  }

  /** Chiede le immagini (senza aspettare) solo quando cambia cio' che serve. */
  private fun want(snap: TimelineSnapshot, current: MediaId, next: List<MediaId>) {
    val key = Triple(snap, current, next)
    if (wanted == key) return
    wanted = key
    images.want(snap, current, next)
  }

  private suspend fun guarded(block: suspend () -> Unit) {
    try {
      block()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      // Il "visto" non si e' salvato: si riprovera' alla prossima apertura. Niente nei log.
    }
  }

  private suspend fun <T> guardedValue(block: suspend () -> T?): T? = try {
    block()
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    null
  }
}
