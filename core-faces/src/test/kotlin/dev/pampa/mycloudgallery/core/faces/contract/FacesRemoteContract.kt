package dev.pampa.mycloudgallery.core.faces.contract

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.index.contract.RemoteFailure
import dev.pampa.mycloudgallery.core.index.contract.RemoteResult
import dev.pampa.mycloudgallery.core.index.contract.okOrNull
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.trust.crypto.Hashes
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.util.Random
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * La SUITE DI CONTRATTO del NAS dei volti (docs/fase8-progetto.md §5.2 "InMemoryFacesRemote": la suite di `NasPersonalRemote` adattata;
 * §2.4, D11, D12, L23). Gira sull'esecutore di riferimento ([InMemoryFacesRemoteContractTest]) e sulla porta vera di sync/ (P3) sopra
 * `NasFileSystem` con `InMemoryNasFileSystem`. Le proprieta': si scrive **solo** sotto `faces/<G>/<io>/`, **solo** senza sovrascrivere,
 * **mai** nell'area comune; gli elenchi ignorano (e contano) i nomi strani; le letture hanno il tetto; un guasto e' un esito, mai
 * un'eccezione.
 */
abstract class FacesRemoteContract {

  interface World {
    fun remote(deviceId: String): FacesRemote

    /** Un file messo da un PC o da un attaccante con la password (anche fuori da `faces/`). */
    fun put(path: SharePath, bytes: ByteArray)

    fun files(): List<SharePath>

    /** Le scritture fatte dai dispositivi: chi e dove. */
    fun writes(): List<Pair<String, SharePath>>

    fun failNext(operations: Int)
  }

  protected abstract fun world(share: String, common: String): World

  private val me = TrustIds.random()
  private val other = TrustIds.random()
  private val g = FaceGeneration.fromBytes(ByteArray(16) { 7 })
  private fun hash(n: Int) = Hashes.sha256Name("segmento-$n".toByteArray())

  @Test
  fun scriveSoloSottoLaPropriaCartellaESenzaSovrascrivere() = runTest {
    val w = world("test", "test-comune")
    val remote = w.remote(me)
    assertThat(remote.publishSegment(g, hash(1), byteArrayOf(1)).okOrNull()).isEqualTo(FacesPut.CREATED)
    assertThat(remote.publishHead(g, 1, byteArrayOf(2)).okOrNull()).isEqualTo(FacesPut.CREATED)
    // Lo stesso nome una seconda volta: nessun byte sovrascritto.
    assertThat(remote.publishHead(g, 1, byteArrayOf(9)).okOrNull()).isEqualTo(FacesPut.ALREADY_EXISTS)
    assertThat((remote.readHead(g, me, 1, 10).okOrNull() as FacesRead.Data).bytes).isEqualTo(byteArrayOf(2))
    assertThat(w.files()).containsExactly(NasLayout.facesSegment(g, me, hash(1)), NasLayout.facesHead(g, me, 1))
    for ((device, path) in w.writes()) {
      assertThat(device).isEqualTo(me)
      assertThat(path.isWithin(NasLayout.facesDeviceDir(g, me))).isTrue()
    }
    // Un nome che non e' un'impronta o un numero non valido non si scrive.
    assertThat(remote.publishSegment(g, "../../Foto", byteArrayOf(1))).isInstanceOf(RemoteResult.Failed::class.java)
    assertThat(remote.publishHead(g, 0, byteArrayOf(1))).isInstanceOf(RemoteResult.Failed::class.java)
  }

  /** L23, E-F42: nell'area comune niente si legge ne' si scrive, nemmeno se ci sono file dei volti (falsi o messi per errore). */
  @Test
  fun nellAreaComuneNiente() = runTest {
    val w = world("test-comune", "test-comune")
    w.put(NasLayout.facesHead(g, other, 1), byteArrayOf(1))
    val remote = w.remote(me)
    val denied = RemoteResult.Failed(RemoteFailure.NOT_ALLOWED)
    assertThat(remote.publishHead(g, 1, byteArrayOf(1))).isEqualTo(denied)
    assertThat(remote.publishSegment(g, hash(1), byteArrayOf(1))).isEqualTo(denied)
    assertThat(remote.generations()).isEqualTo(denied)
    assertThat(remote.readHead(g, other, 1, 10)).isEqualTo(denied)
    assertThat(w.writes()).isEmpty()
  }

  /** Gli elenchi danno solo nomi validi e contano gli altri; si leggono anche le cartelle di altri dispositivi (chi legge giudica la firma). */
  @Test
  fun elenchiELetture() = runTest {
    val w = world("test", "test-comune")
    w.put(NasLayout.facesHead(g, other, 3), byteArrayOf(1, 2, 3))
    w.put(NasLayout.facesGenerationDir(g).child("non-un-dispositivo").child("x"), byteArrayOf(0))
    w.put(NasLayout.FACES_DIR.child("NON-UNA-GENERAZIONE").child("x"), byteArrayOf(0))
    w.put(NasLayout.facesHeadDir(g, other).child("spazzatura"), byteArrayOf(0))
    w.put(NasLayout.facesLeaseFile(g, NasLayout.facesClusterResource(g), 1), byteArrayOf(0))
    val remote = w.remote(me)
    val generations = remote.generations().okOrNull()!!
    assertThat(generations.items).containsExactly(g)
    assertThat(generations.ignored).isEqualTo(1)
    val devices = remote.devices(g).okOrNull()!!
    assertThat(devices.items).containsExactly(other)
    assertThat(devices.ignored).isEqualTo(1)
    val heads = remote.headNumbers(g, other).okOrNull()!!
    assertThat(heads.items).containsExactly(3L)
    assertThat(heads.ignored).isEqualTo(1)
    assertThat(remote.readHead(g, other, 3, 2).okOrNull()).isEqualTo(FacesRead.TooLarge)
    assertThat((remote.readHead(g, other, 3, 3).okOrNull() as FacesRead.Data).bytes).isEqualTo(byteArrayOf(1, 2, 3))
    assertThat(remote.readHead(g, other, 4, 3).okOrNull()).isEqualTo(FacesRead.NotFound)
    assertThat(remote.readSegment(g, other, hash(9), 100).okOrNull()).isEqualTo(FacesRead.NotFound)
  }

  @Test
  fun unGuastoEUnEsitoMaiUnEccezione() = runTest {
    val w = world("test", "test-comune")
    val remote = w.remote(me)
    w.failNext(2)
    assertThat(remote.publishHead(g, 1, byteArrayOf(1))).isInstanceOf(RemoteResult.Failed::class.java)
    assertThat(remote.generations()).isInstanceOf(RemoteResult.Failed::class.java)
    assertThat(remote.publishHead(g, 1, byteArrayOf(1)).okOrNull()).isEqualTo(FacesPut.CREATED)
  }

  /** Proprieta': operazioni a caso di due dispositivi non scrivono mai fuori dalle loro cartelle, mai sopra un file. */
  @Test
  fun proprietaMaiFuoriDallaPropriaCartella() = runTest {
    val random = Random(13)
    val w = world("test", "test-comune")
    val devices = listOf(me, other)
    val generations = listOf(g, FaceGeneration.fromBytes(ByteArray(16) { 8 }))
    val first = HashMap<SharePath, Byte>()
    repeat(300) {
      val device = devices[random.nextInt(2)]
      val gen = generations[random.nextInt(2)]
      val bytes = byteArrayOf(random.nextInt().toByte())
      if (random.nextInt(6) == 0) w.failNext(1)
      val result = if (random.nextBoolean()) {
        w.remote(device).publishSegment(gen, hash(random.nextInt(20)), bytes)
      } else {
        w.remote(device).publishHead(gen, 1L + random.nextInt(20), bytes)
      }
      if (result.okOrNull() == FacesPut.CREATED) first[w.writes().last().second] = bytes[0]
    }
    for ((device, path) in w.writes()) {
      val inOwn = generations.any { path.isWithin(NasLayout.facesDeviceDir(it, device)) }
      assertWithMessage("scrittura fuori dalla propria cartella").that(inOwn).isTrue()
    }
    assertThat(w.writes().map { it.second }.toSet()).hasSize(w.writes().size)
    // Ogni file ha ancora i byte della prima scrittura: niente sovrascritto.
    for ((path, byte) in first) {
      val base = NasLayout.FACES_DIR.components.size
      val generation = FaceGeneration.parse(path.components[base])!!
      val device = path.components[base + 1]
      val read = if (path.components[base + 2] == "head") {
        w.remote(me).readHead(generation, device, NasLayout.parseVersionName(path.name)!!, 10)
      } else {
        w.remote(me).readSegment(generation, device, path.name, 10)
      }
      assertThat((read.okOrNull() as FacesRead.Data).bytes.single()).isEqualTo(byte)
    }
  }
}
