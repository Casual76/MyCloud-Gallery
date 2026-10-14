package dev.pampa.mycloudgallery.core.nasmedia

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.index.contract.ReplicaRecords
import dev.pampa.mycloudgallery.core.index.testing.InMemoryReplica
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.ThumbHash
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nasmedia.contract.NasMetaResult
import dev.pampa.mycloudgallery.core.nasmedia.testing.SyntheticNas
import dev.pampa.mycloudgallery.core.trust.data.MetaFile
import dev.pampa.mycloudgallery.core.trust.data.MetaRecord
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * L'adozione dei record dell'indice con lo stesso timbro (D6, D7, D8; E-N11, E-N12): un file gia' pubblicato da un altro
 * dispositivo si registra senza leggerlo. Due "dispositivi" (due cataloghi) sullo stesso NAS.
 */
@RunWith(AndroidJUnit4::class)
class ScanAdoptionTest {

  private val devices = ArrayList<ScanHarness>()

  @After
  fun close() {
    devices.forEach { it.close() }
  }

  private fun device(replica: InMemoryReplica = InMemoryReplica(), records: ReplicaRecords = replica, shared: ScanHarness? = null): ScanHarness =
    ScanHarness(
      replica = replica,
      replicaRecords = records,
      sharedNas = shared?.nas,
      clockSource = shared,
      writeMetadata = true,
    ).also { devices += it }

  /** Il record che il dispositivo [a] avrebbe pubblicato per [path], dal suo catalogo e dal NAS. */
  private suspend fun recordOf(a: ScanHarness, path: String, seq: Long): MetaRecord.Present {
    val entry = (a.nas.reader.stat("test", SharePath.parse(path), Purpose.BACKGROUND) as NasResult.Ok).value!!
    val media = a.catalog.media(checkNotNull(a.store.mediaIdAt(a.keyOf(path))))!!
    val version = media.currentVersion
    return MetaRecord.Present(
      SharePath.parse(path),
      seq,
      MetaFile(
        stamp = entry.stamp,
        quickHash = version.fingerprint.quickHash,
        mediaId = media.id,
        versionId = version.id,
        kind = MediaKind.PHOTO,
        source = MediaSource.OTHER,
        mime = "image/jpeg",
        width = 4000,
        height = 3000,
        thumbHash = ThumbHash.of(ByteArray(12) { it.toByte() }),
      ),
    )
  }

  @Test
  fun unFileGiaPubblicatoDaUnAltroDispositivoSiAdottaSenzaLeggerlo() = runTest {
    val a = device()
    val paths = (0 until 40).map { "Foto/f$it.jpg" }
    paths.forEachIndexed { i, p -> a.photo(p, seed = i.toLong()) }
    assertThat(a.scan().detected).isEqualTo(40)

    val replica = InMemoryReplica()
    paths.forEachIndexed { i, p -> replica.putRecord("test", "aaaaaaaaaaaaaaaaaaaaaaaaaa", recordOf(a, p, i + 1L)) }
    val b = device(replica = replica, shared = a)
    val opens = a.nas.opens.get()
    val bytes = a.nas.bytesRead.get()

    val report = b.scan()

    assertThat(report.adopted).isEqualTo(40)
    assertThat(report.detected).isEqualTo(0)
    assertThat(report.bytesRead).isEqualTo(0)
    assertThat(a.nas.opens.get()).isEqualTo(opens)
    assertThat(a.nas.bytesRead.get()).isEqualTo(bytes)
    assertThat(b.store.knownCount(b.me, "test")).isEqualTo(40)
    // D7: l'identita' pubblicata dall'altro dispositivo si adotta (il catalogo decide, qui si chiede).
    assertThat(b.store.adoptions).hasSize(40)
    paths.forEach { p ->
      val remote = checkNotNull(a.store.mediaIdAt(a.keyOf(p)))
      val local = checkNotNull(b.store.mediaIdAt(b.keyOf(p)))
      assertThat(b.store.adoptions.single { it.local == local }.remoteMedia).isEqualTo(remote)
    }
    // I metadati sono quelli del record (gia' orientati), non rileggendo niente.
    val applied = b.store.appliedMetadata.values.filterIsInstance<NasMetaResult.Read>()
    assertThat(applied).hasSize(40)
    assertThat(applied.first().metadata.width).isEqualTo(4000)
    assertThat(applied.first().metadata.orientationDegrees).isEqualTo(0)
    assertThat(applied.first().metadata.thumbHash).isNotNull()
  }

  @Test
  fun unRecordConIlTimbroVecchioNonVale_e_ilFileSiRileva() = runTest {
    val a = device()
    val paths = (0 until 10).map { "Foto/f$it.jpg" }
    paths.forEachIndexed { i, p -> a.photo(p, seed = i.toLong()) }
    a.scan()
    val replica = InMemoryReplica()
    paths.forEachIndexed { i, p -> replica.putRecord("test", "aaaaaaaaaaaaaaaaaaaaaaaaaa", recordOf(a, p, i + 1L)) }
    // E-N12: dopo la pubblicazione il file e' stato modificato dal PC.
    a.nas.modify("test", paths[3], SyntheticNas.content(1_700_000, 99, SyntheticNas.JPEG_HEAD), modifiedAt = a.nasNow - 600_000)

    val b = device(replica = replica, shared = a)
    val report = b.scan()
    assertThat(report.adopted).isEqualTo(9)
    assertThat(report.detected).isEqualTo(1)
    assertThat(b.store.knownCount(b.me, "test")).isEqualTo(10)
  }

  @Test
  fun unaAdozioneRifiutataDalCatalogoSiContaMaIlFileNonSiLegge() = runTest {
    val a = device()
    a.photo("Foto/a.jpg", seed = 1)
    a.photo("Foto/b.jpg", seed = 2)
    a.scan()
    val replica = InMemoryReplica()
    replica.putRecord("test", "aaaaaaaaaaaaaaaaaaaaaaaaaa", recordOf(a, "Foto/a.jpg", 1))
    replica.putRecord("test", "aaaaaaaaaaaaaaaaaaaaaaaaaa", recordOf(a, "Foto/b.jpg", 2))
    val b = device(replica = replica, shared = a)
    // D7: l'id remoto di `a.jpg` e' gia' legato qui a un contenuto diverso.
    b.store.refusedRemoteMedia += checkNotNull(a.store.mediaIdAt(a.keyOf("Foto/a.jpg")))
    val report = b.scan()
    assertThat(report.adopted).isEqualTo(2)
    assertThat(report.identityRefused).isEqualTo(1)
    assertThat(report.detected).isEqualTo(0)
    assertThat(b.store.adoptions).hasSize(1)
  }

  @Test
  fun unRecordOstileCheDiceTimbroGiustoMaOffreAltroNonFaCrashNeSiFida() = runTest {
    val a = device()
    a.photo("Foto/a.jpg", seed = 1)
    a.scan()
    val good = recordOf(a, "Foto/a.jpg", 1)
    // Una replica che restituisce un record con un timbro diverso da quello chiesto (non dovrebbe: ma e' un dato altrui).
    val hostile = object : ReplicaRecords {
      override suspend fun matching(share: String, stamps: Map<SharePath, NasStamp>): Map<SharePath, MetaRecord.Present> =
        stamps.keys.associateWith { path ->
          val wrong = NasStamp(good.file.stamp.size + 1, good.file.stamp.modifiedAtMillis, good.file.stamp.fileId)
          MetaRecord.Present(path, 1, good.file.copy(stamp = wrong))
        }
    }
    val b = device(records = hostile, shared = a)
    val report = b.scan()
    assertThat(report.adopted).isEqualTo(0)
    assertThat(report.detected).isEqualTo(1)
    assertThat(b.store.adoptions).isEmpty()
  }

  @Test
  fun unRecordAdottatoNonPortaMaiLHashCompletoDellIndice() = runTest {
    val a = device()
    a.photo("Foto/a.jpg", seed = 1)
    a.scan()
    val record = recordOf(a, "Foto/a.jpg", 1)
    val withFullHash = record.copy(file = record.file.copy(fullHash = dev.pampa.mycloudgallery.core.model.Sha256.of(byteArrayOf(1, 2, 3))))
    val replica = InMemoryReplica()
    replica.putRecord("test", "aaaaaaaaaaaaaaaaaaaaaaaaaa", withFullHash)
    val b = device(replica = replica, shared = a)
    b.scan()
    // D8: l'hash completo puo' venire dall'indice solo dopo un qh1 letto qui; adottando senza leggere non si usa.
    val media = b.catalog.media(checkNotNull(b.store.mediaIdAt(b.keyOf("Foto/a.jpg"))))!!
    assertThat(media.currentVersion.fingerprint.fullHash).isNull()
    assertThat(media.currentVersion.fingerprint.quickHash).isEqualTo(record.file.quickHash)
  }
}
