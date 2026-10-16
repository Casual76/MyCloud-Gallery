package dev.pampa.mycloudgallery.core.backup.chaos

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.backup.contract.BackupKind
import dev.pampa.mycloudgallery.core.backup.contract.BackupTrigger
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import java.io.File
import kotlin.random.Random
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Il verificatore degli invarianti del §5.4 ([BackupVerifier]) invariante per invariante, con stati fatti a mano (anche un solo byte diverso lo
 * rompe), e **da solo su uno stato salvato** in un file: e' cio' che si fa con `build/chaos/backup-state-<giro>.txt` di un giro rosso, senza
 * il banco, il motore o il NAS.
 */
class BackupVerifierTest {

  private val device = BackupChaosWorld.DEVICE
  private val folder = "Backup/S25 di prova/2026/10"

  private fun sha(n: Int) = Sha256.of(byteArrayOf(n.toByte(), 7, 7))

  private fun file(path: String, n: Int, id: Long = 100L + n, share: String = "test") = NasFileFact(share, path, 1000L + n, id, sha(n))

  private fun tmp(id: Int, deviceId: String = device) = ".mycloudgallery/tmp/$deviceId-bk-$id"

  /** Uno stato in ordine: una foto del telefono copiata e al sicuro, un file vecchio che non si e' mosso. */
  private class Draft(val device: String) {
    val before = ArrayList<NasFileFact>()
    val files = ArrayList<NasFileFact>()
    val phoneShas = LinkedHashSet<Sha256>()
    val items = ArrayList<ItemFact>()
    val transitions = ArrayList<TransitionFact>()
    val media = ArrayList<MediaFact>()
    val copies = ArrayList<CopyFact>()
    val cited = LinkedHashSet<String>()
    val removed = ArrayList<String>()
    val notes = ArrayList<Note>()
    var cacheHits = 0
    var hashed = 0

    fun build(): BackupState = BackupState(
      device, "test", "test-comune", before, files, phoneShas, items, transitions, media, copies, cited, removed, notes, cacheHits, maxOf(hashed, files.size),
    )
  }

  private fun healthy(): Draft {
    val d = Draft(device)
    val old = file("Vecchio/x.jpg", 1)
    d.before += old
    d.files += old
    val photo = file("$folder/IMG_1.jpg", 2)
    d.files += photo
    d.phoneShas += photo.sha
    d.items += item(1, "done", photo.path, temp = tmp(1))
    d.transitions += listOf("planned" to "planned", "planned" to "hashed", "hashed" to "uploading", "uploading" to "uploaded", "uploaded" to "verified", "verified" to "publishing", "publishing" to "published", "published" to "linked", "linked" to "done").map { TransitionFact(1, it.first, it.second) }
    d.media += MediaFact("m1", "v1", photo.size, photo.sha, safe = true, problem = null, onPhone = true)
    d.copies += CopyFact("m1", "v1", "test", photo.path, photo.size, verified = true)
    return d
  }

  private fun item(id: Long, state: String, finalPath: String?, media: String = "m1", temp: String? = null, resumeAt: Long = 0, waits: Boolean = false) =
    ItemFact(id, "new_copy", state, media, "v1", 1002, "test", "$folder/IMG_1.jpg", finalPath, temp, resumeAt, null, 0, waits)

  private fun violations(d: Draft, phase: Phase) = BackupVerifier.check(d.build(), phase)

  private fun assertOnly(vs: List<Violation>, vararg expected: Invariant) {
    assertWithMessage("violazioni: $vs").that(vs.map { it.invariant }.toSet()).isEqualTo(expected.toSet())
  }

  @Test
  fun unoStatoInOrdineNonHaViolazioni() {
    assertThat(violations(healthy(), Phase.MID)).isEmpty()
    assertThat(violations(healthy(), Phase.FINAL)).isEmpty()
  }

  @Test
  fun unFileVisibileConByteDiNessunMediaEUnFileAMeta() {
    val d = healthy()
    d.files += file("$folder/IMG_2.jpg", 9)
    assertOnly(violations(d, Phase.MID), Invariant.HALF_FILE)
    // Anche un solo byte diverso: la stessa dimensione, un'altra impronta.
    val e = healthy()
    val photo = e.files.last()
    e.files[e.files.lastIndex] = NasFileFact(photo.share, photo.path, photo.size, photo.fileId, sha(77))
    assertThat(violations(e, Phase.MID).map { it.invariant }).contains(Invariant.HALF_FILE)
  }

  @Test
  fun unDoppioneEUnaSecondaCopiaDiByteGiaPresentiNellAreaPrivata() {
    val old = file("$folder/IMG_3.jpg", 3, share = "test")
    val d = healthy()
    d.before += old
    d.files += old
    d.phoneShas += old.sha
    // Una copia nuova con i byte di un file che c'era gia': doppione.
    d.files += file("$folder/IMG_3 (2).jpg", 3, id = 999)
    assertOnly(violations(d, Phase.MID), Invariant.DUPLICATE)
    // Gli stessi byte pubblicati due volte.
    val e = healthy()
    e.files += file("$folder/IMG_4.jpg", 2, id = 500)
    assertOnly(violations(e, Phase.MID), Invariant.DUPLICATE)
  }

  @Test
  fun unaSostituzioneUnaSparizioneEUnNomeCambiato() {
    val replaced = healthy()
    val old = replaced.before.first()
    replaced.files[0] = NasFileFact(old.share, old.path, old.size, old.fileId, sha(50))
    assertOnly(violations(replaced, Phase.MID), Invariant.REPLACED)
    val moved = healthy()
    moved.files[0] = NasFileFact(old.share, old.path, old.size, 12345, old.sha)
    assertOnly(violations(moved, Phase.MID), Invariant.REPLACED)
    val gone = healthy()
    gone.files.removeAt(0)
    assertOnly(violations(gone, Phase.MID), Invariant.REPLACED)
    val renamed = healthy()
    renamed.files[0] = NasFileFact(old.share, "vecchio/X.jpg", old.size, old.fileId, old.sha)
    assertOnly(violations(renamed, Phase.MID), Invariant.REPLACED)
  }

  @Test
  fun unMediaAlSicuroSenzaLaCopiaConIByteDellaVersioneEUnaPerdita() {
    val missing = healthy()
    missing.files.removeAt(1)
    assertThat(violations(missing, Phase.MID).map { it.invariant }).contains(Invariant.LOSS)
    val changed = healthy()
    val photo = changed.files[1]
    changed.files[1] = NasFileFact(photo.share, photo.path, photo.size, photo.fileId, sha(60))
    assertThat(violations(changed, Phase.MID).map { it.invariant }).containsAtLeast(Invariant.LOSS, Invariant.HALF_FILE)
  }

  @Test
  fun laRilletturaFinaleSenzaCacheEInvarianteCinque() {
    val d = healthy()
    d.cacheHits = 1
    assertOnly(violations(d, Phase.FINAL), Invariant.HASH_100)
    assertThat(violations(d, Phase.MID)).isEmpty()
    val e = healthy()
    e.hashed = 1
    // `hashed` e' il numero di file riletti: meno dei file elencati vuol dire che qualcuno non e' stato riletto.
    val partial = BackupState(
      device, "test", "test-comune", e.before, e.files, e.phoneShas, e.items, e.transitions, e.media, e.copies, e.cited, e.removed, e.notes, 0, 1,
    )
    assertOnly(BackupVerifier.check(partial, Phase.FINAL), Invariant.HASH_100)
  }

  @Test
  fun iTemporaneiASoloQuelliPropriECitatiEAFineNessuno() {
    val cited = healthy()
    cited.files += file(tmp(2), 5)
    cited.cited += tmp(2)
    cited.items += item(2, "uploading", null, media = "m2", temp = tmp(2))
    assertThat(violations(cited, Phase.MID)).isEmpty()
    assertOnly(violations(cited, Phase.FINAL), Invariant.TEMP, Invariant.CONVERGENCE)
    val uncited = healthy()
    uncited.files += file(tmp(3), 5)
    assertOnly(violations(uncited, Phase.MID), Invariant.TEMP)
    val foreign = healthy()
    foreign.files += file(tmp(4, deviceId = "ZZZZZZZZZZZZZZZZZZZZZZZZZZ"), 5)
    assertOnly(violations(foreign, Phase.MID), Invariant.TEMP)
    val opTemp = healthy()
    opTemp.files += file(".mycloudgallery/tmp/$device-op-1", 5)
    assertOnly(violations(opTemp, Phase.MID), Invariant.TEMP)
    val cleaned = healthy()
    cleaned.removed += tmp(5)
    assertOnly(violations(cleaned, Phase.MID), Invariant.TEMP)
  }

  @Test
  fun ilCatalogoUnElementoFinitoHaLaSuaCopiaAlSicuroEUnFallitoNon() {
    val noCopy = healthy()
    noCopy.media[0] = MediaFact("m1", "v1", 1002, sha(2), safe = false, problem = null, onPhone = true)
    assertThat(violations(noCopy, Phase.MID).map { it.invariant }).contains(Invariant.CATALOG)
    // Un fallito che e' l'ultimo elemento di un media al sicuro.
    val failedLast = healthy()
    failedLast.items += item(2, "failed", null)
    assertThat(violations(failedLast, Phase.MID).map { it.invariant }).contains(Invariant.CATALOG)
    // Un fallito superato da un elemento riuscito dello stesso media (dopo "Riprova"): non e' una violazione, ne' a meta' ne' alla fine.
    val retried = healthy()
    retried.items.add(0, item(0, "failed", null))
    assertThat(violations(retried, Phase.MID)).isEmpty()
    assertThat(violations(retried, Phase.FINAL)).isEmpty()
  }

  @Test
  fun ilDiarioSoloTransizioniAmmesseENomiFinaliSulNas() {
    val d = healthy()
    d.transitions += TransitionFact(1, "done", "planned")
    assertOnly(violations(d, Phase.MID), Invariant.DIARY)
    val noName = healthy()
    noName.items[0] = item(1, "done", "$folder/IMG_9.jpg", temp = tmp(1))
    assertThat(violations(noName, Phase.MID).map { it.invariant }).contains(Invariant.DIARY)
    val unknown = healthy()
    unknown.transitions += TransitionFact(1, "inventato", "done")
    assertOnly(violations(unknown, Phase.MID), Invariant.DIARY)
  }

  @Test
  fun aLavoroFinitoUnElementoFermoEUnMediaNonAlSicuroSullTelefonoSonoInvariantoNove() {
    val stuck = healthy()
    stuck.items += item(2, "hashed", null, media = "m2")
    assertThat(violations(stuck, Phase.MID)).isEmpty()
    assertOnly(violations(stuck, Phase.FINAL), Invariant.CONVERGENCE)
    val unsafe = healthy()
    unsafe.media += MediaFact("m3", "v1", 5, null, safe = false, problem = "verify_failed", onPhone = true)
    assertOnly(violations(unsafe, Phase.FINAL), Invariant.CONVERGENCE)
    // Se il media non e' piu' sul telefono (cancellato) non c'e' niente da mettere al sicuro.
    val deleted = healthy()
    deleted.media += MediaFact("m3", "v1", 5, null, safe = false, problem = null, onPhone = false)
    assertThat(violations(deleted, Phase.FINAL)).isEmpty()
    // F1: un elemento che aspetta una riga che il catalogo non conosce piu' e' un difetto NOTO, non una violazione.
    val gap = healthy()
    gap.items += item(2, "planned", null, media = "m2", waits = true)
    assertOnly(violations(gap, Phase.FINAL), Invariant.KNOWN_GAP)
  }

  @Test
  fun nulladiNuovoFuoriDaBackupDellAreaPrivata() {
    val common = healthy()
    common.files += file("Backup/altro.jpg", 2, id = 600, share = "test-comune")
    assertThat(violations(common, Phase.MID).map { it.invariant }).contains(Invariant.OUT_OF_PLACE)
    val outside = healthy()
    outside.files += file("Documenti/nuovo.jpg", 2, id = 601)
    assertThat(violations(outside, Phase.MID).map { it.invariant }).contains(Invariant.OUT_OF_PLACE)
    val app = healthy()
    app.files += file(".mycloudgallery/rec/1", 8)
    assertOnly(violations(app, Phase.MID), Invariant.OUT_OF_PLACE)
  }

  @Test
  fun leNoteDelBancoSonoViolazioniConIlLoroInvariante() {
    val d = healthy()
    d.notes += Note(Invariant.NAS_READS, "un file non si rilegge")
    d.notes += Note(Invariant.CONTRACT, "il motore ha lanciato")
    assertOnly(violations(d, Phase.MID), Invariant.NAS_READS, Invariant.CONTRACT)
  }

  @Test
  fun senzaNomiIlRapportoNonDiceIPercorsi() {
    val d = healthy()
    d.files += file("$folder/Segreta.jpg", 9)
    val text = BackupVerifier.check(d.build(), Phase.MID, showNames = false).joinToString { it.toString() }
    assertThat(text).doesNotContain("Segreta")
    assertThat(text).contains("un file")
  }

  // --- Da solo, su uno stato salvato -------------------------------------------------------------------------------------------

  @Test
  fun uno_stato_salvato_si_verifica_da_solo() {
    val file = File.createTempFile("mcg-backup-state", ".txt")
    try {
      val d = healthy()
      d.files += file(tmp(2), 5)
      d.cited += tmp(2)
      d.items += item(2, "uploading", null, media = "m2", temp = tmp(2), resumeAt = 32768, waits = true)
      d.notes += Note(Invariant.NAS_READS, "una nota")
      BackupStateFile.write(d.build(), file)
      val back = BackupStateFile.read(file)
      assertThat(back.files.map { it.path }).isEqualTo(d.files.map { it.path })
      assertThat(back.items.map { it.waitsForRow }).containsExactly(false, true).inOrder()
      assertThat(back.cited).containsExactly(tmp(2))
      assertThat(BackupVerifier.check(back, Phase.MID).map { it.toString() }).isEqualTo(violations(d, Phase.MID).map { it.toString() })
    } finally {
      file.delete()
    }
  }

  @Test
  fun uno_stato_vero_si_salva_si_rilegge_e_si_verifica_da_solo(): Unit = runBlocking {
    val world = FakeNasTarget().openWorld(0)
    try {
      val data = Random(11).nextBytes(40_000)
      world.addPhoneMedia("IMG_1.jpg", NasContent.of(data), MediaKind.PHOTO, 20261012)
      val scanner = BackupNasScanner.ofShares(world.nas.reader, world.nas.shares)
      val before = scanner.scan().files
      // Il motore di riferimento, senza colpi, come lo costruisce il banco.
      val env = EngineEnv(world, world.nas.ops, world.nas.reader, world.store, world.catalog, world.phone.port, Random(1))
      val engine = ReferenceStack.create(env)
      engine.run(BackupKind.PHOTOS, 1.hours, BackupTrigger.SCHEDULED)
      val scan = scanner.scan(allowCache = false)
      val state = world.capture(before, scan, emptyList(), emptyList(), emptyList())
      assertThat(BackupVerifier.check(state, Phase.FINAL)).isEmpty()
      val saved = BackupStateFile.text(state)
      assertThat(BackupVerifier.check(BackupStateFile.parse(saved.lines()), Phase.FINAL)).isEmpty()
      // Un byte di una copia sul NAS cambia nello stato salvato: il verificatore da solo lo vede (1: byte di nessun media, 4: nessuna copia con i byte della versione).
      val victim = state.files.first { it.path.startsWith("Backup/") }
      val tampered = saved.lines().joinToString("\n") { line ->
        if (line.startsWith("file=") && line.contains(victim.path)) line.replaceAfterLast('|', sha(99).hex) else line
      }
      val found = BackupVerifier.check(BackupStateFile.parse(tampered.lines()), Phase.FINAL).map { it.invariant }
      assertThat(found).containsAtLeast(Invariant.HALF_FILE, Invariant.LOSS)
    } finally {
      world.close()
    }
  }
}
