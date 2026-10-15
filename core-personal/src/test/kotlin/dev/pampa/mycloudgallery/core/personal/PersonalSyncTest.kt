package dev.pampa.mycloudgallery.core.personal

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.index.contract.RemoteResult
import dev.pampa.mycloudgallery.core.index.contract.WriteBlock
import dev.pampa.mycloudgallery.core.index.publish.IndexIdentity
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.personal.codec.PersonalSegments
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKind
import dev.pampa.mycloudgallery.core.personal.contract.PersonalStop
import dev.pampa.mycloudgallery.core.personal.contract.PersonalSyncStatus
import dev.pampa.mycloudgallery.core.personal.contract.PersonalUnavailable
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import dev.pampa.mycloudgallery.core.personal.contract.PersonalVerdict
import dev.pampa.mycloudgallery.core.personal.remote.NasPersonalRemote
import dev.pampa.mycloudgallery.core.personal.remote.PersonalHeadFetch
import dev.pampa.mycloudgallery.core.personal.testing.InMemoryPersonalLedger
import dev.pampa.mycloudgallery.core.personal.sync.SyncLimits
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.crypto.Hashes
import dev.pampa.mycloudgallery.core.trust.crypto.Signer
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.data.ClassedSegment
import dev.pampa.mycloudgallery.core.trust.data.DeviceHead
import dev.pampa.mycloudgallery.core.trust.data.HeadKind
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.data.SegmentRef
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.NasRead
import dev.pampa.mycloudgallery.core.trust.registry.DeviceStatus
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * La sincronizzazione dei dati personali (D26, §2.9) con il codice VERO: store, lettore, pubblicazione e motore sopra la share
 * finta di core-nas, una famiglia con il registro firmato vero, orologi diversi per dispositivo. Dispositivi ostili: un dispositivo
 * approvato compromesso o un PC sulla share che scrivono quello che vogliono.
 */
class PersonalSyncTest {

  private val world = PersonalWorld()
  private val m1 = mediaId(1)
  private val m2 = mediaId(2)

  private suspend fun PersonalDevice.flag(media: String, field: String): Boolean? =
    (store.get(flagKey(media, field))?.value as? PersonalValue.Flag)?.on

  private suspend fun PersonalDevice.isFavorite(media: String) = flag(media, PersonalFields.FAVORITE)

  private suspend fun PersonalDevice.isHidden(media: String) = flag(media, PersonalFields.HIDDEN)

  private fun forger(device: PersonalDevice) = Forger(world, device.signer, device.account)

  // --- Convergenza ---------------------------------------------------------------------------------------------------------------

  @Test
  fun preferitoENascostoConvergonoFraTreDispositiviENonSuUnQuartoDiUnAltroAccount() = runTest {
    val phone = world.device()
    val tablet = world.device()
    val third = world.device()
    val stranger = world.device(account = "test2")

    phone.favorite(m1)
    world.clock.advance(1_000)
    tablet.hide(m1)
    world.clock.advance(1_000)
    stranger.favorite(m1)
    stranger.hide(m2)
    for (device in listOf(phone, tablet, third, stranger, phone, tablet, third, stranger)) device.syncOnce()

    for (device in listOf(phone, tablet, third)) {
      assertThat(device.isFavorite(m1)).isTrue()
      assertThat(device.isHidden(m1)).isTrue()
      assertThat(device.isHidden(m2)).isNull()
    }
    // Il quarto vede solo il suo (E-H4, C19): niente dell'altro account, e l'altro account niente del suo.
    assertThat(stranger.store.entries.value.keys).containsExactly(flagKey(m1, PersonalFields.FAVORITE), flagKey(m2, PersonalFields.HIDDEN))
    assertThat(stranger.isHidden(m1)).isNull()
    assertThat(phone.store.get(flagKey(m2, PersonalFields.HIDDEN))).isNull()
    // Nessun file di un account nella share dell'altro.
    val shareOfTest = world.nas.share("test")
    val shareOfTest2 = world.nas.share("test2")
    assertThat(shareOfTest.list(NasLayout.ROOT.child("devices"), 100).names).containsExactly(phone.id, tablet.id, third.id)
    assertThat(shareOfTest2.list(NasLayout.ROOT.child("devices"), 100).names).containsExactly(stranger.id)
    assertThat(stranger.sync.status.value).isInstanceOf(PersonalSyncStatus.UpToDate::class.java)
  }

  @Test
  fun lAltroAccountChePubblicaNellaShareDeiMieiNonEntraNeppureSeFirmaBene() = runTest {
    val phone = world.device()
    val stranger = world.device(account = "test2")
    stranger.favorite(m1)
    phone.syncOnce()
    // La password dell'account `test` e' nota a `test2`, o l'ACL e' sbagliata: scrive una testa firmata da un dispositivo approvato,
    // ma di un altro account. Chi legge guarda l'account del firmatario (NOT_OWNER), non la share.
    val forged = forger(stranger).publishState("test", listOf(forger(stranger).entry(flagKey(m2, PersonalFields.HIDDEN))))
    assertThat(forged).isInstanceOf(RemoteResult.Ok::class.java)
    val report = phone.syncOnce()
    assertThat(report.rejectedHeads).isEqualTo(1)
    assertThat(report.rejected[PersonalVerdict.AUTHOR_NOT_ALLOWED]).isEqualTo(1)
    assertThat(report.applied).isEqualTo(0)
    assertThat(phone.isHidden(m2)).isNull()
    assertThat(phone.store.entries.value).isEmpty()
  }

  @Test
  fun unDispositivoDiUnAltroAccountCheLeggeLaShareDeiMieiNonImportaNiente() = runTest {
    val phone = world.device()
    phone.hide(m1)
    phone.syncOnce()
    // L'ACL e' sbagliata (o `test2` ha la password di `test`) e il suo dispositivo e' configurato sulla mia area privata: legge,
    // ma le teste sono firmate da dispositivi di un altro account e non valgono niente.
    val stranger = world.device(account = "test2", share = "test")
    val report = stranger.syncOnce()
    assertThat(report.rejectedHeads).isEqualTo(1)
    assertThat(report.rejected[PersonalVerdict.AUTHOR_NOT_ALLOWED]).isEqualTo(1)
    assertThat(report.applied).isEqualTo(0)
    assertThat(stranger.store.entries.value).isEmpty()
  }

  @Test
  fun unaVoceConAutoreDiUnAltroAccountDentroLaTestaDiUnMioDispositivoNonVale() = runTest {
    val phone = world.device()
    val tablet = world.device()
    val stranger = world.device(account = "test2")
    // Il tablet e' compromesso (L29): firma una testa sua ma attribuisce una voce a un dispositivo dell'altro account.
    forger(tablet).publishState("test", listOf(forger(tablet).entry(flagKey(m1, PersonalFields.HIDDEN), author = stranger.id)))
    val report = phone.syncOnce()
    assertThat(report.rejected[PersonalVerdict.AUTHOR_NOT_ALLOWED]).isEqualTo(1)
    assertThat(phone.store.entries.value).isEmpty()
  }

  @Test
  fun unGiroSenzaCambiNonScriveUnaTestaNuovaEUnaModificaNeScriveUnaDopoCinqueMinuti() = runTest {
    val phone = world.device()
    phone.favorite(m1)
    val first = phone.syncOnce()
    assertThat(first.published).isEqualTo(1)
    assertThat(phone.headFiles()).hasSize(1)

    // Nessun cambiamento: nessuna testa nuova, anche a distanza di ore.
    world.clock.advance(3_600_000)
    assertThat(phone.syncOnce().published).isEqualTo(0)
    assertThat(phone.headFiles()).hasSize(1)

    // Un cambiamento dopo ore: la testa vecchia non limita, ne esce una nuova.
    phone.favorite(m2)
    assertThat(phone.syncOnce().published).isEqualTo(2)
    assertThat(phone.headFiles()).hasSize(2)
    val publishedAt = world.clock.now

    // M3: una testa ogni 5 minuti per tipo e share (ora del NAS). Prima si aspetta, e si dice fino a quando.
    phone.hide(m1)
    val tooSoon = phone.syncOnce()
    assertThat(tooSoon.published).isEqualTo(0)
    assertThat(tooSoon.publishRetryAtNas).isEqualTo(publishedAt + TrustLimits.HEAD_MIN_INTERVAL_MILLIS)
    assertThat(phone.headFiles()).hasSize(2)
    assertThat(phone.sync.status.value).isInstanceOf(PersonalSyncStatus.UpToDate::class.java)

    world.clock.advance(TrustLimits.HEAD_MIN_INTERVAL_MILLIS)
    assertThat(phone.syncOnce().published).isEqualTo(3)
    assertThat(phone.headFiles()).hasSize(3)
  }

  @Test
  fun unaModificaDiUnSegmentoSoloRiscriveQuelloSegmento() = runTest {
    val phone = world.device()
    val state = (0 until 3_000).map { PersonalEntry(flagKey(mediaId(it), PersonalFields.FAVORITE), world.clock.now - 10_000, phone.id, PersonalValue.Flag(true)) }
    phone.store.applyRemote(state)
    phone.syncOnce()
    val fs = world.fs("test")
    val segments = PersonalSegments.plan(phone.store.state().values).segments.size
    assertThat(segments).isGreaterThan(2)
    val before = fs.publishes
    world.clock.advance(TrustLimits.HEAD_MIN_INTERVAL_MILLIS + 1_000)
    phone.hide(mediaId(7))
    phone.syncOnce()
    // Un segmento nuovo e una testa nuova: gli altri segmenti non si riscrivono nemmeno ("esiste gia'" non serve).
    assertThat(fs.publishes - before).isEqualTo(2)
  }

  // --- Modifiche offline "da datare" -------------------------------------------------------------------------------------------

  @Test
  fun unaModificaSenzaOraDelNasRestaDaDatareEPrendeLOraAllaPrimaSincronizzazione() = runTest {
    val phone = world.device(skewMillis = 58_000)
    val tablet = world.device()
    phone.anchored = false
    phone.favorite(m1)
    assertThat(phone.store.undated()).containsExactly(flagKey(m1, PersonalFields.FAVORITE))
    assertThat(phone.store.get(flagKey(m1, PersonalFields.FAVORITE))).isNull()

    // Senza ora del NAS (nessuna ancora in questo processo) il giro non parte e la modifica resta da datare.
    val offline = phone.syncOnce()
    assertThat(offline.stoppedBy).isEqualTo(PersonalStop.UNAVAILABLE)
    assertThat(offline.stillUndated).isEqualTo(1)
    assertThat(phone.sync.status.value).isEqualTo(PersonalSyncStatus.Unavailable(PersonalUnavailable.NAS_UNREACHABLE))
    assertThat(phone.headFiles()).isEmpty()

    // Torna l'ora del NAS: la modifica prende QUELL'ora, non quella del telefono ne' quella del giorno in cui e' stata fatta.
    world.clock.advance(7_200_000)
    phone.anchored = true
    val report = phone.syncOnce()
    assertThat(report.stillUndated).isEqualTo(0)
    assertThat(report.published).isEqualTo(1)
    assertThat(phone.store.get(flagKey(m1, PersonalFields.FAVORITE))!!.atNas).isEqualTo(world.clock.now + 58_000)

    tablet.syncOnce()
    assertThat(tablet.isFavorite(m1)).isTrue()
  }

  @Test
  fun leModificheDaDatareSopravvivonoAllaMorteDelProcesso() = runTest {
    val ledger = InMemoryPersonalLedger()
    val first = world.device(ledger = ledger)
    first.anchored = false
    first.favorite(m1)
    first.hide(m2)
    // Il processo muore: nuovo store sullo stesso registro.
    val second = world.device(ledger = ledger, signer = first.signer)
    assertThat(second.store.undated()).containsExactly(flagKey(m1, PersonalFields.FAVORITE), flagKey(m2, PersonalFields.HIDDEN))
    val report = second.syncOnce()
    assertThat(report.stillUndated).isEqualTo(0)
    assertThat(report.published).isEqualTo(2)
  }

  @Test
  fun unaModificaFattaDopoAverVistoUnaVoceNonPerdeControDiLeiPerUnOrologioIndietro() = runTest {
    // Il tablet ha il NAS 58 s indietro; il telefono 58 s avanti: 116 s di differenza fra i due.
    val phone = world.device(skewMillis = 58_000)
    val tablet = world.device(skewMillis = -58_000)
    phone.favorite(m1, on = true)
    phone.syncOnce()
    tablet.syncOnce()
    assertThat(tablet.isFavorite(m1)).isTrue()
    val seen = tablet.store.get(flagKey(m1, PersonalFields.FAVORITE))!!

    // L'utente toglie il preferito sul tablet SUBITO dopo: la sua ora del NAS e' piu' bassa di quella della voce che ha appena visto.
    world.clock.advance(10_000)
    tablet.favorite(m1, on = false)
    val mine = tablet.store.get(flagKey(m1, PersonalFields.FAVORITE))!!
    assertThat(mine.atNas).isEqualTo(seen.atNas + 1)
    assertThat(mine.atNas).isGreaterThan(tablet.nasNow())

    world.clock.advance(TrustLimits.HEAD_MIN_INTERVAL_MILLIS)
    tablet.syncOnce()
    phone.syncOnce()
    assertThat(phone.isFavorite(m1)).isFalse()
    assertThat(tablet.isFavorite(m1)).isFalse()
  }

  @Test
  fun conGliOrologiSfasatiTuttiConvergonoAlloStessoValore() = runTest {
    val phone = world.device(skewMillis = 58_000)
    val tablet = world.device(skewMillis = -58_000)
    val third = world.device(skewMillis = 20_000)
    // Tre modifiche dello stesso flag senza che nessuno veda le altre.
    phone.favorite(m1, on = true)
    world.clock.advance(5_000)
    tablet.favorite(m1, on = false)
    world.clock.advance(5_000)
    third.favorite(m1, on = true)
    repeat(3) {
      for (device in listOf(phone, tablet, third)) device.syncOnce()
      world.clock.advance(TrustLimits.HEAD_MIN_INTERVAL_MILLIS)
    }
    val values = listOf(phone, tablet, third).map { it.store.get(flagKey(m1, PersonalFields.FAVORITE)) }
    assertThat(values.toSet()).hasSize(1)
    // Vince l'ora piu' alta: il telefono (+58 s).
    assertThat(values.first()!!.author).isEqualTo(phone.id)
  }

  // --- Autore revocato -----------------------------------------------------------------------------------------------------------

  @Test
  fun unaVoceDiUnAutoreRevocatoSiScartaAncheSeLaRipubblicaUnAltro() = runTest {
    val phone = world.device()
    val tablet = world.device()
    val lost = world.device()
    val fresh = world.device()

    lost.hide(m1)
    lost.syncOnce()
    tablet.syncOnce()
    phone.syncOnce()
    // Prima della revoca la voce vale ovunque, e il telefono la ripubblica nel suo stato unito.
    assertThat(phone.isHidden(m1)).isTrue()
    phone.favorite(m2)
    world.clock.advance(TrustLimits.HEAD_MIN_INTERVAL_MILLIS)
    phone.syncOnce()

    world.trust.changeStatus(lost.signer, DeviceStatus.REVOKED)
    world.clock.advance(TrustLimits.HEAD_MIN_INTERVAL_MILLIS)

    // Un dispositivo che arriva dopo la revoca: la testa del revocato e la voce ripubblicata dal telefono, entrambe scartate.
    val report = fresh.syncOnce()
    // La voce del revocato e' nello stato unito del tablet e in quello del telefono: due teste, due scarti. Il revocato non si legge
    // nemmeno piu' (fuori da `devices/` accettati).
    assertThat(report.rejected[PersonalVerdict.AUTHOR_REVOKED]).isEqualTo(2)
    assertThat(fresh.isHidden(m1)).isNull()
    // Il resto dello stato del telefono vale.
    assertThat(fresh.isFavorite(m2)).isTrue()
  }

  @Test
  fun chiHaGiaAccettatoLaVoceDiUnAutoreRevocatoLaTogliePoiNonLaRipubblica() = runTest {
    val phone = world.device()
    val lost = world.device()
    lost.hide(m1)
    lost.syncOnce()
    phone.favorite(m2)
    phone.syncOnce()
    assertThat(phone.isHidden(m1)).isTrue()
    val publishedBefore = PersonalSegments.plan(phone.store.state().values).names

    world.trust.changeStatus(lost.signer, DeviceStatus.REVOKED)
    world.clock.advance(TrustLimits.HEAD_MIN_INTERVAL_MILLIS)
    phone.syncOnce()
    assertThat(phone.isHidden(m1)).isNull()
    assertThat(phone.isFavorite(m2)).isTrue()
    // La testa nuova non contiene piu' la voce del revocato.
    val after = PersonalSegments.plan(phone.store.state().values).names
    assertThat(after).isNotEqualTo(publishedBefore)
    assertThat(phone.headFiles()).hasSize(2)
    val reader = world.device()
    reader.syncOnce()
    assertThat(reader.isHidden(m1)).isNull()
    assertThat(reader.isFavorite(m2)).isTrue()
  }

  @Test
  fun unaVoceRipubblicataDaUnTabletCompromessoConAutoreRevocatoNonVale() = runTest {
    val phone = world.device()
    val tablet = world.device()
    val lost = world.device()
    world.trust.changeStatus(lost.signer, DeviceStatus.REVOKED)
    // Il tablet e' approvato e firma bene, ma mette nel suo stato una voce del dispositivo revocato (anche retrodatata).
    val old = forger(tablet).entry(flagKey(m1, PersonalFields.HIDDEN), author = lost.id, at = world.clock.now - 86_400_000)
    forger(tablet).publishState("test", listOf(old, forger(tablet).entry(flagKey(m2, PersonalFields.FAVORITE))))
    val report = phone.syncOnce()
    assertThat(report.rejected[PersonalVerdict.AUTHOR_REVOKED]).isEqualTo(1)
    assertThat(phone.isHidden(m1)).isNull()
    assertThat(phone.isFavorite(m2)).isTrue()
  }

  @Test
  fun unDispositivoDismessoLasciaLeSueScelteNelloStatoUnitoDegliAltri() = runTest {
    val phone = world.device()
    val old = world.device()
    old.hide(m1)
    old.syncOnce()
    phone.syncOnce()
    world.trust.changeStatus(old.signer, DeviceStatus.RETIRED)
    world.clock.advance(TrustLimits.HEAD_MIN_INTERVAL_MILLIS)
    val fresh = world.device()
    phone.favorite(m2)
    phone.syncOnce()
    fresh.syncOnce()
    // Il telefono sostituito non e' sospetto: le sue scelte restano, nello stato unito del telefono (non nella sua testa congelata).
    assertThat(fresh.isHidden(m1)).isTrue()
    assertThat(fresh.isFavorite(m2)).isTrue()
  }

  // --- Area comune e share sbagliate ----------------------------------------------------------------------------------------------

  @Test
  fun unFileTrovatoNellAreaComuneNonVale() = runTest {
    val phone = world.device()
    val tablet = world.device()
    // Un dispositivo approvato (compromesso, o un errore) mette dati personali nell'area comune, letta da tutti.
    forger(tablet).publishState(TestTrust.COMMON, listOf(forger(tablet).entry(flagKey(m1, PersonalFields.HIDDEN))))
    val report = phone.syncOnce()
    assertThat(phone.store.entries.value).isEmpty()
    assertThat(report.applied).isEqualTo(0)
    // Il lettore dell'area comune rifiuta comunque, anche se qualcuno lo configurasse cosi' (difesa in profondita').
    val remote = NasPersonalRemote(TestTrust.COMMON, { world.nas.share(it) }, phone.identity, Dispatchers.Unconfined)
    val fetch = (remote.latestHead(tablet.id, null) as RemoteResult.Ok).value
    assertThat(fetch).isEqualTo(PersonalHeadFetch.Rejected(RejectReason.WRONG_LOCATION))
  }

  @Test
  fun unaTestaCopiataDallAreaPrivataNellAreaComuneNonVale() = runTest {
    val phone = world.device()
    phone.favorite(m1)
    phone.syncOnce()
    val privateShare = world.nas.share("test")
    val headName = phone.headFiles().single()
    val headPath = NasLayout.headDir(phone.id, "personal").child(headName)
    val bytes = (privateShare.read(headPath, 1 shl 20) as NasRead.Data).bytes
    world.nas.share(TestTrust.COMMON).put(headPath, bytes)
    val remote = NasPersonalRemote(TestTrust.COMMON, { world.nas.share(it) }, phone.identity, Dispatchers.Unconfined)
    assertThat((remote.latestHead(phone.id, null) as RemoteResult.Ok).value).isEqualTo(PersonalHeadFetch.Rejected(RejectReason.WRONG_LOCATION))
    // E la stessa testa letta da un'altra share privata (copiata li' da chi puo' scriverci): il posto non torna.
    world.nas.share("test2").put(headPath, bytes)
    val other = world.device(account = "test2")
    val otherRemote = NasPersonalRemote("test2", { world.nas.share(it) }, other.identity, Dispatchers.Unconfined)
    val result = (otherRemote.latestHead(phone.id, null) as RemoteResult.Ok).value
    assertThat(result).isInstanceOf(PersonalHeadFetch.Rejected::class.java)
  }

  @Test
  fun conLAreaPrivataUgualeAllaComuneNonSiLeggeNeSiScriveNiente() = runTest {
    val device = world.device(share = TestTrust.COMMON)
    device.favorite(m1)
    val before = world.fs(TestTrust.COMMON).requests
    val report = device.syncOnce()
    assertThat(report.stoppedBy).isEqualTo(PersonalStop.UNAVAILABLE)
    assertThat(device.sync.status.value).isEqualTo(PersonalSyncStatus.Unavailable(PersonalUnavailable.NO_PRIVATE_AREA))
    assertThat(world.fs(TestTrust.COMMON).requests).isEqualTo(before)
    assertThat(world.fs(TestTrust.COMMON).publishes).isEqualTo(0)
  }

  // --- Voci nel futuro ---------------------------------------------------------------------------------------------------------------

  @Test
  fun leVociNelFuturoSiRifiutanoESiAccettanoQuandoLOraArriva() = runTest {
    val phone = world.device()
    val ahead = world.device(skewMillis = 10 * 60_000)
    ahead.hide(m1)
    ahead.favorite(m2)
    ahead.syncOnce()

    val first = phone.syncOnce()
    assertThat(first.rejected[PersonalVerdict.FROM_FUTURE]).isEqualTo(2)
    assertThat(first.applied).isEqualTo(0)
    assertThat(phone.store.entries.value).isEmpty()

    // Finche' l'ora non arriva non si riscarica niente (la testa si ricorda "tenuta"): nessun rifiuto in piu'.
    world.clock.advance(5 * 60_000)
    val second = phone.syncOnce()
    assertThat(second.rejected).isEmpty()
    assertThat(second.applied).isEqualTo(0)

    // Passati i minuti, la stessa testa, mai ripubblicata, vale.
    world.clock.advance(4 * 60_000)
    val third = phone.syncOnce()
    assertThat(third.rejected).isEmpty()
    assertThat(third.applied).isEqualTo(2)
    assertThat(phone.isHidden(m1)).isTrue()
    assertThat(phone.isFavorite(m2)).isTrue()
  }

  @Test
  fun unaVoceNelFuturoNonImpedisceLeAltreDellaStessaTesta() = runTest {
    val phone = world.device()
    val tablet = world.device()
    val soon = forger(tablet).entry(flagKey(m1, PersonalFields.HIDDEN), at = world.clock.now + 3_600_000)
    val fine = forger(tablet).entry(flagKey(m2, PersonalFields.FAVORITE))
    forger(tablet).publishState("test", listOf(soon, fine))
    val report = phone.syncOnce()
    assertThat(report.rejected[PersonalVerdict.FROM_FUTURE]).isEqualTo(1)
    assertThat(report.applied).isEqualTo(1)
    assertThat(phone.isFavorite(m2)).isTrue()
    assertThat(phone.isHidden(m1)).isNull()
  }

  @Test
  fun unaVoceADecenniDiDistanzaRestaRifiutataSenzaRompereNiente() = runTest {
    // Un'ora a 100 anni da ora non diventa mai valida in un tempo ragionevole, ma non rompe niente.
    val phone = world.device()
    val tablet = world.device()
    forger(tablet).publishState("test", listOf(forger(tablet).entry(flagKey(m1, PersonalFields.HIDDEN), at = 4_000_000_000_000L)))
    repeat(3) { assertThat(phone.syncOnce().stoppedBy).isEqualTo(PersonalStop.COMPLETE) }
    assertThat(phone.isHidden(m1)).isNull()
  }

  // --- Tipi riservati e dati ostili -----------------------------------------------------------------------------------------------

  @Test
  fun leVociDeiNomiDelleFacceSiRifiutanoOggi() = runTest {
    val phone = world.device()
    val tablet = world.device()
    val person = PersonalEntry(PersonalKey(PersonalKind.PERSON, "face-1", "name"), world.clock.now - 1_000, tablet.id, PersonalValue.Text("Mario"))
    forger(tablet).publishState("test", listOf(person, forger(tablet).entry(flagKey(m1, PersonalFields.FAVORITE))))
    val report = phone.syncOnce()
    assertThat(report.rejected[PersonalVerdict.RESERVED_KIND]).isEqualTo(1)
    assertThat(report.applied).isEqualTo(1)
    assertThat(phone.store.get(person.key)).isNull()
  }

  @Test
  fun unSegmentoRovinatoOMancanteSiScartaSenzaFermareGliAltri() = runTest {
    val phone = world.device()
    val tablet = world.device()
    val hostile = forger(tablet)
    val good = PersonalSegments.encode(listOf(hostile.entry(flagKey(m1, PersonalFields.HIDDEN))))
    val garbageHash = Hashes.sha256Name("spazzatura".toByteArray())
    val garbage = SegmentRef(garbageHash, 17)
    val missing = SegmentRef(Hashes.sha256Name("assente".toByteArray()), 300)
    val tampered = PersonalSegments.encode(listOf(hostile.entry(flagKey(m2, PersonalFields.HIDDEN))))
    hostile.putSegment("test", good.ref.hash, good.bytes)
    hostile.putSegment("test", garbageHash, ByteArray(17) { 7 })
    // Un segmento con il nome giusto ma byte diversi.
    hostile.putSegment("test", tampered.ref.hash, tampered.bytes.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() })
    hostile.publishHead("test", 1, listOf(good.ref, garbage, missing, tampered.ref))

    val report = phone.syncOnce()
    assertThat(report.rejectedSegments).isEqualTo(3)
    assertThat(report.applied).isEqualTo(1)
    assertThat(phone.isHidden(m1)).isTrue()
    assertThat(phone.isHidden(m2)).isNull()
    assertThat(report.stoppedBy).isEqualTo(PersonalStop.COMPLETE)
    // E non si rilegge a ogni giro: la testa e' stata letta per intero (gli errori sono permanenti).
    val reads = world.fs("test").reads
    phone.syncOnce()
    assertThat(world.fs("test").reads - reads).isAtMost(3)
  }

  @Test
  fun unaTestaConCentoSegmentiInventatiCostaPocoENonFaNiente() = runTest {
    val phone = world.device()
    val tablet = world.device()
    val refs = (0 until 300).map { SegmentRef(Hashes.sha256Name("falso-$it".toByteArray()), 1_000L + it) }
    forger(tablet).publishHead("test", 1, refs)
    val report = phone.syncOnce()
    assertThat(report.rejectedSegments).isEqualTo(300)
    assertThat(phone.store.entries.value).isEmpty()
  }

  @Test
  fun iSegmentiDiSpazzaturaCostanoComeGliAltriEIlTettoLiFermaPrimaDiScaricarli() = runTest {
    val phone = world.device(limits = SyncLimits(maxBytesPerDevice = 100_000))
    val tablet = world.device()
    val hostile = forger(tablet)
    // Sei segmenti da 40.000 byte con un nome che non e' la loro impronta: sono tutti da scartare, ma scaricarli costa.
    val refs = (0 until 6).map { i ->
      val hash = Hashes.sha256Name("falso-$i".toByteArray())
      hostile.putSegment("test", hash, ByteArray(40_000) { (i + it).toByte() })
      SegmentRef(hash, 40_000)
    }
    hostile.publishHead("test", 1, refs)
    var segmentReads = 0
    world.fs("test").failWhen = { op, path ->
      if (op == "read" && path.value.contains("/seg/")) segmentReads++
      null
    }
    val report = phone.syncOnce()
    world.fs("test").failWhen = null
    assertThat(report.rejectedSegments).isEqualTo(6)
    // 80.000 byte letti; il terzo (120.000 in tutto) supera il tetto del dispositivo e non si scarica.
    assertThat(segmentReads).isEqualTo(2)
  }

  @Test
  fun leVociCheNonSiPossonoScrivereRestanoSulTelefonoESiContano() = runTest {
    val phone = world.device()
    val albums = dev.pampa.mycloudgallery.core.personal.settings.PersonalAlbums(phone.store)
    albums.define("bello", "Oslo")
    albums.define("strano", "x" + 0x202E.toChar() + "y")
    phone.favorite(m1)
    val report = phone.syncOnce()
    assertThat(report.notPublishable).isEqualTo(1)
    assertThat(report.published).isEqualTo(2)
    val tablet = world.device()
    tablet.syncOnce()
    assertThat(dev.pampa.mycloudgallery.core.personal.settings.PersonalAlbums(tablet.store).albums().map { it.id }).containsExactly("bello")
  }

  @Test
  fun unaTestaGiaVistaNonSiRiscaricaEUnaNuovaSiLegge() = runTest {
    val phone = world.device()
    val tablet = world.device()
    tablet.favorite(m1)
    tablet.syncOnce()
    phone.syncOnce()
    val reads = world.fs("test").reads
    val lists = world.fs("test").lists
    phone.syncOnce()
    // Verifica senza novita': per dispositivo una lettura che risponde "non trovato" (e l'elenco di `devices/`).
    assertThat(world.fs("test").lists - lists).isEqualTo(1)
    assertThat(world.fs("test").reads - reads).isAtMost(3)

    world.clock.advance(TrustLimits.HEAD_MIN_INTERVAL_MILLIS)
    tablet.hide(m2)
    tablet.syncOnce()
    phone.syncOnce()
    assertThat(phone.isHidden(m2)).isTrue()
  }

  // --- Cancello delle scritture, rete, budget ----------------------------------------------------------------------------------------

  @Test
  fun conUnBloccoSiLeggeEnonSiPubblica() = runTest {
    val tablet = world.device()
    tablet.favorite(m2)
    tablet.syncOnce()
    val expected = mapOf(
      WriteBlock.FROZEN to PersonalUnavailable.WRITES_BLOCKED,
      WriteBlock.EMERGENCY_STOP to PersonalUnavailable.WRITES_BLOCKED,
      WriteBlock.READ_ONLY_AREA to PersonalUnavailable.READ_ONLY,
      WriteBlock.NOT_TRUSTED to PersonalUnavailable.NOT_TRUSTED,
      WriteBlock.CLOCK_JUMP to PersonalUnavailable.CLOCK_JUMP,
    )
    for ((block, reason) in expected) {
      val phone = world.device()
      phone.block(block)
      phone.hide(m1)
      val report = phone.syncOnce()
      assertThat(report.published).isEqualTo(0)
      assertThat(phone.isFavorite(m2)).isTrue()
      assertThat(phone.headFiles()).isEmpty()
      assertThat(phone.sync.status.value).isEqualTo(PersonalSyncStatus.Unavailable(reason))
      // Tolto il blocco, la modifica di prima parte.
      phone.block(null)
      assertThat(phone.syncOnce().published).isGreaterThan(0)
      assertThat(phone.headFiles()).hasSize(1)
    }
  }

  @Test
  fun conUnSaltoDellOraNonSiDataNienteENonSiPubblica() = runTest {
    val phone = world.device()
    phone.anchored = false
    phone.favorite(m1)
    phone.anchored = true
    phone.block(WriteBlock.CLOCK_JUMP)
    val report = phone.syncOnce()
    // D32: senza ora fidata non si datano le modifiche ne' si pubblica.
    assertThat(report.stillUndated).isEqualTo(1)
    assertThat(report.published).isEqualTo(0)
    assertThat(phone.headFiles()).isEmpty()
    phone.block(null)
    val after = phone.syncOnce()
    assertThat(after.stillUndated).isEqualTo(0)
    assertThat(after.published).isEqualTo(1)
  }

  @Test
  fun suUnaShareVeraIDatiRestanoSulTelefonoENonParteNessunaRichiesta() = runTest {
    // D31: l'area privata `alessio` e' una share di famiglia, non di prova.
    val device = world.device(share = "alessio")
    device.favorite(m1)
    val report = device.syncOnce()
    assertThat(device.sync.status.value).isEqualTo(PersonalSyncStatus.LocalOnly)
    assertThat(report.stoppedBy).isEqualTo(PersonalStop.UNAVAILABLE)
    assertThat(world.fs("alessio").requests).isEqualTo(0)
    // La modifica ha l'ora del NAS ed e' nello stato locale: l'effetto sul telefono c'e', solo non parte verso il NAS.
    assertThat(device.isFavorite(m1)).isTrue()
  }

  @Test
  fun senzaReteNonSiPubblicaESiRiprendeDopo() = runTest {
    val phone = world.device()
    phone.favorite(m1)
    world.nas.share("test").failWith = NasError.Timeout
    val offline = phone.syncOnce()
    assertThat(offline.stoppedBy).isEqualTo(PersonalStop.UNAVAILABLE)
    assertThat(phone.sync.status.value).isEqualTo(PersonalSyncStatus.Unavailable(PersonalUnavailable.NAS_UNREACHABLE))
    world.nas.share("test").failWith = null
    val online = phone.syncOnce()
    assertThat(online.published).isEqualTo(1)
    assertThat(phone.sync.status.value).isInstanceOf(PersonalSyncStatus.UpToDate::class.java)
  }

  @Test
  fun conIlNasPienoLoStatoRestaSulTelefonoESiRiprova() = runTest {
    val phone = world.device()
    phone.favorite(m1)
    world.fs("test").failWhen = { op, _ -> if (op == "publish") NasError.NasFull else null }
    val full = phone.syncOnce()
    assertThat(full.published).isEqualTo(0)
    assertThat(phone.sync.status.value).isEqualTo(PersonalSyncStatus.Unavailable(PersonalUnavailable.NAS_FULL))
    assertThat(phone.isFavorite(m1)).isTrue()
    assertThat(phone.headFiles()).isEmpty()
    world.fs("test").failWhen = null
    assertThat(phone.syncOnce().published).isEqualTo(1)
  }

  @Test
  fun unaTestaNonScrittaLasciaIlSegmentoOrfanoEUnaRipetizioneNonLoDuplica() = runTest {
    val phone = world.device()
    phone.favorite(m1)
    // La testa fallisce (il NAS cade dopo il segmento): il segmento resta, la testa no.
    world.fs("test").failWhen = { op, path -> if (op == "publish" && path.value.contains("/head/")) NasError.Timeout else null }
    assertThat(phone.syncOnce().published).isEqualTo(0)
    assertThat(phone.headFiles()).isEmpty()
    val segmentsAfterFailure = world.nas.share("test").fileCount()
    world.fs("test").failWhen = null
    assertThat(phone.syncOnce().published).isEqualTo(1)
    // Stesso nome, stessi byte: "esiste gia'", nessun segmento nuovo; in piu' solo la testa.
    assertThat(world.nas.share("test").fileCount()).isEqualTo(segmentsAfterFailure + 1)
  }

  @Test
  fun unBudgetFinitoFermaIlGiroSenzaPubblicareEIlSuccessivoLoCompleta() = runTest {
    val phone = world.device()
    val tablet = world.device()
    tablet.hide(m1)
    tablet.syncOnce()
    phone.favorite(m2)
    phone.monotonicStep = 60_000 // ogni lettura dell'orologio monotono "costa" un minuto
    val short = phone.syncOnce(1.minutes)
    assertThat(short.stoppedBy).isEqualTo(PersonalStop.BUDGET)
    assertThat(short.published).isEqualTo(0)
    phone.monotonicStep = 0
    val full = phone.syncOnce(Duration.INFINITE)
    assertThat(full.stoppedBy).isEqualTo(PersonalStop.COMPLETE)
    assertThat(full.published).isEqualTo(2)
    assertThat(phone.isHidden(m1)).isTrue()
  }

  // --- Reinstallazione e perdita del registro locale (E-O7) ------------------------------------------------------------------------

  @Test
  fun unaReinstallazioneConUnaChiaveNuovaRitrovaITuoiDatiDalNas() = runTest {
    val phone = world.device()
    val tablet = world.device()
    phone.favorite(m1)
    phone.hide(m2)
    phone.syncOnce()
    tablet.syncOnce()
    // Il tablet viene reinstallato: chiave nuova, nuova approvazione (E-O7), registro locale vuoto.
    val reinstalled = world.device()
    val report = reinstalled.syncOnce()
    assertThat(report.applied).isEqualTo(2)
    assertThat(reinstalled.isFavorite(m1)).isTrue()
    assertThat(reinstalled.isHidden(m2)).isTrue()
  }

  @Test
  fun conLaChiaveMaSenzaIlRegistroLocaleLoStatoTornaDallaPropriaTesta() = runTest {
    val phone = world.device()
    phone.favorite(m1)
    phone.syncOnce()
    // Il registro locale si perde (file rovinato): stessa chiave, stato vuoto.
    val rebuilt = world.device(signer = phone.signer, ledger = InMemoryPersonalLedger())
    assertThat(rebuilt.store.entries.value).isEmpty()
    val report = rebuilt.syncOnce()
    assertThat(report.applied).isEqualTo(1)
    assertThat(rebuilt.isFavorite(m1)).isTrue()
    // La prossima testa non collide con la propria e rispetta i 5 minuti dall'ultima.
    rebuilt.hide(m2)
    assertThat(rebuilt.syncOnce().publishRetryAtNas).isNotNull()
    world.clock.advance(TrustLimits.HEAD_MIN_INTERVAL_MILLIS)
    assertThat(rebuilt.syncOnce().published).isEqualTo(2)
    assertThat(rebuilt.headFiles()).hasSize(2)
  }
}
