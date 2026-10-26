package dev.pampa.mycloudgallery.core.library.duplicates

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.model.LocationId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionId
import java.util.Random
import org.junit.Test

/**
 * Le regole della pulizia dei doppioni identici (Fase 7, P8, D25, §2.1.1; E-U1, E-U2): l'ordine della copia da tenere e i "mai". Puro, su JVM,
 * con gruppi scritti a mano e con una proprieta' su mille gruppi casuali.
 */
class IdenticalCleanupTest {

  private val hash = Sha256("a".repeat(64))
  private var next = 1L

  private fun copy(
    area: CopyArea = CopyArea.MY_PRIVATE,
    path: String = "Foto/x.jpg",
    modifiedAt: Long = 1_000,
    verifiedAt: Long? = null,
    album: Boolean = false,
    id: Long = next++,
  ): IdentCopy = IdentCopy(
    location = LocationId(id),
    area = area,
    share = if (area == CopyArea.PHONE) null else "alessio",
    path = path,
    size = 2_000_000,
    modifiedAt = modifiedAt,
    verifiedAt = verifiedAt,
    backupFolder = if (area == CopyArea.MY_PRIVATE) IdenticalCleanup.backupFolderOf(path) else null,
    inSharedAlbum = album,
  )

  private fun group(vararg copies: IdentCopy, hash: Sha256? = this.hash, part: CopyPart = CopyPart.NAS): IdenticalGroup =
    IdenticalGroup(MediaId("00000000-0000-4000-8000-000000000001"), VersionId("00000000-0000-4000-8000-0000000000a1"), part, hash, copies.toList(), safe = true, isVideo = false)

  private fun ids(copies: List<IdentCopy>) = copies.map { it.location.value }

  // --- L'ordine di D25 --------------------------------------------------------------------------------------------------------

  @Test
  fun siTieneLaCopiaPrivataVerificataPrimaDiTutto() {
    val verified = copy(path = "Documenti/x.jpg", verifiedAt = 5_000, modifiedAt = 9_000)
    val backup = copy(path = "Backup/pixel/2026/x.jpg", modifiedAt = 1)
    val proposal = IdenticalCleanup.propose(group(backup, verified))

    assertThat(proposal.status).isEqualTo(IdenticalStatus.CLEANABLE)
    assertThat(proposal.keep).isSameInstanceAs(verified)
    assertThat(proposal.remove).containsExactly(backup)
  }

  @Test
  fun ASeguireLaCopiaInUnAlbumCondiviso() {
    val plain = copy(area = CopyArea.COMMON, path = "Famiglia/x.jpg", modifiedAt = 1)
    val inAlbum = copy(area = CopyArea.COMMON, path = "Album condivisi/Oslo/x.jpg", modifiedAt = 9_000, album = true)
    val proposal = IdenticalCleanup.propose(group(plain, inAlbum))

    assertThat(proposal.keep).isSameInstanceAs(inAlbum)
    assertThat(proposal.remove).containsExactly(plain)
  }

  @Test
  fun PoiLaCopiaNellaCartellaDiBackupDelDispositivo() {
    val older = copy(path = "Foto/vecchia.jpg", modifiedAt = 1)
    val backup = copy(path = "Backup/Pixel 9/2026/10/x.jpg", modifiedAt = 9_000)
    val proposal = IdenticalCleanup.propose(group(older, backup))

    assertThat(backup.backupFolder).isEqualTo("pixel 9")
    assertThat(proposal.keep).isSameInstanceAs(backup)
    assertThat(proposal.remove).containsExactly(older)
  }

  @Test
  fun InfineLaPiuVecchiaEAParitaLIdentita() {
    val old = copy(path = "A/x.jpg", modifiedAt = 100, id = 7)
    val new = copy(path = "B/x.jpg", modifiedAt = 200, id = 3)
    assertThat(IdenticalCleanup.propose(group(new, old)).keep).isSameInstanceAs(old)

    val first = copy(path = "A/x.jpg", modifiedAt = 100, id = 3)
    val second = copy(path = "B/x.jpg", modifiedAt = 100, id = 7)
    assertThat(IdenticalCleanup.propose(group(second, first)).keep).isSameInstanceAs(first)
  }

  @Test
  fun SulTelefonoSiTieneLaPiuVecchiaEIlRestoVaNelCestino() {
    val original = copy(area = CopyArea.PHONE, path = "DCIM/Camera/IMG_1.jpg", modifiedAt = 100)
    val copyInDownload = copy(area = CopyArea.PHONE, path = "Download/IMG_1.jpg", modifiedAt = 900)
    val another = copy(area = CopyArea.PHONE, path = "Pictures/IMG_1 (1).jpg", modifiedAt = 950)
    val proposal = IdenticalCleanup.propose(group(copyInDownload, original, another, part = CopyPart.PHONE))

    assertThat(proposal.keep).isSameInstanceAs(original)
    assertThat(ids(proposal.remove)).containsExactlyElementsIn(ids(listOf(copyInDownload, another)))
  }

  // --- I "mai" ----------------------------------------------------------------------------------------------------------------

  @Test
  fun e_u1_UnaCopiaNellAreaDiUnAltroAccountNonSiPropone_ESiConta() {
    val mine = copy(path = "Foto/a.jpg", modifiedAt = 1)
    val dup = copy(path = "Foto/b.jpg", modifiedAt = 2)
    val foreign = copy(area = CopyArea.OTHERS, path = "marco/x.jpg", modifiedAt = 0)
    val proposal = IdenticalCleanup.propose(group(mine, dup, foreign))

    assertThat(proposal.keep).isSameInstanceAs(mine)
    assertThat(proposal.remove).containsExactly(dup)
    assertThat(proposal.blocked.map { it.copy to it.reason }).containsExactly(foreign to BlockReason.OTHER_AREA)
    assertThat(proposal.inOthersArea).isEqualTo(1)
  }

  @Test
  fun e_u1_ConUnaSolaCopiaConIlPermessoNonCEDaToglierePerche_LAltraEDiUnAltro() {
    val mine = copy(path = "Foto/a.jpg")
    val foreign = copy(area = CopyArea.OTHERS, path = "marco/x.jpg")
    val proposal = IdenticalCleanup.propose(group(mine, foreign))

    assertThat(proposal.status).isEqualTo(IdenticalStatus.NOTHING_TO_REMOVE)
    assertThat(proposal.remove).isEmpty()
    assertThat(proposal.inOthersArea).isEqualTo(1)
  }

  @Test
  fun e_u2_LUnicaCopiaVerificataNellaCartellaDaEliminareResta_ESiEliminaLAltra() {
    // Per l'ordine "la piu' vecchia" si sarebbe tenuta la copia in Backup/, ma l'unica verificata e' quella in Documenti/.
    val oldInBackup = copy(path = "Backup/pixel/2024/x.jpg", modifiedAt = 1)
    val verified = copy(path = "Documenti/x.jpg", modifiedAt = 9_000, verifiedAt = 5_000)
    val proposal = IdenticalCleanup.propose(group(oldInBackup, verified))

    assertThat(proposal.keep).isSameInstanceAs(verified)
    assertThat(proposal.remove).containsExactly(oldInBackup)
  }

  @Test
  fun maiLUltimaCopiaPrivata_LaMiglioreRestaSeSiTieneUnaCopiaComune() {
    // La comune (la piu' vecchia, non in un album) vincerebbe l'ordine: le due copie private sarebbero entrambe da togliere.
    val common = copy(area = CopyArea.COMMON, path = "Famiglia/x.jpg", modifiedAt = 1)
    val privateOld = copy(path = "Foto/a.jpg", modifiedAt = 500)
    val privateNew = copy(path = "Foto/b.jpg", modifiedAt = 900)
    val proposal = IdenticalCleanup.propose(group(common, privateOld, privateNew))

    assertThat(proposal.keep).isSameInstanceAs(common)
    assertThat(proposal.blocked.map { it.copy to it.reason }).containsExactly(privateOld to BlockReason.LAST_PRIVATE)
    assertThat(proposal.remove).containsExactly(privateNew)
  }

  @Test
  fun maiLUltimaCopiaPrivata_ConUnaPrivataEUnaComuneNonSiTogliePiuDelNecessario() {
    val common = copy(area = CopyArea.COMMON, path = "Famiglia/x.jpg", modifiedAt = 1)
    val onlyPrivate = copy(path = "Foto/a.jpg", modifiedAt = 500)
    val proposal = IdenticalCleanup.propose(group(common, onlyPrivate))

    assertThat(proposal.status).isEqualTo(IdenticalStatus.NOTHING_TO_REMOVE)
    assertThat(proposal.remove).isEmpty()
    assertThat(proposal.keep).isSameInstanceAs(common)
    assertThat(proposal.blocked.map { it.reason }).containsExactly(BlockReason.LAST_PRIVATE)
  }

  @Test
  fun unaCopiaInUnAlbumCondivisoNonSiTogliePerche_ToglierlaLaToglieATutti() {
    val verified = copy(path = "Foto/a.jpg", verifiedAt = 5_000)
    val inAlbum = copy(area = CopyArea.COMMON, path = "Album condivisi/Oslo/x.jpg", album = true)
    val proposal = IdenticalCleanup.propose(group(verified, inAlbum))

    // L'ordine terrebbe la privata verificata, ma la copia dell'album non si propone mai.
    assertThat(proposal.keep).isSameInstanceAs(verified)
    assertThat(proposal.remove).isEmpty()
    assertThat(proposal.status).isEqualTo(IdenticalStatus.NOTHING_TO_REMOVE)
    assertThat(proposal.blocked.map { it.copy to it.reason }).containsExactly(inAlbum to BlockReason.SHARED_ALBUM)
  }

  @Test
  fun senzaHashCompletoIlGruppoEDaVerificare_ENonSiProponeNiente() {
    val a = copy(path = "A/x.mp4", modifiedAt = 1)
    val b = copy(path = "B/x.mp4", modifiedAt = 2)
    val proposal = IdenticalCleanup.propose(group(a, b, hash = null))

    assertThat(proposal.status).isEqualTo(IdenticalStatus.TO_VERIFY)
    assertThat(proposal.remove).isEmpty()
    assertThat(proposal.reclaimableBytes).isEqualTo(0)
  }

  // --- La scelta dell'utente in "Rivedi" --------------------------------------------------------------------------------------

  @Test
  fun l_utenteScegliLaCopiaDaTenere_EIMaiValgonoLoStesso() {
    val a = copy(path = "A/x.jpg", modifiedAt = 1, id = 1)
    val b = copy(path = "B/x.jpg", modifiedAt = 2, id = 2)
    val inAlbum = copy(area = CopyArea.COMMON, path = "Album condivisi/Oslo/x.jpg", album = true, id = 3)
    val proposal = IdenticalCleanup.propose(group(a, b, inAlbum), keepChoice = LocationId(2))

    assertThat(proposal.keep).isSameInstanceAs(b)
    assertThat(proposal.remove).containsExactly(a)
    assertThat(proposal.blocked.map { it.reason }).containsExactly(BlockReason.SHARED_ALBUM)
  }

  @Test
  fun unaScelta_CheNonEDelGruppoOEDiUnAltroAccount_SiIgnora() {
    val a = copy(path = "A/x.jpg", modifiedAt = 1, id = 1)
    val b = copy(path = "B/x.jpg", modifiedAt = 2, id = 2)
    val foreign = copy(area = CopyArea.OTHERS, path = "marco/x.jpg", id = 9)

    assertThat(IdenticalCleanup.propose(group(a, b, foreign), keepChoice = LocationId(9)).keep).isSameInstanceAs(a)
    assertThat(IdenticalCleanup.propose(group(a, b), keepChoice = LocationId(404)).keep).isSameInstanceAs(a)
  }

  // --- Percorsi ---------------------------------------------------------------------------------------------------------------

  @Test
  fun laCartellaDiBackupEDelloSoloDispositivo() {
    assertThat(IdenticalCleanup.backupFolderOf("Backup/Pixel 9/2026/10/IMG.jpg")).isEqualTo("pixel 9")
    assertThat(IdenticalCleanup.backupFolderOf("backup/Pixel/IMG.jpg")).isEqualTo("pixel")
    assertThat(IdenticalCleanup.backupFolderOf("Backup/Area comune/2026/10/IMG.jpg")).isNull()
    assertThat(IdenticalCleanup.backupFolderOf("Backup/IMG.jpg")).isNull()
    assertThat(IdenticalCleanup.backupFolderOf("Foto/Backup/Pixel/IMG.jpg")).isNull()
    assertThat(IdenticalCleanup.backupFolderOf("")).isNull()
  }

  @Test
  fun unFileDiUnAlbumCondivisoStaDentroUnaCartellaDellAlbum() {
    assertThat(IdenticalCleanup.isInSharedAlbumFolder("Album condivisi/Oslo/IMG.jpg")).isTrue()
    assertThat(IdenticalCleanup.isInSharedAlbumFolder("album condivisi/Oslo 2026/sotto/IMG.jpg")).isTrue()
    assertThat(IdenticalCleanup.isInSharedAlbumFolder("Album condivisi/IMG.jpg")).isFalse()
    assertThat(IdenticalCleanup.isInSharedAlbumFolder("Famiglia/Album condivisi/Oslo/IMG.jpg")).isFalse()
  }

  // --- Proprieta' -------------------------------------------------------------------------------------------------------------

  /** Mille gruppi casuali: le garanzie valgono sempre, qualunque sia la mescolanza di aree, verifiche e album. */
  @Test
  fun proprieta_iMaiValgonoSuMilleGruppiCasuali() {
    val random = Random(25)
    repeat(1_000) { round ->
      val size = 2 + random.nextInt(5)
      val part = if (random.nextInt(4) == 0) CopyPart.PHONE else CopyPart.NAS
      val copies = (0 until size).map { index ->
        val area = if (part == CopyPart.PHONE) CopyArea.PHONE else CopyArea.entries.filter { it != CopyArea.PHONE }[random.nextInt(3)]
        val path = when (random.nextInt(4)) {
          0 -> "Backup/dev${random.nextInt(2)}/2026/f$index.jpg"
          1 -> "Album condivisi/A${random.nextInt(2)}/f$index.jpg"
          else -> "Cartella${random.nextInt(3)}/f$index.jpg"
        }
        copy(
          area = area,
          path = path,
          modifiedAt = random.nextInt(5).toLong(),
          verifiedAt = if (area == CopyArea.MY_PRIVATE && random.nextInt(3) == 0) 1L else null,
          album = area == CopyArea.COMMON && IdenticalCleanup.isInSharedAlbumFolder(path),
          id = round * 10L + index + 1,
        )
      }
      val verifiedGroup = random.nextInt(5) != 0
      val g = group(*copies.toTypedArray(), hash = if (verifiedGroup) hash else null, part = part)
      val choice = if (random.nextBoolean()) copies[random.nextInt(copies.size)].location else null
      val p = IdenticalCleanup.propose(g, choice)
      val message = "round $round: $p"

      // Mai nell'area di un altro account.
      assertWithMessage(message).that(p.remove.none { it.area == CopyArea.OTHERS }).isTrue()
      assertWithMessage(message).that(p.keep?.area).isNotEqualTo(CopyArea.OTHERS)
      // Mai una copia di un album condiviso.
      assertWithMessage(message).that(p.remove.none { it.inSharedAlbum }).isTrue()
      // Mai senza hash completo.
      if (!verifiedGroup) assertWithMessage(message).that(p.remove).isEmpty()
      // Qualcosa resta sempre: la copia da tenere non e' fra quelle da togliere.
      if (p.status == IdenticalStatus.CLEANABLE) {
        assertWithMessage(message).that(p.keep).isNotNull()
        assertWithMessage(message).that(p.remove).doesNotContain(p.keep)
        assertWithMessage(message).that(p.remove).isNotEmpty()
      }
      // Mai l'ultima copia privata.
      val privates = copies.filter { it.area == CopyArea.MY_PRIVATE }
      if (privates.isNotEmpty()) assertWithMessage(message).that(privates.any { it !in p.remove }).isTrue()
      // Ogni copia e' in una sola lista: da tenere, da togliere o bloccata (le OTHERS sempre bloccate).
      val placed = listOfNotNull(p.keep) + p.remove + p.blocked.map { it.copy }
      assertWithMessage(message).that(placed.map { it.location }.distinct().size).isEqualTo(placed.size)
      // Il piano di una scelta dell'utente rispetta le stesse regole.
      assertWithMessage(message).that(p.remove.sumOf { it.size }).isEqualTo(p.reclaimableBytes)
    }
  }
}
