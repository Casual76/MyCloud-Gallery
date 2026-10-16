package dev.pampa.mycloudgallery.core.library.similar

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.MediaGroupId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.VisualSignature
import org.junit.Test

/**
 * Il pianificatore puro (Fase 5A, P15; §2.12 condizioni 3-7, E-L12): chi sta con chi dentro un secondo di scatto. Le firme sono di
 * "disegni" (`pattern`): uguali a meno di un po' di rumore per le copie, lontane per gli altri scatti.
 */
class SimilarGroupPlannerTest {

  private val none = SeparatedPairs.NONE

  private fun plan(vararg entries: Pair<dev.pampa.mycloudgallery.core.database.SimilarCandidate, VisualSignature?>, apart: SeparatedPairs = none): RunPlan =
    SimilarGroupPlanner.plan(runOf(*entries.map { it.first }.toTypedArray()), signaturesOf(*entries), apart)

  private fun ids(vararg c: dev.pampa.mycloudgallery.core.database.SimilarCandidate): Set<MediaId> = c.map { it.mediaId }.toSet()

  @Test
  fun dueCopieDelloStessoScattoFannoUnGruppo() {
    val a = candidate()
    val b = candidate()
    val plan = plan(a to signatureOf(1), b to signatureOf(1, noise = 2, noiseSeed = 9))
    assertThat(plan.groups.map { it.toSet() }).containsExactly(ids(a, b))
    assertThat(plan.leaving).isEmpty()
    assertThat(plan.ambiguous).isEmpty()
  }

  @Test
  fun treCopieFannoUnGruppoSoloEDueScattiDiversiNellaStessaSecondaDueGruppi() {
    val a = candidate()
    val b = candidate()
    val c = candidate()
    val d = candidate()
    val e = candidate()
    val plan = plan(
      a to signatureOf(1), b to signatureOf(1, 2, 1), c to signatureOf(1, 3, 2),
      d to signatureOf(2), e to signatureOf(2, 2, 5),
    )
    assertThat(plan.groups.map { it.toSet() }).containsExactly(ids(a, b, c), ids(d, e))
  }

  @Test
  fun unoScattoDiversoNonSiUnisce() {
    val a = candidate()
    val b = candidate()
    val plan = plan(a to signatureOf(1), b to signatureOf(2))
    assertThat(plan.groups).isEmpty()
    assertThat(plan.ambiguous).isEmpty()
  }

  /** La firma [sig] con la luminosita' di ogni cella spostata di [levels] livelli (un'esposizione quasi invisibile): la distanza e' levels/255. */
  private fun shifted(sig: VisualSignature, levels: Int): VisualSignature {
    val bytes = sig.bytes()
    for (i in 0 until VisualSignatures.LUMA_SIDE * VisualSignatures.LUMA_SIDE) {
      bytes[i] = ((bytes[i].toInt() and 0xFF) + levels).coerceIn(0, 255).toByte()
    }
    return VisualSignature.of(sig.algorithm, sig.frames, bytes)
  }

  @Test
  fun unMediaCheSomigliaADueMediaNonSimiliFraLoroNonEntraInNessunGruppo() {
    // A sta in mezzo: B e' un livello sopra, C un livello sotto. A~B (0,0039) e A~C, ma B e C distano 0,0078 > 0,0075.
    val base = signatureOf(1)
    val a = candidate()
    val b = candidate()
    val c = candidate()
    assertThat(SimilarityRules.visual(base, shifted(base, 1))).isEqualTo(VisualVerdict.SAME)
    assertThat(SimilarityRules.visual(shifted(base, 1), shifted(base, -1))).isEqualTo(VisualVerdict.DIFFERENT)
    val plan = plan(a to base, b to shifted(base, 1), c to shifted(base, -1))
    assertThat(plan.groups).isEmpty()
    assertThat(plan.ambiguous).containsExactly(a.mediaId)
    assertThat(plan.leaving).isEmpty()
    // B e C da soli, senza A, restano due media distinti.
    assertThat(plan(b to shifted(base, 1), c to shifted(base, -1)).groups).isEmpty()
    // Con un quarto media D vicino a tutti e tre (a meta' strada fra B e C, e' A stesso): la catena resta ambigua, non si unisce a caso.
    val d = candidate()
    val withD = plan(a to base, b to shifted(base, 1), c to shifted(base, -1), d to shifted(base, 0))
    assertThat(withD.groups).isEmpty()
    assertThat(withD.ambiguous).containsExactly(a.mediaId, d.mediaId)
  }

  @Test
  fun unaCatenaAbCDoveAEdNonSonoSimiliLasciaFuoriIlMezzo() {
    val base = signatureOf(2)
    val a = candidate()
    val b = candidate()
    val c = candidate()
    // A=-1, B=0, C=+1: A~B e B~C, ma A!~C. B somiglia a due media non simili fra loro.
    val plan = plan(a to shifted(base, -1), b to base, c to shifted(base, 1))
    assertThat(plan.groups).isEmpty()
    assertThat(plan.ambiguous).containsExactly(b.mediaId)
  }

  @Test
  fun unGruppoGiaFattoNonSiDisfaPerUnArrivoAmbiguoEUnArrivoSimileATuttiEntra() {
    val g = MediaGroupId.random()
    val a = candidate(group = g)
    val b = candidate(group = g)
    val sameAsBoth = candidate()
    val plan = plan(a to signatureOf(1), b to signatureOf(1, 2, 3), sameAsBoth to signatureOf(1, 2, 4))
    assertThat(plan.groups.map { it.toSet() }).containsExactly(ids(a, b, sameAsBoth))
    val different = candidate()
    val keep = plan(a to signatureOf(1), b to signatureOf(1, 2, 3), different to signatureOf(9))
    assertThat(keep.groups.map { it.toSet() }).containsExactly(ids(a, b))
    assertThat(keep.leaving).isEmpty()
  }

  @Test
  fun laSeparazioneDellUtenteVinceSullaSomiglianzaPerSempre() {
    val a = candidate()
    val b = candidate()
    val sigs = arrayOf(a to signatureOf(1), b to signatureOf(1, 2, 3))
    assertThat(plan(*sigs).groups).hasSize(1)
    val apart = SeparatedPairs(listOf(MediaPair(a.mediaId, b.mediaId)))
    val after = plan(*sigs, apart = apart)
    assertThat(after.groups).isEmpty()
    // E vale in entrambe le direzioni, e un gruppo che c'era si scioglie.
    assertThat(apart.contains(b.mediaId, a.mediaId)).isTrue()
    val g = MediaGroupId.random()
    val ga = candidate(id = a.mediaId, version = a.versionId, group = g)
    val gb = candidate(id = b.mediaId, version = b.versionId, group = g)
    val dissolved = plan(ga to signatureOf(1), gb to signatureOf(1, 2, 3), apart = apart)
    assertThat(dissolved.groups).isEmpty()
    assertThat(dissolved.leaving).containsExactly(a.mediaId, b.mediaId)
  }

  @Test
  fun separandoUnaCopiaDaTreRestanoInsiemeLeAltreDue() {
    val g = MediaGroupId.random()
    val a = candidate(group = g)
    val b = candidate(group = g)
    val c = candidate(group = g)
    val apart = SeparatedPairs(listOf(MediaPair(a.mediaId, b.mediaId), MediaPair(a.mediaId, c.mediaId)))
    val plan = plan(a to signatureOf(1), b to signatureOf(1, 2, 3), c to signatureOf(1, 2, 4), apart = apart)
    assertThat(plan.groups.map { it.toSet() }).containsExactly(ids(b, c))
    assertThat(plan.leaving).containsExactly(a.mediaId)
  }

  @Test
  fun proporzioneDurataESottosecondiContano() {
    val photo = candidate()
    val portrait = candidate(aspect = 750)
    assertThat(plan(photo to signatureOf(1), portrait to signatureOf(1)).groups).isEmpty()
    // Entro l'1%: unite; oltre: no.
    assertThat(plan(photo to signatureOf(1), candidate(aspect = 1340) to signatureOf(1, 2, 3)).groups).hasSize(1)
    assertThat(plan(photo to signatureOf(1), candidate(aspect = 1350) to signatureOf(1, 2, 3)).groups).isEmpty()
    // Sottosecondi: entrambi scritti e diversi (una raffica) = no; uno senza = decide la firma.
    val burst1 = candidate(takenAt = SECOND * 1000 + 120)
    val burst2 = candidate(takenAt = SECOND * 1000 + 450)
    assertThat(plan(burst1 to signatureOf(1), burst2 to signatureOf(1, 2, 3)).groups).isEmpty()
    val sameSub = candidate(takenAt = SECOND * 1000 + 120)
    assertThat(plan(burst1 to signatureOf(1), sameSub to signatureOf(1, 2, 3)).groups).hasSize(1)
    assertThat(plan(burst1 to signatureOf(1), photo to signatureOf(1, 2, 3)).groups).hasSize(1)
    // Video: durata a meno di 0,5 s.
    val v1 = candidate(kind = MediaKind.VIDEO, duration = 8_000)
    val v2 = candidate(kind = MediaKind.VIDEO, duration = 8_400)
    val v3 = candidate(kind = MediaKind.VIDEO, duration = 8_600)
    val v4 = candidate(kind = MediaKind.VIDEO, duration = null)
    assertThat(plan(v1 to signatureOf(1), v2 to signatureOf(1, 2, 3)).groups).hasSize(1)
    assertThat(plan(v1 to signatureOf(1), v3 to signatureOf(1, 2, 3)).groups).isEmpty()
    assertThat(plan(v1 to signatureOf(1), v4 to signatureOf(1, 2, 3)).groups).isEmpty()
  }

  @Test
  fun unaFirmaMancanteRimandaIlMediaEUnGruppoConUnMembroSenzaFirmaNonSiTocca() {
    val a = candidate()
    val b = candidate()
    val missing = plan(a to signatureOf(1), b to null)
    assertThat(missing.groups).isEmpty()
    assertThat(missing.deferred).containsExactly(b.mediaId)
    // Il gruppo A,B,C ha C senza firma: nessuno dei tre esce e niente cambia.
    val g = MediaGroupId.random()
    val ga = candidate(group = g)
    val gb = candidate(group = g)
    val gc = candidate(group = g)
    val frozen = plan(ga to signatureOf(5), gb to signatureOf(9), gc to null)
    assertThat(frozen.leaving).isEmpty()
    assertThat(frozen.groups).isEmpty()
    assertThat(frozen.deferred).containsExactly(ga.mediaId, gb.mediaId, gc.mediaId)
    // Una firma di un altro algoritmo vale "manca" (si ricalcola).
    val alien = VisualSignature.of(2, 1, ByteArray(100) { 1 })
    assertThat(plan(a to signatureOf(1), b to alien).deferred).containsExactly(b.mediaId)
  }

  @Test
  fun unGruppoConUnSoloMembroPresenteNonSiDisfaPerUnArrivoDiverso() {
    // Gli altri membri non sono raggiungibili adesso (NAS spento): il solo presente resta nel suo gruppo anche se il vicino e' diverso.
    val g = MediaGroupId.random()
    val a = candidate(group = g)
    val stranger = candidate()
    val plan = plan(a to signatureOf(1), stranger to signatureOf(8))
    assertThat(plan.leaving).isEmpty()
    val kept = SimilarGroupPlanner.plan(runOf(a, stranger), signaturesOf(a to signatureOf(1), stranger to signatureOf(8)), none)
    assertThat(kept.kept).containsExactly(a.mediaId)
    // Se l'arrivo e' simile, il gruppo si allarga (e chi chiama porta con se' i membri assenti).
    val joined = plan(a to signatureOf(1), candidate() to signatureOf(1, 2, 6))
    assertThat(joined.groups).hasSize(1)
  }

  @Test
  fun unSecondoConTroppiCandidatiNonSiDecideNiente() {
    val many = List(SimilarityRules.MAX_RUN + 1) { candidate() }
    val plan = SimilarGroupPlanner.plan(SimilarRunFor(many), many.associate { it.versionId to signatureOf(1) }, none)
    assertThat(plan.groups).isEmpty()
    assertThat(plan.ambiguous).hasSize(many.size)
    assertThat(SimilarGroupPlanner.needSignature(SimilarRunFor(many), none)).isEmpty()
  }

  @Test
  fun siFirmanoSoloICandidatiConUnCompagnoCompatibile() {
    val a = candidate()
    val b = candidate()
    val lonelyPortrait = candidate(aspect = 560)
    val run = SimilarRunFor(listOf(a, b, lonelyPortrait))
    assertThat(SimilarGroupPlanner.needSignature(run, none).map { it.mediaId }).containsExactly(a.mediaId, b.mediaId)
    // Se l'utente li ha separati, nessuno dei due ha piu' un compagno: non si firma niente.
    val apart = SeparatedPairs(listOf(MediaPair(a.mediaId, b.mediaId)))
    assertThat(SimilarGroupPlanner.needSignature(run, apart)).isEmpty()
  }

  @Test
  fun dueGruppiVicinissimiSiUniscono() {
    val g1 = MediaGroupId.random()
    val g2 = MediaGroupId.random()
    val a = candidate(group = g1)
    val b = candidate(group = g1)
    val c = candidate(group = g2)
    val d = candidate(group = g2)
    val plan = plan(a to signatureOf(1), b to signatureOf(1, 2, 3), c to signatureOf(1, 2, 4), d to signatureOf(1, 2, 5))
    assertThat(plan.groups.map { it.toSet() }).containsExactly(ids(a, b, c, d))
  }

  @Test
  fun unaFotoSenzaStrutturaNonSiUnisceMaiAdUnAltra() {
    val flat = Raster(128, 96, IntArray(128 * 96) { 0x808080 })
    val flat2 = Raster(128, 96, IntArray(128 * 96) { 0x818181 })
    val a = candidate()
    val b = candidate()
    val plan = plan(a to VisualSignatures.of(flat), b to VisualSignatures.of(flat2))
    assertThat(plan.groups).isEmpty()
  }

  /** Un secondo di candidati qualunque (la `SimilarRun` accetta anche piu' di MAX_RUN). */
  private fun SimilarRunFor(candidates: List<dev.pampa.mycloudgallery.core.database.SimilarCandidate>) =
    dev.pampa.mycloudgallery.core.database.SimilarRun(candidates.first().second, candidates)
}
