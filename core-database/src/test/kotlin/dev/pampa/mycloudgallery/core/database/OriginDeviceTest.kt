package dev.pampa.mycloudgallery.core.database

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.LocationId
import dev.pampa.mycloudgallery.core.model.LocationState
import dev.pampa.mycloudgallery.core.model.MediaLocation
import dev.pampa.mycloudgallery.core.model.NasArea
import dev.pampa.mycloudgallery.core.model.NasAreaLookup
import dev.pampa.mycloudgallery.core.model.NasPath
import dev.pampa.mycloudgallery.core.model.NasShare
import dev.pampa.mycloudgallery.core.model.VersionId
import org.junit.Test

/** Le regole del dispositivo d'origine e delle aree delle copie (colonne derivate di v6): funzioni pure, nessun database. */
class OriginDeviceTest {

  private val me = AccountId.of("alessio")
  private val areas = NasAreaLookup.of(listOf(NasShare(me, "alessio", NasArea.PRIVATE), NasShare(me, "NAS", NasArea.COMMON)))

  private fun nas(path: String, share: String = "alessio", state: LocationState = LocationState.PRESENT) =
    located(Location.Nas(me, share, NasPath.of(path)), state)

  private fun phone(state: LocationState = LocationState.PRESENT) =
    located(Location.Phone("external_primary", "DCIM/Camera/", "IMG_1.jpg", 1, "store-v1"), state)

  private var next = 1L

  private fun located(location: Location, state: LocationState) = MediaLocation(
    id = LocationId(next++),
    versionId = VersionId("10000000-0000-4000-8000-000000000001"),
    location = location,
    stamp = FileStamp(100, 1),
    state = state,
    absentSinceMillis = if (state == LocationState.ABSENT) 1 else null,
    lastSeenAtMillis = 1,
  )

  private fun keyOf(path: String, share: String = "alessio") = Location.Nas(me, share, NasPath.of(path)).key.value

  @Test
  fun laChiaveDiUnaPosizioneDiBackupNominaLaCartellaDelDispositivo() {
    assertThat(OriginDevice.ofNasKey(keyOf("Backup/S25/2026/10/IMG_1.jpg"))).isEqualTo("backup:s25")
    assertThat(OriginDevice.ofNasKey(keyOf("Backup/S25/IMG_1.jpg"))).isEqualTo("backup:s25")
    // Solo la radice vera: una `Backup` piu' in basso (anche sotto la radice di prova del debug) non nomina un dispositivo.
    assertThat(OriginDevice.ofNasKey(keyOf("altro/Backup/Prova/IMG_1.jpg"))).isNull()
    assertThat(OriginDevice.ofNasKey(keyOf("prova/Backup/Prova/2026/IMG_1.jpg"))).isNull()
    // Maiuscole e forma Unicode non contano (la chiave e' gia' in forma di confronto).
    assertThat(OriginDevice.ofNasKey(keyOf("BACKUP/Ta\u0301b/IMG_1.jpg"))).isEqualTo(OriginDevice.ofNasKey(keyOf("backup/T\u00e1b/IMG_1.jpg")))
  }

  @Test
  fun nonSonoDispositiviLeCopieDelGuardianoUnFileSenzaCartellaEIPercorsiEstranei() {
    assertThat(OriginDevice.ofNasKey(keyOf("Backup/Area comune/2026/10/IMG_1.jpg"))).isNull()
    assertThat(OriginDevice.ofNasKey(keyOf("Backup/IMG_1.jpg"))).isNull()
    assertThat(OriginDevice.ofNasKey(keyOf("Famiglia/Backup/zio/IMG_1.jpg"))).isNull()
    assertThat(OriginDevice.ofNasKey(keyOf("Foto/IMG_1.jpg"))).isNull()
    assertThat(OriginDevice.ofNasKey("phone:external_primary:dcim/camera/img_1.jpg")).isNull()
    assertThat(OriginDevice.ofNasKey("")).isNull()
    assertThat(OriginDevice.ofNasKey("nas:alessio:alessio")).isNull()
  }

  @Test
  fun ilTelefonoVinceSuTuttoMaSoloSeLaCopiaEsiste() {
    val backup = nas("Backup/S25/2026/IMG_1.jpg")
    assertThat(OriginDevice.of(listOf(backup, phone()), areas)).isEqualTo(OriginDevice.PHONE)
    assertThat(OriginDevice.of(listOf(backup, phone(LocationState.ABSENT)), areas)).isEqualTo("backup:s25")
    assertThat(OriginDevice.of(listOf(phone(LocationState.UNAVAILABLE)), areas)).isEqualTo(OriginDevice.PHONE)
    assertThat(OriginDevice.of(listOf(phone(LocationState.ABSENT)), areas)).isNull()
    assertThat(OriginDevice.of(emptyList(), areas)).isNull()
  }

  @Test
  fun soloUnAreaPrivataDaUnDispositivoEConPiuCartelleVinceLaPrimaInOrdineAlfabetico() {
    assertThat(OriginDevice.of(listOf(nas("Backup/Marco/IMG_1.jpg", share = "NAS")), areas)).isNull()
    assertThat(OriginDevice.of(listOf(nas("Backup/Marco/IMG_1.jpg", share = "ignota")), areas)).isNull()
    assertThat(OriginDevice.of(listOf(nas("Backup/S25/a.jpg", state = LocationState.ABSENT)), areas)).isNull()
    val two = listOf(nas("Backup/S25/a.jpg"), nas("Backup/Altro/a.jpg"))
    assertThat(OriginDevice.of(two, areas)).isEqualTo("backup:altro")
    assertThat(OriginDevice.of(two.reversed(), areas)).isEqualTo("backup:altro")
  }

  @Test
  fun leAreeDelleCopieSonoUnaMascheraDiBit() {
    assertThat(CopyAreas.of(emptyList(), areas)).isEqualTo(0)
    assertThat(CopyAreas.of(listOf(phone()), areas)).isEqualTo(0)
    assertThat(CopyAreas.of(listOf(nas("a.jpg")), areas)).isEqualTo(CopyAreas.PRIVATE)
    assertThat(CopyAreas.of(listOf(nas("a.jpg", "NAS")), areas)).isEqualTo(CopyAreas.COMMON)
    assertThat(CopyAreas.of(listOf(nas("a.jpg", "ignota")), areas)).isEqualTo(CopyAreas.OTHER)
    assertThat(CopyAreas.of(listOf(nas("a.jpg"), nas("b.jpg", "NAS"), nas("c.jpg", "ignota")), areas))
      .isEqualTo(CopyAreas.PRIVATE or CopyAreas.COMMON or CopyAreas.OTHER)
    // Solo le copie presenti: una assente o non raggiungibile non conta.
    assertThat(CopyAreas.of(listOf(nas("a.jpg", state = LocationState.ABSENT), nas("b.jpg", "NAS", LocationState.UNAVAILABLE)), areas)).isEqualTo(0)
    assertThat(CopyAreas.has(CopyAreas.PRIVATE or CopyAreas.COMMON, CopyAreas.COMMON)).isTrue()
    assertThat(CopyAreas.has(CopyAreas.PRIVATE, CopyAreas.COMMON)).isFalse()
  }
}
