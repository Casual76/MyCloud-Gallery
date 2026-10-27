package dev.pampa.mycloudgallery.core.personal

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.FacesSetting
import dev.pampa.mycloudgallery.core.personal.contract.FacesPersonalSettings
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * La suite di contratto della voce `faces` (Fase 8, P7, §5.2): gira sull'implementazione vera (`SyncedFacesSettings` sopra lo store e il
 * motore di sincronizzazione veri) e sul fake (`InMemoryFacesPersonalSettings`). Due dispositivi dello stesso account, A e B: ognuno puo' non
 * avere l'ora del NAS (fuori casa), e una "sincronizzazione" porta le scelte da uno all'altro.
 */
abstract class FacesPersonalSettingsContract {

  /** Il mondo di prova: i due dispositivi e il modo di farli sincronizzare o di scrivere "da un altro dispositivo". */
  interface Rig {
    val a: FacesPersonalSettings
    val b: FacesPersonalSettings

    /** Il dispositivo A non ha (o torna ad avere) l'ora del NAS. */
    fun aOnline(online: Boolean)

    fun bOnline(online: Boolean)

    /** Un giro completo: A porta la sua scelta nel registro, B la legge e porta la sua, A rilegge. Entrambi con l'ora del NAS. */
    suspend fun syncAll()

    /** Una voce arrivata da un altro dispositivo dell'account, con il testo [text] (null = lapide). */
    suspend fun remoteWrites(text: String?)
  }

  protected abstract fun rig(): Rig

  protected val g1: FaceGeneration = FaceGeneration.fromBytes(ByteArray(16) { (it + 1).toByte() })
  protected val g2: FaceGeneration = FaceGeneration.fromBytes(ByteArray(16) { (it + 50).toByte() })
  protected val g3: FaceGeneration = FaceGeneration.fromBytes(ByteArray(16) { (it + 100).toByte() })

  @Test
  fun senzaVoceEspenta() {
    val rig = rig()
    assertThat(rig.a.current()).isNull()
    assertThat(rig.a.setting.value).isNull()
    assertThat(rig.a.syncPending.value).isFalse()
  }

  @Test
  fun attivareValeSubitoELaVoceTornaIdentica() = runTest {
    val rig = rig()
    assertThat(rig.a.set(FacesSetting.On(g1, 1))).isTrue()
    // Sincrono: niente attesa del flusso.
    assertThat(rig.a.current()).isEqualTo(FacesSetting.On(g1, 1))
    assertThat(rig.a.setting.value).isEqualTo(FacesSetting.On(g1, 1))
    assertThat(rig.a.syncPending.value).isFalse()
    rig.syncAll()
    assertThat(rig.b.current()).isEqualTo(FacesSetting.On(g1, 1))
  }

  @Test
  fun spegnereScriveLaGenerazioneSpenta() = runTest {
    val rig = rig()
    rig.a.set(FacesSetting.On(g1, 2))
    rig.a.set(FacesSetting.Off(g1))
    assertThat(rig.a.current()).isEqualTo(FacesSetting.Off(g1))
    rig.syncAll()
    assertThat(rig.b.current()).isEqualTo(FacesSetting.Off(g1))
  }

  @Test
  fun scrivereLaStessaVoceDueVolteNonCambiaNiente() = runTest {
    val rig = rig()
    assertThat(rig.a.set(FacesSetting.On(g1, 1))).isTrue()
    assertThat(rig.a.set(FacesSetting.On(g1, 1))).isTrue()
    assertThat(rig.a.current()).isEqualTo(FacesSetting.On(g1, 1))
  }

  /** R6 della revisione B, applicato ai dati biometrici: fuori casa la scelta vale qui subito, e viaggia alla prima sincronizzazione. */
  @Test
  fun senzaLOraDelNasLaScelteValeSubito_RestaDaDatare_EViaggiaDopo() = runTest {
    val rig = rig()
    rig.aOnline(false)
    assertThat(rig.a.set(FacesSetting.On(g1, 1))).isTrue()
    assertThat(rig.a.current()).isEqualTo(FacesSetting.On(g1, 1))
    assertThat(rig.a.setting.value).isEqualTo(FacesSetting.On(g1, 1))
    assertThat(rig.a.syncPending.value).isTrue()
    assertThat(rig.b.current()).isNull()

    rig.aOnline(true)
    rig.syncAll()
    assertThat(rig.a.syncPending.value).isFalse()
    assertThat(rig.a.current()).isEqualTo(FacesSetting.On(g1, 1))
    assertThat(rig.b.current()).isEqualTo(FacesSetting.On(g1, 1))
  }

  /** Spegnere senza l'ora del NAS spegne subito, anche sopra una voce "accesa" arrivata dagli altri dispositivi. */
  @Test
  fun spegnereSenzaOraPrevaleSullaVoceAccesaAltrove() = runTest {
    val rig = rig()
    rig.b.set(FacesSetting.On(g1, 1))
    rig.syncAll()
    rig.aOnline(false)
    assertThat(rig.a.current()).isEqualTo(FacesSetting.On(g1, 1))
    rig.a.set(FacesSetting.Off(g1))
    assertThat(rig.a.current()).isEqualTo(FacesSetting.Off(g1))
    assertThat(rig.a.syncPending.value).isTrue()
  }

  /** E-F9: due attivazioni quasi insieme (due generazioni): una sola vince, la stessa su tutti e due i dispositivi. */
  @Test
  fun dueAttivazioniQuasiInsiemeHannoUnSoloVincitoreUgualeOvunque() = runTest {
    val rig = rig()
    rig.aOnline(false)
    rig.bOnline(false)
    rig.a.set(FacesSetting.On(g1, 1))
    rig.b.set(FacesSetting.On(g2, 1))
    assertThat(rig.a.current()).isEqualTo(FacesSetting.On(g1, 1))
    assertThat(rig.b.current()).isEqualTo(FacesSetting.On(g2, 1))
    rig.aOnline(true)
    rig.bOnline(true)
    rig.syncAll()
    rig.syncAll()
    val winner = rig.a.current()
    assertThat(winner).isInstanceOf(FacesSetting.On::class.java)
    assertThat(rig.b.current()).isEqualTo(winner)
    assertThat(setOf(g1, g2)).contains((winner as FacesSetting.On).generation)
    assertThat(rig.a.syncPending.value).isFalse()
    assertThat(rig.b.syncPending.value).isFalse()
  }

  /** E-F12: una forma che questa versione non capisce e' `Unknown`, e non si riscrive. */
  @Test
  fun unaVoceSconosciutaNonSiRiscrive() = runTest {
    val rig = rig()
    rig.remoteWrites("2|on|${g1.base32}|7|nuovo")
    assertThat(rig.a.current()).isEqualTo(FacesSetting.Unknown)
    assertThat(rig.a.set(FacesSetting.On(g2, 1))).isFalse()
    assertThat(rig.a.set(FacesSetting.Off(g2))).isFalse()
    assertThat(rig.a.current()).isEqualTo(FacesSetting.Unknown)
    assertThat(rig.a.syncPending.value).isFalse()
    assertThat(runCatching { rig.a.set(FacesSetting.Unknown) }.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
  }

  @Test
  fun unaVoceSconosciutaCheLaPiuNuovaSostituisceTornaLeggibile() = runTest {
    val rig = rig()
    rig.remoteWrites("2|on|${g1.base32}|7|nuovo")
    assertThat(rig.a.current()).isEqualTo(FacesSetting.Unknown)
    rig.remoteWrites("1|on|${g3.base32}|1")
    assertThat(rig.a.current()).isEqualTo(FacesSetting.On(g3, 1))
  }

  @Test
  fun laLapideEQuellaAssenteSonoSpente() = runTest {
    val rig = rig()
    rig.a.set(FacesSetting.On(g1, 1))
    rig.syncAll()
    rig.remoteWrites(null)
    assertThat(rig.a.current()).isNull()
    assertThat(rig.a.setting.value).isNull()
  }

  @Test
  fun unaFormaMalformataNonEMaiUnaVoceValida() = runTest {
    val rig = rig()
    for (text in listOf("", "1|on|abc|1", "1|on|${g1.base32}|0", "1|off|${g1.base32}|1", "1|on|${g1.base32.uppercase()}|1")) {
      rig.remoteWrites(text)
      assertThat(rig.a.current()).isEqualTo(FacesSetting.Unknown)
    }
  }
}
