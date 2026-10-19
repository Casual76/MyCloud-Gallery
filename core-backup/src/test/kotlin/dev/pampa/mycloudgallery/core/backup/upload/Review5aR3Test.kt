package dev.pampa.mycloudgallery.core.backup.upload

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.backup.contract.BackupItem
import dev.pampa.mycloudgallery.core.backup.contract.UploadState
import dev.pampa.mycloudgallery.core.backup.testing.BackupTestKit
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.nas.testing.FaultPhase
import dev.pampa.mycloudgallery.core.nas.testing.FaultPlan
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.nas.testing.SimFault
import dev.pampa.mycloudgallery.core.nas.testing.SimOp
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * R3 della revisione di sicurezza della 5A (`docs/review/5a-fase5a.md`): la ripresa di un file grande (`reopenTemp`) accorciava e
 * riscriveva **quello che trovava** al nome del temporaneo `.mycloudgallery/tmp/<io>-bk-<n>`, anche un collegamento a un media vero. Qui il
 * **motore vero** sul **simulatore Samba** con la sessione e le primitive di produzione: un video caricato a meta' (punto di ripresa nel
 * diario), poi al nome del temporaneo compare un collegamento (un dispositivo compromesso dello stesso account, uno script via SSH), poi la
 * ripresa. Il media collegato deve restare intero, l'elemento deve ricominciare da capo e arrivare comunque, e gli invarianti del §5.4
 * devono reggere. La ripresa normale deve restare una ripresa (riapertura e accorciamento del proprio temporaneo, mai il file da capo).
 */
class Review5aR3Test {

  private fun bytes(n: Int, seed: Int) = ByteArray(n) { (it * 131 + seed * 7 + (it shr 8)).toByte() }

  private val dir = "Backup/${BackupTestKit.DEVICE_FOLDER}/2026/10"
  private val video = bytes(300_000, 1)

  /** Il media vero che l'attaccante collega: piu' lungo del punto di ripresa, cosi' la riapertura lo accorcerebbe davvero. */
  private val media = bytes(250_000, 9)
  private val mediaPath = "Foto/vacanze.mp4"

  private fun TestScope.world(): SimWorld = SimWorld(backgroundScope)

  /** Il video caricato a meta': la dodicesima scrittura da 16 KiB arriva al NAS, la risposta no; il punto di ripresa e' a 160 KiB. */
  private suspend fun interrupted(w: SimWorld, h: RealEngineKit): BackupItem {
    w.putFile(mediaPath, media)
    w.kit.addPhoneMedia("VID_1.mp4", NasContent.of(video), kind = MediaKind.VIDEO)
    w.backend.faults = FaultPlan().at(12, FaultPhase.AFTER, SimFault.DropConnection, op = SimOp.WRITE)
    h.runAll()
    w.backend.faults = null
    val item = w.kit.store.all().single()
    assertThat(item.state).isEqualTo(UploadState.UPLOADING)
    assertThat(item.resumeAt).isEqualTo(10L * 16 * 1024)
    // Con il punto di ripresa, l'identita' del temporaneo letta sull'handle dello scrittore.
    assertThat(item.resumeId).isEqualTo(w.backend.fileId(w.kit.share, item.temp!!.value))
    return item
  }

  private fun SimWorld.recordOps(): MutableList<SimOp> = CopyOnWriteArrayList<SimOp>().also { seen -> backend.beforeOp = { op, _ -> seen += op } }

  private fun SimWorld.sha(path: String): Sha256? = backend.bytes(kit.share, path)?.let { Sha256.of(it) }

  /** La ripresa dopo il collegamento: niente accorciato, il media intero, il video arrivato da capo, gli invarianti veri. */
  private suspend fun resumeAndCheck(w: SimWorld, h: RealEngineKit, label: String, untouched: Map<String, Sha256>) {
    val before = w.files()
    val seen = w.recordOps()
    h.restart()
    h.resumeUntilQuiet()
    for ((path, sha) in untouched) {
      assertWithMessage("$label: $path intero").that(w.sha(path)).isEqualTo(sha)
    }
    assertWithMessage("$label: nessun accorciamento").that(seen).doesNotContain(SimOp.SET_END_OF_FILE)
    assertWithMessage("$label: il video arriva da capo").that(w.sha("$dir/VID_1.mp4")).isEqualTo(Sha256.of(video))
    assertWithMessage("$label: elemento finito").that(w.kit.store.all().single().state).isEqualTo(UploadState.DONE)
    assertWithMessage("$label: invarianti del §5.4").that(w.checks(before, w.phoneShas(), final = true)).isEmpty()
  }

  @Test
  fun r3_unCollegamentoFisicoAlNomeDelTemporaneoNonSiAccorciaEIlMediaResta() = runTest {
    val w = world()
    val h = w.engineKit(parallel = 1)
    val temp = interrupted(w, h).temp!!.value
    val mediaId = w.backend.fileId(w.kit.share, mediaPath)
    // Al nome del temporaneo, al posto del temporaneo: un collegamento fisico al media (stesso file, due nomi).
    w.backend.delete(w.kit.share, temp)
    w.backend.putHardLink(w.kit.share, existing = mediaPath, link = temp)
    assertThat(w.backend.links(w.kit.share, mediaPath)).isEqualTo(2)
    resumeAndCheck(w, h, "collegamento fisico", mapOf(mediaPath to Sha256.of(media)))
    // Togliere il nome del temporaneo (deleteOwnTemp) ha tolto solo il nome: il media e' lo stesso file, di nuovo con un nome solo.
    assertThat(w.backend.fileId(w.kit.share, mediaPath)).isEqualTo(mediaId)
    assertThat(w.backend.links(w.kit.share, mediaPath)).isEqualTo(1)
    assertThat(w.backend.exists(w.kit.share, temp)).isFalse()
  }

  @Test
  fun r3_unFileConUnAltraIdentitaAlNomeDelTemporaneoNonSiAccorcia() = runTest {
    val w = world()
    val h = w.engineKit(parallel = 1)
    val temp = interrupted(w, h).temp!!.value
    // Un collegamento simbolico come lo mostra Samba di serie: un file normale, un nome solo, con l'identita' e i byte del media.
    w.backend.delete(w.kit.share, temp)
    w.backend.putFileLink(w.kit.share, link = temp, target = mediaPath)
    assertThat(w.backend.fileId(w.kit.share, temp)).isEqualTo(w.backend.fileId(w.kit.share, mediaPath))
    assertThat(w.backend.links(w.kit.share, temp)).isEqualTo(1)
    resumeAndCheck(w, h, "identita' diversa", mapOf(mediaPath to Sha256.of(media)))
    assertThat(w.backend.exists(w.kit.share, mediaPath)).isTrue()
  }

  @Test
  fun r3_unTemporaneoConDueNomiNonSiAccorcia() = runTest {
    val w = world()
    val h = w.engineKit(parallel = 1)
    val temp = interrupted(w, h).temp!!.value
    // Il temporaneo e' proprio il nostro (stessa identita'), ma ha anche un altro nome fuori dalla cartella dell'app: NumberOfLinks = 2.
    val other = "Foto/copia.mp4"
    w.backend.putHardLink(w.kit.share, existing = temp, link = other)
    assertThat(w.backend.links(w.kit.share, temp)).isEqualTo(2)
    val otherSha = w.sha(other)!!
    resumeAndCheck(w, h, "due nomi", mapOf(other to otherSha))
    assertThat(w.backend.links(w.kit.share, other)).isEqualTo(1)
  }

  @Test
  fun r3_laRipresaNormaleRiapreEAccorciaAncoraIlProprioTemporaneo() = runTest {
    val w = world()
    val h = w.engineKit(parallel = 1)
    interrupted(w, h)
    val before = w.files()
    val seen = w.recordOps()
    h.restart()
    h.resumeUntilQuiet()
    assertThat(w.sha("$dir/VID_1.mp4")).isEqualTo(Sha256.of(video))
    assertThat(seen.count { it == SimOp.OPEN_WRITE }).isEqualTo(1)
    assertThat(seen.count { it == SimOp.SET_END_OF_FILE }).isEqualTo(1)
    // Riscritti al piu' i blocchi dopo l'ultimo punto (due da 16 KiB): mai il file da capo.
    val chunks = (video.size + 16 * 1024 - 1) / (16 * 1024)
    assertThat(seen.count { it == SimOp.WRITE } - (chunks - 10)).isAtMost(2)
    assertThat(w.sha(mediaPath)).isEqualTo(Sha256.of(media))
    assertThat(w.checks(before, w.phoneShas(), final = true)).isEmpty()
  }
}
