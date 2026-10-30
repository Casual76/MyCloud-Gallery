package dev.pampa.mycloudgallery.feature.memories

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.memories.contract.MemoryId
import dev.pampa.mycloudgallery.core.memories.contract.MemoryKind
import dev.pampa.mycloudgallery.core.memories.contract.MusicChoice
import dev.pampa.mycloudgallery.core.memories.noop.NoMemories
import dev.pampa.mycloudgallery.core.memories.noop.NoMemoryClips
import dev.pampa.mycloudgallery.core.memories.testing.FakeMemoryClips
import dev.pampa.mycloudgallery.core.memories.testing.InMemoryMusicSettings
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.feature.memories.contract.MusicEvent
import dev.pampa.mycloudgallery.feature.memories.contract.NoMemoryPlaceNames
import dev.pampa.mycloudgallery.feature.memories.contract.PlayerEnvironment
import dev.pampa.mycloudgallery.feature.memories.contract.PlayerItem
import dev.pampa.mycloudgallery.feature.memories.contract.PlayerMusic
import dev.pampa.mycloudgallery.feature.memories.contract.PlayerState
import dev.pampa.mycloudgallery.feature.memories.player.PlayerController
import dev.pampa.mycloudgallery.feature.memories.player.PlayerTimings
import dev.pampa.mycloudgallery.feature.memories.testing.FakeMusicLibrary
import dev.pampa.mycloudgallery.feature.memories.testing.FakeMusicPlayback
import dev.pampa.mycloudgallery.feature.memories.testing.RecordingPlayerImages
import dev.pampa.mycloudgallery.feature.memories.ui.FinalFilter
import dev.pampa.mycloudgallery.feature.memories.ui.MemoryNames
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * La presentazione (Fase 9, P9; §1.5 `PlayerState`, §2.4, D13, D31, Q3, E-MEM14, E-MEM17, E-MEM19, E-H8), sul servizio dei ricordi di riferimento e con il
 * **tempo virtuale** (mai un'attesa scritta a mano): il titolo per 2 s, una foto ogni 4 s, la carta finale; il tocco, lo scorrimento, "Rivedi"; TalkBack
 * senza avanzamento e animazioni ridotte; gli elementi nascosti saltati e quello sullo schermo che esce subito; la musica con il focus audio.
 */
class PlayerControllerTest {

  private class Setup(
    val rig: MemoriesRig,
    val id: MemoryId,
    val scope: CoroutineScope,
    environment: PlayerEnvironment,
    mask: HiddenMask?,
    music: MusicChoice,
    songs: Set<String>,
    phoneVideos: Set<MediaId>,
  ) {
    val images = RecordingPlayerImages()
    val playback = FakeMusicPlayback()
    val library = FakeMusicLibrary().also { it.availableSongs += songs }
    val settings = InMemoryMusicSettings(music)
    val clips = FakeMemoryClips().also { it.phoneVideos += phoneVideos }
    val player = PlayerController(
      id, rig.memories, MemoryNames(rig.people, NoMemoryPlaceNames), FinalFilter(mask), clips, images, settings, library, playback, scope, environment,
    )
    val state: PlayerState get() = checkNotNull(player.ui.value.state)
    val index: Int get() = (state as? PlayerState.Playing)?.index ?: (state as PlayerState.Paused).index
  }

  private suspend fun TestScope.setup(
    environment: PlayerEnvironment = PlayerEnvironment(),
    mask: Boolean = true,
    music: MusicChoice = MusicChoice.None,
    songs: Set<String> = emptySet(),
    phoneVideos: Set<MediaId> = emptySet(),
    rig: MemoriesRig? = null,
  ): Setup {
    val rig = rig ?: MemoriesRig().also { it.refresh() }
    val id = rig.card(MemoryKind.ON_THIS_DAY, 1).id
    return Setup(rig, id, backgroundScope, environment, if (mask) rig.lib.visibility else null, music, songs, phoneVideos).also { runCurrent() }
  }

  private companion object {
    /** Il riferimento opaco di una canzone scelta (mai un percorso vero). */
    const val SONG = "riferimento"
    val OWN_SONG = MusicChoice.OwnSong(SONG)
  }

  private suspend fun TestScope.after(ms: Long) {
    advanceTimeBy(ms)
    runCurrent()
  }

  @Test
  fun ilTitoloPerDueSecondiPoiUnaFotoOgniQuattro_poiLaCartaFinale() = runTest {
    val s = setup()
    // Subito: il titolo sopra la copertina, la prima foto in attesa.
    assertThat(s.player.ui.value.intro).isNotNull()
    assertThat(s.state).isInstanceOf(PlayerState.Playing::class.java)
    after(PlayerTimings.INTRO_MS - 1)
    assertThat(s.player.ui.value.intro).isNotNull()
    after(1)
    assertThat(s.player.ui.value.intro).isNull()
    assertThat(s.index).isEqualTo(0)
    after(PlayerTimings.PHOTO_MS - 1)
    assertThat(s.index).isEqualTo(0)
    after(1)
    assertThat(s.index).isEqualTo(1)
    // Nove foto: la nona finisce a 2 s + 9 x 4 s.
    after(7 * PlayerTimings.PHOTO_MS)
    assertThat(s.index).isEqualTo(8)
    after(PlayerTimings.PHOTO_MS)
    val ended = s.state as PlayerState.Ended
    assertThat(ended.count).isEqualTo(9)
    // Non si muove piu' da solo.
    after(60_000)
    assertThat(s.state).isInstanceOf(PlayerState.Ended::class.java)
  }

  @Test
  fun leDueImmaginiSuccessiveSiChiedonoInAnticipo_eMaiPiuDiDue() = runTest {
    val s = setup()
    val first = s.images.wants.first()
    assertThat(first.second).hasSize(PlayerState.Playing.PRELOAD)
    after(PlayerTimings.INTRO_MS + PlayerTimings.PHOTO_MS)
    val (current, next) = s.images.wants.last()
    assertThat(next.size).isAtMost(PlayerState.Playing.PRELOAD)
    // L'elemento chiesto e' quello mostrato, e quelli in anticipo sono i due dopo.
    assertThat(s.images.wants.map { it.first }.distinct().size).isAtLeast(2)
    assertThat(next).doesNotContain(current)
    val playing = s.state as PlayerState.Playing
    assertThat(playing.next.map { it.media }).isEqualTo(next)
    // Alla fine: il penultimo ne ha uno, l'ultimo nessuno.
    after(10 * PlayerTimings.PHOTO_MS)
    assertThat(s.images.wants.last().second).isEmpty()
  }

  @Test
  fun uscireFermaLaMusicaELeImmagini() = runTest {
    val s = setup(music = OWN_SONG, songs = setOf(SONG))
    assertThat(s.playback.started).isTrue()
    s.player.exit()
    assertThat(s.playback.started).isFalse()
    assertThat(s.images.cleared).isEqualTo(1)
    // Fermo: il tempo non fa piu' niente.
    val before = s.index
    after(60_000)
    assertThat(s.index).isEqualTo(before)
  }

  @Test
  fun tocco_pausa_e_riprendi() = runTest {
    val s = setup()
    after(PlayerTimings.INTRO_MS + PlayerTimings.PHOTO_MS + 1)
    assertThat(s.index).isEqualTo(1)
    s.player.tap()
    assertThat(s.state).isInstanceOf(PlayerState.Paused::class.java)
    assertThat(s.player.ui.value.controls).isTrue()
    after(60_000)
    assertThat(s.index).isEqualTo(1)
    s.player.tap()
    runCurrent()
    assertThat(s.state).isInstanceOf(PlayerState.Playing::class.java)
    after(PlayerTimings.PHOTO_MS)
    assertThat(s.index).isEqualTo(2)
  }

  @Test
  fun ilToccoDuranteIlTitoloLoSalta() = runTest {
    val s = setup()
    s.player.tap()
    runCurrent()
    assertThat(s.player.ui.value.intro).isNull()
    assertThat(s.state).isInstanceOf(PlayerState.Playing::class.java)
    // Da qui una foto dura i suoi 4 secondi.
    after(PlayerTimings.PHOTO_MS)
    assertThat(s.index).isEqualTo(1)
  }

  @Test
  fun scorrimentoAvantiEIndietro_dopoLUltimaLaCartaFinale_eRivedi() = runTest {
    val s = setup()
    after(PlayerTimings.INTRO_MS)
    s.player.next()
    runCurrent()
    assertThat(s.index).isEqualTo(1)
    s.player.previous()
    runCurrent()
    assertThat(s.index).isEqualTo(0)
    s.player.previous()
    assertThat(s.index).isEqualTo(0)
    repeat(9) {
      s.player.next()
      runCurrent()
    }
    assertThat(s.state).isInstanceOf(PlayerState.Ended::class.java)
    // Indietro dalla carta finale: l'ultimo elemento.
    s.player.previous()
    runCurrent()
    assertThat(s.index).isEqualTo(8)
    repeat(2) {
      s.player.next()
      runCurrent()
    }
    s.player.restart()
    runCurrent()
    assertThat(s.index).isEqualTo(0)
    assertThat(s.player.ui.value.intro).isNull()
    after(PlayerTimings.PHOTO_MS)
    assertThat(s.index).isEqualTo(1)
  }

  @Test
  fun conTalkBackNienteParteDaSolo_iGestiScorronoLeFoto() = runTest {
    val s = setup(PlayerEnvironment(talkBack = true), music = OWN_SONG, songs = setOf(SONG))
    // Il titolo si legge e non parte niente: in pausa dal primo istante, i comandi sempre visibili, nessuna musica.
    assertThat(s.state).isInstanceOf(PlayerState.Paused::class.java)
    assertThat(s.state.talkBack).isTrue()
    assertThat(s.player.ui.value.intro).isNotNull()
    assertThat(s.player.ui.value.controls).isTrue()
    after(10 * 60_000L)
    assertThat(s.index).isEqualTo(0)
    assertThat(s.player.ui.value.intro).isNotNull()
    assertThat(s.playback.calls).doesNotContain("start")
    // Riprodurre e il tocco non fanno partire niente; i gesti avanzano.
    s.player.resume()
    s.player.tap()
    runCurrent()
    assertThat(s.state).isInstanceOf(PlayerState.Paused::class.java)
    s.player.next()
    runCurrent()
    assertThat(s.index).isEqualTo(1)
    assertThat(s.player.ui.value.intro).isNull()
    after(10 * 60_000L)
    assertThat(s.index).isEqualTo(1)
  }

  @Test
  fun TalkBackAccesoMentreSiGuarda_fermaTutto() = runTest {
    val s = setup(music = OWN_SONG, songs = setOf(SONG))
    after(PlayerTimings.INTRO_MS + PlayerTimings.PHOTO_MS + 1)
    assertThat(s.index).isEqualTo(1)
    assertThat(s.playback.started).isTrue()
    s.player.setEnvironment(PlayerEnvironment(talkBack = true))
    runCurrent()
    assertThat(s.state).isInstanceOf(PlayerState.Paused::class.java)
    assertThat(s.playback.started).isFalse()
    after(60_000)
    assertThat(s.index).isEqualTo(1)
  }

  @Test
  fun leAnimazioniRidotteSiVedonoNelloStato() = runTest {
    val s = setup(PlayerEnvironment(reducedMotion = true))
    assertThat(s.state.reducedMotion).isTrue()
    after(PlayerTimings.INTRO_MS + PlayerTimings.PHOTO_MS)
    assertThat(s.state.reducedMotion).isTrue()
    s.player.setEnvironment(PlayerEnvironment(reducedMotion = false))
    runCurrent()
    assertThat(s.state.reducedMotion).isFalse()
  }

  /** D31, E-H8: l'elemento diventato nascosto durante la presentazione si salta al prossimo passaggio e non si mostra mai. */
  @Test
  fun unElementoNascostoDurantePresentazioneSiSalta() = runTest {
    val rig = MemoriesRig().also { it.refresh() }
    val id = rig.card(MemoryKind.ON_THIS_DAY, 1).id
    val members = rig.members(id)
    val s = setup(rig = rig)
    val shown = ArrayList<MediaId>()
    fun note() {
      shown += (s.state as? PlayerState.Playing)?.item?.media ?: return
    }
    after(PlayerTimings.INTRO_MS)
    note()
    // Il catalogo dice che il terzo e' nascosto (l'istantanea ancora no): al passaggio dal secondo non compare.
    rig.lib.visibility.hidden += members[2]
    repeat(9) {
      after(PlayerTimings.PHOTO_MS)
      note()
    }
    assertThat(shown).doesNotContain(members[2])
    assertThat(shown.toSet()).containsExactlyElementsIn(members.filter { it != members[2] })
    assertThat(s.state).isInstanceOf(PlayerState.Ended::class.java)
    assertThat((s.state as PlayerState.Ended).count).isEqualTo(8)
  }

  @Test
  fun quelloSulloSchermoCheDiventaNascostoEsceSubito() = runTest {
    val rig = MemoriesRig().also { it.refresh() }
    val id = rig.card(MemoryKind.ON_THIS_DAY, 1).id
    val members = rig.members(id)
    val s = setup(rig = rig)
    after(PlayerTimings.INTRO_MS + PlayerTimings.PHOTO_MS)
    assertThat((s.state as PlayerState.Playing).item.media).isEqualTo(members[1])
    rig.lib.hide(members[1])
    runCurrent()
    // Subito il successivo (non il tempo che resta), e l'indice segue gli elementi rimasti.
    val now = (s.state as PlayerState.Playing)
    assertThat(now.item.media).isEqualTo(members[2])
    assertThat(now.index).isEqualTo(1)
    // Nascondere quello che e' prima non sposta niente.
    rig.lib.hide(members[0])
    runCurrent()
    assertThat((s.state as PlayerState.Playing).item.media).isEqualTo(members[2])
    assertThat((s.state as PlayerState.Playing).index).isEqualTo(0)
  }

  @Test
  fun sottoTreElementiQuestoRicordoNonCePiu() = runTest {
    val rig = MemoriesRig().also { it.refresh() }
    val id = rig.card(MemoryKind.ON_THIS_DAY, 1).id
    val members = rig.members(id)
    val s = setup(rig = rig)
    after(PlayerTimings.INTRO_MS)
    members.take(7).forEach { rig.lib.hide(it) }
    runCurrent()
    assertThat(s.player.ui.value.gone).isTrue()
    assertThat(s.player.ui.value.state).isNull()
    assertThat(s.playback.calls).contains("stop")
    after(60_000)
    assertThat(s.player.ui.value.gone).isTrue()
  }

  @Test
  fun senzaIlServizio_nonCrolla_nonCePiu() = runTest {
    val s = setup()
    val none = PlayerController(
      s.id, NoMemories, MemoryNames(null, NoMemoryPlaceNames), FinalFilter(null), NoMemoryClips, s.images, s.settings, s.library, s.playback, backgroundScope,
    )
    runCurrent()
    assertThat(none.ui.value.gone).isTrue()
    assertThat(none.ui.value.state).isNull()
  }

  @Test
  fun visto_siSegnaPartendoUnaVoltaSola() = runTest {
    val rig = MemoriesRig().also { it.refresh() }
    val id = rig.card(MemoryKind.ON_THIS_DAY, 1).id
    assertThat(rig.cards().first { it.id == id }.isNew).isTrue()
    val s = setup(rig = rig)
    assertThat(rig.cards().first { it.id == id }.isNew).isFalse()
    after(60_000)
    assertThat(s.state).isInstanceOf(PlayerState.Ended::class.java)
  }

  @Test
  fun unaSorgenteLentaNonFermaIlTempo_leImmaginiSiChiedonoSenzaAspettare() = runTest {
    // `RecordingPlayerImages.want` e' come quello vero: non sospende. Il ritmo non dipende da niente di cio' che le immagini fanno.
    val s = setup()
    after(PlayerTimings.INTRO_MS + 2 * PlayerTimings.PHOTO_MS)
    assertThat(s.index).isEqualTo(2)
    assertThat(s.images.wants).isNotEmpty()
  }

  @Test
  fun unVideoDelTelefonoDuraQuantoIlSuoClip_unoSoloDelNasIlFotogramma() = runTest {
    val rig = MemoriesRig()
    val video = rig.lib.add(LocalDate.of(2025, 11, 3), time = LocalTime.of(9, 10), kind = MediaKind.VIDEO, durationMs = 20_000, flags = CellFlags.FAVORITE)
    rig.refresh()
    val id = rig.card(MemoryKind.ON_THIS_DAY, 1).id
    val members = rig.members(id)
    assertThat(members).contains(video)
    val at = members.indexOf(video)
    // Un video del telefono: il clip muto (8 s). Un video del solo NAS: il fotogramma di copertina (4 s, E-MEM17).
    for ((phone, seconds) in listOf(true to 8_000L, false to PlayerTimings.PHOTO_MS)) {
      val s = setup(rig = rig, phoneVideos = if (phone) setOf(video) else emptySet())
      s.player.tap()
      runCurrent()
      repeat(at) {
        s.player.next()
        runCurrent()
      }
      val item = (s.state as PlayerState.Playing).item
      assertThat(item).isInstanceOf(PlayerItem.Video::class.java)
      assertThat((item as PlayerItem.Video).clip != null).isEqualTo(phone)
      after(seconds - 1)
      assertThat(s.index).isEqualTo(at)
      after(1)
      assertThat(s.index).isEqualTo(at + 1)
      s.player.exit()
    }
  }

  // --- La musica (Q3) e il focus audio ---------------------------------------------------------------------------------------------

  @Test
  fun diSerieMuta_nessunaMusica() = runTest {
    val s = setup()
    assertThat((s.state as PlayerState.Playing).music).isEqualTo(PlayerMusic.MUTED)
    assertThat(s.playback.calls).isEmpty()
    assertThat(s.player.ui.value.musicMissing).isFalse()
  }

  @Test
  fun laCanzoneProprieParteConLaPresentazione_eSiFermaAllaFine() = runTest {
    val s = setup(music = OWN_SONG, songs = setOf(SONG))
    assertThat(s.playback.calls).containsExactly("start")
    assertThat((s.state as PlayerState.Playing).music).isEqualTo(PlayerMusic.PLAYING)
    after(PlayerTimings.INTRO_MS + 9 * PlayerTimings.PHOTO_MS)
    assertThat(s.state).isInstanceOf(PlayerState.Ended::class.java)
    assertThat(s.playback.calls.last()).isEqualTo("stop")
  }

  @Test
  fun ilFileSparito_presentazioneMutaEUnAvviso() = runTest {
    val s = setup(music = MusicChoice.OwnSong("sparita"))
    assertThat((s.state as PlayerState.Playing).music).isEqualTo(PlayerMusic.MISSING)
    assertThat(s.player.ui.value.musicMissing).isTrue()
    assertThat(s.playback.calls).doesNotContain("start")
    // La presentazione va avanti, muta (prima il titolo, poi la prima foto).
    after(PlayerTimings.INTRO_MS + PlayerTimings.PHOTO_MS)
    assertThat(s.index).isEqualTo(1)
  }

  @Test
  fun ilFileCheSpariscePerStrada_MISSING_avvisoEMuta() = runTest {
    val s = setup(music = OWN_SONG, songs = setOf(SONG))
    s.playback.emit(MusicEvent.MISSING)
    runCurrent()
    assertThat(s.player.ui.value.musicMissing).isTrue()
    assertThat((s.state as PlayerState.Playing).music).isEqualTo(PlayerMusic.MISSING)
  }

  /** Focus audio: una chiamata ferma presentazione e musica; quando finisce si riparte; non se l'utente era gia' in pausa. */
  @Test
  fun unaChiamataFermaTuttoELaFineRiprende() = runTest {
    val s = setup(music = OWN_SONG, songs = setOf(SONG))
    after(PlayerTimings.INTRO_MS + PlayerTimings.PHOTO_MS + 10)
    assertThat(s.index).isEqualTo(1)
    s.playback.emit(MusicEvent.FOCUS_LOST)
    runCurrent()
    assertThat(s.state).isInstanceOf(PlayerState.Paused::class.java)
    assertThat(s.playback.calls.last()).isEqualTo("pause")
    after(60_000)
    assertThat(s.index).isEqualTo(1)
    s.playback.emit(MusicEvent.FOCUS_GAINED)
    runCurrent()
    assertThat(s.state).isInstanceOf(PlayerState.Playing::class.java)
    assertThat(s.playback.calls.last()).isEqualTo("resume")
    after(PlayerTimings.PHOTO_MS)
    assertThat(s.index).isEqualTo(2)
    // L'utente mette in pausa; una chiamata e la sua fine non lo fanno ripartire.
    s.player.tap()
    s.playback.emit(MusicEvent.FOCUS_LOST)
    s.playback.emit(MusicEvent.FOCUS_GAINED)
    runCurrent()
    assertThat(s.state).isInstanceOf(PlayerState.Paused::class.java)
  }

  @Test
  fun unAltroAppPrendeLAudioPerSempre_laMusicaSiFermaELaPresentazioneAspetta() = runTest {
    val s = setup(music = OWN_SONG, songs = setOf(SONG))
    s.playback.emit(MusicEvent.FOCUS_LOST_FOR_GOOD)
    runCurrent()
    assertThat(s.state).isInstanceOf(PlayerState.Paused::class.java)
    assertThat(s.playback.started).isFalse()
    // Il ritorno dell'audio non riprende da solo; l'utente riprende, muto.
    s.playback.emit(MusicEvent.FOCUS_GAINED)
    runCurrent()
    assertThat(s.state).isInstanceOf(PlayerState.Paused::class.java)
    s.player.tap()
    runCurrent()
    val playing = s.state as PlayerState.Playing
    assertThat(playing.music).isEqualTo(PlayerMusic.MUTED)
  }

  @Test
  fun inPausaLaMusicaSiFerma_eRiprendeConLaPresentazione() = runTest {
    val s = setup(music = OWN_SONG, songs = setOf(SONG))
    after(PlayerTimings.INTRO_MS)
    s.player.tap()
    assertThat(s.playback.calls.last()).isEqualTo("pause")
    s.player.tap()
    runCurrent()
    assertThat(s.playback.calls.last()).isEqualTo("resume")
  }
}
