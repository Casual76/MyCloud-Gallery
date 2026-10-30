package dev.pampa.mycloudgallery.core.faces.erase

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.FacesSetting
import dev.pampa.mycloudgallery.core.personal.contract.FacesSettingCodec
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKind
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import dev.pampa.mycloudgallery.core.personal.sync.PersonalHeadsRecord
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * I7 (correzioni della Fase 8), il vincolo: la testa personale che un dispositivo con il montaggio delle persone fallito pubblica **senza la voce
 * `faces`** non vale mai come conferma di una cancellazione ([EraseRules.confirms]) e non fa credere agli altri che quel dispositivo abbia cancellato
 * ([EraseRules.announcedAt]) o tratti una generazione ([EraseRules.recentlyClaimed]). La testa si ricorda come la ricorda chi la legge
 * (`PersonalHeadsRecord`, la stessa classe del motore vero: `FacesPrePublishSyncTest.i7_*` prova che la testa esce cosi').
 */
class MountFailedHeadTest {
  private val g = FaceGeneration.fromBytes(ByteArray(16) { 9 })
  private val now = 1_800_000_000_000L

  private val author = TrustIds.random()

  private fun favorite(): PersonalEntry = PersonalEntry(PersonalKey(PersonalKind.SETTING, "impostazioni", "altro"), now, author, PersonalValue.Text("x"))

  @Test
  fun i7_unaTestaSenzaLaVoceFacesNonConfermaENonDiceNiente() = runTest {
    val view = PersonalHeadsRecord()
    // B ha nello stato `on|G` ma, con le persone non montate, la sua testa esce senza la voce `faces` (solo il resto dei dati personali).
    view.record("dispositivo-b", now, listOf(favorite()))
    val heads = view.latestValueOf(FacesSettingCodec.KEY)
    val b = heads.getValue("dispositivo-b")
    assertThat(b.entry).isNull()
    assertThat(EraseRules.confirms(b, listOf(g), offAtNas = now - 60_000)).isFalse()
    assertThat(EraseRules.announcedAt(b, listOf(g), FacesSetting.Off(g))).isNull()
    assertThat(EraseRules.recentlyClaimed(heads, g, now)).isFalse()
    // Per contrasto: la stessa testa con la voce `off|G` conferma; con `on|G` dice di trattare G.
    view.record("dispositivo-c", now, listOf(PersonalEntry(FacesSettingCodec.KEY, now, author, FacesSettingCodec.valueOf(FacesSetting.Off(g)))))
    view.record("dispositivo-d", now, listOf(PersonalEntry(FacesSettingCodec.KEY, now, author, FacesSettingCodec.valueOf(FacesSetting.On(g, 1)))))
    val all = view.latestValueOf(FacesSettingCodec.KEY)
    assertThat(EraseRules.confirms(all.getValue("dispositivo-c"), listOf(g), offAtNas = now - 60_000)).isTrue()
    assertThat(EraseRules.recentlyClaimed(all, g, now)).isTrue()
  }
}
