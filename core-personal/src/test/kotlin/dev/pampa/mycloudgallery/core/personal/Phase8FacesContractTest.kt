package dev.pampa.mycloudgallery.core.personal

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.FacesSetting
import dev.pampa.mycloudgallery.core.personal.codec.PersonalSegments
import dev.pampa.mycloudgallery.core.personal.contract.FacesSettingCodec
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKind
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import dev.pampa.mycloudgallery.core.personal.contract.PrePublish
import dev.pampa.mycloudgallery.core.personal.legacy.LegacyPersonalSegments
import dev.pampa.mycloudgallery.core.personal.noop.NoPersonalHeadsView
import dev.pampa.mycloudgallery.core.personal.noop.NoPrePublishHook
import dev.pampa.mycloudgallery.core.trust.acceptedOrNull
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.data.SegmentRef
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.NasRead
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Fase 8 (P0; `docs/fase8-progetto.md` D3, D13, D30, E-F11, E-F12; formato §5.5-bis A4): la voce `faces` di `SETTING`, il gancio prima
 * della pubblicazione e la regola "`PERSON` non si pubblica mai". La voce si scrive e si rilegge identica con il codec di oggi e con
 * quello della Fase 6 (un'app senza la Fase 8 la conserva senza capirla); una forma sconosciuta non si interpreta.
 */
class Phase8FacesContractTest {

  private val device = TrustIds.random()
  private val t = 1_760_000_000_000L
  private val g = FaceGeneration.fromBytes(ByteArray(16) { (it + 3).toByte() })

  @Test
  fun laVoceFacesHaDueFormeEUnaChiaveSola() {
    assertThat(FacesSettingCodec.KEY).isEqualTo(PersonalKey(PersonalKind.SETTING, PersonalFields.SETTINGS_SUBJECT, "faces"))
    assertThat(FacesSettingCodec.on(g, 1)).isEqualTo(PersonalValue.Text("1|on|${g.base32}|1"))
    assertThat(FacesSettingCodec.off(g)).isEqualTo(PersonalValue.Text("1|off|${g.base32}"))
    assertThat(FacesSettingCodec.parse(FacesSettingCodec.on(g, 3))).isEqualTo(FacesSetting.On(g, 3))
    assertThat(FacesSettingCodec.parse(FacesSettingCodec.off(g))).isEqualTo(FacesSetting.Off(g))
    assertThat(FacesSettingCodec.valueOf(FacesSetting.On(g, 2))).isEqualTo(FacesSettingCodec.on(g, 2))
    assertThrows(IllegalArgumentException::class.java) { FacesSettingCodec.valueOf(FacesSetting.Unknown) }
    // Voce assente o lapide = spenta.
    assertThat(FacesSettingCodec.parse(null)).isNull()
    assertThat(FacesSettingCodec.parse(PersonalValue.Removed)).isNull()
  }

  /** E-F12: ogni forma che questa versione non capisce e' Unknown (niente calcolo, niente pubblicazione, nessuna cancellazione). */
  @Test
  fun unaFormaSconosciutaNonSiInterpreta() {
    val unknown = listOf(
      PersonalValue.Text("2|on|${g.base32}|1"),
      PersonalValue.Text("1|on|${g.base32}"),
      PersonalValue.Text("1|on|${g.base32}|0"),
      PersonalValue.Text("1|on|${g.base32}|01"),
      PersonalValue.Text("1|on|${g.base32}|1|altro"),
      PersonalValue.Text("1|on|${g.base32.uppercase()}|1"),
      PersonalValue.Text("1|off|${g.base32}|1"),
      PersonalValue.Text("1|pausa|${g.base32}"),
      PersonalValue.Text(""),
      PersonalValue.Flag(true),
      PersonalValue.Number(1),
    )
    for (value in unknown) assertThat(FacesSettingCodec.parse(value)).isEqualTo(FacesSetting.Unknown)
  }

  /** E-F11: la voce sta nel tipo `SETTING` che ogni app conosce: il codec di oggi e quello della Fase 6 la rileggono identica. */
  @Test
  fun laVoceViaggiaAncheFraLeAppSenzaLaFase8() {
    val on = PersonalEntry(FacesSettingCodec.KEY, t, device, FacesSettingCodec.on(g, 1))
    val off = PersonalEntry(FacesSettingCodec.KEY, t + 1, device, FacesSettingCodec.off(g))
    for (entry in listOf(on, off)) {
      val plan = PersonalSegments.plan(listOf(entry))
      assertThat(plan.skipped).isEqualTo(0)
      val today = plan.segments.flatMap { PersonalSegments.decode(it.bytes, it.ref).acceptedOrNull().orEmpty() }
      val phase6 = plan.segments.flatMap { LegacyPersonalSegments.decode(it.bytes, it.ref).acceptedOrNull().orEmpty() }
      assertThat(today).containsExactly(entry)
      assertThat(phase6).containsExactly(entry)
    }
  }

  /**
   * D13: `PERSON` non compare mai in una testa `personal` scritta da questa app. Due strade, con il codice vero (store, motore, pubblicazione
   * sulla share finta): la voce messa da questo dispositivo (lo store la rifiuta) e quella arrivata da un dispositivo dell'account che la
   * scrive a mano (chi legge la scarta, `RESERVED_KIND`). Ogni segmento pubblicato dal telefono si rilegge: nessuna voce riservata.
   */
  @Test
  fun unaVoceRiservataNonSiPubblicaMai() = runTest {
    val world = PersonalWorld()
    val phone = world.device()
    val tablet = world.device()
    val person = PersonalKey(PersonalKind.PERSON, "persona-1", "name")
    assertThat(runCatching { phone.store.set(person, PersonalValue.Text("Mario")) }.isFailure).isTrue()
    val forged = PersonalEntry(person, world.clock.now - 1_000, tablet.id, PersonalValue.Text("Mario"))
    Forger(world, tablet.signer, tablet.account).publishState(tablet.share, listOf(forged, entry(flagKey(mediaId(2), PersonalFields.HIDDEN), world.clock.now - 1_000, tablet.id)))
    phone.favorite(mediaId(1))
    phone.syncOnce()
    val share = world.nas.share(phone.share)
    val dir = NasLayout.headDir(phone.id, "personal").parent()!!.child("seg")
    val published = share.list(dir, 10_000).names.flatMap { name ->
      val bytes = (share.read(dir.child(name), 16 * 1024 * 1024) as NasRead.Data).bytes
      PersonalSegments.decode(bytes, SegmentRef(name, bytes.size.toLong())).acceptedOrNull().orEmpty()
    }
    assertThat(phone.headFiles()).isNotEmpty()
    assertThat(published.map { it.key.kind }).containsAtLeast(PersonalKind.MEDIA_FLAG, PersonalKind.MEDIA_FLAG)
    assertThat(published.map { it.key.kind }).doesNotContain(PersonalKind.PERSON)
    assertThat(phone.store.state().keys).doesNotContain(person)
  }

  @Test
  fun ilGancioEILeTesteDiSerieNonCambianoNiente() = runTest {
    assertThat(NoPrePublishHook.beforePublish(emptyMap())).isEqualTo(PrePublish.Proceed)
    assertThat(NoPersonalHeadsView.latestValueOf(FacesSettingCodec.KEY)).isEmpty()
    assertThat(PrePublish.Hold("FACES_ERASE_PENDING").code).isEqualTo("FACES_ERASE_PENDING")
    assertThrows(IllegalArgumentException::class.java) { PrePublish.Hold("errore libero con spazi") }
  }
}
