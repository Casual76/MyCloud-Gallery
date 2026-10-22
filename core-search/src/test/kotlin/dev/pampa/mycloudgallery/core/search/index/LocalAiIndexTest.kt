package dev.pampa.mycloudgallery.core.search.index

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * L'indice locale (P6, §2.4, D23, D24) e le richieste di P1 e P2: origini (un dato calcolato qui non lo toglie mai la replica),
 * testo nel database che segue l'origine scelta, ripetizioni idempotenti, file rovinati ricostruiti, compattazione.
 */
class LocalAiIndexTest {
  @get:Rule val tmp = TemporaryFolder()

  private val texts = InMemoryTextRows()
  private val repair = RecordingRepair()
  private lateinit var dir: File

  private suspend fun index(): LocalAiIndex {
    if (!::dir.isInitialized) dir = tmp.newFolder("search")
    return LocalAiIndex(dir, texts, repair).also { it.open() }
  }

  private fun v(seed: Long): FloatArray = FloatArray(768).also { FastNoise.fill(seed, it) }

  @Test
  fun vettoriETestoSopravvivonoAllaRiapertura() = runTest {
    val a = index()
    a.saveVectors(key(1), vecBody(v(1)))
    a.saveVectors(key(2), vecBody(v(2), v(3), v(4)))
    a.saveText(key(1), ocrBody("SCONTRINO Esselunga\ntotale 12,50 €"))
    assertThat(a.saved(key(1), 1)).containsExactly(AiClass.VEC, AiClass.OCR)
    assertThat(a.saved(key(2), 2)).isEmpty()
    a.close()
    val b = index()
    assertThat(b.sizes).isEqualTo(2 to 1)
    assertThat(b.vectors(key(2))).hasSize(3)
    assertThat(b.text(key(1))).isEqualTo("scontrino esselunga totale 12 50 euro")
    assertThat(texts.rows[key(1)]!!.text).isEqualTo("scontrino esselunga totale 12 50 euro")
    assertThat(b.saved(key(1), 1)).containsExactly(AiClass.VEC, AiClass.OCR)
    // Il vettore riletto e' quello salvato (float16).
    assertThat(cos(b.vectors(key(1)).single(), normalized(v(1)))).isGreaterThan(0.999f)
  }

  /** P11b: il menu del viewer chiede senza attese se una chiave ha un vettore ("Trova simili" spiegato per un media non ancora analizzato, E-Q14). */
  @Test
  fun hasVectorsDiceSeUnaChiaveHaUnVettoreESegueLaRiapertura() = runTest {
    val a = index()
    assertThat(a.hasVectors(key(1))).isFalse()
    a.saveVectors(key(1), vecBody(v(1)))
    a.saveText(key(2), ocrBody("solo testo"))

    assertThat(a.hasVectors(key(1))).isTrue()
    // Un testo senza vettore non basta: i simili partono dai vettori.
    assertThat(a.hasVectors(key(2))).isFalse()
    a.close()
    assertThat(index().hasVectors(key(1))).isTrue()
  }

  /** Richiesta di P2: `withdraw`, `forgetDevice` e `forgetShare` di una replica non tolgono mai cio' che e' stato calcolato qui. */
  @Test
  fun laReplicaNonToglieMaiIDatiCalcolatiQui() = runTest {
    val idx = index()
    idx.saveVectors(key(1), vecBody(v(1)))
    idx.saveText(key(1), ocrBody("testo locale"))
    idx.applyReplica("comune", listOf(LocalAiIndex.ReplicaItem.Vectors(key(1), "dev-b", vecBody(v(9), recipe = 2))))
    idx.applyReplica("comune", listOf(LocalAiIndex.ReplicaItem.Text(key(1), "dev-b", ocrBody("testo remoto", recipe = 2))))
    // Ricetta piu' alta: vince la replica, anche nel database del testo.
    assertThat(cos(idx.vectors(key(1)).single(), normalized(v(9)))).isGreaterThan(0.999f)
    assertThat(texts.rows[key(1)]!!.text).isEqualTo("testo remoto")
    idx.withdraw("comune", listOf(key(1) to AiClass.VEC, key(1) to AiClass.OCR))
    assertThat(cos(idx.vectors(key(1)).single(), normalized(v(1)))).isGreaterThan(0.999f)
    assertThat(texts.rows[key(1)]!!.text).isEqualTo("testo locale")
    idx.applyReplica("comune", listOf(LocalAiIndex.ReplicaItem.Text(key(1), "dev-b", ocrBody("di nuovo", recipe = 3))))
    idx.forgetDevice("dev-b")
    assertThat(texts.rows[key(1)]!!.text).isEqualTo("testo locale")
    idx.applyReplica("comune", listOf(LocalAiIndex.ReplicaItem.Vectors(key(1), "dev-c", vecBody(v(7), recipe = 3))))
    idx.forgetShare("comune")
    assertThat(cos(idx.vectors(key(1)).single(), normalized(v(1)))).isGreaterThan(0.999f)
    assertThat(idx.saved(key(1), 1)).containsExactly(AiClass.VEC, AiClass.OCR)
    // E dopo una riapertura e' ancora cosi'.
    idx.close()
    val again = index()
    assertThat(again.text(key(1))).isEqualTo("testo locale")
    assertThat(cos(again.vectors(key(1)).single(), normalized(v(1)))).isGreaterThan(0.999f)
  }

  @Test
  fun loStessoContenutoInDueShareRestaFinchéUnaLoAttesta() = runTest {
    val idx = index()
    idx.applyReplica("privata", listOf(LocalAiIndex.ReplicaItem.Text(key(5), "dev-a", ocrBody("uno"))))
    idx.applyReplica("comune", listOf(LocalAiIndex.ReplicaItem.Text(key(5), "dev-b", ocrBody("uno"))))
    idx.withdraw("privata", listOf(key(5) to AiClass.OCR))
    assertThat(idx.text(key(5))).isEqualTo("uno")
    assertThat(texts.rows).containsKey(key(5))
    idx.withdraw("comune", listOf(key(5) to AiClass.OCR))
    assertThat(idx.text(key(5))).isNull()
    assertThat(texts.rows).doesNotContainKey(key(5))
  }

  @Test
  fun unNuovoVincitoreDellaShareSostituisceIlPrecedente() = runTest {
    val idx = index()
    idx.applyReplica("comune", listOf(LocalAiIndex.ReplicaItem.Vectors(key(3), "dev-a", vecBody(v(1)))))
    idx.applyReplica("comune", listOf(LocalAiIndex.ReplicaItem.Vectors(key(3), "dev-b", vecBody(v(2)))))
    idx.forgetDevice("dev-b")
    // Il vincitore era uno solo: tolto dev-b non resta il vecchio di dev-a.
    assertThat(idx.vectors(key(3))).isEmpty()
  }

  @Test
  fun ripetereLaStessaConsegnaNonRiscriveNiente() = runTest {
    val idx = index()
    val item = LocalAiIndex.ReplicaItem.Vectors(key(4), "dev-a", vecBody(v(4)))
    idx.applyReplica("comune", listOf(item))
    val length = File(dir, "vectors-${idx.space.code}-0.bin").length()
    val version = idx.version.value
    repeat(3) { idx.applyReplica("comune", listOf(item)) }
    assertThat(File(dir, "vectors-${idx.space.code}-0.bin").length()).isEqualTo(length)
    assertThat(idx.version.value).isEqualTo(version)
  }

  @Test
  fun unVettoreNonFinitoODiUnAltroSpazioNonEntra() = runTest {
    val idx = index()
    val bad = v(1).also { it[3] = Float.NaN }
    idx.saveVectors(key(1), dev.pampa.mycloudgallery.core.trust.data.VecBody(
      idx.space, 1, dev.pampa.mycloudgallery.core.model.VecSource.ANALYSIS,
      listOf(dev.pampa.mycloudgallery.core.trust.data.VecItem(null, dev.pampa.mycloudgallery.core.model.Float16.encode(bad))),
    ))
    assertThat(idx.vectors(key(1))).isEmpty()
  }

  /** File dell'indice rovinato (§2.4): il record con il CRC sbagliato non si usa, si toglie e si chiede di riaverlo. */
  @Test
  fun unRecordRovinatoSiToglieESiChiedeDiRiaverlo() = runTest {
    val idx = index()
    idx.applyReplica("comune", (1..5).map { LocalAiIndex.ReplicaItem.Vectors(key(it), "dev-a", vecBody(v(it.toLong()))) })
    idx.saveVectors(key(9), vecBody(v(9)))
    idx.close()
    // Si rovina il record della chiave 3 (il terzo) e quello locale (il sesto).
    val file = File(dir, "vectors-${idx.space.code}-0.bin")
    val recordSize = 44 + 768 * 2 + 4
    RandomAccessFile(file, "rw").use { raf ->
      for (n in listOf(2, 5)) {
        raf.seek(n.toLong() * recordSize + 100)
        raf.write(byteArrayOf(1, 2, 3, 4))
      }
    }
    File(dir, "coarse-${idx.space.code}.bin").delete()
    val again = index()
    assertThat(again.vectors(key(3))).isEmpty()
    assertThat(again.vectors(key(9))).isEmpty()
    assertThat(again.vectors(key(4))).hasSize(1)
    assertThat(repair.keys).containsAtLeast(key(3), key(9))
    assertThat(again.counters.rebuilt.get()).isAtLeast(1)
    // Il calcolo sa di doverlo rifare; la replica lo riconsegna e torna.
    assertThat(again.saved(key(9), 1)).isEmpty()
    again.applyReplica("comune", listOf(LocalAiIndex.ReplicaItem.Vectors(key(3), "dev-a", vecBody(v(3)))))
    assertThat(again.vectors(key(3))).hasSize(1)
  }

  /** Il file della prima passata rovinato o mancante si ricostruisce dal file dei vettori, con gli stessi codici. */
  @Test
  fun laPrimaPassataRovinataSiRicostruisceUguale() = runTest {
    val idx = index()
    idx.applyReplica("comune", (1..50).map { LocalAiIndex.ReplicaItem.Vectors(key(it), "dev-a", vecBody(v(it.toLong()))) })
    idx.close()
    val coarse = File(dir, "coarse-${idx.space.code}.bin")
    assertThat(coarse.isFile).isTrue()
    val before = index()
    // Il file sano si usa com'e': nessuna ricostruzione.
    assertThat(before.counters.rebuilt.get()).isEqualTo(0)
    val codes = before.codes.copyOf()
    val scales = before.scales.copyOf()
    before.close()
    RandomAccessFile(coarse, "rw").use { it.seek(coarse.length() / 2); it.write(byteArrayOf(9, 9, 9)) }
    val after = index()
    assertThat(after.counters.rebuilt.get()).isEqualTo(1)
    assertThat(after.codes.copyOf(codes.size)).isEqualTo(codes)
    assertThat(after.scales.copyOf(scales.size)).isEqualTo(scales)
    after.close()
    coarse.delete()
    val missing = index()
    assertThat(missing.codes.copyOf(codes.size)).isEqualTo(codes)
  }

  @Test
  fun unaCodaAMetaDelRegistroSiScartaUnaCorniceRovinataChiedeTutto() = runTest {
    val idx = index()
    idx.saveText(key(1), ocrBody("primo"))
    idx.saveText(key(2), ocrBody("secondo"))
    idx.close()
    val ledger = File(dir, "ledger.log")
    // Coda a meta' (morte del processo durante una scrittura): si scarta, il resto vale.
    RandomAccessFile(ledger, "rw").use { it.seek(it.length()); it.write(byteArrayOf(0, 0, 0, 40, 1, 2)) }
    val torn = index()
    assertThat(torn.sizes.second).isEqualTo(2)
    assertThat(repair.all).isEqualTo(0)
    torn.close()
    // Un byte cambiato nella prima cornice: si chiede di riavere tutto.
    RandomAccessFile(ledger, "rw").use { it.seek(12); it.write(0x55) }
    val broken = index()
    assertThat(repair.all).isEqualTo(1)
    assertThat(broken.sizes.second).isEqualTo(0)
  }

  /** Compattazione (§2.4): oltre il 20% di record morti il file si riscrive; i dati restano gli stessi. */
  @Test
  fun laCompattazioneTieneSoloIRecordViviEGliStessiDati() = runTest {
    val idx = index()
    repeat(3) { round ->
      idx.applyReplica("comune", (1..20).map { LocalAiIndex.ReplicaItem.Vectors(key(it), "dev-$round", vecBody(v(it * 100L + round))) })
    }
    idx.saveVectors(key(50), vecBody(v(50)))
    assertThat(idx.maintain()).isTrue()
    assertThat(idx.counters.compactions.get()).isEqualTo(1)
    val file = File(dir, "vectors-${idx.space.code}-1.bin")
    assertThat(file.length()).isEqualTo(21L * (44 + 768 * 2 + 4))
    assertThat(cos(idx.vectors(key(7)).single(), normalized(v(702)))).isGreaterThan(0.999f)
    assertThat(cos(idx.vectors(key(50)).single(), normalized(v(50)))).isGreaterThan(0.999f)
    assertThat(idx.maintain()).isFalse()
    idx.close()
    val again = index()
    assertThat(cos(again.vectors(key(7)).single(), normalized(v(702)))).isGreaterThan(0.999f)
    assertThat(repair.all).isEqualTo(0)
    assertThat(repair.keys).isEmpty()
    assertThat(again.counters.rebuilt.get()).isEqualTo(0)
  }

  private fun cos(a: FloatArray, b: FloatArray): Float {
    var d = 0.0
    var na = 0.0
    var nb = 0.0
    for (i in a.indices) {
      d += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]
    }
    return (d / Math.sqrt(na * nb)).toFloat()
  }
}
