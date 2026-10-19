package dev.pampa.mycloudgallery.core.backup.chaos

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.model.Sha256
import java.io.File
import org.junit.Test

/**
 * Il verificatore degli invarianti della 5B ([FiveBVerifier]) invariante per invariante, con stati fatti a mano (anche un solo byte diverso lo
 * rompe), e **da solo su uno stato salvato** in un file ([FiveBStateFile]): e' cio' che si fa con `build/chaos/backup5b-state-<giro>.txt` di un giro
 * rosso, senza il banco, i servizi o il NAS.
 */
class FiveBVerifierTest {

  private val device = BackupChaosWorld.DEVICE
  private val usual = "Backup/S25 di prova/2024/01/FOTO5B_01.jpg"
  private val archived = "Versioni/Backup/S25 di prova/2024/01/FOTO5B_01 (originale).jpg"

  private fun sha(n: Int) = Sha256.of(byteArrayOf(n.toByte(), 5, 5))

  private fun file(path: String, n: Int, id: Long, share: String = "test", size: Long = 1000L + n) = NasFileFact(share, path, size, id, sha(n))

  private fun tmp(id: Int) = ".mycloudgallery/tmp/$device-bk-$id"

  /** Uno stato in ordine: un media con l'originale in `Versioni/` e la versione modificata al solito percorso, un file vecchio che non si e' mosso. */
  private class Draft {
    val before = ArrayList<NasFileFact>()
    val files = ArrayList<NasFileFact>()
    val known = LinkedHashSet<Sha256>()
    val lost = LinkedHashSet<Sha256>()
    val media = ArrayList<FiveBMediaFact>()
    val jobs = ArrayList<FiveBJobFact>()
    val transitions = ArrayList<FiveBTransitionFact>()
    val cited = LinkedHashSet<String>()
    val free = ArrayList<FiveBFreeFact>()
    val notes = ArrayList<Note>()
    var cacheHits = 0

    fun build(device: String) = FiveBState(
      device, "test", "test-comune", before, files, known, lost, media, jobs, transitions, cited, free, notes, cacheHits, files.size,
    )
  }

  private fun media(
    d: Draft,
    current: String = "v1",
    tampered: Boolean = false,
    pc: List<Sha256> = emptyList(),
    phone: List<Sha256> = listOf(sha(2)),
    copies: List<FiveBCopyFact> = listOf(
      FiveBCopyFact("v0", "test", archived, 1001, verified = true, present = true),
      FiveBCopyFact("v1", "test", usual, 1002, verified = true, present = true),
    ),
  ) = FiveBMediaFact(
    "m1", true, "edited_safe", current,
    versions = listOf(
      FiveBVersionFact("v0", "original", 1001, sha(1), sha(1), seenOnNas = true),
      FiveBVersionFact("v1", "edited", 1002, sha(2), sha(2), seenOnNas = true),
    ),
    copies = copies, usualShare = "test", usualPath = usual, tampered = tampered, pcShas = pc, phoneShas = phone, stagedShas = emptyList(),
  )

  private fun healthy(): Draft {
    val d = Draft()
    val old = file("Documenti/a.txt", 9, 90)
    val original = file(archived, 1, 11)
    val edited = file(usual, 2, 12)
    d.before += old
    // L'originale c'era al solito percorso e adesso e' in Versioni/ (stesso `fileId`, stessi byte): una archiviazione.
    d.before += file(usual, 1, 11)
    d.files += listOf(old, original, edited)
    d.known += listOf(sha(1), sha(2), sha(9))
    d.media += media(d)
    d.jobs += FiveBJobFact(1_000_001, "version", "done", "m1", "v1", null, null, tmp(1_000_001))
    d.transitions += listOf("planned" to "planned", "planned" to "hashed", "hashed" to "uploading", "uploading" to "uploaded", "uploaded" to "verified", "verified" to "archiving", "archiving" to "archived", "archived" to "publishing", "publishing" to "published", "published" to "linked", "linked" to "done")
      .map { FiveBTransitionFact(1_000_001, "version", it.first, it.second) }
    return d
  }

  private fun violations(d: Draft, phase: Phase) = FiveBVerifier.check(d.build(device), phase)

  private fun assertOnly(vs: List<Violation>, vararg expected: Invariant) {
    assertWithMessage("violazioni: $vs").that(vs.map { it.invariant }.toSet()).isEqualTo(expected.toSet())
  }

  @Test
  fun unoStatoInOrdineNonHaViolazioni() {
    assertThat(violations(healthy(), Phase.MID)).isEmpty()
    assertThat(violations(healthy(), Phase.FINAL)).isEmpty()
  }

  @Test
  fun inv3_unaSostituzioneUnaSparizioneEUnoSpostamentoFuoriDaVersioni() {
    // Byte cambiati in un file che c'era: stesso `fileId`, altra impronta.
    val replaced = healthy()
    replaced.files[0] = NasFileFact("test", "Documenti/a.txt", 1009, 90, sha(50))
    replaced.known += sha(50)
    assertOnly(violations(replaced, Phase.MID), Invariant.REPLACED)
    // Un file di prima che sparisce.
    val gone = healthy()
    gone.files.removeAt(0)
    assertOnly(violations(gone, Phase.MID), Invariant.REPLACED)
    // Un file di prima spostato, ma non da o verso Versioni/.
    val moved = healthy()
    moved.files[0] = NasFileFact("test", "Altrove/a.txt", 1009, 90, sha(9))
    assertThat(violations(moved, Phase.MID).map { it.invariant }).contains(Invariant.REPLACED)
    // Il file archiviato che perde il suo `fileId`: un'altra copia al suo posto (una sostituzione).
    val swapped = healthy()
    swapped.files[1] = NasFileFact("test", archived, 1001, 77, sha(1))
    assertThat(violations(swapped, Phase.MID).map { it.invariant }).contains(Invariant.REPLACED)
  }

  @Test
  fun inv1_unFileVisibileConByteDiNessunContenutoEUnFileAMeta() {
    val d = healthy()
    d.files += file("Backup/S25 di prova/2024/01/FOTO5B_02.jpg", 33, 70)
    assertOnly(violations(d, Phase.MID), Invariant.HALF_FILE)
  }

  @Test
  fun inv2_inv10_unDoppioneEUnFileFuoriDalleRadici() {
    val dup = healthy()
    dup.files += file("Backup/S25 di prova/2024/01/FOTO5B_01 (2).jpg", 9, 71)
    assertThat(violations(dup, Phase.MID).map { it.invariant }).contains(Invariant.DUPLICATE)
    val out = healthy()
    out.files += file("Altro/x.jpg", 2, 72)
    out.known += sha(2)
    assertThat(violations(out, Phase.MID).map { it.invariant }).contains(Invariant.OUT_OF_PLACE)
  }

  @Test
  fun inv5_6_laRilettura_ITemporanei() {
    val cache = healthy().also { it.cacheHits = 2 }
    assertOnly(violations(cache, Phase.FINAL), Invariant.HASH_100)
    val temp = healthy()
    temp.files += file(tmp(5), 2, 80)
    // A meta' un temporaneo non citato e' una violazione, uno citato no; a lavoro finito nessuno.
    assertOnly(violations(temp, Phase.MID), Invariant.TEMP)
    temp.cited += tmp(5)
    assertThat(violations(temp, Phase.MID)).isEmpty()
    assertOnly(violations(temp, Phase.FINAL), Invariant.TEMP)
    // Il temporaneo di un altro dispositivo non e' del backup.
    val foreign = healthy()
    foreign.files += file(".mycloudgallery/tmp/ZZZZZZZZZZZZZZZZZZZZZZZZZZ-bk-3", 2, 81)
    assertOnly(violations(foreign, Phase.MID), Invariant.TEMP)
  }

  @Test
  fun inv7_ilCatalogoDiceIlVeroSulNas() {
    val missing = healthy()
    missing.media[0] = media(missing, copies = listOf(FiveBCopyFact("v0", "test", archived, 1001, true, true), FiveBCopyFact("v1", "test", "Backup/altro.jpg", 1002, true, true)))
    assertOnly(violations(missing, Phase.FINAL), Invariant.CATALOG)
    val size = healthy()
    size.media[0] = media(size, copies = listOf(FiveBCopyFact("v0", "test", archived, 1001, true, true), FiveBCopyFact("v1", "test", usual, 5000, true, true)))
    assertOnly(violations(size, Phase.FINAL), Invariant.CATALOG)
    // Una copia verificata ha i byte della sua versione: altri byte e' una verifica che mente.
    val wrong = healthy()
    wrong.files[1] = NasFileFact("test", archived, 1001, 11, sha(60))
    wrong.known += sha(60)
    assertThat(violations(wrong, Phase.FINAL).map { it.invariant }).contains(Invariant.CATALOG)
    // Un byte marcito con lo stesso timbro non e' colpa dei servizi: non si rimprovera al catalogo.
    wrong.media[0] = media(wrong, tampered = true)
    assertThat(violations(wrong, Phase.FINAL).map { it.invariant }).doesNotContain(Invariant.CATALOG)
    // A meta' un media con un lavoro aperto puo' avere la rinomina fatta e il catalogo ancora da aggiornare.
    val open = healthy()
    open.jobs[0] = FiveBJobFact(1_000_001, "version", "archived", "m1", "v1", null, null, tmp(1_000_001))
    open.cited += tmp(1_000_001)
    open.media[0] = media(open, copies = listOf(FiveBCopyFact("v0", "test", usual, 1001, true, true), FiveBCopyFact("v1", "test", usual, 1002, false, false)))
    assertThat(violations(open, Phase.MID).map { it.invariant }).doesNotContain(Invariant.CATALOG)
  }

  @Test
  fun inv8_inv9_ilDiarioEIlLavoroArrivaInFondo() {
    val badStep = healthy()
    badStep.transitions += FiveBTransitionFact(1_000_001, "version", "done", "planned")
    assertOnly(violations(badStep, Phase.MID), Invariant.DIARY)
    val open = healthy()
    open.jobs[0] = FiveBJobFact(1_000_001, "version", "archiving", "m1", "v1", "source_changed", null, null)
    assertThat(violations(open, Phase.MID).map { it.invariant }).doesNotContain(Invariant.CONVERGENCE)
    assertThat(violations(open, Phase.FINAL).map { it.invariant }).contains(Invariant.CONVERGENCE)
    val unexpected = healthy()
    unexpected.jobs[0] = FiveBJobFact(1_000_001, "version", "failed", "m1", "v1", null, "unexpected", null)
    assertThat(violations(unexpected, Phase.FINAL).map { it.invariant }).contains(Invariant.CONVERGENCE)
  }

  @Test
  fun inv12_loStatoDelSolitoPercorso() {
    // Il solito percorso vuoto, con la versione attuale che c'e' ancora sul telefono.
    val empty = healthy()
    empty.files.removeAt(2)
    empty.media[0] = media(empty, copies = listOf(FiveBCopyFact("v0", "test", archived, 1001, true, true)))
    assertThat(violations(empty, Phase.FINAL).map { it.invariant }).contains(Invariant.USUAL_PATH)
    // Altri byte al solito percorso.
    val wrong = healthy()
    wrong.files[2] = NasFileFact("test", usual, 1002, 12, sha(61))
    wrong.known += sha(61)
    assertThat(violations(wrong, Phase.FINAL).map { it.invariant }).contains(Invariant.USUAL_PATH)
    // I byte del PC al solito percorso: l'app non li sposta finche' nessuno li ha riletti e verificati.
    wrong.media[0] = media(wrong, pc = listOf(sha(61)))
    assertThat(violations(wrong, Phase.FINAL).map { it.invariant }).doesNotContain(Invariant.USUAL_PATH)
    // Se i byte della versione attuale non ci sono piu' da nessuna parte (la riga del telefono e' sparita) non si pretende.
    val lost = healthy()
    lost.files.removeAt(2)
    lost.media[0] = media(lost, phone = emptyList(), copies = listOf(FiveBCopyFact("v0", "test", archived, 1001, true, true)))
    assertThat(violations(lost, Phase.FINAL).map { it.invariant }).doesNotContain(Invariant.USUAL_PATH)
  }

  @Test
  fun inv13_leVersioniSiConservano() {
    // Una versione che era sul NAS e non c'e' piu'.
    val lost = healthy()
    lost.files.removeAt(1)
    lost.before.removeAt(1)
    val ids = violations(lost, Phase.MID).map { it.invariant }
    assertThat(ids).contains(Invariant.VERSIONS_KEPT)
    // Non se i byte li ha sovrascritti il PC.
    lost.lost += sha(1)
    assertThat(violations(lost, Phase.MID).map { it.invariant }).doesNotContain(Invariant.VERSIONS_KEPT)
    // A lavoro finito una versione non attuale sta in `Versioni/`: al solito percorso o altrove no.
    val misplaced = healthy()
    misplaced.files[1] = NasFileFact("test", "Backup/S25 di prova/2024/01/FOTO5B_01 (copia).jpg", 1001, 11, sha(1))
    assertThat(violations(misplaced, Phase.FINAL).map { it.invariant }).contains(Invariant.VERSIONS_KEPT)
  }

  @Test
  fun inv14_laContabilitaDelleRigheTolteDalTelefono() {
    val untraced = healthy()
    untraced.free += FiveBFreeFact(1, 0, "m1", "verified", removedFromPhone = true, rowOnPhone = false)
    assertOnly(violations(untraced, Phase.MID), Invariant.FREE_SPACE)
    val freedButHere = healthy()
    freedButHere.free += FiveBFreeFact(1, 0, "m1", "freed", removedFromPhone = false, rowOnPhone = true)
    assertOnly(violations(freedButHere, Phase.MID), Invariant.FREE_SPACE)
    val keptButGone = healthy()
    keptButGone.free += FiveBFreeFact(1, 0, "m1", "kept", removedFromPhone = true, rowOnPhone = false)
    assertThat(violations(keptButGone, Phase.MID).map { it.invariant }).contains(Invariant.FREE_SPACE)
    val inDialog = healthy()
    inDialog.free += FiveBFreeFact(1, 0, "m1", "in_dialog", removedFromPhone = true, rowOnPhone = false)
    assertThat(violations(inDialog, Phase.MID)).isEmpty()
    assertOnly(violations(inDialog, Phase.FINAL), Invariant.FREE_SPACE)
    val ok = healthy()
    ok.free += FiveBFreeFact(1, 0, "m1", "freed", removedFromPhone = true, rowOnPhone = false)
    ok.free += FiveBFreeFact(1, 1, "m1", "in_doubt", removedFromPhone = true, rowOnPhone = false)
    ok.free += FiveBFreeFact(1, 2, "m1", "kept", removedFromPhone = false, rowOnPhone = true)
    assertThat(violations(ok, Phase.FINAL)).isEmpty()
  }

  @Test
  fun leNoteDelBancoPassanoComeViolazioni() {
    val d = healthy()
    d.notes += Note(Invariant.FREE_SPACE, "un lotto con una copia che non ha i byte")
    d.notes += Note(Invariant.CONTRACT, "un servizio ha lanciato")
    assertOnly(violations(d, Phase.MID), Invariant.FREE_SPACE, Invariant.CONTRACT)
  }

  @Test
  fun unoStatoSalvatoSiRileggeESiVerificaDaSolo() {
    val file = File.createTempFile("mcg-5b-state", ".txt")
    try {
      val bad = healthy()
      bad.files.removeAt(2)
      bad.media[0] = media(bad, copies = listOf(FiveBCopyFact("v0", "test", archived, 1001, true, true)), pc = listOf(sha(3)))
      bad.free += FiveBFreeFact(1, 0, "m1", "kept", removedFromPhone = true, rowOnPhone = false)
      bad.notes += Note(Invariant.FREE_SPACE, "una verifica scaduta")
      bad.jobs += FiveBJobFact(1_000_002, "swap", "archiving", "m1", "v0", "rolled_back", "unexpected", tmp(2))
      bad.cited += tmp(2)
      val state = bad.build(device)
      FiveBStateFile.write(state, file)
      assertThat(FiveBStateFile.isFiveB(file)).isTrue()
      val back = FiveBStateFile.read(file)
      for (phase in Phase.entries) {
        assertThat(FiveBVerifier.check(back, phase).map { it.toString() }).isEqualTo(FiveBVerifier.check(state, phase).map { it.toString() })
      }
      assertThat(FiveBVerifier.check(back, Phase.FINAL)).isNotEmpty()
      // Lo stato sano, riletto, resta sano.
      FiveBStateFile.write(healthy().build(device), file)
      assertThat(FiveBVerifier.check(FiveBStateFile.read(file), Phase.FINAL)).isEmpty()
    } finally {
      file.delete()
    }
  }

  @Test
  fun senzaNomiIlRapportoNonDiceIPercorsi() {
    val d = healthy()
    d.files += file("Backup/S25 di prova/2024/01/FOTO5B_02.jpg", 33, 70)
    val text = FiveBVerifier.check(d.build(device), Phase.MID, showNames = false).joinToString { it.toString() }
    assertThat(text).doesNotContain("FOTO5B")
    assertThat(FiveBVerifier.check(d.build(device), Phase.MID, showNames = true).joinToString { it.toString() }).contains("FOTO5B_02")
  }
}
