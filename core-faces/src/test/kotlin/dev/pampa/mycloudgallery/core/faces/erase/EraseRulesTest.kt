package dev.pampa.mycloudgallery.core.faces.erase

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.faces.contract.AckState
import dev.pampa.mycloudgallery.core.faces.contract.DeviceAck
import dev.pampa.mycloudgallery.core.faces.contract.EraseWait
import dev.pampa.mycloudgallery.core.faces.contract.NasEraseRound
import dev.pampa.mycloudgallery.core.faces.contract.PeopleEdit
import dev.pampa.mycloudgallery.core.faces.contract.PeopleRecord
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.FacesSetting
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.personal.contract.FacesSettingCodec
import dev.pampa.mycloudgallery.core.personal.contract.HeadValue
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import org.junit.Test

/**
 * Le regole pure di "Disattiva e cancella" (Fase 8, P8; D30, §2.8 passi 4 e 10, D31): chi conferma (solo con una prova esplicita non piu' vecchia
 * della voce `off`), quando la propria voce e' uscita, la trattenuta di E-F9, i nomi della cronologia, l'impronta dell'anteprima, gli esiti di
 * core-nasops letti dai codici.
 */
class EraseRulesTest {

  private val g = FaceGeneration.fromBytes(ByteArray(16) { 1 })
  private val g2 = FaceGeneration.fromBytes(ByteArray(16) { 2 })
  private val author = TrustIds.random()
  private val off = 1_800_000_000_000L

  private fun head(setting: FacesSetting?, at: Long, headAt: Long = at): HeadValue =
    HeadValue(setting?.let { PersonalEntry(FacesSettingCodec.KEY, at, author, FacesSettingCodec.valueOf(it)) }, headAt)

  @Test
  fun confermaSoloUnaProvaEsplicitaNonPiuVecchiaDellOff() {
    val erased = listOf(g)
    assertThat(EraseRules.confirms(head(FacesSetting.Off(g), off), erased, off)).isTrue()
    assertThat(EraseRules.confirms(head(FacesSetting.Off(g2), off + 1), erased, off)).isTrue()
    assertThat(EraseRules.confirms(head(FacesSetting.On(g2, 1), off + 1), erased, off)).isTrue()
    // Ancora accesa sulla generazione cancellata, senza voce, sconosciuta, piu' vecchia dell'off, o l'off non ancora uscito: niente.
    assertThat(EraseRules.confirms(head(FacesSetting.On(g, 1), off + 5), erased, off)).isFalse()
    assertThat(EraseRules.confirms(head(null, off + 5), erased, off)).isFalse()
    assertThat(EraseRules.confirms(HeadValue(PersonalEntry(FacesSettingCodec.KEY, off + 5, author, PersonalValue.Text("9|futuro")), off + 5), erased, off)).isFalse()
    assertThat(EraseRules.confirms(head(FacesSetting.Off(g2), off - 1), erased, off)).isFalse()
    assertThat(EraseRules.confirms(head(FacesSetting.On(g2, 1), off - 1), erased, off)).isFalse()
    assertThat(EraseRules.confirms(head(FacesSetting.Off(g), off), erased, null)).isFalse()
    assertThat(EraseRules.confirms(null, erased, off)).isFalse()
  }

  @Test
  fun laPropriaVoceEUscitaSoloSeELaVoceDiAdesso() {
    val erased = listOf(g)
    assertThat(EraseRules.announcedAt(head(FacesSetting.Off(g), off), erased, FacesSetting.Off(g))).isEqualTo(off)
    // La testa e' ancora quella di prima (on), o c'e' una voce piu' nuova in attesa: non ancora.
    assertThat(EraseRules.announcedAt(head(FacesSetting.On(g, 1), off), erased, FacesSetting.Off(g))).isNull()
    assertThat(EraseRules.announcedAt(head(FacesSetting.Off(g), off), erased, FacesSetting.On(g2, 1))).isNull()
    // L'account e' gia' andato avanti (riattivato su un altro dispositivo).
    assertThat(EraseRules.announcedAt(head(FacesSetting.On(g2, 1), off + 3), erased, FacesSetting.On(g2, 1))).isEqualTo(off + 3)
    assertThat(EraseRules.announcedAt(null, erased, FacesSetting.Off(g))).isNull()
  }

  @Test
  fun unaGenerazioneDettaAccesaDaUnaTestaRecenteNonSiTocca() {
    val heads = mapOf("a" to head(FacesSetting.On(g2, 1), off, headAt = off), "b" to head(FacesSetting.Off(g), off))
    assertThat(EraseRules.recentlyClaimed(heads, g2, nasNow = off + 10 * 60_000)).isTrue()
    assertThat(EraseRules.recentlyClaimed(heads, g2, nasNow = off + 2 * EraseRules.CLAIM_WINDOW_MILLIS)).isFalse()
    assertThat(EraseRules.recentlyClaimed(heads, g, nasNow = off)).isFalse()
    // Senza l'ora del NAS ogni testa conta come recente.
    assertThat(EraseRules.recentlyClaimed(heads, g2, nasNow = null)).isTrue()
  }

  @Test
  fun nomiPersoneConUnNomeEImpronta() {
    val p1 = PersonId.random()
    val p2 = PersonId.random()
    val p3 = PersonId.random()
    val records = listOf(
      PeopleRecord(PeopleEdit.Name(p1, "Uno"), 1, author),
      PeopleRecord(PeopleEdit.Name(p2, "Due"), 2, author),
      PeopleRecord(PeopleEdit.Alias(p2, p1), 3, author),
      PeopleRecord(PeopleEdit.Name(p3, "Tre"), 4, author),
      PeopleRecord(PeopleEdit.Forget(p3), 5, author),
    )
    assertThat(EraseRules.names(records)).containsExactly("Uno", "Due", "Tre")
    // Due unita in uno, tre dimenticata: una sola persona con un nome che l'utente vede.
    assertThat(EraseRules.namedPeople(records)).isEqualTo(1)
    val devices = listOf(DeviceAck("Telefono", "x".repeat(26), AckState.WAITING, 10L, thisDevice = true))
    val base = EraseRules.digest(g, 1, 2, true, false, devices)
    assertThat(EraseRules.digest(g, 1, 2, true, false, devices.map { it.copy(lastContactAtNas = 99L, state = AckState.CONFIRMED) })).isEqualTo(base)
    assertThat(EraseRules.digest(g, 2, 2, true, false, devices)).isNotEqualTo(base)
    assertThat(EraseRules.digest(g, 1, 3, true, false, devices)).isNotEqualTo(base)
    assertThat(EraseRules.digest(g2, 1, 2, true, false, devices)).isNotEqualTo(base)
    assertThat(EraseRules.digest(g, 1, 2, true, true, devices)).isNotEqualTo(base)
    assertThat(EraseRules.digest(g, 1, 2, true, false, devices + devices[0].copy(deviceId = "y".repeat(26)))).isNotEqualTo(base)
  }

  @Test
  fun gliEsitiDiCoreNasopsSiLeggonoDaiCodici() {
    assertThat(FacesEraseCodes.round("done", 5, 0)).isEqualTo(NasEraseRound.Done(5))
    assertThat(FacesEraseCodes.round("budget", 5, 7)).isEqualTo(NasEraseRound.More(5, 7))
    assertThat(FacesEraseCodes.round("not_allowed", 0, null)).isEqualTo(NasEraseRound.NotAllowed)
    assertThat(FacesEraseCodes.round("not_private_area", 0, null)).isEqualTo(NasEraseRound.NotAllowed)
    assertThat(FacesEraseCodes.round("unsure", 0, null)).isEqualTo(NasEraseRound.Unsure)
    val waits = mapOf(
      "unreachable" to EraseWait.NAS, "frozen" to EraseWait.FROZEN, "emergency_stop" to EraseWait.EMERGENCY_STOP, "clock_jump" to EraseWait.CLOCK_JUMP,
      "read_only" to EraseWait.READ_ONLY, "not_trusted" to EraseWait.NOT_TRUSTED, "test_shares_only" to EraseWait.NAS, "nuovo" to EraseWait.NAS,
    )
    for ((code, wait) in waits) assertThat(FacesEraseCodes.round(code, 0, null)).isEqualTo(NasEraseRound.Waiting(wait))
    assertThat(FacesEraseCodes.check(stopped = true, generationPresent = false, recycleBinCopy = false)).isNull()
  }
}
