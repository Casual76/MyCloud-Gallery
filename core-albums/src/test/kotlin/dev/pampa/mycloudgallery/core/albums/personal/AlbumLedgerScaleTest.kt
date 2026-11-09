package dev.pampa.mycloudgallery.core.albums.personal

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.albums.contract.AddOutcome
import dev.pampa.mycloudgallery.core.albums.contract.CreateOutcome
import dev.pampa.mycloudgallery.core.albums.contract.EditOutcome
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumFamily
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumKind
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKind
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import dev.pampa.mycloudgallery.core.personal.settings.PersonalAlbums
import dev.pampa.mycloudgallery.core.personal.store.FilePersonalLedger
import dev.pampa.mycloudgallery.core.personal.store.LedgerPersonalStore
import dev.pampa.mycloudgallery.core.personal.store.LedgerSnapshot
import dev.pampa.mycloudgallery.core.personal.store.PersonalLedger
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * Review generale, OA-1: aggiungere o togliere foto da un album non riscrive tutto il registro personale per ogni foto. Sugli album veri
 * (catalogo Room, proiezione sulle tabelle) e sul registro **su file** (`FilePersonalLedger`: ogni salvataggio riscrive l'istantanea intera
 * con fsync), con 20.000 voci gia' nel registro e 2.000 foto: i byte scritti restano entro tre volte la dimensione del registro (prima erano
 * 2.000 volte: 7,5 GiB e 44 secondi sul PC), in un tempo ragionevole, e sul file c'e' tutto.
 */
@RunWith(AndroidJUnit4::class)
class AlbumLedgerScaleTest {

  @get:Rule
  val folder = TemporaryFolder()

  /** Il registro su file, che conta i salvataggi e i byte scritti e si rifiuta di superare [hardCap] (la prova non scrive gigabyte per dire di no). */
  private class MeteredLedger(private val file: File) : PersonalLedger {
    private val inner = FilePersonalLedger(file)
    var saves = 0
      private set
    var bytes = 0L
      private set
    private var hardCap = Long.MAX_VALUE

    fun meter(cap: Long) {
      saves = 0
      bytes = 0L
      hardCap = cap
    }

    override suspend fun load(): LedgerSnapshot = inner.load()

    override suspend fun save(snapshot: LedgerSnapshot) {
      inner.save(snapshot)
      saves++
      bytes += file.length()
      check(bytes <= hardCap) { "Registro personale riscritto $saves volte: $bytes byte, oltre il tetto di $hardCap." }
    }
  }

  private fun idOf(n: Int): MediaId = MediaId(UUID(0x7000L, n.toLong()).toString())

  @Test
  fun aggiungereEToglereDuemilaFotoNonRiscriveIlRegistroPerOgniFoto() = runBlocking<Unit> {
    val room = AlbumsRoom()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    try {
      val file = File(folder.root, "personal.ledger")
      val ledger = MeteredLedger(file)
      val author = TrustIds.random()
      var tick = T0
      val store = LedgerPersonalStore(author, { tick += 10; tick }, ledger, RoomFingerprints(room))

      // 20.000 voci gia' nel registro (arrivate dagli altri dispositivi: un salvataggio solo), che gli album non devono guardare.
      val other = TrustIds.random()
      store.applyRemote(
        (0 until REGISTRY).map { n ->
          PersonalEntry(PersonalKey(PersonalKind.MEMORY, "ricordo-%05d-%s".format(n, "m".repeat(60)), PersonalFields.MEMORY_SEEN), T0, other, PersonalValue.Number(20_260_101L))
        },
      )
      room.insertRawMany(PHOTOS, id = ::idOf, size = { 5_000L + it }, quick = { "%064x".format(it + 1) })
      val visibility = RoomVisibility(room)
      val albums = CatalogAlbums(store, room.catalog, room.db, visibility, visibility, FakeAlbumFamily(account = AccountId("alessio")), scope)
      val album = (albums.create("Scala", AlbumKind.MANUAL, null) as CreateOutcome.Created).id
      val media = (0 until PHOTOS).map(::idOf)
      val before = file.length()
      // Una voce `item:` pesa circa 210 byte: il registro finale non supera questo, e tre volte tanto e' il tetto oltre cui la prova si ferma.
      val worstFinal = before + PHOTOS * 512L

      // --- Aggiungere ---
      ledger.meter(cap = 3 * worstFinal)
      var started = System.nanoTime()
      assertThat(albums.add(album, media)).isEqualTo(AddOutcome.Added(PHOTOS, 0))
      val addMs = (System.nanoTime() - started) / 1_000_000
      println("OA-1 misura: $PHOTOS foto aggiunte con $REGISTRY voci nel registro: ${ledger.saves} salvataggi, ${ledger.bytes} byte scritti, registro ${file.length()} byte, $addMs ms")
      assertThat(room.db.albumDao().items(album.value)).hasSize(PHOTOS)
      assertWithMessage("byte scritti aggiungendo").that(ledger.bytes).isAtMost(3 * file.length())
      assertWithMessage("salvataggi aggiungendo").that(ledger.saves).isAtMost(2)
      assertWithMessage("millisecondi aggiungendo").that(addMs).isLessThan(MAX_MS)

      // Sul file c'e' tutto: un processo nuovo rilegge l'album intero, nell'ordine in cui le foto sono state aggiunte.
      val reopened = LedgerPersonalStore(author, { null }, FilePersonalLedger(file))
      reopened.open()
      assertThat(PersonalAlbums(reopened).albums().single().items).isEqualTo(media.map { it.value })
      assertThat(reopened.state()).hasSize(store.state().size)

      // --- Togliere ---
      ledger.meter(cap = 3 * worstFinal)
      started = System.nanoTime()
      assertThat(albums.remove(album, media)).isEqualTo(EditOutcome.Done)
      val removeMs = (System.nanoTime() - started) / 1_000_000
      println("OA-1 misura: $PHOTOS foto tolte: ${ledger.saves} salvataggi, ${ledger.bytes} byte scritti, registro ${file.length()} byte, $removeMs ms")
      assertThat(room.db.albumDao().items(album.value)).isEmpty()
      assertWithMessage("byte scritti togliendo").that(ledger.bytes).isAtMost(3 * file.length())
      assertWithMessage("salvataggi togliendo").that(ledger.saves).isAtMost(2)
      assertWithMessage("millisecondi togliendo").that(removeMs).isLessThan(MAX_MS)
      val after = LedgerPersonalStore(author, { null }, FilePersonalLedger(file))
      after.open()
      assertThat(PersonalAlbums(after).albums().single().items).isEmpty()
    } finally {
      scope.cancel()
      room.close()
    }
  }

  private companion object {
    const val REGISTRY = 20_000
    const val PHOTOS = 2_000

    /** "Un tempo ragionevole" anche su un PC carico: prima della correzione erano 44 secondi sul PC libero. */
    const val MAX_MS = 20_000L
  }
}
