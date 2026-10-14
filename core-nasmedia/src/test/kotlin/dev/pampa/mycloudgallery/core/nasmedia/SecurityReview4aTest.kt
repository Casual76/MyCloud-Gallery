package dev.pampa.mycloudgallery.core.nasmedia

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.index.contract.ReplicaRecords
import dev.pampa.mycloudgallery.core.index.testing.InMemoryReplica
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.trust.data.MetaFile
import dev.pampa.mycloudgallery.core.trust.data.MetaRecord
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Revisione di sicurezza della Fase 4A (`docs/review/4a-fase4a.md`), R6: la scansione adotta dal record di un altro
 * dispositivo l'impronta rapida `qh1` **senza leggerla** (D9, passo 4) e la scrive nel catalogo come se fosse stata letta
 * qui. Quando l'autore viene revocato (o il record sparisce) il file non si rileva piu': l'impronta inventata resta.
 *
 * La prova descrive il comportamento **sicuro** atteso: oggi fallisce, e cosi' dimostra il problema.
 */
@RunWith(AndroidJUnit4::class)
class SecurityReview4aTest {

  private val devices = ArrayList<ScanHarness>()

  @After
  fun close() {
    devices.forEach { it.close() }
  }

  @Test
  fun r6_unImprontaAdottataDaUnDispositivoPoiRevocatoNonRestaNelCatalogo() = runTest {
    val path = "Foto/a.jpg"
    val honest = ScanHarness(writeMetadata = true).also { devices += it }
    honest.photo(path, seed = 1)
    honest.scan()
    val trueQuickHash = honest.catalog.media(checkNotNull(honest.store.mediaIdAt(honest.keyOf(path))))!!
      .currentVersion.fingerprint.quickHash

    // Il dispositivo compromesso pubblica per quel file un record con il timbro giusto (lo legge dall'elenco) e un `qh1`
    // inventato (per esempio quello di una foto che la vittima ha sul telefono).
    val entry = (honest.nas.reader.stat("test", SharePath.parse(path), Purpose.BACKGROUND) as NasResult.Ok).value!!
    val forged = QuickHash("ab".repeat(32))
    val hostile = MetaRecord.Present(
      SharePath.parse(path),
      1,
      MetaFile(
        stamp = entry.stamp,
        quickHash = forged,
        mediaId = MediaId.random(),
        versionId = VersionId.random(),
        kind = MediaKind.PHOTO,
        source = MediaSource.OTHER,
        mime = "image/jpeg",
      ),
    )
    var revoked = false
    val records = object : ReplicaRecords {
      override suspend fun matching(share: String, stamps: Map<SharePath, NasStamp>): Map<SharePath, MetaRecord.Present> =
        if (revoked) emptyMap() else stamps.filter { (p, s) -> p == hostile.path && s.sameAs(hostile.file.stamp) }.mapValues { hostile }
    }

    val victim = ScanHarness(
      replica = InMemoryReplica(),
      replicaRecords = records,
      sharedNas = honest.nas,
      clockSource = honest,
      writeMetadata = true,
    ).also { devices += it }
    val key = victim.keyOf(path)
    suspend fun catalogQuickHash(): QuickHash = victim.catalog.media(checkNotNull(victim.store.mediaIdAt(key)))!!.currentVersion.fingerprint.quickHash

    val first = victim.scan()
    assertThat(first.adopted).isEqualTo(1)
    assertThat(first.detected).isEqualTo(0)
    // D9 com'e' scritto: adottato senza leggere, l'impronta del catalogo e' quella del record.
    assertThat(catalogQuickHash()).isEqualTo(forged)

    // L'autore viene revocato: la replica non ha piu' il suo record (E-I15). Il file sul NAS non e' cambiato.
    revoked = true
    victim.scan()

    // Atteso: senza un record valido l'impronta adottata non vale piu' e il file si rileva qui (qh1 vero).
    // Oggi: stesso timbro = "invariato", nessuna lettura; l'impronta inventata resta nel catalogo per sempre.
    assertThat(catalogQuickHash()).isEqualTo(trueQuickHash)
  }
}
