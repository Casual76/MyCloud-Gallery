package dev.pampa.mycloudgallery.core.search.index

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Revisione A della Fase 6 (docs/review/6-fase6.md), l'indice locale: un dispositivo compromesso non rompe la ricerca degli altri ne'
 * fa passare per proprio cio' che pubblica. R1 (tetti, compattazione subito, apertura che si ricostruisce), R3 (tetti del testo), R4
 * (righe del testo dal vivo dall'origine che vince), R5 (provenienza dei calcoli fatti qui), R16 (ricetta oltre quella nota), I13.
 */
class RevisioneAIndiceTest {
  @get:Rule val tmp = TemporaryFolder()

  private val texts = InMemoryTextRows()
  private val repair = RecordingRepair()
  private lateinit var dir: File

  private suspend fun index(limits: IndexLimits = IndexLimits(), known: Int = 1): LocalAiIndex {
    if (!::dir.isInitialized) dir = tmp.newFolder("search")
    return LocalAiIndex(dir, texts, repair, limits = limits, knownRecipe = { known }).also { it.open() }
  }

  /** Il file dei vettori della generazione corrente (la piu' alta). */
  private fun currentVectors(): File =
    dir.listFiles()!!.filter { it.name.startsWith("vectors-") }.maxBy { it.name.substringAfterLast('-').substringBefore('.').toLong() }

  private fun v(seed: Long): FloatArray = FloatArray(768).also { FastNoise.fill(seed, it) }

  private fun cos(a: FloatArray, b: FloatArray): Float {
    var d = 0.0
    var na = 0.0
    var nb = 0.0
    for (i in a.indices) {
      d += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]
    }
    return (d / Math.sqrt(na * nb)).toFloat()
  }

  /** R16: una ricetta oltre quella nota (255) non vince sul calcolo di questo dispositivo, ne' nella ricerca ne' nel testo. */
  @Test
  fun r16_unaRicettaFinoA255NonVinceSulCalcoloLocale() = runTest {
    val idx = index()
    idx.saveVectors(key(1), vecBody(v(1)))
    idx.saveText(key(1), ocrBody("IBAN giusto"))
    idx.applyReplica("comune", listOf(LocalAiIndex.ReplicaItem.Vectors(key(1), "dev-b", vecBody(v(9), recipe = 255))))
    idx.applyReplica("comune", listOf(LocalAiIndex.ReplicaItem.Text(key(1), "dev-b", ocrBody("IBAN falso", recipe = 255))))
    assertThat(cos(idx.vectors(key(1)).single(), normalized(v(1)))).isGreaterThan(0.999f)
    assertThat(idx.text(key(1))).isEqualTo("iban giusto")
    assertThat(texts.rows[key(1)]!!.text).isEqualTo("iban giusto")
    // Senza il calcolo locale la voce della replica c'e' (L45): vale come la ricetta nota.
    idx.applyReplica("comune", listOf(LocalAiIndex.ReplicaItem.Text(key(2), "dev-b", ocrBody("altro", recipe = 255))))
    assertThat(idx.text(key(2))).isEqualTo("altro")
  }

  /** R4: le righe del testo dal vivo seguono l'origine che vince (prima la lettura propria) e la revoca le toglie. */
  @Test
  fun r4_leRigheDelViewerSeguonoLaSceltaELaRevoca() = runTest {
    val idx = index(known = 3)
    idx.saveText(key(1), ocrBody("letto qui"))
    assertThat(idx.textBody(key(1))!!.lines.single().text).isEqualTo("letto qui")
    // Una ricetta piu' alta e nota vince (D21): le righe sono le sue.
    idx.applyReplica("comune", listOf(LocalAiIndex.ReplicaItem.Text(key(1), "dev-b", ocrBody("letto da b", recipe = 2))))
    assertThat(idx.textBody(key(1))!!.lines.single().text).isEqualTo("letto da b")
    // La revoca di b: tornano le righe lette qui (prima restava il file di b fino all'azzeramento).
    idx.forgetDevice("dev-b")
    assertThat(idx.textBody(key(1))!!.lines.single().text).isEqualTo("letto qui")
    // Un testo della replica ritirato esce anche dal viewer (VT-2).
    idx.applyReplica("comune", listOf(LocalAiIndex.ReplicaItem.Text(key(2), "dev-c", ocrBody("solo di c"))))
    assertThat(idx.textBody(key(2))).isNotNull()
    idx.withdraw("comune", listOf(key(2) to AiClass.OCR))
    assertThat(idx.textBody(key(2))).isNull()
    // E dopo una riapertura e' ancora cosi'.
    idx.close()
    assertThat(index(known = 3).textBody(key(1))!!.lines.single().text).isEqualTo("letto qui")
  }

  /**
   * R5: un calcolo fatto qui sull'anteprima di un altro dispositivo vale solo per la share dell'anteprima, non si ripubblica altrove, e la
   * revoca di chi l'ha preparata lo toglie (torna "da fare"); il calcolo dai propri byte lo sostituisce e vale ovunque.
   */
  @Test
  fun r5_unCalcoloSuUnAnteprimaAltruiValeSoloPerLaSuaShare() = runTest {
    val idx = index()
    val fromB = LocalAiIndex.Derivation.Preview("Comune", "dev-b")
    idx.saveVectors(key(1), vecBody(v(1)), fromB)
    idx.saveText(key(1), ocrBody("documento di b"), fromB)
    assertThat(idx.savedFor(key(1), 1, "comune")).containsExactly(AiClass.VEC, AiClass.OCR)
    assertThat(idx.savedFor(key(1), 1, "privata")).isEmpty()
    assertThat(idx.saved(key(1), 1)).isEmpty()
    assertThat(idx.localVectors(key(1), "privata")).isNull()
    assertThat(idx.localText(key(1), "comune")!!.lines.single().text).isEqualTo("documento di b")
    // La ricerca lo usa comunque (L45 vale per il contenuto).
    assertThat(idx.hasVectors(key(1))).isTrue()
    // La revoca di b: via anche cio' che ne deriva, e il media torna da fare.
    idx.forgetDevice("dev-b")
    assertThat(idx.hasVectors(key(1))).isFalse()
    assertThat(idx.text(key(1))).isNull()
    assertThat(idx.savedFor(key(1), 1, "comune")).isEmpty()
    // Il calcolo dai propri byte vale ovunque e toglie quello su anteprime altrui.
    idx.saveVectors(key(2), vecBody(v(2)), LocalAiIndex.Derivation.Preview("comune", "dev-c"))
    idx.saveVectors(key(2), vecBody(v(3)))
    assertThat(idx.savedFor(key(2), 1, "privata")).containsExactly(AiClass.VEC)
    val back = idx.localVectors(key(2), "comune")!!
    assertThat(cos(FloatArray(768) { dev.pampa.mycloudgallery.core.model.Float16.toFloat(back.vectors.single().component(it)) }, normalized(v(3))))
      .isGreaterThan(0.999f)
    idx.forgetDevice("dev-c")
    assertThat(idx.hasVectors(key(2))).isTrue()
  }

  /** I13: una share scritta con maiuscole diverse e' la stessa share (Samba non distingue le maiuscole). */
  @Test
  fun i13_laShareNonDipendeDalleMaiuscole() = runTest {
    val idx = index()
    idx.applyReplica("Comune", listOf(LocalAiIndex.ReplicaItem.Text(key(1), "dev-b", ocrBody("uno"))))
    idx.applyReplica("comune", listOf(LocalAiIndex.ReplicaItem.Text(key(1), "dev-c", ocrBody("due"))))
    // Un vincitore per share: quello nuovo sostituisce il vecchio anche con un'altra grafia.
    idx.withdraw("COMUNE", listOf(key(1) to AiClass.OCR))
    assertThat(idx.text(key(1))).isNull()
    idx.applyReplica("Comune", listOf(LocalAiIndex.ReplicaItem.Vectors(key(2), "dev-b", vecBody(v(2)))))
    idx.forgetShare("comune")
    assertThat(idx.hasVectors(key(2))).isFalse()
  }

  /**
   * R1: un dispositivo che ricambia a ogni giro vettori a 9 fotogrammi non fa crescere il file senza fine: la sua origine ha un tetto
   * di record (il resto si scarta e si conta) e i record morti si compattano subito, non solo di notte.
   */
  @Test
  fun r1_unDispositivoCheRicambiaIVettoriNonFaCrescereIlFile() = runTest {
    val limits = IndexLimits(maxRecordsPerOrigin = 90, compactDeadRecords = 200)
    val idx = index(limits)
    idx.saveVectors(key(500), vecBody(v(500)))
    repeat(30) { round ->
      idx.applyReplica("comune", (1..20).map { k ->
        LocalAiIndex.ReplicaItem.Vectors(key(k), "dev-b", vecBody(*Array(9) { f -> v(round * 1_000L + k * 10 + f) }))
      })
    }
    val recordSize = 44L + 768 * 2 + 4
    // Al piu' 90 record vivi di b (10 chiavi da 9) piu' quello locale, e i morti mai oltre i vivi piu' la soglia. (Le generazioni vecchie
    // si tolgono subito su Android; sulla JVM di Windows un file ancora mappato resta finche' il collector non lo libera.)
    assertThat(currentVectors().length()).isAtMost((90L + 1 + 200 + 90) * recordSize)
    assertThat(idx.counters.overCap.get()).isGreaterThan(0)
    assertThat(idx.counters.earlyCompactions.get()).isGreaterThan(0)
    assertThat(idx.sizes.first).isEqualTo(11)
    // Il dato calcolato qui non ha tetti e non si perde.
    assertThat(cos(idx.vectors(key(500)).single(), normalized(v(500)))).isGreaterThan(0.999f)
    // La revoca libera il posto: i record morti si tolgono con la compattazione.
    idx.forgetDevice("dev-b")
    idx.maintain()
    assertThat(currentVectors().length()).isEqualTo(recordSize)
  }

  /** R3: il testo di un'origine ha un tetto in byte, e il registro si compatta anche per il solo testo morto (chi ricambia i testi). */
  @Test
  fun r3_ilTestoDiUnOrigineHaUnTettoEIlRegistroSiCompatta() = runTest {
    val limits = IndexLimits(maxTextBytesPerOrigin = 20_000, compactDeadLedgerBytes = 50_000)
    val idx = index(limits)
    val long = (1..40).joinToString("\n") { "riga$it parola$it" }
    repeat(40) { round ->
      idx.applyReplica("comune", (1..10).map { k -> LocalAiIndex.ReplicaItem.Text(key(k), "dev-b", ocrBody("$long giro$round chiave$k")) })
    }
    assertThat(idx.counters.overCap.get()).isGreaterThan(0)
    assertThat(idx.sizes.second).isLessThan(10)
    // Il registro non tiene 400 testi: i morti si tolgono man mano.
    assertThat(File(dir, "ledger.log").length()).isAtMost(3L * (20_000 + 50_000))
    // Il testo calcolato qui entra sempre.
    idx.saveText(key(99), ocrBody(long))
    assertThat(idx.text(key(99))).isNotNull()
  }

  /** R1: un'apertura che fallisce (file illeggibile) ricostruisce l'indice vuoto e chiede di riavere tutto, invece di restare chiusa. */
  @Test
  fun r1_unAperturaFallitaRicostruisceLIndice() = runTest {
    val first = index()
    first.saveVectors(key(1), vecBody(v(1)))
    first.close()
    // Il file dei vettori diventa illeggibile (al posto del file, una cartella).
    val vectors = File(dir, "vectors-${first.space.code}-0.bin")
    vectors.delete()
    vectors.mkdirs()
    File(vectors, "x").writeText("x")
    val again = LocalAiIndex(dir, texts, repair)
    again.open()
    assertThat(repair.all).isEqualTo(1)
    assertThat(again.counters.resets.get()).isEqualTo(1)
    again.applyReplica("comune", listOf(LocalAiIndex.ReplicaItem.Vectors(key(2), "dev-a", vecBody(v(2)))))
    assertThat(again.hasVectors(key(2))).isTrue()
  }

  /** R2: una scrittura grande del registro si divide in piu' cornici, e si rilegge tutta. */
  @Test
  fun r2_unaScritturaGrandeDelRegistroSiDivideInPiuCornici() {
    val file = File(tmp.newFolder("ledger"), "ledger.log")
    val ledger = OriginLedger(file, frameTarget = 32 * 1024)
    val big = "parola ".repeat(1_500).trim()
    val ops = (1..100).map { k ->
      LedgerOp.PutText(AiOrigin.replica("comune", "dev-b"), key(k), 1, 1, 1, 1, big.length, "$big $k", byteArrayOf(1, 2, 3))
    }
    ledger.open()
    ledger.commit(ops)
    ledger.close()
    // Piu' cornici: ognuna sotto il tetto chiesto (piu' un margine per l'ultima voce).
    val again = OriginLedger(file)
    val report = again.open()
    assertThat(report.frames).isGreaterThan(10)
    assertThat(report.lost).isFalse()
    assertThat(again.texts).hasSize(100)
    assertThat(again.readText(again.texts.getValue(key(77)).values.single().text!!)).isEqualTo("$big 77")
    assertThat(again.readBody(again.texts.getValue(key(77)).values.single().text!!)).isEqualTo(byteArrayOf(1, 2, 3))
    again.close()
  }
}
