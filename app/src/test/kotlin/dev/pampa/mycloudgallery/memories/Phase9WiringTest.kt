package dev.pampa.mycloudgallery.memories

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.map.contract.MapDiag
import dev.pampa.mycloudgallery.core.map.contract.MapNetwork
import dev.pampa.mycloudgallery.core.map.contract.MapOnline
import dev.pampa.mycloudgallery.core.memories.contract.DropReason
import dev.pampa.mycloudgallery.core.memories.contract.MemoriesDiag
import dev.pampa.mycloudgallery.core.memories.contract.MusicChoice
import dev.pampa.mycloudgallery.core.memories.contract.NotifyFrequency
import dev.pampa.mycloudgallery.core.memories.contract.NotifyPrefs
import dev.pampa.mycloudgallery.core.memories.contract.NotifySkip
import dev.pampa.mycloudgallery.core.memories.contract.RefreshReport
import dev.pampa.mycloudgallery.core.memories.settings.less.PeoplePhase
import dev.pampa.mycloudgallery.core.network.contract.NetPlace
import dev.pampa.mycloudgallery.core.network.contract.NetTransport
import dev.pampa.mycloudgallery.core.network.contract.NetworkSnapshot
import dev.pampa.mycloudgallery.core.network.contract.UnderlyingNetwork
import dev.pampa.mycloudgallery.core.rules.traffic.TrafficNetwork
import dev.pampa.mycloudgallery.feature.diagnostics.catalog.DiagCatalog
import dev.pampa.mycloudgallery.feature.diagnostics.export.ExportKeys
import dev.pampa.mycloudgallery.feature.memories.contract.NotifyPermission
import dev.pampa.mycloudgallery.feature.settings.map.MapEntryState
import dev.pampa.mycloudgallery.feature.settings.memories.MemoriesEntryState
import dev.pampa.mycloudgallery.map.AppMapEntryStatus
import dev.pampa.mycloudgallery.map.AppMapNetworkState
import dev.pampa.mycloudgallery.map.LedgerMapTraffic
import dev.pampa.mycloudgallery.notify.NotifyPagePermission
import java.io.File
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Fase 9, P13: le porte piccole di `:app` per ricordi e mappa, una per una e senza Android. I codici della Diagnostica della Fase 9 sono tutti nel catalogo
 * (`feature-diagnostics` non vede `core-memories` ne' `core-map`: il controllo sta qui, come `Phase8DiagCatalogTest`), le chiavi che le due sezioni esportano
 * sono nell'elenco chiuso, lo stato di Persone per i ricordi non cancella mai niente "per non so", la rete e il traffico della mappa, le voci di Impostazioni,
 * la scelta della musica su file e i ritardi delle notifiche.
 */
class Phase9WiringTest {

  @get:Rule
  val temp = TemporaryFolder()

  @Test
  fun ogniCodiceDelParagrafo212StaNelCatalogoNellaSuaSezione() {
    for (id in MemoriesDiag.ALL) {
      val entry = DiagCatalog.entry(DiagCode(id))
      assertWithMessage("$id non e' nel catalogo").that(entry).isNotNull()
      assertThat(entry!!.section).isEqualTo(DiagSection.MEMORIES)
      assertThat(entry.titleRes).isNotEqualTo(0)
      assertThat(entry.guideRes).isNotEqualTo(0)
    }
    for (id in MapDiag.ALL) {
      val entry = DiagCatalog.entry(DiagCode(id))
      assertWithMessage("$id non e' nel catalogo").that(entry).isNotNull()
      assertThat(entry!!.section).isEqualTo(DiagSection.MAP)
      assertThat(entry.titleRes).isNotEqualTo(0)
    }
  }

  @Test
  fun leChiaviDiRicordiEMappaSonoNellElencoChiusoDellEsportazione() {
    val memories = listOf("mounted", "onShelf", "unseen", "status", "roundErrors", "lastRoundDays", "roundMs", "failed", "count", "late", "delivered") +
      DropReason.entries.map { "dropped.${it.code}" } + NotifySkip.entries.map { "skip.${it.code}" }
    val map = listOf("online", "available", "regions", "byDefault", "opened", "points", "withoutPosition", "allowed", "bytes", "errors", "paused",
      "withoutVersion", "tileJson", "count") + dev.pampa.mycloudgallery.core.map.contract.RefuseReason.entries.map { "reason.${it.name}" }
    for (key in memories + map) assertWithMessage(key).that(ExportKeys.isAllowed(key)).isTrue()
  }

  @Test
  fun loStatoDiPersonePerIRicordiNonCancellaMaiPerNonSo() {
    // "Spenta" vale solo dopo un giro completo dei dati personali in questo processo: prima potrebbe essere un registro locale indietro.
    assertThat(FacesPeopleSwitch.phaseOf(FacesState.Off, confirmed = false)).isEqualTo(PeoplePhase.UNKNOWN)
    assertThat(FacesPeopleSwitch.phaseOf(FacesState.Off, confirmed = true)).isEqualTo(PeoplePhase.OFF)
    assertThat(FacesPeopleSwitch.phaseOf(FacesState.NeedsUpdate, confirmed = true)).isEqualTo(PeoplePhase.UNKNOWN)
    assertThat(FacesPeopleSwitch.phaseOf(FacesState.NeedsUpdate, confirmed = true).gone).isFalse()
    assertThat(PeoplePhase.UNKNOWN.gone).isFalse()
  }

  @Test
  fun laReteDellaMappaEIlSuoTraffico() {
    fun snapshot(transport: NetTransport?, metered: Boolean = false, roaming: Boolean = false, validated: Boolean = true, place: NetPlace = NetPlace.VPN_OFF) =
      NetworkSnapshot.OFFLINE.copy(
        place = place,
        underlying = transport?.let { UnderlyingNetwork("rete", it, metered, roaming, validated, captivePortal = false) },
      )
    assertThat(AppMapNetworkState.networkOf(snapshot(null))).isEqualTo(MapNetwork.NONE)
    assertThat(AppMapNetworkState.networkOf(snapshot(NetTransport.WIFI, validated = false))).isEqualTo(MapNetwork.NONE)
    assertThat(AppMapNetworkState.networkOf(snapshot(NetTransport.CELLULAR, roaming = true))).isEqualTo(MapNetwork.ROAMING)
    assertThat(AppMapNetworkState.networkOf(snapshot(NetTransport.CELLULAR))).isEqualTo(MapNetwork.MOBILE)
    // E-R13: un Wi-Fi a consumo vale come i dati mobili.
    assertThat(AppMapNetworkState.networkOf(snapshot(NetTransport.WIFI, metered = true))).isEqualTo(MapNetwork.MOBILE)
    assertThat(AppMapNetworkState.networkOf(snapshot(NetTransport.WIFI))).isEqualTo(MapNetwork.WIFI)
    // Il traffico va nel registro come rete vista dal client; il Wi-Fi di casa o fuori lo dice core-network.
    assertThat(LedgerMapTraffic.trafficOf(MapNetwork.ROAMING, snapshot(NetTransport.CELLULAR, roaming = true))).isEqualTo(TrafficNetwork.ROAMING)
    assertThat(LedgerMapTraffic.trafficOf(MapNetwork.MOBILE, snapshot(NetTransport.CELLULAR))).isEqualTo(TrafficNetwork.MOBILE)
    assertThat(LedgerMapTraffic.trafficOf(MapNetwork.WIFI, snapshot(NetTransport.WIFI, place = NetPlace.HOME))).isEqualTo(TrafficNetwork.HOME_WIFI)
  }

  @Test
  fun leVociDiImpostazioni() {
    assertThat(AppMapEntryStatus.summaryOf(MapOnline.On(byDefault = true)).state).isEqualTo(MapEntryState.ON)
    assertThat(AppMapEntryStatus.summaryOf(MapOnline.Off).state).isEqualTo(MapEntryState.OFF)
    assertThat(AppMapEntryStatus.summaryOf(MapOnline.Unavailable).state).isEqualTo(MapEntryState.UNAVAILABLE)
    val ok = AppMemoriesEntryStatus.summaryOf(NotifyPrefs(), NotifyPermission.GRANTED)
    assertThat(ok.state).isEqualTo(MemoriesEntryState.FEW_PER_WEEK)
    assertThat(ok.needsAttention).isFalse()
    // Accese ma bloccate dal permesso o dal canale: la voce lo segnala (non chiede niente).
    assertThat(AppMemoriesEntryStatus.summaryOf(NotifyPrefs(frequency = NotifyFrequency.DAILY), NotifyPermission.CHANNEL_OFF).needsAttention).isTrue()
    // Spente: niente da segnalare.
    val off = AppMemoriesEntryStatus.summaryOf(NotifyPrefs(enabled = false), NotifyPermission.DENIED)
    assertThat(off.state).isEqualTo(MemoriesEntryState.OFF)
    assertThat(off.needsAttention).isFalse()
    // I quattro stati della pagina si legano per codice (P10, punto 5).
    for (page in NotifyPagePermission.entries) assertThat(AppNotificationAccess.pageOf(page).code).isEqualTo(page.code)
  }

  @Test
  fun laSceltaDellaMusicaSuFile() {
    for (choice in listOf(MusicChoice.None, MusicChoice.OwnSong("content://com.android.providers.media.documents/document/audio%3A12"))) {
      assertThat(FileMusicSettings.decode(FileMusicSettings.encode(choice))).isEqualTo(choice)
    }
    // Un file rovinato, un percorso al posto di un documento, un brano libero che il catalogo (vuoto, Q3) non conosce: nessuna musica.
    for (bad in listOf("", "boh", "own\n/sdcard/Music/canzone.mp3", "free\nbrano-sconosciuto")) assertThat(FileMusicSettings.decode(bad)).isEqualTo(MusicChoice.None)
  }

  @Test
  fun iRitardiDelleNotificheContanoGliUltimiQuattordiciGiorni() {
    var today = LocalDate.of(2026, 10, 1)
    val late = LateDeliveries(File(temp.root, LateDeliveries.FILE)) { today }
    late.record()
    today = today.plusDays(5)
    late.record()
    assertThat(late.count()).isEqualTo(2)
    today = today.plusDays(10)
    // Il primo ritardo ha 15 giorni: fuori dalla finestra.
    assertThat(late.count()).isEqualTo(1)
    // Il file ha solo giorni, mai che ricordo.
    assertThat(File(temp.root, LateDeliveries.FILE).readText().lines().all { it.matches(Regex("[0-9]{8}")) }).isTrue()
  }

  @Test
  fun iContatoriDellaDiagnosticaSommanoPerMotivo() {
    val counters = MemoriesCounters()
    counters.round(RefreshReport(1, 0, 0, shelfReset = true, incomplete = false, withoutVectors = true, dropped = mapOf(DropReason.SCREENSHOT to 3)))
    counters.round(RefreshReport(0, 0, 0, shelfReset = false, incomplete = true, withoutVectors = false, dropped = mapOf(DropReason.SCREENSHOT to 2)))
    counters.skipped(NotifySkip.PERMISSION)
    counters.revoked()
    assertThat(counters.shelfResets).isEqualTo(1)
    assertThat(counters.withoutVectors).isEqualTo(1)
    assertThat(counters.droppedByReason()).containsExactly(DropReason.SCREENSHOT, 5)
    assertThat(counters.skippedByReason()).containsExactly(NotifySkip.PERMISSION, 1)
    assertThat(counters.revoked).isEqualTo(1)
  }
}
