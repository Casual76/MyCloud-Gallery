package dev.pampa.mycloudgallery.core.personal

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFields
import dev.pampa.mycloudgallery.core.personal.contract.PersonalFingerprint
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKey
import dev.pampa.mycloudgallery.core.personal.contract.PersonalKind
import dev.pampa.mycloudgallery.core.personal.contract.PersonalPublication
import dev.pampa.mycloudgallery.core.personal.contract.PersonalRules
import dev.pampa.mycloudgallery.core.personal.contract.PersonalValue
import dev.pampa.mycloudgallery.core.personal.contract.SimilarCopiesRecords
import dev.pampa.mycloudgallery.core.personal.store.FilePersonalLedger
import dev.pampa.mycloudgallery.core.personal.store.LedgerPersonalStore
import dev.pampa.mycloudgallery.core.personal.store.LedgerSnapshot
import dev.pampa.mycloudgallery.core.personal.store.LocalFlag
import dev.pampa.mycloudgallery.core.personal.store.PendingChange
import dev.pampa.mycloudgallery.core.personal.store.PersonalProjection
import dev.pampa.mycloudgallery.core.personal.store.ProjectionResult
import dev.pampa.mycloudgallery.core.personal.testing.InMemoryPersonalLedger
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import dev.pampa.mycloudgallery.core.trust.data.HeadMark
import java.io.File
import java.io.IOException
import java.util.zip.CRC32
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Lo store locale: l'ora delle modifiche, le modifiche "da datare", il registro su disco e la proiezione sul catalogo. Il registro
 * e' tutto cio' che sopravvive alla morte del processo; un file rovinato vale "vuoto", mai un'eccezione.
 */
class LedgerStoreTest {

  @get:Rule
  val folder = TemporaryFolder()

  private fun device(n: Int) = Base32.encode(ByteArray(16) { n.toByte() })

  private val me = device(1)
  private val other = device(2)
  private val revoked = device(3)
  private val m1 = mediaId(1)
  private val m2 = mediaId(2)
  private val fav1 = flagKey(m1, PersonalFields.FAVORITE)
  private val hid1 = flagKey(m1, PersonalFields.HIDDEN)

  /** Una proiezione che registra cio' che le arriva, con esiti e guasti programmabili. */
  private class RecordingProjection : PersonalProjection {
    val applied = ArrayList<PersonalEntry>()
    var result: (PersonalEntry) -> ProjectionResult = { ProjectionResult.APPLIED }
    var failOnce = false
    var local: List<LocalFlag> = emptyList()
    var fingerprint: PersonalFingerprint? = null

    override suspend fun apply(entry: PersonalEntry): ProjectionResult {
      if (failOnce) {
        failOnce = false
        throw IOException("catalogo non disponibile")
      }
      applied += entry
      return result(entry)
    }

    override suspend fun fingerprintOf(key: PersonalKey): PersonalFingerprint? = fingerprint

    override suspend fun localFlags(): List<LocalFlag> = local
  }

  private var nasNow: Long? = NAS_NOW

  private fun store(
    ledger: InMemoryPersonalLedger = InMemoryPersonalLedger(),
    projection: PersonalProjection = RecordingProjection(),
    author: String = me,
  ) = LedgerPersonalStore(author, { nasNow }, ledger, projection)

  // --- Ora delle modifiche -----------------------------------------------------------------------------------------------------------

  @Test
  fun unaModificaPrendeLOraDelNasEAppareSubitoNelloStato() = runTest {
    val store = store()
    store.set(fav1, PersonalValue.Flag(true))
    val e = store.get(fav1)!!
    assertThat(e.atNas).isEqualTo(NAS_NOW)
    assertThat(e.author).isEqualTo(me)
    assertThat(store.entries.value).containsKey(fav1)
    assertThat(store.undated()).isEmpty()
  }

  @Test
  fun senzaOraDelNasLaModificaRestaDaDatareEHaGiaLEffettoLocale() = runTest {
    val projection = RecordingProjection()
    val store = store(projection = projection)
    nasNow = null
    store.set(hid1, PersonalValue.Flag(true))
    assertThat(store.undated()).containsExactly(hid1)
    assertThat(store.get(hid1)).isNull()
    // L'utente ha nascosto la foto: sparisce subito dalla Libreria anche se la voce non ha ancora un'ora.
    assertThat(projection.applied.map { it.key to it.value }).containsExactly(hid1 to PersonalValue.Flag(true))

    store.stamp(NAS_NOW + 5_000)
    assertThat(store.undated()).isEmpty()
    assertThat(store.get(hid1)!!.atNas).isEqualTo(NAS_NOW + 5_000)
  }

  @Test
  fun tornareAlValoreGiaNelloStatoCancellaLaModificaDaDatare() = runTest {
    val store = store()
    store.set(fav1, PersonalValue.Flag(true))
    nasNow = null
    store.set(fav1, PersonalValue.Flag(false))
    assertThat(store.undated()).containsExactly(fav1)
    store.set(fav1, PersonalValue.Flag(true))
    assertThat(store.undated()).isEmpty()
  }

  @Test
  fun nonSiScriveUnaVoceNuovaSeIlValoreNonCambia() = runTest {
    val ledger = InMemoryPersonalLedger()
    val store = store(ledger)
    store.set(fav1, PersonalValue.Flag(true))
    val first = store.get(fav1)!!
    nasNow = NAS_NOW + 60_000
    store.set(fav1, PersonalValue.Flag(true))
    assertThat(store.get(fav1)).isEqualTo(first)
  }

  @Test
  fun unaModificaNonPrecedeMaiCioCheIlDispositivoGiaSa() = runTest {
    val store = store()
    // Una voce di un altro dispositivo, a un'ora del NAS un minuto dopo la nostra (un orologio sfasato, dentro i 2 minuti).
    store.applyRemote(listOf(PersonalEntry(fav1, NAS_NOW + 60_000, other, PersonalValue.Flag(true))))
    store.set(fav1, PersonalValue.Flag(false))
    val mine = store.get(fav1)!!
    assertThat(mine.value).isEqualTo(PersonalValue.Flag(false))
    assertThat(mine.atNas).isEqualTo(NAS_NOW + 60_001)
    assertThat(mine.author).isEqualTo(me)
    // Lo stesso per una modifica da datare.
    nasNow = null
    store.set(hid1, PersonalValue.Flag(true))
    store.applyRemote(listOf(PersonalEntry(hid1, NAS_NOW + 90_000, other, PersonalValue.Flag(false))))
    store.stamp(NAS_NOW)
    assertThat(store.get(hid1)!!.atNas).isEqualTo(NAS_NOW + 90_001)
    assertThat(store.get(hid1)!!.value).isEqualTo(PersonalValue.Flag(true))
  }

  @Test
  fun unaVoceDiUnAltroNonCancellaLaScelta_ancoraDaDatare_dellUtente() = runTest {
    val projection = RecordingProjection()
    val store = store(projection = projection)
    nasNow = null
    store.set(hid1, PersonalValue.Flag(true))
    projection.applied.clear()
    // Arriva dal NAS un "non nascosto" di un altro dispositivo: entra nello stato ma NON nel catalogo (la foto resta nascosta).
    store.applyRemote(listOf(PersonalEntry(hid1, NAS_NOW - 5_000, other, PersonalValue.Flag(false))))
    assertThat(projection.applied).isEmpty()
    assertThat(store.get(hid1)!!.value).isEqualTo(PersonalValue.Flag(false))
    // Alla prima sincronizzazione la scelta dell'utente prende l'ora e vince.
    store.stamp(NAS_NOW)
    assertThat(store.get(hid1)!!.value).isEqualTo(PersonalValue.Flag(true))
    assertThat(store.get(hid1)!!.author).isEqualTo(me)
  }

  @Test
  fun iTipiRiservatiNonSiScrivonoNeSiApplicano() = runTest {
    val store = store()
    val person = PersonalKey(PersonalKind.PERSON, "p", "name")
    assertThrows(IllegalArgumentException::class.java) { runBlocking { store.set(person, PersonalValue.Text("Mario")) } }
    val result = store.applyRemote(listOf(PersonalEntry(person, NAS_NOW, other, PersonalValue.Text("Mario"))))
    assertThat(result.applied).isEqualTo(0)
    assertThat(store.entries.value).isEmpty()
  }

  @Test
  fun lApplicazioneContaSoloLeVociCheCambianoLoStato() = runTest {
    val store = store()
    val a = PersonalEntry(fav1, 100 + NAS_NOW, other, PersonalValue.Flag(true))
    val older = PersonalEntry(fav1, 50 + NAS_NOW, other, PersonalValue.Flag(false))
    val b = PersonalEntry(hid1, 10 + NAS_NOW, other, PersonalValue.Flag(true))
    val first = store.applyRemote(listOf(older, a, b))
    assertThat(first.applied).isEqualTo(2)
    assertThat(first.ignored).isEqualTo(1)
    val again = store.applyRemote(listOf(older, a, b))
    assertThat(again.applied).isEqualTo(0)
    assertThat(again.ignored).isEqualTo(3)
  }

  // --- Il registro ---------------------------------------------------------------------------------------------------------------------

  @Test
  fun tuttoSopravvivealLaMorteDelProcesso() = runTest {
    val ledger = InMemoryPersonalLedger()
    val first = store(ledger)
    first.set(fav1, PersonalValue.Flag(true))
    nasNow = null
    first.set(hid1, PersonalValue.Flag(true))
    first.applyFrom(other, listOf(PersonalEntry(flagKey(m2, PersonalFields.FAVORITE), NAS_NOW, other, PersonalValue.Flag(true))), HeadMark(3, "abc"))
    first.setPublished(PersonalPublication(2, HeadMark(2, "def"), NAS_NOW, setOf("s1", "s2"), 2))

    val second = store(ledger)
    assertThat(second.get(fav1)).isEqualTo(first.get(fav1))
    assertThat(second.undated()).containsExactly(hid1)
    assertThat(second.marks()).containsExactly(other, HeadMark(3, "abc"))
    assertThat(second.published()!!.segments).containsExactly("s1", "s2")
    assertThat(second.entries.value).hasSize(2)
  }

  @Test
  fun leVociApplicateELaTestaDaCuiVengonoSiSalvanoInsieme() = runTest {
    val ledger = InMemoryPersonalLedger()
    val store = store(ledger)
    store.applyFrom(other, listOf(PersonalEntry(fav1, NAS_NOW, other, PersonalValue.Flag(true))), HeadMark(1, "h"))
    // Una sola istantanea contiene sia la voce sia la testa: non esiste uno stato con la testa "gia' letta" e senza le sue voci.
    val snap = ledger.snapshot
    assertThat(snap.entries).containsKey(fav1)
    assertThat(snap.marks).containsKey(other)
  }

  @Test
  fun seIlCatalogoCadeASecondaMetaLaVoceSiRifaAlGiroDopo() = runTest {
    val ledger = InMemoryPersonalLedger()
    val projection = RecordingProjection()
    projection.failOnce = true
    val first = store(ledger, projection)
    assertThrows(IOException::class.java) {
      runBlocking { first.applyFrom(other, listOf(PersonalEntry(hid1, NAS_NOW, other, PersonalValue.Flag(true))), HeadMark(1, "h")) }
    }
    // La voce e la testa sono salvate, e la chiave e' segnata come "da portare nel catalogo".
    assertThat(ledger.snapshot.entries).containsKey(hid1)
    assertThat(ledger.snapshot.unprojected).containsExactly(hid1)

    // Il processo riparte: il giro dopo rifa' la proiezione.
    val second = store(ledger, projection)
    assertThat(second.reproject()).isEqualTo(0)
    assertThat(projection.applied.map { it.key }).containsExactly(hid1)
    assertThat(ledger.snapshot.unprojected).isEmpty()
  }

  @Test
  fun unMediaNonAncoraNelCatalogoRestaInAttesaEViene_poi_applicato() = runTest {
    val ledger = InMemoryPersonalLedger()
    val projection = RecordingProjection()
    projection.result = { ProjectionResult.UNRESOLVED }
    val store = store(ledger, projection)
    store.applyFrom(other, listOf(PersonalEntry(hid1, NAS_NOW, other, PersonalValue.Flag(true))), null)
    assertThat(ledger.snapshot.unprojected).containsExactly(hid1)
    assertThat(store.reproject()).isEqualTo(1)
    // Il media arriva (la replica lo ha portato nel catalogo): la voce si applica.
    projection.result = { ProjectionResult.APPLIED }
    assertThat(store.reproject()).isEqualTo(0)
    assertThat(ledger.snapshot.unprojected).isEmpty()
  }

  @Test
  fun unGuastoDelDiscoNonCambiaNienteNeSulDiscoNeInMemoriaESiRiprovaDopo() = runTest {
    val ledger = InMemoryPersonalLedger()
    val store = store(ledger)
    store.set(fav1, PersonalValue.Flag(true))
    ledger.failNextSave = true
    assertThrows(IOException::class.java) { runBlocking { store.set(hid1, PersonalValue.Flag(true)) } }
    // Ne' sul disco ne' in memoria: la modifica che non si e' potuta salvare non c'e' (SOL-10: chi chiama dice "non salvato", e dirlo e' vero).
    assertThat(ledger.snapshot.entries.keys).containsExactly(fav1)
    assertThat(store.entries.value.keys).containsExactly(fav1)
    assertThat(store.effectiveEntries.value.keys).containsExactly(fav1)
    // Chi riprova la porta sul disco.
    store.set(hid1, PersonalValue.Flag(true))
    assertThat(ledger.snapshot.entries.keys).containsExactly(fav1, hid1)
  }

  // --- Revoca e modifiche locali del catalogo -----------------------------------------------------------------------------------------

  @Test
  fun leVociDiUnAutoreRevocatoSiTolgonoDalloStatoMaNonLeProprie() = runTest {
    val store = store()
    store.set(fav1, PersonalValue.Flag(true))
    store.applyRemote(
      listOf(
        PersonalEntry(hid1, NAS_NOW, revoked, PersonalValue.Flag(true)),
        PersonalEntry(flagKey(m2, PersonalFields.FAVORITE), NAS_NOW, other, PersonalValue.Flag(true)),
      ),
    )
    val rules = PersonalRules(NAS_NOW, approvedAuthors = setOf(other), revokedAuthors = setOf(revoked, me))
    assertThat(store.purgeInvalid(rules)).isEqualTo(1)
    assertThat(store.get(hid1)).isNull()
    assertThat(store.get(fav1)).isNotNull()
    assertThat(store.get(flagKey(m2, PersonalFields.FAVORITE))).isNotNull()
    assertThat(store.purgeInvalid(rules)).isEqualTo(0)
  }

  @Test
  fun lEffettoDiUnaVoceDiUnRevocatoGiaNelCatalogoNonSiAnnullaDaSolo() = runTest {
    // Nel dubbio si lascia com'e' (E-H1): un nascosto non riappare per una revoca. Il costo e' dichiarato: se il revocato avesse
    // mostrato una foto nascosta, il catalogo la mostra ancora finche' l'utente non la nasconde di nuovo.
    val projection = RecordingProjection()
    val store = store(projection = projection)
    store.applyRemote(listOf(PersonalEntry(hid1, NAS_NOW, revoked, PersonalValue.Flag(true))))
    projection.applied.clear()
    store.purgeInvalid(PersonalRules(NAS_NOW, approvedAuthors = emptySet(), revokedAuthors = setOf(revoked)))
    assertThat(projection.applied).isEmpty()
  }

  @Test
  fun lePreferenzeAccesePrimaDellaSincronizzazioneSiAdottanoUnaVoltaSola() = runTest {
    val projection = RecordingProjection()
    projection.local = listOf(
      LocalFlag(m1, PersonalFields.FAVORITE, true),
      LocalFlag(m1, PersonalFields.HIDDEN, false),
      LocalFlag(m2, PersonalFields.HIDDEN, true),
    )
    projection.fingerprint = PersonalFingerprint(1_000, QuickHash("cd".repeat(32)))
    val store = store(projection = projection)
    assertThat(store.adoptLocal()).isEqualTo(2)
    assertThat(store.get(fav1)!!.value).isEqualTo(PersonalValue.Flag(true))
    assertThat(store.get(fav1)!!.fingerprint).isNotNull()
    assertThat(store.get(flagKey(m2, PersonalFields.HIDDEN))!!.author).isEqualTo(me)
    assertThat(store.get(hid1)).isNull()
    assertThat(store.adoptLocal()).isEqualTo(0)
  }

  @Test
  fun unPreferitoTolto_dalLibreriaSenzaPassareDalloStore_siAdottaComeModificaLocale() = runTest {
    val projection = RecordingProjection()
    val store = store(projection = projection)
    store.set(fav1, PersonalValue.Flag(true))
    projection.local = listOf(LocalFlag(m1, PersonalFields.FAVORITE, false))
    nasNow = NAS_NOW + 10_000
    assertThat(store.adoptLocal()).isEqualTo(1)
    assertThat(store.get(fav1)!!.value).isEqualTo(PersonalValue.Flag(false))
    assertThat(store.get(fav1)!!.atNas).isEqualTo(NAS_NOW + 10_000)
  }

  @Test
  fun cioCheStaAncoraPerEntrareNelCatalogoNonSiScambiaPerUnaModificaLocale() = runTest {
    val projection = RecordingProjection()
    projection.result = { ProjectionResult.UNRESOLVED }
    val store = store(projection = projection)
    store.applyRemote(listOf(PersonalEntry(fav1, NAS_NOW, other, PersonalValue.Flag(true))))
    // Il catalogo dice "non preferito" perche' la proiezione non e' ancora avvenuta: lo stato unito vince.
    projection.local = listOf(LocalFlag(m1, PersonalFields.FAVORITE, false))
    assertThat(store.adoptLocal()).isEqualTo(0)
    assertThat(store.get(fav1)!!.author).isEqualTo(other)
    // E una modifica locale ancora da datare non si adotta due volte.
    nasNow = null
    store.set(hid1, PersonalValue.Flag(true))
    projection.local = listOf(LocalFlag(m1, PersonalFields.HIDDEN, true))
    assertThat(store.adoptLocal()).isEqualTo(0)
  }

  // --- Il file ---------------------------------------------------------------------------------------------------------------------------

  private fun fullSnapshot(): LedgerSnapshot {
    val fp = PersonalFingerprint(5, QuickHash("ab".repeat(32)))
    val entries = listOf(
      PersonalEntry(fav1, NAS_NOW, me, PersonalValue.Flag(true), fp),
      PersonalEntry(hid1, NAS_NOW + 1, other, PersonalValue.Flag(false)),
      PersonalEntry(PersonalKey(PersonalKind.ALBUM, "a", PersonalFields.ALBUM_DEFINITION), NAS_NOW, me, PersonalValue.Text("Oslo è")),
      PersonalEntry(PersonalKey(PersonalKind.ALBUM, "a", PersonalFields.ALBUM_ORDER), NAS_NOW, me, PersonalValue.Number(-4)),
      PersonalEntry(PersonalKey(PersonalKind.ALBUM, "a", PersonalFields.albumItem(m2)), NAS_NOW, me, PersonalValue.Removed),
      // 20/10: una separazione con l'impronta dell'altro media.
      PersonalEntry(SimilarCopiesRecords.separation(m1, m2), NAS_NOW, me, PersonalValue.Apart(PersonalFingerprint(9, QuickHash("cd".repeat(32)))), fp),
    )
    return LedgerSnapshot(
      entries = entries.associateBy { it.key },
      pending = mapOf(
        flagKey(m2, PersonalFields.HIDDEN) to PendingChange(PersonalValue.Flag(true), fp),
        SimilarCopiesRecords.separation(m2, mediaId(3)) to PendingChange(PersonalValue.Apart(fp), null),
      ),
      unprojected = setOf(hid1),
      marks = mapOf(other to HeadMark(7, "cafe")),
      published = PersonalPublication(4, HeadMark(4, "babe"), NAS_NOW, setOf("x", "y"), 5),
    )
  }

  @Test
  fun ilFileTornaIdentico() = runTest {
    val file = File(folder.newFolder(), "personal.ledger")
    val ledger = FilePersonalLedger(file)
    assertThat(ledger.load().entries).isEmpty()
    val original = fullSnapshot()
    ledger.save(original)
    val back = FilePersonalLedger(file).load()
    assertThat(back.entries).isEqualTo(original.entries)
    assertThat(back.pending).isEqualTo(original.pending)
    assertThat(back.unprojected).isEqualTo(original.unprojected)
    assertThat(back.marks).isEqualTo(original.marks)
    assertThat(back.published).isEqualTo(original.published)
    // Un temporaneo non resta in giro.
    assertThat(file.parentFile.listFiles()!!.map { it.name }).containsExactly("personal.ledger")
    // E si riscrive sopra senza problemi.
    ledger.save(LedgerSnapshot.EMPTY)
    assertThat(FilePersonalLedger(file).load().entries).isEmpty()
  }

  /** Un file con intestazione e CRC giusti attorno a un contenuto qualunque. */
  private fun forge(payload: ByteArray): ByteArray {
    val crc = CRC32().apply { update(payload) }.value
    val trailer = ByteArray(8) { i -> (crc ushr (56 - 8 * i)).toByte() }
    return "MCGPL".toByteArray(Charsets.US_ASCII) + byteArrayOf(1) + payload + trailer
  }

  @Test
  fun unFileRovinatoTroncatoOEstranVale_vuoto_senzaEccezioni() = runTest {
    val file = File(folder.newFolder(), "personal.ledger")
    FilePersonalLedger(file).save(fullSnapshot())
    val good = file.readBytes()

    fun loadOf(bytes: ByteArray): LedgerSnapshot {
      file.writeBytes(bytes)
      return runBlocking { FilePersonalLedger(file).load() }
    }
    // Un byte cambiato in qualunque punto: il CRC lo vede.
    for (at in listOf(0, 5, 6, 10, good.size / 2, good.size - 9, good.size - 1)) {
      val flipped = good.copyOf().also { it[at] = (it[at] + 1).toByte() }
      assertThat(loadOf(flipped).entries).isEmpty()
    }
    // Troncato in qualunque punto, vuoto, spazzatura, un'altra versione.
    for (cut in listOf(0, 1, 5, 6, 7, good.size / 2, good.size - 1)) assertThat(loadOf(good.copyOf(cut)).entries).isEmpty()
    assertThat(loadOf(ByteArray(1_000) { it.toByte() }).entries).isEmpty()
    assertThat(loadOf(good.copyOf().also { it[5] = 9 }).entries).isEmpty()
    // Un file con il CRC giusto che dichiara miliardi di voci non fa allocare niente.
    val claim = byteArrayOf(0x7f, 0xff.toByte(), 0xff.toByte(), 0xff.toByte())
    assertThat(loadOf(forge(claim)).entries).isEmpty()
    assertThat(loadOf(forge(ByteArray(0))).entries).isEmpty()
    // Quello buono resta buono.
    assertThat(loadOf(good).entries).isNotEmpty()
    // Una cartella al posto del file, un file che non c'e'.
    assertThat(runBlocking { FilePersonalLedger(File(folder.newFolder(), "nuovo")).load() }.entries).isEmpty()
    assertThat(runBlocking { FilePersonalLedger(folder.newFolder()).load() }.entries).isEmpty()
  }

  @Test
  fun loStoreSopraUnFileSiRiprendeDopoLaMorteDelProcesso() = runTest {
    val file = File(folder.newFolder(), "sub/personal.ledger")
    val first = LedgerPersonalStore(me, { null }, FilePersonalLedger(file), RecordingProjection())
    first.set(fav1, PersonalValue.Flag(true))
    first.applyRemote(listOf(PersonalEntry(hid1, NAS_NOW, other, PersonalValue.Flag(true))))
    val second = LedgerPersonalStore(me, { NAS_NOW }, FilePersonalLedger(file), RecordingProjection())
    assertThat(second.undated()).containsExactly(fav1)
    assertThat(second.get(hid1)!!.author).isEqualTo(other)
    second.stamp(NAS_NOW + 1)
    assertThat(LedgerPersonalStore(me, { null }, FilePersonalLedger(file), RecordingProjection()).get(fav1)!!.atNas).isEqualTo(NAS_NOW + 1)
  }
}
