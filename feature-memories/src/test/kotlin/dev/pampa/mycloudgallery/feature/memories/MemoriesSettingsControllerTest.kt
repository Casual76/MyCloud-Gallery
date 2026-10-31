package dev.pampa.mycloudgallery.feature.memories

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.memories.contract.LessLevel
import dev.pampa.mycloudgallery.core.memories.contract.LessTarget
import dev.pampa.mycloudgallery.core.memories.contract.LockScreenMode
import dev.pampa.mycloudgallery.core.memories.contract.MusicChoice
import dev.pampa.mycloudgallery.core.memories.contract.MusicLicense
import dev.pampa.mycloudgallery.core.memories.contract.MusicPackCatalog
import dev.pampa.mycloudgallery.core.memories.contract.MusicTrackPin
import dev.pampa.mycloudgallery.core.memories.contract.MusicTrackState
import dev.pampa.mycloudgallery.core.memories.contract.NotifyFrequency
import dev.pampa.mycloudgallery.core.memories.contract.NotifyPrefs
import dev.pampa.mycloudgallery.core.memories.contract.DayRange
import dev.pampa.mycloudgallery.core.memories.testing.InMemoryMusicSettings
import dev.pampa.mycloudgallery.core.memories.testing.InMemoryNotifySettings
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.feature.memories.contract.MemoriesMessage
import dev.pampa.mycloudgallery.feature.memories.contract.NotifyPermission
import dev.pampa.mycloudgallery.feature.memories.contract.PeopleMemoriesRow
import dev.pampa.mycloudgallery.feature.memories.settings.MemoriesSettingsController
import dev.pampa.mycloudgallery.feature.memories.testing.FakeMusicLibrary
import dev.pampa.mycloudgallery.feature.memories.testing.FakeNotificationAccess
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Impostazioni -> Ricordi e notifiche (Fase 9, P9; §1.5 `MemoriesSettingsState`, §2.3, §2.6, D11, D15, D16, D18, Q3, Q4, Q5 (a), Q6 (a), Q9 (a)): lo stato
 * di ogni riga, le scelte che si salvano, il permesso e il canale, "Togli" e la musica (con il pacchetto di musica libera **vuoto** finche' l'utente non
 * approva fonti e brani).
 */
class MemoriesSettingsControllerTest {

  private class Rig(scope: CoroutineScope, peopleAvailable: Boolean = true, prefs: NotifyPrefs = NotifyPrefs(), val catalog: List<MusicTrackPin> = emptyList()) {
    val mem = MemoriesRig()
    val notify = InMemoryNotifySettings(prefs)
    val access = FakeNotificationAccess()
    val music = InMemoryMusicSettings()
    val library = FakeMusicLibrary()
    val available = MutableStateFlow(peopleAvailable)
    val controller = MemoriesSettingsController(mem.memories, notify, access, music, library, available, scope, catalog)
  }

  private fun pin(id: String) = MusicTrackPin(id, "Brano di prova", "Autore di prova", MusicLicense.CC0, 1_000_000, "a".repeat(64))

  @Test
  fun lo_StatoDiPartenza_QuelloDiSerie() = runTest {
    val r = Rig(backgroundScope)
    runCurrent()
    val state = r.controller.ui.value.state
    assertThat(state.people).isEqualTo(PeopleMemoriesRow.ON)
    assertThat(state.notify).isEqualTo(NotifyPrefs())
    assertThat(state.notify.hour).isEqualTo(LocalTime.of(19, 0))
    assertThat(state.notify.frequency).isEqualTo(NotifyFrequency.FEW_PER_WEEK)
    // Q5 (a): di serie solo "Un ricordo per te".
    assertThat(state.notify.lockScreen).isEqualTo(LockScreenMode.GENERIC)
    assertThat(state.permission).isEqualTo(NotifyPermission.GRANTED)
    assertThat(state.lessChoices).isEmpty()
    // Q3: di serie nessuna musica.
    assertThat(state.music).isEqualTo(MusicChoice.None)
  }

  @Test
  fun leRighiDelleSettingsNonHannoIlFiltroAutomatico_Q6a() {
    val fields = dev.pampa.mycloudgallery.feature.memories.contract.MemoriesSettingsUi::class.java.declaredFields.map { it.name.lowercase() }
    assertThat(fields.none { "sad" in it || "filter" in it || "trist" in it }).isTrue()
  }

  @Test
  fun leRighePersoneCeSoloConPersoneAttiva_eSiSpegne() = runTest {
    val r = Rig(backgroundScope, peopleAvailable = false)
    runCurrent()
    assertThat(r.controller.ui.value.state.people).isEqualTo(PeopleMemoriesRow.HIDDEN)
    r.available.value = true
    runCurrent()
    assertThat(r.controller.ui.value.state.people).isEqualTo(PeopleMemoriesRow.ON)
    r.controller.setPeople(false)
    runCurrent()
    assertThat(r.mem.memories.peopleMemories.value).isFalse()
    assertThat(r.controller.ui.value.state.people).isEqualTo(PeopleMemoriesRow.OFF)
  }

  @Test
  fun leScelteDelleNotificheSiSalvano() = runTest {
    val r = Rig(backgroundScope)
    runCurrent()
    r.controller.setNotifyEnabled(false)
    runCurrent()
    assertThat(r.notify.prefs.value.enabled).isFalse()
    r.controller.setNotifyEnabled(true)
    r.controller.setFrequency(NotifyFrequency.DAILY)
    r.controller.setLockScreen(LockScreenMode.TITLE_AND_PHOTO)
    runCurrent()
    assertThat(r.controller.ui.value.state.notify).isEqualTo(NotifyPrefs(enabled = true, frequency = NotifyFrequency.DAILY, lockScreen = LockScreenMode.TITLE_AND_PHOTO))
  }

  /** L'ora va a passi di 15 minuti e ricomincia dopo la mezzanotte (e prima): mai un'ora che `NotifyPrefs` rifiuterebbe. */
  @Test
  fun lOraVaAQuartiDOra_eFaIlGiro() = runTest {
    val r = Rig(backgroundScope)
    runCurrent()
    r.controller.shiftHour(1)
    runCurrent()
    assertThat(r.notify.prefs.value.hour).isEqualTo(LocalTime.of(19, 15))
    r.controller.shiftHour(-3)
    runCurrent()
    assertThat(r.notify.prefs.value.hour).isEqualTo(LocalTime.of(18, 30))
    val late = Rig(backgroundScope, prefs = NotifyPrefs(hour = LocalTime.of(23, 45)))
    late.controller.shiftHour(1)
    runCurrent()
    assertThat(late.notify.prefs.value.hour).isEqualTo(LocalTime.MIDNIGHT)
    late.controller.shiftHour(-1)
    runCurrent()
    assertThat(late.notify.prefs.value.hour).isEqualTo(LocalTime.of(23, 45))
  }

  @Test
  fun ilPermessoEIlCanaleSiVedono_eSiRileggono() = runTest {
    val r = Rig(backgroundScope)
    runCurrent()
    for (permission in NotifyPermission.entries) {
      r.access.set(permission)
      runCurrent()
      assertThat(r.controller.ui.value.state.permission).isEqualTo(permission)
    }
    r.controller.refreshAccess()
    assertThat(r.access.refreshes).isEqualTo(1)
  }

  @Test
  fun togliUnaScelta_valeSubito_ePersoneEPeriodi() = runTest {
    val r = Rig(backgroundScope)
    r.mem.refresh()
    val anna = PersonId.fromBytes(ByteArray(16) { (it + 5).toByte() })
    val days = DayRange(LocalDate.of(2024, 3, 1), LocalDate.of(2024, 3, 6))
    r.mem.memories.setLess(LessTarget.Person(anna), LessLevel.LESS)
    r.mem.memories.setLess(LessTarget.Days(days), LessLevel.NEVER)
    runCurrent()
    val choices = r.controller.ui.value.state.lessChoices
    assertThat(choices).hasSize(2)
    r.controller.removeLess(choices.first { it.target is LessTarget.Person })
    runCurrent()
    assertThat(r.controller.ui.value.state.lessChoices.map { it.target }).containsExactly(LessTarget.Days(days))
    r.controller.removeLess(r.controller.ui.value.state.lessChoices.single())
    runCurrent()
    assertThat(r.controller.ui.value.state.lessChoices).isEmpty()
  }

  /** Q3: il pacchetto di musica libera ha il catalogo VUOTO finche' l'utente non approva fonti e brani: la pagina non ha brani da mostrare. */
  @Test
  fun ilPacchettoDiMusicaLiberaEVuoto_SinoAllApprovazione() = runTest {
    assertThat(MusicPackCatalog.TRACKS).isEmpty()
    val r = Rig(backgroundScope)
    runCurrent()
    assertThat(r.controller.ui.value.tracks).isEmpty()
    // Una scelta della canzone propria si vede (la salva :app dopo il selettore di sistema).
    r.music.set(MusicChoice.OwnSong("riferimento"))
    runCurrent()
    assertThat(r.controller.ui.value.state.music).isEqualTo(MusicChoice.OwnSong("riferimento"))
    r.controller.chooseNoMusic()
    runCurrent()
    assertThat(r.controller.ui.value.state.music).isEqualTo(MusicChoice.None)
  }

  @Test
  fun conUnPacchettoApprovato_siInstallaESiUsaSoloSePronto() = runTest {
    val r = Rig(backgroundScope, catalog = listOf(pin("brano_uno"), pin("brano_due")))
    runCurrent()
    assertThat(r.controller.ui.value.tracks.map { it.pin.id }).containsExactly("brano_uno", "brano_due").inOrder()
    assertThat(r.controller.ui.value.tracks.map { it.state }).containsExactly(MusicTrackState.NotInstalled, MusicTrackState.NotInstalled)
    // Non installato: non si usa.
    r.controller.useTrack("brano_uno")
    runCurrent()
    assertThat(r.controller.ui.value.state.music).isEqualTo(MusicChoice.None)
    r.controller.installTrack("brano_uno")
    runCurrent()
    assertThat(r.library.installs).isEqualTo(1)
    assertThat(r.controller.ui.value.tracks.first().state).isInstanceOf(MusicTrackState.Installing::class.java)
    r.library.set("brano_uno", MusicTrackState.Ready)
    runCurrent()
    r.controller.useTrack("brano_uno")
    runCurrent()
    val chosen = r.controller.ui.value.tracks
    assertThat(chosen.first().chosen).isTrue()
    assertThat(chosen.last().chosen).isFalse()
    assertThat(r.controller.ui.value.state.music).isEqualTo(MusicChoice.FreeTrack("brano_uno"))
    // Un brano che non e' nel catalogo non si installa, e lo dice.
    r.controller.installTrack("sconosciuto")
    runCurrent()
    assertThat(r.controller.ui.value.message).isEqualTo(MemoriesMessage.FAILED)
    r.controller.dismissMessage()
    runCurrent()
    assertThat(r.controller.ui.value.message).isNull()
  }

  /** MU-3 (31/10): la canzone scelta non si e' potuta conservare (il fornitore non concede il permesso persistente): la pagina lo dice, e la scelta resta com'era. */
  @Test
  fun laCanzoneCheNonSiConserva_laPaginaLoDice() = runTest {
    val r = Rig(backgroundScope)
    runCurrent()
    assertThat(r.controller.ui.value.message).isNull()
    r.controller.ownSongNotKept()
    runCurrent()
    assertThat(r.controller.ui.value.message).isEqualTo(MemoriesMessage.FAILED)
    assertThat(r.controller.ui.value.state.music).isEqualTo(MusicChoice.None)
    r.controller.dismissMessage()
    runCurrent()
    assertThat(r.controller.ui.value.message).isNull()
  }
}
