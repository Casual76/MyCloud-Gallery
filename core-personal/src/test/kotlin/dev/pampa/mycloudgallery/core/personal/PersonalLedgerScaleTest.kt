package dev.pampa.mycloudgallery.core.personal

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFingerprint
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKind
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import dev.pampa.mycloudgallery.core.personal.settings.PersonalAlbums
import dev.pampa.mycloudgallery.core.personal.store.FilePersonalLedger
import dev.pampa.mycloudgallery.core.personal.store.LedgerPersonalStore
import dev.pampa.mycloudgallery.core.personal.store.LedgerSnapshot
import dev.pampa.mycloudgallery.core.personal.store.PersonalLedger
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Review generale, OA-1, la prova di scala: un registro personale **su file** con 20.000 voci (elementi di album con l'impronta: circa
 * 4 MB), a cui si aggiungono e poi si tolgono 2.000 foto di un album. Ogni salvataggio riscrive l'istantanea intera con fsync
 * (`FilePersonalLedger`): una voce alla volta erano 2.000 riscritture (7,5 GiB e 44 secondi sul PC). A lotti i byte scritti restano entro
 * tre volte la dimensione del registro, in un tempo ragionevole, e un processo nuovo rilegge dal file tutto il lotto.
 */
class PersonalLedgerScaleTest {

  @get:Rule
  val folder = TemporaryFolder()

  private fun device(n: Int) = Base32.encode(ByteArray(16) { n.toByte() })

  private val me = device(1)
  private val other = device(2)
  private val album = "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"

  private fun fp(n: Int) = PersonalFingerprint(5_000L + n, QuickHash("%064x".format(n + 1)))

  /** Il registro su file, con il conto dei salvataggi e dei byte scritti; oltre [hardCap] si rifiuta (la prova non scrive gigabyte per dire di no). */
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

  @Test
  fun ventimilaVociPiuDuemilaFoto_iByteScrittiRestanoEntroTreVolteIlRegistro() = runBlocking<Unit> {
    val file = File(folder.root, "personal.ledger")
    val ledger = MeteredLedger(file)
    val store = LedgerPersonalStore(me, { NAS_NOW }, ledger)
    val albums = PersonalAlbums(store)

    // 20.000 voci gia' nel registro: gli elementi di venti album arrivati da un altro dispositivo, ognuno con la sua impronta.
    store.applyRemote(
      (0 until REGISTRY).map { n ->
        val key = PersonalKey(PersonalKind.ALBUM, "0a0b0c0d-0000-4000-8000-%012d".format(n % 20), PersonalFields.albumItem(mediaId(100_000 + n)))
        PersonalEntry(key, NAS_NOW - 60_000, other, PersonalValue.Number((n + 1) * 1_000L), fp(100_000 + n))
      },
    )
    albums.define(album, "Scala")
    val before = file.length()
    assertThat(before).isGreaterThan(3_000_000L)

    // --- Aggiungere 2.000 foto ---
    ledger.meter(cap = 3 * (before + PHOTOS * 512L))
    var started = System.nanoTime()
    albums.batch { for (n in 0 until PHOTOS) addItem(album, mediaId(n), (n + 1) * 1_000L, fp(n)) }
    val addMs = (System.nanoTime() - started) / 1_000_000
    println("OA-1 misura (store): $PHOTOS foto aggiunte a un registro di $REGISTRY voci: ${ledger.saves} salvataggi, ${ledger.bytes} byte scritti, registro ${file.length()} byte, $addMs ms")
    assertWithMessage("salvataggi aggiungendo").that(ledger.saves).isEqualTo(1)
    assertWithMessage("byte scritti aggiungendo").that(ledger.bytes).isAtMost(3 * file.length())
    assertWithMessage("millisecondi aggiungendo").that(addMs).isLessThan(MAX_MS)

    // Un processo nuovo rilegge dal file il lotto intero, con l'ordine e le impronte.
    val reopened = LedgerPersonalStore(me, { null }, FilePersonalLedger(file))
    reopened.open()
    assertThat(reopened.state()).hasSize(REGISTRY + 1 + PHOTOS)
    assertThat(PersonalAlbums(reopened).albums().single().items).isEqualTo((0 until PHOTOS).map(::mediaId))
    assertThat(reopened.state().getValue(PersonalKey(PersonalKind.ALBUM, album, PersonalFields.albumItem(mediaId(1_234)))).fingerprint).isEqualTo(fp(1_234))

    // --- Toglierle ---
    ledger.meter(cap = 3 * (before + PHOTOS * 512L))
    started = System.nanoTime()
    albums.batch { for (n in 0 until PHOTOS) removeItem(album, mediaId(n), fp(n)) }
    val removeMs = (System.nanoTime() - started) / 1_000_000
    println("OA-1 misura (store): $PHOTOS foto tolte: ${ledger.saves} salvataggi, ${ledger.bytes} byte scritti, registro ${file.length()} byte, $removeMs ms")
    assertWithMessage("salvataggi togliendo").that(ledger.saves).isEqualTo(1)
    assertWithMessage("byte scritti togliendo").that(ledger.bytes).isAtMost(3 * file.length())
    assertWithMessage("millisecondi togliendo").that(removeMs).isLessThan(MAX_MS)
    val after = LedgerPersonalStore(me, { null }, FilePersonalLedger(file))
    after.open()
    assertThat(PersonalAlbums(after).albums().single().items).isEmpty()
    // Le lapidi restano nello stato (gli altri dispositivi devono vederle): il registro non si accorcia.
    assertThat(after.state()).hasSize(REGISTRY + 1 + PHOTOS)
  }

  @Test
  fun unaVoceAllaVoltaRiscriveIlRegistroOgniVolta_eIlPercheDelLotto() = runBlocking<Unit> {
    // La misura del difetto, in piccolo: dieci foto una alla volta sono dieci riscritture dell'istantanea intera.
    val file = File(folder.root, "personal.ledger")
    val ledger = MeteredLedger(file)
    val store = LedgerPersonalStore(me, { NAS_NOW }, ledger)
    val albums = PersonalAlbums(store)
    store.applyRemote(
      (0 until 2_000).map { n ->
        PersonalEntry(PersonalKey(PersonalKind.ALBUM, album, PersonalFields.albumItem(mediaId(100_000 + n))), NAS_NOW - 60_000, other, PersonalValue.Number(n * 1_000L), fp(n))
      },
    )
    ledger.meter(Long.MAX_VALUE)
    for (n in 0 until 10) albums.addItem(album, mediaId(n), (n + 1) * 1_000L, fp(n))
    assertThat(ledger.saves).isEqualTo(10)
    assertThat(ledger.bytes).isGreaterThan(9 * file.length())

    ledger.meter(Long.MAX_VALUE)
    albums.batch { for (n in 10 until 20) addItem(album, mediaId(n), (n + 1) * 1_000L, fp(n)) }
    assertThat(ledger.saves).isEqualTo(1)
    assertThat(ledger.bytes).isEqualTo(file.length())
  }

  private companion object {
    const val REGISTRY = 20_000
    const val PHOTOS = 2_000

    /** "Un tempo ragionevole" anche su un PC carico: una voce alla volta erano 44 secondi sul PC libero. */
    const val MAX_MS = 10_000L
  }
}
