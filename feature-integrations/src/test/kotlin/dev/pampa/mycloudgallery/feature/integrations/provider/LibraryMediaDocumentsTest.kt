package dev.pampa.mycloudgallery.feature.integrations.provider

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.integrations.contract.DocumentEntry
import dev.pampa.mycloudgallery.feature.integrations.contract.DocumentId
import dev.pampa.mycloudgallery.feature.integrations.contract.DocumentIds
import dev.pampa.mycloudgallery.feature.integrations.provider.testing.FakeIntegrationLibrary
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fase 5B (P10, D23, C17; E-H1, E-X1): i documenti sopra la Libreria per le altre app, con il telefono finto e il NAS finto. Elenchi dalla
 * vista di adesso, id opachi, nomi sicuri, scadenza, e una porta che "sbaglia" (restituisce nascosti e file che l'indice non ha).
 */
@RunWith(AndroidJUnit4::class)
class LibraryMediaDocumentsTest {

  private val library = FakeIntegrationLibrary()
  private val labels = DocumentLabels(root = "MyCloud Gallery", recent = "Recenti", nasFolders = "Cartelle NAS")
  private val documents = LibraryMediaDocuments(library, labels)

  @Before
  fun reset() = IntegrationCounters.reset()

  @After
  fun cleanup() = IntegrationCounters.reset()

  private fun <T> run(block: suspend () -> T): T = runBlocking { block() }

  private fun rootId(): DocumentId = run { documents.roots().single().rootDocument }

  private fun recent(limit: Int = 100): List<DocumentEntry> = run {
    documents.children(rootId(), 10).first { it.displayName == labels.recent }.let { documents.children(it.id, limit) }
  }

  // --- Radice e cartelle -----------------------------------------------------------------------------------------------

  @Test
  fun leEtichetteVengonoDalleStringheDelModulo() {
    val fromResources = DocumentLabels.from(androidx.test.core.app.ApplicationProvider.getApplicationContext())
    assertThat(fromResources.root).isEqualTo("MyCloud Gallery")
    assertThat(fromResources.recent).isEqualTo("Recenti")
    assertThat(fromResources.nasFolders).isEqualTo("Cartelle NAS")
  }

  @Test
  fun laRadiceHaRecentiECartelleNas() {
    val root = run { documents.roots() }.single()
    assertThat(root.rootId).isEqualTo(LibraryMediaDocuments.ROOT_ID)
    val entry = run { documents.entry(root.rootDocument) }!!
    assertThat(entry.isFolder).isTrue()
    assertThat(entry.displayName).isEqualTo("MyCloud Gallery")
    val children = run { documents.children(root.rootDocument, 10) }
    assertThat(children.map { it.displayName }).containsExactly("Recenti", "Cartelle NAS").inOrder()
    assertThat(children.all { it.isFolder }).isTrue()
    assertThat(children.map { it.id }.toSet()).hasSize(2)
  }

  @Test
  fun recentiSonoIPiuRecentiDellaVistaEPortanoNomiEMisureDelCatalogo() {
    val entries = recent(limit = 40)
    assertThat(entries).hasSize(40)
    // Dal piu' recente (indice 0) in giu': l'ordine e' quello della vista.
    for (n in 0 until 40) assertThat(entries[n].displayName).startsWith("IMG_%05d.".format(n))
    val first = entries.first()
    assertThat(first.isFolder).isFalse()
    assertThat(first.displayName).startsWith("IMG_00000")
    assertThat(first.size).isGreaterThan(0)
    assertThat(first.mime).matches("(image|video)/[a-z0-9.+-]+")
    // Ogni id e' opaco e diverso.
    assertThat(entries.all { DocumentIds.isOpaque(it.id.value) }).isTrue()
    assertThat(entries.map { it.id }.toSet()).hasSize(40)
  }

  @Test
  fun unaCartellaNonLeggeMaiIByte() {
    recent(limit = 100)
    run { documents.children(rootId(), 10) }
    assertThat(library.nasReaders).isEmpty()
    assertThat(library.phoneReaders).isEmpty()
  }

  @Test
  fun ilTettoDelleCartelleSiRispetta() {
    assertThat(recent(limit = 100_000)).hasSize(DocumentPolicy.MAX_CHILDREN)
    assertThat(recent(limit = 0)).isEmpty()
    assertThat(recent(limit = -5)).isEmpty()
  }

  @Test
  fun leCartelleNasMostranoSoloMediaDellaVista() {
    val folders = run { documents.children(documents.children(rootId(), 10).last().id, 10) }
    assertThat(folders.map { it.displayName }).containsExactly("Area privata", "Area comune")
    val privateArea = folders.first { it.displayName == "Area privata" }
    val sub = run { documents.children(privateArea.id, 10) }
    assertThat(sub.map { it.displayName }).containsExactly("Vacanze")
    val holidays = run { documents.children(sub.single().id, 500) }
    val expected = library.nasOnlyIndices.filter { it % 2 == 0 }
    assertThat(holidays).hasSize(expected.size)
    assertThat(holidays.all { !it.isFolder }).isTrue()
  }

  // --- Dopo un riavvio (IN-2) -----------------------------------------------------------------------------------------

  private fun fileKey(): FileDocumentIdKey {
    val file = java.io.File.createTempFile("doc-id", ".key").also { it.delete(); it.deleteOnExit() }
    return FileDocumentIdKey(file, java.util.Random(1))
  }

  @Test
  fun in2_unaCartellaRicordataDaDocumentsUiSiRitrovaDopoIlRiavvio_EUnIdInventatoNo() {
    val key = fileKey()
    val before = LibraryMediaDocuments(library, labels, DocumentIdTable(idKey = key))
    val root = run { before.roots().single().rootDocument }
    val nas = run { before.children(root, 10).last() }
    val area = run { before.children(nas.id, 10).first { it.displayName == "Area privata" } }
    val holidays = run { before.children(area.id, 10).single() }

    // Il processo nuovo: tutto vuoto in memoria, la stessa chiave sul file.
    val after = LibraryMediaDocuments(library, labels, DocumentIdTable(idKey = key))
    assertThat(run { after.roots().single().rootDocument }).isEqualTo(root)
    assertThat(run { after.entry(nas.id) }?.displayName).isEqualTo("Cartelle NAS")
    assertThat(run { after.entry(holidays.id) }?.displayName).isEqualTo("Vacanze") // anche il nome, dalla Libreria
    assertThat(run { after.children(holidays.id, 500) }).isNotEmpty()
    assertThat(library.folderIndexCalls.get()).isEqualTo(1)

    // Un id che non e' di nessuna cartella resta sconosciuto, e la ricerca non si ripete a ogni id sbagliato (pochi secondi fra una e l'altra).
    val invented = DocumentId("aaaaaaaaaaaaaaaaaaaaaaaa")
    assertThat(run { after.entry(invented) }).isNull()
    assertThat(run { after.children(invented, 10) }).isEmpty()
    assertThat(library.folderIndexCalls.get()).isEqualTo(1)
  }

  @Test
  fun in2_laRicercaDiUnaCartellaNonSiRipeteTroppoSpesso() {
    var now = 0L
    val clock = DocumentClock { now }
    val documents = LibraryMediaDocuments(library, labels, DocumentIdTable(idKey = fileKey()), clock)
    repeat(5) { run { documents.entry(DocumentId("bbbbbbbbbbbbbbbbbbbbbbb$it")) } }
    assertThat(library.folderIndexCalls.get()).isEqualTo(1)
    now += 6_000
    run { documents.entry(DocumentId("ccccccccccccccccccccccc0")) }
    assertThat(library.folderIndexCalls.get()).isEqualTo(2)
  }

  // --- Accesso parziale (IN-9) ----------------------------------------------------------------------------------------------

  /**
   * IN-9 della verifica funzionale 5B: con l'accesso parziale ("solo le foto selezionate") la vista della Libreria e' una parte dell'indice. I Recenti
   * dei documenti scorrevano l'indice intero e mostravano anche le foto che il telefono non concede (senza miniatura) e i solo-NAS che la Libreria nasconde.
   */
  @Test
  fun in9_conLAccessoParzialeIRecentiSonoLaVistaNonLIndice() {
    val allowed = library.visibleIds.filterIndexed { n, _ -> n % 7 == 3 }.toSet()
    library.restrictView(allowed)
    val names = recent(limit = 500)
    assertThat(names).hasSize(allowed.size)
    // Un media fuori dalla vista non si descrive, non si apre, e il suo id (se ne esisteva uno) si revoca.
    val outside = library.visibleIds.first { it !in allowed }
    val token = documents.issue(outside)
    assertThat(token).isNull()
    assertThat(IntegrationCounters.get(IntegrationCounter.HIDDEN_STOPPED)).isEqualTo(1L)
    val inside = allowed.first()
    assertThat(documents.issue(inside)).isNotNull()
  }

  // --- Nascosti (E-H1) ------------------------------------------------------------------------------------------------

  @Test
  fun unaPortaCheSbagliaNonFaUscireINascosti() {
    library.leakHidden = true
    val holidays = run {
      val areas = documents.children(documents.children(rootId(), 10).last().id, 10)
      val sub = documents.children(areas.first { it.displayName == "Area privata" }.id, 10)
      documents.children(sub.single().id, 500)
    }
    assertThat(holidays.map { it.displayName }.filter { it.startsWith("SEGRETO_") }).isEmpty()
    assertThat(IntegrationCounters.get(IntegrationCounter.HIDDEN_STOPPED)).isEqualTo(library.hiddenIds.size.toLong())
    // Nemmeno nei recenti.
    assertThat(recent(limit = 500).map { it.displayName }.filter { it.startsWith("SEGRETO_") }).isEmpty()
  }

  @Test
  fun unGruppoDiCopieSimiliConUnNascostoNonCompareDallaPortaDelleSueCopie() {
    // D36, E-H1: una foto nascosta nasconde l'intero gruppo. Il catalogo toglie dall'indice tutte le copie; i file restano sul NAS e la porta
    // delle cartelle li elenca ancora: la stessa foto non deve ricomparire dalla porta della sua copia.
    val group = library.nasOnlyIndices.filter { it % 2 == 0 }.take(3).map { library.visibleIds[it] }
    assertThat(group).hasSize(3)
    library.hide(group.toSet())
    val holidays = run {
      val areas = documents.children(documents.children(rootId(), 10).last().id, 10)
      val sub = documents.children(areas.first { it.displayName == "Area privata" }.id, 10)
      documents.children(sub.single().id, 500)
    }
    val groupNames = group.map { library.synthetic.snapshot.indexOf(it) }.map { "IMG_%05d.".format(it) }
    for (name in groupNames) assertThat(holidays.map { it.displayName }.any { it.startsWith(name) }).isFalse()
    assertThat(IntegrationCounters.get(IntegrationCounter.HIDDEN_STOPPED)).isAtLeast(3L)
  }

  @Test
  fun unMediaNascostoDopoLaNascitaDellIdNonSiApreNeSiDescrivePiu() {
    val entry = recent(limit = 5).first()
    assertThat(run { documents.entry(entry.id) }).isNotNull()
    val media = library.visibleIds.first()
    library.hide(setOf(media))
    assertThat(run { documents.entry(entry.id) }).isNull()
    assertThat(run { documents.open(entry.id) }).isNull()
    // L'id e' revocato: anche rimettendo il media in vista, quel token non torna a valere.
    library.showAll()
    assertThat(run { documents.entry(entry.id) }).isNull()
  }

  @Test
  fun lAperturaDiUnMediaNascostoDopoLaNascitaDellIdNonTocca_NemmenoIlFile() {
    // La porta "sbaglia" e darebbe i byte: a fermare l'apertura e' il controllo sulla vista di adesso, prima di aprire qualunque file.
    library.leakHidden = true
    val media = library.phoneIds().first()
    val token = documents.issue(media)!!
    library.hide(setOf(media))
    assertThat(run { documents.open(token) }).isNull()
    assertThat(library.phoneReaders).isEmpty()
    assertThat(library.nasReaders).isEmpty()
  }

  @Test
  fun unNascostoNonSiConsegnaNeSiApreEvenSeLaPortaLoConosce() {
    library.leakHidden = true
    val hidden = library.hiddenIds.first()
    assertThat(documents.issue(hidden)).isNull()
    assertThat(IntegrationCounters.get(IntegrationCounter.HIDDEN_STOPPED)).isEqualTo(1L)
  }

  // --- Apertura ---------------------------------------------------------------------------------------------------------

  @Test
  fun siApreUnaCopiaDelTelefonoEUnMediaSoloNas() {
    val entries = recent(limit = 200)
    val phone = library.phoneIds().first()
    val nas = library.nasOnlyIds().first()
    for (id in listOf(phone, nas)) {
      val token = documents.issue(id)!!
      val reader = run { documents.open(token) }!!
      val buffer = ByteArray(64)
      assertThat(run { reader.read(0, buffer, 0, 64) }).isEqualTo(64)
      reader.close()
    }
    assertThat(library.phoneReaders).hasSize(1)
    assertThat(library.nasReaders).hasSize(1)
    assertThat(entries).isNotEmpty()
  }

  @Test
  fun unNasChePersoRisponde_NullaSubito() {
    library.nasOpenFails = true
    val token = documents.issue(library.nasOnlyIds().first())!!
    assertThat(run { documents.open(token) }).isNull()
  }

  @Test
  fun unaCartellaOUnIdSconosciutoNonSiApre() {
    assertThat(run { documents.open(rootId()) }).isNull()
    assertThat(run { documents.open(DocumentId("zzzzzzzzzzzzzzzzzzzzzzzz")) }).isNull()
    assertThat(run { documents.entry(DocumentId("zzzzzzzzzzzzzzzzzzzzzzzz")) }).isNull()
    assertThat(run { documents.children(DocumentId("zzzzzzzzzzzzzzzzzzzzzzzz"), 10) }).isEmpty()
  }

  @Test
  fun unIdScadutoNonApreNiente_E_X1() {
    val clock = object : DocumentClock {
      var now = 0L
      override fun elapsedMs(): Long = now
    }
    val expiring = LibraryMediaDocuments(library, labels, DocumentIdTable(clock, java.util.Random(1), browseTtlMs = 1_000, deliveredTtlMs = 5_000))
    val id = library.nasOnlyIds().first()
    val token = expiring.issue(id)!!
    clock.now = 4_000
    assertThat(run { expiring.entry(token) }).isNotNull() // un consegnato vale piu' a lungo di uno da sfoglio, e si rinnova con l'uso
    clock.now = 4_000 + 6_000
    assertThat(run { expiring.entry(token) }).isNull()
    assertThat(run { expiring.open(token) }).isNull()
    assertThat(IntegrationCounters.get(IntegrationCounter.PROVIDER_EXPIRED)).isAtLeast(1L)
  }

  // --- Id e nomi --------------------------------------------------------------------------------------------------------

  @Test
  fun neGliIdNeLeRigheContengonoPercorsiShareAccountOIdInterni() {
    val entries = recent(limit = 100) + run {
      val areas = documents.children(documents.children(rootId(), 10).last().id, 10)
      areas.flatMap { documents.children(it.id, 100) }
    }
    val forbidden = library.visibleIds.map { it.value } + listOf("area-privata", "area-comune", "vacanze", "external_primary", "SEGRETO")
    for (entry in entries) {
      for (word in forbidden) assertThat(entry.id.value).doesNotContain(word)
      assertThat(entry.toString()).doesNotContain("IMG_")
    }
  }

  @Test
  fun iNomiOstiliSiRipulisconoENonPortanoMaiUnPercorso() {
    val ids = library.visibleIds.take(6)
    library.nameOverride[ids[0]] = "../../etc/passwd"
    library.nameOverride[ids[1]] = "..\\..\\sdcard\\x.jpg"
    library.nameOverride[ids[2]] = "foto\u0000.jpg\u0007"
    library.nameOverride[ids[3]] = "...."
    library.nameOverride[ids[4]] = "   "
    library.nameOverride[ids[5]] = "a".repeat(5_000)
    library.mimeOverride[ids[5]] = "text/html; charset=\"><script>"
    val entries = recent(limit = 6)
    for (entry in entries) {
      assertThat(entry.displayName).doesNotContain("/")
      assertThat(entry.displayName).doesNotContain("\\")
      assertThat(entry.displayName.any { it.isISOControl() }).isFalse()
      assertThat(entry.displayName).isNotEmpty()
      assertThat(entry.displayName.length).isAtMost(200)
    }
    assertThat(entries[0].displayName).isEqualTo(".._.._etc_passwd")
    assertThat(entries[3].displayName).isEqualTo("media")
    assertThat(entries[4].displayName).isEqualTo("media")
    assertThat(entries[5].mime).isEqualTo("application/octet-stream")
  }

  @Test
  fun unIdConsegnatoESempreLoStessoFincheVale() {
    val id = library.nasOnlyIds().first()
    val a = documents.issue(id)!!
    val b = documents.issue(id)!!
    assertThat(a).isEqualTo(b)
    assertThat(documents.isDelivered(a)).isTrue()
    documents.revoke(listOf(a))
    assertThat(documents.isDelivered(a)).isFalse()
    assertThat(run { documents.open(a) }).isNull()
    val c = documents.issue(id)!!
    assertThat(c).isNotEqualTo(a)
    documents.revokeAll()
    assertThat(documents.isDelivered(c)).isFalse()
  }

  @Test
  fun unaFamigliaCheNonCEPiuNonOffreNiente() {
    val none = LibraryMediaDocuments(NoIntegrationLibrary, labels)
    assertThat(run { none.children(none.roots().single().rootDocument, 10) }.map { it.displayName }).containsExactly("Recenti", "Cartelle NAS")
    val recentFolder = run { none.children(none.roots().single().rootDocument, 10) }.first()
    assertThat(run { none.children(recentFolder.id, 100) }).isEmpty()
    assertThat(none.issue(MediaId.random())).isNull()
  }
}
