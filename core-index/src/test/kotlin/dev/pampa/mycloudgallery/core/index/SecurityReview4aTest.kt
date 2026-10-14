package dev.pampa.mycloudgallery.core.index

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.index.contract.FlushReason
import dev.pampa.mycloudgallery.core.index.contract.WorkPass
import dev.pampa.mycloudgallery.core.index.p3.Images
import dev.pampa.mycloudgallery.core.index.p3.SimWorker
import dev.pampa.mycloudgallery.core.index.p3.TestWorld
import dev.pampa.mycloudgallery.core.index.p3.simMedia
import dev.pampa.mycloudgallery.core.index.sync.Fx
import dev.pampa.mycloudgallery.core.index.sync.Harness
import dev.pampa.mycloudgallery.core.index.testing.InMemoryRemoteIndex
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.data.IndexSegments
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.data.SegmentContent
import kotlin.random.Random
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Revisione di sicurezza della Fase 4A (`docs/review/4a-fase4a.md`). Ogni prova descrive il comportamento **sicuro** atteso:
 * oggi fallisce, e cosi' dimostra il problema; diventa verde con la correzione proposta.
 *
 * - R1: la replica sceglie i blob per i primi 64 bit della chiave di contenuto e senza guardare la share: un dispositivo
 *   compromesso che scrive solo nell'area comune sostituisce la miniatura di un media dell'area privata di un altro account.
 * - R5: chi prepara crede alla maschera `prep` dei record altrui senza che esista un blob: un dispositivo compromesso
 *   ferma la preparazione di un'intera share.
 */
class SecurityReview4aTest {

  @get:Rule val tmp = TemporaryFolder()

  @Test
  fun r1_unaChiaveInventataConGliStessiPrimi64BitInUnAltraShareNonSostituisceLaMiniatura() = runTest {
    val nas = InMemoryRemoteIndex(nasNow = { Fx.NOW })
    val victim = Fx.file(7, prepared = setOf(SegmentClass.THUMB_S))
    val key = victim.contentKey
    val honest = "MINIATURA VERA DELLA FOTO PRIVATA".toByteArray()

    // Il dispositivo onesto (id minore) pubblica nella share privata del suo account il record e la miniatura vera.
    nas.publish(
      "test",
      "aaaaaaaaaaaaaaaaaaaaaaaaaa",
      listOf(Fx.meta(Fx.put("Foto/7.jpg", 1, victim)), Fx.images(SegmentClass.THUMB_S, key to honest)),
    )

    // Il dispositivo compromesso (id maggiore, account diverso) scrive solo nella share comune: nessun record, solo un blob
    // con una chiave inventata che ha i primi 64 bit di quella della vittima e il resto a zero (sempre prima nell'ordine).
    val forged = ContentKey(key.hex.take(16) + "0".repeat(48))
    val evil = "IMMAGINE DELL'ATTACCANTE".toByteArray()
    nas.publish("test-comune", "zzzzzzzzzzzzzzzzzzzzzzzzzz", listOf(Fx.images(SegmentClass.THUMB_S, forged to evil)))

    // Il codec vero accetta una chiave qualunque nella tabella: niente lega la chiave ai byte (ne' alla share).
    val encoded = IndexSegments.blobs(SegmentClass.THUMB_S, listOf(forged to Images.webp()))
    val decoded = IndexSegments.decode(encoded.bytes, encoded.ref, SegmentClass.THUMB_S)
    assertThat(decoded).isInstanceOf(Verdict.Accepted::class.java)
    val entry = ((decoded as Verdict.Accepted).value as SegmentContent.Blobs).entries.single()
    assertThat(entry.contentKey).isEqualTo(forged)
    assertThat(forged.short).isEqualTo(key.short)

    // Un dispositivo del primo account che legge le sue due aree.
    val reader = Harness("mmmmmmmmmmmmmmmmmmmmmmmmmm", tmp.root, nas)
    reader.sync("test", "test-comune")

    // Atteso: la Libreria (che cerca per `MediaRef.Nas.contentKey`, 64 bit) mostra la miniatura vera.
    // Oggi: `BlobIndex` indicizza per (64 bit, classe) senza share e ordina per chiave intera: vince l'attaccante.
    assertThat(String(reader.blob(key, SegmentClass.THUMB_S)!!)).isEqualTo(String(honest))
  }

  @Test
  fun r5_unDispositivoCheDichiaraTuttoPreparatoSenzaPubblicareBlobNonFermaGliAltri() = runTest {
    val world = TestWorld()
    val share = "test-comune"
    val honest = world.device("Telefono onesto", "test")
    val attacker = world.device("Tablet compromesso", "test2")
    val media = simMedia(world, share, listOf("2026-09"), perMonth = 20, random = Random(4))

    // Il dispositivo compromesso pubblica (con il pubblicatore vero: M3 controlla percorso, timbro e qh1, non `prep`) un
    // record per ogni media che dice "tutte le classi preparate", senza un solo blob.
    val publisher = attacker.publisher()
    val all = setOf(SegmentClass.THUMB_S, SegmentClass.THUMB_M, SegmentClass.ANALYSIS, SegmentClass.HQ)
    publisher.stage(share, media.map { it.file.put(all) }, emptyList())
    publisher.flush(share, FlushReason.UNIT_FINISHED)

    // Il Preparatore onesto chiede un'unita' di lavoro: le teste si rileggono con il codec e le firme vere.
    val worker = SimWorker(world, honest, media, share)
    val claim = worker.units.next(share, WorkPass.PREP, worker.priority)

    // Atteso: nessuno ha pubblicato una miniatura, quindi c'e' ancora lavoro.
    // Oggi: `RemotePublishedClasses` somma la maschera `prep` dei record e `LeasedWorkUnits.trim` toglie tutto.
    assertThat(claim).isNotNull()
  }
}
