package dev.pampa.mycloudgallery.core.personal

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.model.StableCode
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFingerprint
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKind
import dev.pampa.mycloudgallery.core.personal.contract.PersonalMerge
import dev.pampa.mycloudgallery.core.personal.contract.PersonalRules
import dev.pampa.mycloudgallery.core.personal.contract.PersonalStop
import dev.pampa.mycloudgallery.core.personal.contract.PersonalSyncStatus
import dev.pampa.mycloudgallery.core.personal.contract.PersonalUnavailable
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValidity
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import dev.pampa.mycloudgallery.core.personal.contract.PersonalVerdict
import dev.pampa.mycloudgallery.core.personal.noop.NoPersonalSettings
import dev.pampa.mycloudgallery.core.personal.noop.NoPersonalStore
import dev.pampa.mycloudgallery.core.personal.noop.NoPersonalSync
import dev.pampa.mycloudgallery.core.personal.testing.FakePersonalCloud
import dev.pampa.mycloudgallery.core.personal.testing.FakePersonalSync
import dev.pampa.mycloudgallery.core.personal.testing.InMemoryPersonalSettings
import dev.pampa.mycloudgallery.core.personal.testing.InMemoryPersonalStore
import dev.pampa.mycloudgallery.core.personal.testing.favorite
import dev.pampa.mycloudgallery.core.personal.testing.hidden
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * I dati personali (D26, L29): la fusione (ultima modifica per ora del NAS, a pari ora l'autore minore), la validita' (stesso account,
 * area privata dichiarata, autore non revocato, mai nel futuro) e la convergenza fra dispositivi con i fake di P0-B.
 */
class PersonalContractTest {

  private fun device(n: Int) = Base32.encode(ByteArray(16) { n.toByte() })

  private val d1 = device(1)
  private val d2 = device(2)
  private val d3 = device(3)
  private val stranger = device(9)
  private val media = Base32.encode(ByteArray(16) { 0x55 })
  private var nasNow = 1_800_000_000_000L

  private fun entry(key: PersonalKey, at: Long, author: String, on: Boolean) = PersonalEntry(key, at, author, PersonalValue.Flag(on))

  // --- Fusione -------------------------------------------------------------------------------------------------------------------------

  @Test
  fun perOgniChiaveVinceLUltimaModifica() {
    val key = favorite(media)
    val merged = PersonalMerge.merge(mapOf(key to entry(key, 10, d1, true)), listOf(entry(key, 20, d2, false), entry(key, 5, d3, true)))
    assertThat(merged.getValue(key)).isEqualTo(entry(key, 20, d2, false))
  }

  @Test
  fun aParitaDiOraVinceLAutoreMinore() {
    val key = hidden(media)
    val a = entry(key, 10, d1, true)
    val b = entry(key, 10, d2, false)
    assertThat(PersonalMerge.winner(a, b)).isEqualTo(a)
    assertThat(PersonalMerge.winner(b, a)).isEqualTo(a)
  }

  @Test
  fun laFusioneEIdempotenteCommutativaEAssociativa() {
    val key = favorite(media)
    val entries = listOf(entry(key, 10, d1, true), entry(key, 10, d2, false), entry(key, 30, d3, false), entry(key, 30, d1, true))
    val forward = PersonalMerge.merge(emptyMap(), entries)
    val backward = PersonalMerge.merge(emptyMap(), entries.reversed())
    val twice = PersonalMerge.merge(forward, entries)
    assertThat(backward).isEqualTo(forward)
    assertThat(twice).isEqualTo(forward)
    assertThat(forward.getValue(key).author).isEqualTo(d1)
    // A pari ora e pari autore decide il valore, sempre allo stesso modo.
    val x = entry(key, 5, d1, true)
    val y = entry(key, 5, d1, false)
    assertThat(PersonalMerge.winner(x, y)).isEqualTo(PersonalMerge.winner(y, x))
  }

  @Test
  fun laLapideVinceSeELUltima() {
    val key = PersonalKey(PersonalKind.ALBUM, media, "item:abc")
    val merged = PersonalMerge.merge(
      mapOf(key to PersonalEntry(key, 10, d1, PersonalValue.Flag(true))),
      listOf(PersonalEntry(key, 11, d2, PersonalValue.Removed)),
    )
    assertThat(merged.getValue(key).value).isEqualTo(PersonalValue.Removed)
  }

  @Test
  fun laChiaveDentroIlSegmentoEUnaImprontaStabileSenzaRivelareIlSoggetto() {
    val key = favorite(media)
    assertThat(key.digest()).hasLength(32)
    assertThat(key.digest()).isEqualTo(favorite(media).digest())
    assertThat(key.digest()).isNotEqualTo(hidden(media).digest())
    assertThat(key.digest()).isNotEqualTo(PersonalKey(PersonalKind.ALBUM, media, "favorite").digest())
    assertThat(key.toString()).doesNotContain(media)
    assertThrows(IllegalArgumentException::class.java) { PersonalKey(PersonalKind.SETTING, "", "x") }
    assertThrows(IllegalArgumentException::class.java) { PersonalKey(PersonalKind.SETTING, "a\u0000b", "x") }
  }

  // --- Validita' -----------------------------------------------------------------------------------------------------------------------

  private fun rules(revoked: Set<String> = emptySet()) = PersonalRules(nasNow, approvedAuthors = setOf(d1, d2, d3), revokedAuthors = revoked)

  @Test
  fun unaVoceValeSoloSeSoddisfaTuttiIControlli() {
    val ok = entry(favorite(media), nasNow - 1_000, d1, true)
    assertThat(PersonalValidity.check(ok, rules(), inDeclaredPrivateArea = true)).isEqualTo(PersonalVerdict.VALID)
    assertThat(PersonalValidity.check(ok, rules(), inDeclaredPrivateArea = false)).isEqualTo(PersonalVerdict.NOT_IN_PRIVATE_AREA)
    assertThat(PersonalValidity.check(entry(favorite(media), 1, stranger, true), rules(), true)).isEqualTo(PersonalVerdict.AUTHOR_NOT_ALLOWED)
    assertThat(PersonalValidity.check(ok, rules(revoked = setOf(d1)), true)).isEqualTo(PersonalVerdict.AUTHOR_REVOKED)
    val future = entry(favorite(media), nasNow + TrustLimits.MAX_FUTURE_SKEW_MILLIS + 1, d1, true)
    assertThat(PersonalValidity.check(future, rules(), true)).isEqualTo(PersonalVerdict.FROM_FUTURE)
    val nearFuture = entry(favorite(media), nasNow + TrustLimits.MAX_FUTURE_SKEW_MILLIS, d1, true)
    assertThat(PersonalValidity.check(nearFuture, rules(), true)).isEqualTo(PersonalVerdict.VALID)
    val reserved = PersonalEntry(PersonalKey(PersonalKind.PERSON, media, "name"), 1, d1, PersonalValue.Text("x"))
    assertThat(PersonalValidity.check(reserved, rules(), true)).isEqualTo(PersonalVerdict.RESERVED_KIND)
  }

  // --- Convergenza con i fake ----------------------------------------------------------------------------------------------------

  private class Device(account: String, val id: String, val cloud: FakePersonalCloud) {
    val store = InMemoryPersonalStore(id) { cloud.nasNow() }
    val sync = FakePersonalSync(account, id, store, cloud)
  }

  @Test
  fun preferitoENascostoConvergonoFraTreDispositiviDelloStessoAccountENonSuUnAltro() = runTest {
    val cloud = FakePersonalCloud { nasNow }
    listOf(d1, d2, d3).forEach { cloud.approve("test", it) }
    cloud.approve("test2", stranger)
    val phone = Device("test", d1, cloud)
    val tablet = Device("test", d2, cloud)
    val third = Device("test", d3, cloud)
    val other = Device("test2", stranger, cloud)

    phone.store.set(favorite(media), PersonalValue.Flag(true))
    nasNow += 1_000
    tablet.store.set(hidden(media), PersonalValue.Flag(true))
    nasNow += 1_000
    for (device in listOf(phone, tablet, third, other, phone, tablet, third)) device.sync.sync(1.minutes)

    for (device in listOf(phone, tablet, third)) {
      assertThat(device.store.get(favorite(media))?.value).isEqualTo(PersonalValue.Flag(true))
      assertThat(device.store.get(hidden(media))?.value).isEqualTo(PersonalValue.Flag(true))
    }
    // Il quarto, di un altro account, non vede niente (E-H4, C19).
    assertThat(other.store.entries.value).isEmpty()
  }

  @Test
  fun unaModificaFattaSenzaOraDelNasRestaDaDatareEPrendeLOraAllaPrimaSincronizzazione() = runTest {
    val nasKnown: Long? = null
    val cloud = FakePersonalCloud { nasNow }
    cloud.approve("test", d1)
    val store = InMemoryPersonalStore(d1) { nasKnown }
    val sync = FakePersonalSync("test", d1, store, cloud)

    store.set(favorite(media), PersonalValue.Flag(true))
    assertThat(store.undated()).containsExactly(favorite(media))
    assertThat(store.get(favorite(media))).isNull()

    val report = sync.sync(1.minutes)
    assertThat(report.stillUndated).isEqualTo(0)
    assertThat(report.published).isEqualTo(1)
    assertThat(store.get(favorite(media))!!.atNas).isEqualTo(nasNow)
    assertThat(sync.status.value).isEqualTo(PersonalSyncStatus.UpToDate(nasNow))
    assertThat(nasKnown).isNull()
  }

  @Test
  fun leVociRifiutateSiContanoPerMotivoEUnaVoceNellAreaComuneNonVale() = runTest {
    val cloud = FakePersonalCloud { nasNow }
    listOf(d1, d2).forEach { cloud.approve("test", it) }
    val phone = Device("test", d1, cloud)
    // Un dispositivo revocato aveva pubblicato un nascosto; un altro lo ha ripubblicato nella sua area: la voce non vale.
    cloud.revoked += d3
    cloud.privateAreas.getOrPut("test") { HashMap() }[d2] = listOf(
      entry(hidden(media), nasNow - 10, d3, true),
      entry(favorite(media), nasNow + 10 * 60_000, d2, true),
      entry(favorite("altro-media"), nasNow - 5, d2, true),
    )
    // E una voce nell'area comune, da chiunque: non entra.
    cloud.commonArea += entry(hidden(media), nasNow - 1, d1, true)

    val report = phone.sync.sync(1.minutes)
    assertThat(report.rejected[PersonalVerdict.AUTHOR_REVOKED]).isEqualTo(1)
    assertThat(report.rejected[PersonalVerdict.FROM_FUTURE]).isEqualTo(1)
    assertThat(report.rejected[PersonalVerdict.NOT_IN_PRIVATE_AREA]).isEqualTo(1)
    assertThat(report.applied).isEqualTo(1)
    assertThat(phone.store.get(hidden(media))).isNull()
    assertThat(phone.store.entries.value).hasSize(1)
  }

  @Test
  fun senzaRetePubblicaNullaEDiceIlMotivoEUnAreaInSolaLetturaSiLegge() = runTest {
    val cloud = FakePersonalCloud { nasNow }
    cloud.approve("test", d1)
    val phone = Device("test", d1, cloud)
    phone.store.set(favorite(media), PersonalValue.Flag(true))

    phone.sync.reachable = false
    val offline = phone.sync.sync(1.minutes)
    assertThat(offline.stoppedBy).isEqualTo(PersonalStop.UNAVAILABLE)
    assertThat(phone.sync.status.value).isEqualTo(PersonalSyncStatus.Unavailable(PersonalUnavailable.NAS_UNREACHABLE))
    assertThat(cloud.privateAreas).isEmpty()

    phone.sync.reachable = true
    phone.sync.publishBlocked = PersonalUnavailable.READ_ONLY
    val readOnly = phone.sync.sync(1.minutes)
    assertThat(readOnly.published).isEqualTo(0)
    assertThat(phone.sync.status.value).isEqualTo(PersonalSyncStatus.Unavailable(PersonalUnavailable.READ_ONLY))
    assertThat(cloud.privateAreas).isEmpty()
  }

  // --- Impostazioni, null-object, codici ------------------------------------------------------------------------------------------

  @Test
  fun leImpostazioniPersonaliSiSalvanoENonCeNienteDelDispositivo() = runTest {
    val settings = InMemoryPersonalSettings()
    assertThat(settings.showAlbumCovers.value).isFalse()
    settings.setShowAlbumCovers(true)
    settings.setPrivateAreaExclusions(setOf(SharePath.parse("Musica")))
    assertThat(settings.showAlbumCovers.value).isTrue()
    assertThat(settings.privateAreaExclusions.value).containsExactly(SharePath.parse("Musica"))
  }

  @Test
  fun iNullObjectNonSincronizzanoNienteEIDatiRestanoVuoti() = runTest {
    assertThat(NoPersonalSync.sync(1.minutes).stoppedBy).isEqualTo(PersonalStop.UNAVAILABLE)
    assertThat(NoPersonalSync.status.value).isEqualTo(PersonalSyncStatus.Unavailable(PersonalUnavailable.NO_PRIVATE_AREA))
    NoPersonalStore.set(favorite(media), PersonalValue.Flag(true))
    assertThat(NoPersonalStore.get(favorite(media))).isNull()
    assertThat(NoPersonalStore.applyRemote(listOf(entry(favorite(media), 1, d1, true))).applied).isEqualTo(0)
    NoPersonalSettings.setShowAlbumCovers(true)
    assertThat(NoPersonalSettings.showAlbumCovers.value).isFalse()
  }

  @Test
  fun unaVoceConLImprontaDelMediaPortaSoloDimensioneEQh1() {
    val fp = PersonalFingerprint(1_000, QuickHash("a".repeat(64)))
    val e = PersonalEntry(favorite(media), 1, d1, PersonalValue.Flag(true), fp)
    assertThat(e.fingerprint!!.size).isEqualTo(1_000)
    assertThat(e.toString()).doesNotContain(media)
    assertThrows(IllegalArgumentException::class.java) { PersonalEntry(favorite(media), 1, "non-un-id", PersonalValue.Flag(true)) }
  }

  @Test
  fun iCodiciSonoUniciEStabili() {
    val all: Map<String, List<StableCode>> = mapOf(
      "PersonalKind" to PersonalKind.entries,
      "PersonalVerdict" to PersonalVerdict.entries,
      "PersonalUnavailable" to PersonalUnavailable.entries,
      "PersonalStop" to PersonalStop.entries,
    )
    all.forEach { (name, values) ->
      val codes = values.map { it.code }
      assertWithMessage(name).that(codes.toSet().size).isEqualTo(codes.size)
      codes.forEach { assertWithMessage(name).that(it).matches("^[a-z0-9_]{1,32}$") }
    }
    assertThat(PersonalKind.entries.map { it.code }).containsAtLeast("media_flag", "album", "memory", "setting", "person")
    assertThat(PersonalKind.PERSON.reserved).isTrue()
    assertThat(PersonalKind.entries.filter { !it.reserved }).hasSize(4)
  }
}
