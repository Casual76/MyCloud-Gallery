package dev.pampa.mycloudgallery.core.faces.eval

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * La regola delle soglie sul **set pubblico** (riga P11 del §4.4; §5.3 punto 1; Q4): i ritratti di pubblico dominio e le composizioni di P1, passati da SFace vero
 * (ONNX fp32: lo stesso vettore del `.tflite` float16, coseno >= 0,999995) da `tools/fixtures/faces/misure_pubbliche.py`. Il file `osservazioni-pubbliche.tsv` ha
 * **solo numeri**: per ogni volto e ogni persona (un soggetto = un gruppo), la somiglianza del volto al gruppo come la calcola il raggruppamento (media dei 3
 * coseni piu' alti con gli esemplari), quella alla seconda persona e se il volto e' davvero di quella persona. Sono i numeri su cui le soglie **provvisorie** (Q4:
 * la misura sui dati veri la fa l'utente piu' avanti, F105) sono dichiarate in `docs/decisioni.md`.
 */
class FacesEvalPublicSetTest {

  private val totals = EvalTotals(coreFaces = 1_000, coreAssigned = 800, weakFaces = 400, weakAssigned = 200, groupsShown = 30)

  private fun observations(): List<JoinObservation> {
    val text = javaClass.getResourceAsStream("/dev/pampa/mycloudgallery/core/faces/eval/osservazioni-pubbliche.tsv")!!.bufferedReader(Charsets.UTF_8).readText()
    return text.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.drop(1).map { line ->
      val c = line.split('\t')
      JoinObservation(c[0].removePrefix("g").toInt(), c[2].toFloat(), c[3].takeIf { it.isNotEmpty() }?.toFloat(), c[1] == "weak", c[4] == "1")
    }.toList()
  }

  @Test
  fun ilFileHaSoloNumeriEIGruppiSonoSigle() {
    val text = javaClass.getResourceAsStream("/dev/pampa/mycloudgallery/core/faces/eval/osservazioni-pubbliche.tsv")!!.bufferedReader(Charsets.UTF_8).readText()
    val rows = text.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.toList()
    assertThat(rows.first()).isEqualTo("gruppo\tlivello\tpunteggio\tseconda\tgiusto")
    val body = rows.drop(1)
    assertThat(body.size).isGreaterThan(1_000)
    assertThat(body.all { Regex("g[0-9]{2}\t(core|weak)\t-?[0-9]\\.[0-9]{4}\t(-?[0-9]\\.[0-9]{4})?\t[01]").matches(it) }).isTrue()
    // Niente di cio' che identifica un volto, una foto o una persona: nessun id, nessun posto, nessuna etichetta di ritratto.
    assertThat(text).doesNotContain("ritratto-")
    assertThat(text).doesNotContain("img-")
    assertThat(text).doesNotContain("-4000-")
  }

  @Test
  fun ilSetHaGruppiVeriEImpostoriEIVoltiPiccoliSonoWeak() {
    val obs = observations()
    assertThat(obs.map { it.group }.distinct().size).isAtLeast(15)
    assertThat(obs.count { it.right }).isGreaterThan(50)
    assertThat(obs.count { !it.right }).isGreaterThan(500)
    assertThat(obs.count { it.weak }).isGreaterThan(100)
    assertThat(obs.count { !it.weak }).isGreaterThan(100)
    // I punteggi sono coseni: fra -1 e 1.
    assertThat(obs.all { it.score in -1f..1f && (it.second ?: 0f) in -1f..1f }).isTrue()
  }

  @Test
  fun leSoglieDiPartenzaNonTengonoIlNovantottoECinqueSulSetPubblico() {
    val obs = observations()
    val start = ThresholdValues()
    val purity = FacesEvalThresholds.purity(obs, start.tJoin, start.tJoinWeak, start.margin)
    println("Set pubblico, soglie di partenza: purezza ${FacesEvalMetrics.pct(purity)} su ${obs.map { it.group }.distinct().size} gruppi")
    // Dipinti antichi e SFace: il set e' piu' difficile dei vettori sintetici (che qui tengono il 100%). E' il motivo per cui le soglie vere si misurano sul telefono.
    assertThat(purity).isLessThan(FacesEvalThresholds.TARGET_PURITY)
  }

  @Test
  fun laRegolaSulSetPubblicoDaLeSoglieProvvisorie() {
    val obs = observations()
    val p = FacesEvalThresholds.choose(obs, emptyList(), 0, ThresholdValues(), totals)
    println("Set pubblico: ${p.values}")
    println(FacesEvalThresholds.toNumbersText(p))
    assertThat(p.changes).isTrue()
    assertThat(p.values.tJoin).isAtLeast(ThresholdValues().tJoin)
    assertThat(p.values.tJoinWeak).isAtLeast(p.values.tJoin)
    assertThat(p.purityAtProposal).isAtLeast(p.purityAtCurrent)
    // Ogni gruppo ha la sua somiglianza: la proposta e' una soglia vera, non un valore ai bordi della griglia per un caso.
    assertThat(p.values.tJoin).isLessThan(0.90f)
  }

  @Test
  fun laRegolaECoerenteConLaPurezzaMisurataDallOsservazione() {
    val obs = observations()
    val p = FacesEvalThresholds.choose(obs, emptyList(), 0, ThresholdValues(), totals)
    // Ricalcolando a mano la purezza con le soglie proposte si ritrova quella dichiarata, e se la regola dice "raggiungibile" tiene il 98,5%.
    val again = FacesEvalThresholds.purity(obs, p.values.tJoin, p.values.tJoinWeak, p.values.margin)
    assertThat(again).isEqualTo(p.purityAtProposal)
    if (p.joinReachable) assertThat(again).isAtLeast(FacesEvalThresholds.TARGET_PURITY - 1e-9)
    // La soglia subito sotto quella scelta (CORE) non basterebbe: e' davvero "il valore piu' basso" a parita' di margine.
    if (p.joinReachable && p.values.tJoin > ThresholdValues().tJoin) {
      val lower = p.values.tJoin - 0.01f
      val core = obs.filter { !it.weak }
      assertThat(FacesEvalThresholds.purity(core, lower, lower, p.values.margin)).isLessThan(FacesEvalThresholds.TARGET_PURITY)
    }
  }
}
