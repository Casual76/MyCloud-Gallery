package dev.pampa.mycloudgallery.feature.integrations.provider

import android.app.AuthenticationRequiredException
import android.database.Cursor
import android.os.Bundle
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.system.ErrnoException
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.integrations.contract.DocumentIds
import dev.pampa.mycloudgallery.feature.integrations.contract.EntryDecision
import dev.pampa.mycloudgallery.feature.integrations.contract.IntegrationPorts
import dev.pampa.mycloudgallery.feature.integrations.provider.testing.FakeIntegrationLibrary
import dev.pampa.mycloudgallery.feature.integrations.testing.FakeEntryGate
import java.io.FileNotFoundException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fase 5B (P10, D23, C17; E-H1, E-X1, E-X6, E-X7): `MediaDocumentsProvider` con Robolectric, dall'esterno. Un'altra app non ottiene niente senza
 * un tocco (uno sblocco e una scelta) e senza un permesso su quel documento: radici, elenchi, descrizioni, tipi e aperture. Il sistema
 * (`MANAGE_DOCUMENTS`) sfoglia solo con l'app sbloccata. Sola lettura. Il NAS che non risponde non lascia in attesa.
 */
@RunWith(AndroidJUnit4::class)
class MediaDocumentsProviderTest {

  private val library = FakeIntegrationLibrary()
  private val labels = DocumentLabels(root = "MyCloud Gallery", recent = "Recenti", nasFolders = "Cartelle NAS")
  private val clock = object : DocumentClock {
    var now = 0L
    override fun elapsedMs(): Long = now
  }
  private val documents = LibraryMediaDocuments(library, labels, DocumentIdTable(clock, java.util.Random(9)))
  private val gate = FakeEntryGate(EntryDecision.ALLOWED)
  private val caller = ScriptedCaller()
  private val opener = FakeProxyOpener()
  private val handlers = CountingHandlers()
  private val authority = ProviderTestSupport.authority

  @Before
  fun setUp() {
    IntegrationCounters.reset()
    IntegrationPorts.install(gate, documents)
    ProviderTuning.reset()
    ProviderTuning.callerChecks = { caller }
    ProviderTuning.proxyOpener = { opener }
    ProviderTuning.handlers = handlers
    ProviderTuning.openDeadlineMs = 400
    ProviderTuning.readDeadlineMs = 200
    caller.documentsUi()
  }

  @After
  fun tearDown() {
    IntegrationPorts.reset()
    ProviderTuning.reset()
    IntegrationCounters.reset()
    opener.cleanup()
  }

  private fun provider() = ProviderTestSupport.provider()

  private fun roots(provider: MediaDocumentsProvider): Cursor = provider.query(ProviderTestSupport.rootsUri(), null, Bundle.EMPTY, null)!!

  private fun rootDocumentId(provider: MediaDocumentsProvider): String =
    roots(provider).use { it.moveToFirst(); it.getString(it.getColumnIndexOrThrow(Root.COLUMN_DOCUMENT_ID)) }

  private fun children(provider: MediaDocumentsProvider, id: String): List<Row> =
    provider.query(ProviderTestSupport.childrenUri(id), null, Bundle.EMPTY, null)!!.use { rows(it) }

  private class Row(val id: String, val name: String, val mime: String, val flags: Int, val size: Long?)

  private fun rows(cursor: Cursor): List<Row> = buildList {
    val id = cursor.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID)
    val name = cursor.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME)
    val mime = cursor.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE)
    val flags = cursor.getColumnIndexOrThrow(Document.COLUMN_FLAGS)
    val size = cursor.getColumnIndexOrThrow(Document.COLUMN_SIZE)
    while (cursor.moveToNext()) {
      add(Row(cursor.getString(id), cursor.getString(name), cursor.getString(mime), cursor.getInt(flags), if (cursor.isNull(size)) null else cursor.getLong(size)))
    }
  }

  /** Un id di un media che il sistema sfoglia nei recenti. */
  private fun browseId(provider: MediaDocumentsProvider, position: Int = 0): String {
    val recentFolder = children(provider, rootDocumentId(provider)).first { it.name == "Recenti" }
    return children(provider, recentFolder.id)[position].id
  }

  @Test
  fun ilProviderPretendeManageDocumentsEGrantUriPermissions() {
    // Senza la protezione del sistema il provider si rifiuta di nascere: non puo' essere esportato "aperto".
    val unprotected = android.content.pm.ProviderInfo().apply { authority = "test.documents.aperto"; exported = true; grantUriPermissions = true }
    assertThrows(SecurityException::class.java) {
      org.robolectric.Robolectric.buildContentProvider(MediaDocumentsProvider::class.java).create(unprotected)
    }
    val notExported = android.content.pm.ProviderInfo().apply {
      authority = "test.documents.chiuso"
      exported = false
      grantUriPermissions = true
      readPermission = AndroidCallerChecks.MANAGE_DOCUMENTS
      writePermission = AndroidCallerChecks.MANAGE_DOCUMENTS
    }
    assertThrows(SecurityException::class.java) {
      org.robolectric.Robolectric.buildContentProvider(MediaDocumentsProvider::class.java).create(notExported)
    }
  }

  @Test
  fun lAutoritaDelCodiceEQuellaDelManifest() {
    val manifest = java.io.File("src/main/AndroidManifest.xml").readText().replace(Regex("""<!--[\s\S]*?-->"""), "")
    val declared = Regex("""android:authorities="([^"]+)"""").findAll(manifest).map { it.groupValues[1] }.toList()
    assertThat(declared).contains("$" + "{applicationId}" + DocumentsAuthority.SUFFIX)
    assertThat(DocumentsAuthority.of(ProviderTestSupport.context)).isEqualTo(ProviderTestSupport.context.packageName + ".integrations.documents")
  }

  // --- Radice e righe --------------------------------------------------------------------------------------------------

  @Test
  fun laRadiceEUnaSolaConIlNomeEDueTipi() {
    val provider = provider()
    roots(provider).use { cursor ->
      assertThat(cursor.count).isEqualTo(1)
      cursor.moveToFirst()
      assertThat(cursor.getString(cursor.getColumnIndexOrThrow(Root.COLUMN_ROOT_ID))).isEqualTo("mcg-root")
      assertThat(cursor.getString(cursor.getColumnIndexOrThrow(Root.COLUMN_TITLE))).isEqualTo("MyCloud Gallery")
      assertThat(cursor.getString(cursor.getColumnIndexOrThrow(Root.COLUMN_MIME_TYPES))).isEqualTo("image/*\nvideo/*")
      assertThat(cursor.isNull(cursor.getColumnIndexOrThrow(Root.COLUMN_SUMMARY))).isTrue()
      // Nessun "recente" di sistema, nessuna ricerca, nessuna creazione: la radice non annuncia niente di tutto cio'.
      val flags = cursor.getInt(cursor.getColumnIndexOrThrow(Root.COLUMN_FLAGS))
      assertThat(flags and Root.FLAG_SUPPORTS_RECENTS).isEqualTo(0)
      assertThat(flags and Root.FLAG_SUPPORTS_SEARCH).isEqualTo(0)
      assertThat(flags and Root.FLAG_SUPPORTS_CREATE).isEqualTo(0)
      assertThat(flags and Root.FLAG_LOCAL_ONLY).isEqualTo(0)
    }
  }

  @Test
  fun iDocumentiSonoDiSolaLetturaEPortanoSoloNomeTipoEMisure() {
    val provider = provider()
    val folders = children(provider, rootDocumentId(provider))
    assertThat(folders.map { it.name }).containsExactly("Recenti", "Cartelle NAS")
    assertThat(folders.all { it.mime == Document.MIME_TYPE_DIR }).isTrue()
    val files = children(provider, folders.first().id)
    assertThat(files).isNotEmpty()
    val writeFlags = Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME or
      Document.FLAG_SUPPORTS_COPY or Document.FLAG_SUPPORTS_MOVE or Document.FLAG_SUPPORTS_REMOVE or Document.FLAG_DIR_SUPPORTS_CREATE or
      Document.FLAG_SUPPORTS_THUMBNAIL
    for (row in files + folders) {
      assertThat(row.flags and writeFlags).isEqualTo(0)
      assertThat(DocumentIds.isOpaque(row.id)).isTrue()
    }
    assertThat(files.first().name).startsWith("IMG_")
    assertThat(files.first().size).isGreaterThan(0L)
  }

  @Test
  fun leRigheRispettanoLaProiezioneChiesta() {
    val provider = provider()
    val id = browseId(provider)
    provider.query(ProviderTestSupport.documentUri(id), arrayOf(Document.COLUMN_DISPLAY_NAME, "colonna_sconosciuta", Document.COLUMN_SIZE), Bundle.EMPTY, null)!!.use {
      assertThat(it.columnNames.toList()).containsExactly(Document.COLUMN_DISPLAY_NAME, "colonna_sconosciuta", Document.COLUMN_SIZE).inOrder()
      it.moveToFirst()
      assertThat(it.isNull(1)).isTrue()
      assertThat(it.getString(0)).startsWith("IMG_")
    }
  }

  // --- Un'altra app non ottiene niente ---------------------------------------------------------------------------------

  @Test
  fun unaltraAppSenzaPermessiNonOttieneNienteDaNessunaVia() {
    val provider = provider()
    val id = browseId(provider)
    val folder = rootDocumentId(provider)
    caller.stranger()
    assertThrows(SecurityException::class.java) { provider.query(ProviderTestSupport.rootsUri(), null, Bundle.EMPTY, null) }
    assertThrows(SecurityException::class.java) { provider.query(ProviderTestSupport.childrenUri(folder), null, Bundle.EMPTY, null) }
    assertThrows(SecurityException::class.java) { provider.query(ProviderTestSupport.documentUri(id), null, Bundle.EMPTY, null) }
    assertThrows(SecurityException::class.java) { provider.openFile(ProviderTestSupport.documentUri(id), "r") }
    assertThrows(SecurityException::class.java) { provider.openFile(ProviderTestSupport.documentUri(id), "r", CancellationSignal()) }
    assertThrows(SecurityException::class.java) { provider.getType(ProviderTestSupport.documentUri(id)) }
    assertThrows(SecurityException::class.java) { provider.queryRoots(null) }
    assertThrows(SecurityException::class.java) { provider.queryChildDocuments(folder, null, null as String?) }
    assertThrows(SecurityException::class.java) { provider.queryDocument(id, null) }
    assertThrows(SecurityException::class.java) { provider.openDocument(id, "r", null) }
    assertThat(opener.callbacks).isEmpty()
    assertThat(IntegrationCounters.get(IntegrationCounter.PROVIDER_STRANGERS_REFUSED)).isEqualTo(10L)
  }

  @Test
  fun chiHaUnPermessoSuUnDocumentoLoLeggeEBasta() {
    val provider = provider()
    val granted = browseId(provider, 0)
    val other = browseId(provider, 1)
    val folder = rootDocumentId(provider)
    caller.stranger()
    caller.grants += ProviderTestSupport.documentUri(granted).toString()
    // Il documento con il permesso: descrizione, tipo, apertura.
    provider.query(ProviderTestSupport.documentUri(granted), null, Bundle.EMPTY, null)!!.use { assertThat(rows(it)).hasSize(1) }
    assertThat(provider.getType(ProviderTestSupport.documentUri(granted))).matches("(image|video)/.+")
    provider.openFile(ProviderTestSupport.documentUri(granted), "r")!!.close()
    assertThat(opener.callbacks).hasSize(1)
    // Un altro documento, la cartella che lo contiene, la radice: niente.
    assertThrows(SecurityException::class.java) { provider.query(ProviderTestSupport.documentUri(other), null, Bundle.EMPTY, null) }
    assertThrows(SecurityException::class.java) { provider.openFile(ProviderTestSupport.documentUri(other), "r") }
    assertThrows(SecurityException::class.java) { provider.query(ProviderTestSupport.childrenUri(folder), null, Bundle.EMPTY, null) }
    assertThrows(SecurityException::class.java) { provider.query(ProviderTestSupport.rootsUri(), null, Bundle.EMPTY, null) }
    assertThrows(SecurityException::class.java) { provider.queryChildDocuments(granted, null, null as String?) }
  }

  @Test
  fun ilControlloVeroDelChiamanteNegaAChiNonHaNulla() {
    // Il controllo vero (non quello scritto dal test): un chiamante di un altro processo senza permessi viene respinto.
    org.robolectric.shadows.ShadowBinder.setCallingUid(android.os.Process.myUid() + 1000)
    try {
      val checks = AndroidCallerChecks(ProviderTestSupport.context)
      assertThat(checks.isOwnProcess()).isFalse()
      assertThat(checks.hasManageDocuments()).isFalse()
      assertThat(checks.hasReadGrant(ProviderTestSupport.documentUri("aaaaaaaaaaaaaaaaaaaaaaaa"))).isFalse()
      val guard = CallerGuard(checks, authority)
      assertThrows(SecurityException::class.java) { guard.requireSystem() }
      assertThrows(SecurityException::class.java) { guard.requireDocument("aaaaaaaaaaaaaaaaaaaaaaaa") }
      assertThrows(SecurityException::class.java) { guard.requireDocument(null) }
    } finally {
      org.robolectric.shadows.ShadowBinder.reset()
    }
  }

  @Test
  fun ilMessaggioDelRifiutoNonDiceNienteDeiMedia() {
    val provider = provider()
    val id = browseId(provider)
    caller.stranger()
    val error = assertThrows(SecurityException::class.java) { provider.queryDocument(id, null) }
    assertThat(error.message).doesNotContain(id)
    assertThat(error.message).doesNotContain("IMG_")
  }

  // --- Sola lettura ---------------------------------------------------------------------------------------------------

  @Test
  fun siApreSoloInLettura() {
    val provider = provider()
    val id = browseId(provider)
    for (mode in listOf("rw", "w", "wt", "rwt", "wa", "", "R", "rt")) {
      assertThrows(SecurityException::class.java) { provider.openDocument(id, mode, null) }
    }
    assertThrows(SecurityException::class.java) { provider.openDocument(id, null, null) }
    assertThat(opener.callbacks).isEmpty()
    provider.openDocument(id, "r", null).close()
    assertThat(opener.callbacks).hasSize(1)
  }

  @Test
  fun nonSiCreaNonSiCancellaNonSiRinomina() {
    val provider = provider()
    val id = browseId(provider)
    assertThrows(UnsupportedOperationException::class.java) { provider.deleteDocument(id) }
    assertThrows(UnsupportedOperationException::class.java) { provider.renameDocument(id, "x.jpg") }
    assertThrows(UnsupportedOperationException::class.java) { provider.createDocument(rootDocumentId(provider), "image/jpeg", "x.jpg") }
    assertThrows(UnsupportedOperationException::class.java) { provider.copyDocument(id, rootDocumentId(provider)) }
    assertThrows(UnsupportedOperationException::class.java) { provider.moveDocument(id, rootDocumentId(provider), rootDocumentId(provider)) }
    assertThrows(UnsupportedOperationException::class.java) { provider.openDocumentThumbnail(id, android.graphics.Point(100, 100), null) }
  }

  // --- Id ostili, scaduti (E-X1) -----------------------------------------------------------------------------------------

  @Test
  fun gliIdChePortanoPercorsiONonSonoOpachiNonSiCercanoNemmeno() {
    val provider = provider()
    for (hostile in listOf("../../etc/passwd", "", "a/b/c/d/e/f/g/h", "corto", "x".repeat(65), "primary:DCIM/Camera/IMG_1.jpg", "IMG_1.jpg.jpg.jpg", "a b c d e f g h")) {
      assertThrows(FileNotFoundException::class.java) { provider.queryDocument(hostile, null) }
      assertThrows(FileNotFoundException::class.java) { provider.openDocument(hostile, "r", null) }
      assertThrows(FileNotFoundException::class.java) { provider.queryChildDocuments(hostile, null, null as String?) }
    }
    assertThrows(FileNotFoundException::class.java) { provider.queryDocument(null, null) }
    // Un id ben formato ma mai emesso: stessa risposta, nessun oracolo.
    assertThrows(FileNotFoundException::class.java) { provider.queryDocument("aaaaaaaaaaaaaaaaaaaaaaaa", null) }
    assertThrows(FileNotFoundException::class.java) { provider.openDocument("aaaaaaaaaaaaaaaaaaaaaaaa", "r", null) }
    // Dal percorso degli URI la richiesta ostile non da' un'eccezione al chiamante ma niente (il framework risponde null).
    assertThat(provider.query(ProviderTestSupport.documentUri("aaaaaaaaaaaaaaaaaaaaaaaa"), null, Bundle.EMPTY, null)).isNull()
  }

  @Test
  fun unUriUsatoDopoLaScadenzaNonApreNiente_E_X1() {
    val provider = provider()
    val id = browseId(provider)
    provider.openDocument(id, "r", null).close()
    clock.now = 31 * 60_000L // oltre i 30 minuti dello sfoglio
    assertThrows(FileNotFoundException::class.java) { provider.queryDocument(id, null) }
    assertThrows(FileNotFoundException::class.java) { provider.openDocument(id, "r", null) }
    assertThat(IntegrationCounters.get(IntegrationCounter.PROVIDER_EXPIRED)).isAtLeast(1L)
    assertThat(opener.callbacks).hasSize(1)
    // Un id consegnato dal selettore vale piu' a lungo (6 ore) e si rinnova con l'uso.
    val delivered = documents.issue(library.nasOnlyIds().first())!!
    clock.now += 5 * 3_600_000L
    provider.openDocument(delivered.value, "r", null).close()
    clock.now += 5 * 3_600_000L
    provider.openDocument(delivered.value, "r", null).close()
    clock.now += 7 * 3_600_000L
    assertThrows(FileNotFoundException::class.java) { provider.openDocument(delivered.value, "r", null) }
  }

  @Test
  fun dopoUnRiavvioDelProcessoGliIdVecchiNonApronoNiente() {
    val provider = provider()
    val id = browseId(provider)
    // Un nuovo avvio: porte nuove, tabella nuova.
    IntegrationPorts.install(gate, LibraryMediaDocuments(library, labels, DocumentIdTable(clock, java.util.Random(77))))
    assertThrows(FileNotFoundException::class.java) { provider.openDocument(id, "r", null) }
  }

  // --- Il blocco dell'app (E-X7) -----------------------------------------------------------------------------------------

  @Test
  fun appNonPronta_NienteEsiste() {
    gate.decision = EntryDecision.NOT_READY
    val provider = provider()
    roots(provider).use { assertThat(it.count).isEqualTo(0) }
    assertThrows(FileNotFoundException::class.java) { provider.queryDocument("aaaaaaaaaaaaaaaaaaaaaaaa", null) }
    assertThrows(FileNotFoundException::class.java) { provider.queryChildDocuments("aaaaaaaaaaaaaaaaaaaaaaaa", null, null as String?) }
    assertThrows(FileNotFoundException::class.java) { provider.openDocument("aaaaaaaaaaaaaaaaaaaaaaaa", "r", null) }
  }

  @Test
  fun appBloccata_LaRadiceCEMaLoSfoglioChiedeLoSblocco() {
    val provider = provider()
    val id = browseId(provider)
    val root = rootDocumentId(provider)
    gate.decision = EntryDecision.NEEDS_UNLOCK
    roots(provider).use { cursor ->
      assertThat(cursor.count).isEqualTo(1)
      cursor.moveToFirst()
      assertThat(cursor.getString(cursor.getColumnIndexOrThrow(Root.COLUMN_SUMMARY))).contains("Bloccata")
    }
    val error = assertThrows(AuthenticationRequiredException::class.java) { provider.queryChildDocuments(root, null, null as String?) }
    assertThat(error).isInstanceOf(SecurityException::class.java)
    assertThrows(AuthenticationRequiredException::class.java) { provider.queryDocument(root, null) }
    // Un id da sfoglio non si descrive ne' si apre con l'app bloccata.
    assertThrows(AuthenticationRequiredException::class.java) { provider.queryDocument(id, null) }
    assertThrows(AuthenticationRequiredException::class.java) { provider.openDocument(id, "r", null) }
    assertThat(opener.callbacks).isEmpty()
    assertThat(IntegrationCounters.get(IntegrationCounter.PROVIDER_LOCKED)).isAtLeast(4L)
    // Sbloccata, tutto torna.
    gate.decision = EntryDecision.ALLOWED
    provider.openDocument(id, "r", null).close()
  }

  @Test
  fun ilTocco_Sblocca_EImmutabileEdEsplicitoSulNostroPacchetto() {
    val pending = unlockPendingIntent(ProviderTestSupport.context)
    val shadow = org.robolectric.Shadows.shadowOf(pending)
    assertThat(shadow.flags and android.app.PendingIntent.FLAG_IMMUTABLE).isNotEqualTo(0)
    assertThat(shadow.isActivityIntent).isTrue()
    val intent = shadow.savedIntent
    assertThat(intent.component?.packageName ?: intent.`package`).isEqualTo(ProviderTestSupport.context.packageName)
    // IN-4: lo sblocco chiesto da DocumentsUI passa dall'activity d'ingresso (tolleranza di due minuti, risultato a chi chiede), non dalla MainActivity.
    assertThat(intent.component?.className).isEqualTo("dev.pampa.mycloudgallery.feature.integrations.picker.PickerActivity")
    assertThat(intent.action).isEqualTo(dev.pampa.mycloudgallery.feature.integrations.picker.PickerActivity.ACTION_UNLOCK)
  }

  // --- A processo freddo (IN-3) ---------------------------------------------------------------------------------------------

  /**
   * IN-3 della verifica funzionale 5B: DocumentsUI interroga il provider appena il processo nasce, mentre l'app sta ancora leggendo la
   * configurazione salvata e le impostazioni del blocco. Prima la risposta era "non pronta" (nessuna radice), e nessuno la correggeva.
   */
  @Test
  fun in3_aFreddoLaRadiceAspettaCheLAppAbbiaLettoLoStatoEPoiCE() {
    gate.decision = EntryDecision.LOADING
    val provider = provider()
    val lateReady = Thread {
      Thread.sleep(150)
      gate.decision = EntryDecision.ALLOWED
    }
    lateReady.start()
    roots(provider).use { cursor ->
      assertThat(cursor.count).isEqualTo(1) // ha aspettato lo stato invece di rispondere "non pronta"
      cursor.moveToFirst()
      assertThat(cursor.isNull(cursor.getColumnIndexOrThrow(Root.COLUMN_SUMMARY))).isTrue() // sbloccata: nessun "Bloccata"
    }
    lateReady.join()
  }

  @Test
  fun in3_aFreddoConLaSchermataDiSbloccoLaRadiceDiceBloccataEnonNonPronta() {
    gate.decision = EntryDecision.LOADING
    val provider = provider()
    Thread {
      Thread.sleep(100)
      gate.decision = EntryDecision.NEEDS_UNLOCK
    }.start()
    roots(provider).use { cursor ->
      assertThat(cursor.count).isEqualTo(1)
      cursor.moveToFirst()
      assertThat(cursor.getString(cursor.getColumnIndexOrThrow(Root.COLUMN_SUMMARY))).contains("Bloccata")
    }
  }

  @Test
  fun in3_seLoStatoNonArrivaMaiNonCeNienteEUnaCartellaNonSiApre() {
    ProviderTuning.loadWaitMs = 100
    gate.decision = EntryDecision.LOADING
    val provider = provider()
    roots(provider).use { assertThat(it.count).isEqualTo(0) }
    assertThrows(FileNotFoundException::class.java) { provider.queryChildDocuments("aaaaaaaaaaaaaaaaaaaaaaaa", null, null as String?) }
    // Appena lo stato e' letto tutto torna (le radici si notificano a ogni cambio: `IntegrationsGraph`).
    gate.decision = EntryDecision.ALLOWED
    roots(provider).use { assertThat(it.count).isEqualTo(1) }
  }

  // --- Dopo un riavvio del processo (IN-2) ----------------------------------------------------------------------------------

  /**
   * IN-2: gli id della radice e delle cartelle erano nuovi a ogni processo e DocumentsUI li tiene nella cache di sistema: dopo un riavvio
   * "Allega file" di Gmail apriva `queryChildDocuments(vecchioToken)` e trovava `FileNotFoundException`. Adesso il processo nuovo (altra tabella,
   * altra istanza di tutto, stessa chiave su file) risponde agli stessi indirizzi.
   */
  @Test
  fun in2_dopoUnRiavvioDelProcessoGliIndirizziCheDocumentsUiRicordaSiAprono() {
    val keyFile = java.io.File.createTempFile("doc-id", ".key").also { it.delete(); it.deleteOnExit() }
    fun processo(seed: Long) = LibraryMediaDocuments(library, labels, DocumentIdTable(clock, java.util.Random(seed), idKey = FileDocumentIdKey(keyFile, java.util.Random(seed))))
    IntegrationPorts.install(gate, processo(1))
    val provider = provider()
    val root = rootDocumentId(provider)
    val rootChildren = children(provider, root)
    val nasFolders = rootChildren.first { it.name == "Cartelle NAS" }
    val areas = children(provider, nasFolders.id)
    val area = areas.first { it.name == "Area privata" }
    val holidays = children(provider, area.id).single()
    assertThat(holidays.name).isEqualTo("Vacanze")
    val holidaysFiles = children(provider, holidays.id).map { it.name }
    assertThat(holidaysFiles).isNotEmpty()
    val media = children(provider, rootChildren.first { it.name == "Recenti" }.id).first().id

    // Il processo muore e rinasce: tutto nuovo in memoria. DocumentsUI ha ancora i vecchi indirizzi.
    IntegrationPorts.install(gate, processo(2))
    val reborn = provider()
    assertThat(rootDocumentId(reborn)).isEqualTo(root)
    assertThat(children(reborn, root).map { it.id }).containsExactlyElementsIn(rootChildren.map { it.id }).inOrder()
    assertThat(children(reborn, nasFolders.id).map { it.id }).containsExactlyElementsIn(areas.map { it.id }).inOrder()
    assertThat(children(reborn, area.id).single().id).isEqualTo(holidays.id)
    assertThat(children(reborn, holidays.id).map { it.name }).containsExactlyElementsIn(holidaysFiles)
    // I media invece no: il loro id e' a scadenza, vale per quel processo e non torna.
    assertThrows(FileNotFoundException::class.java) { reborn.queryDocument(media, null) }
  }

  @Test
  fun unDocumentoConsegnatoDalSelettoreSiServeAnchePoiCheLAppSiRichiude() {
    val provider = provider()
    val delivered = documents.issue(library.nasOnlyIds().first())!!
    gate.decision = EntryDecision.NEEDS_UNLOCK
    provider.queryDocument(delivered.value, null).use { assertThat(rows(it)).hasSize(1) }
    provider.openDocument(delivered.value, "r", null).close()
    // Ma non la cartella che lo contiene, e nessun altro documento: lo sfoglio resta chiuso.
    assertThrows(AuthenticationRequiredException::class.java) { provider.queryChildDocuments(rootDocumentIdUnlocked(provider), null, null as String?) }
  }

  private fun rootDocumentIdUnlocked(provider: MediaDocumentsProvider): String {
    val before = gate.decision
    gate.decision = EntryDecision.ALLOWED
    try {
      return rootDocumentId(provider)
    } finally {
      gate.decision = before
    }
  }

  @Test
  fun senzaLePorteInstallateNonCeNiente() {
    IntegrationPorts.reset()
    val provider = provider()
    roots(provider).use { assertThat(it.count).isEqualTo(0) }
    // Una radice vuota non compare nei selettori, e le colonne chieste sono quelle chieste.
    provider.queryRoots(arrayOf("root_id", "title")).use {
      assertThat(it.count).isEqualTo(0)
      assertThat(it.columnNames.toList()).containsExactly("root_id", "title").inOrder()
    }
    assertThrows(FileNotFoundException::class.java) { provider.queryDocument("aaaaaaaaaaaaaaaaaaaaaaaa", null) }
    assertThrows(FileNotFoundException::class.java) { provider.queryChildDocuments("aaaaaaaaaaaaaaaaaaaaaaaa", null, null as String?) }
    assertThrows(FileNotFoundException::class.java) { provider.openDocument("../../etc/passwd", "r", null) }
  }

  // --- Il NAS (E-X1, E-X4, E-X6) ------------------------------------------------------------------------------------------

  @Test
  fun unNasCheNonRispondeDaUnErroreSubitoMaiUnaAttesaInfinita_E_X1() {
    val provider = provider()
    val id = documents.issue(library.nasOnlyIds().first())!!.value
    library.nasOpenFails = true
    val started = System.nanoTime()
    assertThrows(FileNotFoundException::class.java) { provider.openDocument(id, "r", null) }
    assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(2_000L)

    library.nasOpenFails = false
    library.nasOpenHangs = true
    val hung = System.nanoTime()
    val error = ProviderTestSupport.within { assertThrows(FileNotFoundException::class.java) { provider.openDocument(id, "r", null) } }
    val tookMs = (System.nanoTime() - hung) / 1_000_000
    assertThat(error.message).contains("NAS")
    assertThat(tookMs).isAtLeast(300L) // la scadenza di 400 ms dei test
    assertThat(tookMs).isLessThan(5_000L)
    // Niente e' rimasto aperto: il NAS torna e si apre subito.
    library.nasOpenHangs = false
    provider.openDocument(id, "r", null).close()
    assertThat(opener.callbacks).hasSize(1)
  }

  @Test
  fun unNasCheNonRispondeNelleCartelleDaUnMessaggioEUnaCartellaVuota() {
    val provider = provider()
    val nasFolders = children(provider, rootDocumentId(provider)).last()
    val areas = children(provider, nasFolders.id)
    val sub = children(provider, areas.first().id)
    val vacanze = sub.single()
    // La porta dei documenti viene sostituita con una che si pianta: l'elenco non resta in attesa oltre la scadenza.
    IntegrationPorts.install(gate, object : dev.pampa.mycloudgallery.feature.integrations.contract.MediaDocuments by documents {
      override suspend fun children(parent: dev.pampa.mycloudgallery.feature.integrations.contract.DocumentId, limit: Int) =
        kotlinx.coroutines.awaitCancellation()
    })
    val started = System.nanoTime()
    ProviderTestSupport.within {
      provider.query(ProviderTestSupport.childrenUri(vacanze.id), null, Bundle.EMPTY, null)!!.use { cursor ->
        assertThat(cursor.count).isEqualTo(0)
        assertThat(cursor.extras.getString(android.provider.DocumentsContract.EXTRA_ERROR)).contains("NAS")
      }
    }
    assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(5_000L)
  }

  @Test
  fun unaAperturaAnnullataLasciaTuttoPulito_E_X4() {
    val id = documents.issue(library.nasOnlyIds().first())!!.value
    library.nasOpenHangs = true
    ProviderTuning.openDeadlineMs = 30_000
    // Il provider e' gia' creato con la scadenza di prima: ne creiamo uno nuovo con la scadenza lunga.
    val slow = provider()
    val signal = CancellationSignal()
    val failure = AtomicReference<Throwable>()
    val done = CountDownLatch(1)
    Thread {
      try {
        slow.openDocument(id, "r", signal)
      } catch (t: Throwable) {
        failure.set(t)
      }
      done.countDown()
    }.start()
    Thread.sleep(150)
    signal.cancel()
    assertThat(done.await(5, TimeUnit.SECONDS)).isTrue()
    assertThat(failure.get()).isInstanceOf(OperationCanceledException::class.java)
    // Nessun descrittore, nessun thread, nessun posto occupato: il NAS torna e si apre.
    assertThat(opener.callbacks).isEmpty()
    library.nasOpenHangs = false
    slow.openDocument(id, "r", null).close()
    assertThat(opener.callbacks).hasSize(1)
  }

  @Test
  fun unSegnaleGiaScattatoNonApreNiente() {
    val provider = provider()
    val id = browseId(provider)
    val signal = CancellationSignal().also { it.cancel() }
    assertThrows(OperationCanceledException::class.java) { provider.openDocument(id, "r", signal) }
    assertThat(opener.callbacks).isEmpty()
  }

  @Test
  fun moltiSoloNasInsieme_OltreIlTettoRifiutoMotivato_E_X6() {
    ProviderTuning.slots = { DescriptorSlots(maxOpen = 2, maxWaiting = 1) }
    val provider = provider()
    val ids = library.nasOnlyIds().take(5).map { documents.issue(it)!!.value }
    val first = provider.openDocument(ids[0], "r", null)
    val second = provider.openDocument(ids[1], "r", null)
    assertThat(opener.callbacks).hasSize(2)
    // Il terzo aspetta un posto fino alla scadenza (400 ms dei test) e viene rifiutato con il motivo.
    val error = assertThrows(FileNotFoundException::class.java) { provider.openDocument(ids[2], "r", null) }
    assertThat(error.message).contains("Troppe")
    // Un posto liberato (il sistema chiude il primo descrittore) lascia passare il successivo.
    opener.callbacks[0].onRelease()
    provider.openDocument(ids[3], "r", null).close()
    assertThat(opener.callbacks).hasSize(3)
    first.close()
    second.close()
  }

  @Test
  fun unaCopiaDelTelefonoSiLeggeAPezzi() {
    val provider = provider()
    val id = documents.issue(library.phoneIds().first())!!.value
    provider.openDocument(id, "r", null).close()
    val callback = opener.callbacks.single()
    val buffer = ByteArray(1_024)
    assertThat(callback.onRead(0, 1_024, buffer)).isEqualTo(1_024)
    assertThat(library.phoneReaders.single().reads.get()).isGreaterThan(0)
    val error = assertThrows(ErrnoException::class.java) { callback.onWrite(0, 1, ByteArray(1)) }
    assertThat(error.errno).isEqualTo(android.system.OsConstants.EBADF)
  }

  // --- Mai i nascosti, dal provider ---------------------------------------------------------------------------------------

  @Test
  fun dalProviderNonEsceUnNascostoNeComeRigaNeComeApertura() {
    library.leakHidden = true
    val provider = provider()
    val everything = buildList {
      val root = rootDocumentId(provider)
      val folders = children(provider, root)
      addAll(folders)
      addAll(children(provider, folders.first().id))
      val areas = children(provider, folders.last().id)
      addAll(areas)
      areas.forEach { addAll(children(provider, it.id)) }
      val sub = children(provider, areas.first { it.name == "Area privata" }.id)
      addAll(children(provider, sub.single().id))
    }
    assertThat(everything.map { it.name }.filter { it.startsWith("SEGRETO_") }).isEmpty()
    // E se l'altra app conoscesse l'identita' di un nascosto, non c'e' nessun id che la porti: non si puo' nemmeno chiedere.
    val hidden: MediaId = library.hiddenIds.first()
    assertThat(documents.issue(hidden)).isNull()
    for (row in everything) assertThat(row.id).doesNotContain(hidden.value)
  }

  @Test
  fun nessunaPartePrivataEsceNegliId_OgniIdEUnTokenCasuale() {
    val provider = provider()
    val rows = children(provider, children(provider, rootDocumentId(provider)).first().id)
    val ids = rows.map { it.id }
    assertThat(ids.toSet()).hasSize(ids.size)
    for (id in ids) {
      assertThat(id).hasLength(24)
      assertThat(library.visibleIds.map { it.value.substring(0, 8) }).doesNotContain(id.substring(0, 8))
    }
  }
}
