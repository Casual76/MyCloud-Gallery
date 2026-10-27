package dev.pampa.mycloudgallery.feature.diagnostics.catalog

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.feature.diagnostics.actions.DiagnosticsActions
import java.io.File
import org.junit.Test

/**
 * Il catalogo della Fase 4A (P11; docs/fase4-progetto.md §2.1–§2.6, §1.4): **ogni edge case della 4A che produce un avviso
 * in Diagnostica ha il suo codice** nella sezione «NAS nella Libreria».
 *
 * Come per la Fase 3: i codici dei moduli (`IndexDiag`, `NasMediaDiag`, `PrepDiag`) e quelli di `:app` sono scritti qui a
 * mano (questo modulo non li vede; un test di `:app` verifica che coincidano), e gli identificativi citati nei flussi del
 * documento vengono **letti dal documento**: ciascuno deve essere coperto da un codice oppure stare nell'elenco di quelli
 * che non sono un avviso, col motivo.
 */
class Fase4ACatalogTest {

  /** I codici dei moduli della 4A e di :app, con i loro edge case (tutti nella sezione «NAS nella Libreria»). */
  private val expected: Map<String, Set<String>> = mapOf(
    // :app (NasLibraryDiagnostics): lo stato (P0), l'emergenza (§1.4), le aree non raggiungibili.
    "NAS_LIBRARY_STATE" to emptySet(),
    "NAS_WRITES_STOPPED" to emptySet(),
    // Revisione di fine Fase 7 (R11, pacchetto C): l'emergenza che non si e' potuta salvare su disco.
    "NAS_WRITES_STOP_NOT_SAVED" to emptySet(),
    "NAS_AREA_UNREACHABLE" to setOf("E-L4"),
    // core-nasmedia (NasMediaDiag, P0/P5).
    "NAS_SCAN_STATE" to setOf("E-N5"),
    "NAS_SCAN_FROZEN" to setOf("C11", "E-S11"),
    "NAS_SCAN_SKIPPED_NAMES" to setOf("E-N6"),
    "NAS_SCAN_ALBUM_COVERS" to setOf("E-N14"),
    "NAS_SCAN_NOT_MEDIA" to setOf("E-M12"),
    "NAS_SCAN_ARRIVING" to setOf("E-N4"),
    "NAS_SCAN_BUSY" to setOf("E-N13"),
    "NAS_SCAN_LOOPS" to setOf("E-N10"),
    "NAS_SCAN_TRUNCATED" to setOf("E-N5"),
    "NAS_SCAN_AWAY_CAP" to setOf("D22"),
    // core-index (IndexDiag, P0/P3/P4).
    "INDEX_REJECTED" to setOf("E-I5", "E-I14", "C9"),
    "INDEX_ROLLBACK" to setOf("E-I5"),
    "INDEX_NEWER_FORMAT" to setOf("E-I3"),
    "INDEX_IDENTITY_CONFLICT" to setOf("E-I18"),
    "INDEX_OUTBOX_PENDING" to setOf("E-I6"),
    "INDEX_LOCAL_ONLY" to setOf("D31"),
    "INDEX_CLOCK_JUMP" to setOf("E-N17", "D32"),
    "INDEX_AREA_READ_ONLY" to setOf("E-I19"),
    "INDEX_SHARE_DROPPED" to setOf("E-I8", "E-R10"),
    "INDEX_NO_PREPARERS" to setOf("E-I10", "E-I12"),
    "INDEX_REPLICA_STATE" to setOf("E-L6"),
    "INDEX_LEASES_CONTENDED" to setOf("E-I1"),
    // core-prepare (PrepDiag, P0/P6).
    "PREP_STATE" to emptySet(),
    "PREP_DECODER_MISSING" to setOf("E-M13"),
    "PREP_UNREADABLE" to setOf("E-M10"),
    "PREP_TOO_LARGE" to setOf("E-M3"),
  )

  @Test
  fun iCodiciDellaFase4AHannoGliIdGliEdgeCaseLaSezioneETitoloECosaFare() {
    expected.forEach { (id, edgeCases) ->
      val entry = checkNotNull(DiagCatalog.entry(DiagCode(id))) { "$id non e' nel catalogo" }
      assertWithMessage(id).that(entry.code.edgeCases).containsExactlyElementsIn(edgeCases)
      assertWithMessage(id).that(entry.section).isEqualTo(DiagSection.NAS_LIBRARY)
      assertWithMessage(id).that(entry.titleRes).isNotEqualTo(0)
      assertWithMessage(id).that(entry.guideRes).isNotEqualTo(0)
    }
    // E viceversa: nella sezione ci sono solo questi e quelli della 4B (Fase4BCatalogTest, P16).
    assertThat(DiagCatalog.entriesOf(DiagSection.NAS_LIBRARY).map { it.code.id }).containsExactlyElementsIn(expected.keys + Fase4BCatalogTest.CODES_4B.keys)
  }

  @Test
  fun leAzioniDellaFase4AHannoIlLoroTasto() {
    // Il congelamento si scioglie solo dalla Diagnostica (D10, Q6); l'emergenza si toglie da li'.
    assertThat(DiagCatalog.entry(DiagCodes.NAS_SCAN_FROZEN)!!.action).isEqualTo(DiagnosticsActions.NAS_RELEASE_FREEZE)
    assertThat(DiagCatalog.entry(DiagCodes.NAS_WRITES_STOPPED)!!.action).isEqualTo(DiagnosticsActions.NAS_RESUME_WRITES)
    listOf(DiagnosticsActions.NAS_STOP_WRITES, DiagnosticsActions.NAS_RESUME_WRITES, DiagnosticsActions.NAS_RELEASE_FREEZE).forEach {
      assertWithMessage(it.toString()).that(DiagnosticsActions.labelRes(it)).isNotEqualTo(0)
      // Le esegue :app: senza la sua dichiarazione il tasto non compare.
      assertThat(DiagnosticsActions.isAvailable(it, appActions = emptySet())).isFalse()
      assertThat(DiagnosticsActions.isAvailable(it, appActions = setOf(it))).isTrue()
    }
  }

  // --- Gli edge case della 4A ------------------------------------------------------------------------------------

  /**
   * Gli edge case e le decisioni della 4A che **producono un avviso** in Diagnostica (progetto §2.1–§2.6, §1.4, D10, D22,
   * D31, D32). Ognuno ha almeno un codice della sezione «NAS nella Libreria».
   */
  private val warningEdgeCases = setOf(
    "E-I1", "E-I3", "E-I5", "E-I6", "E-I8", "E-I10", "E-I12", "E-I14", "E-I18", "E-I19",
    "E-N4", "E-N5", "E-N6", "E-N10", "E-N13", "E-N14", "E-N17",
    "E-M3", "E-M10", "E-M12", "E-M13",
    "E-L4", "E-L6", "E-R10", "E-S11", "C11", "D22", "D31", "D32",
  )

  @Test
  fun ogniEdgeCaseDellaFase4ACheProduceUnAvvisoHaIlSuoCodice() {
    val nasCodes = DiagCatalog.entriesOf(DiagSection.NAS_LIBRARY).map { it.code }
    warningEdgeCases.forEach { edgeCase ->
      val covering = nasCodes.filter { edgeCase in it.edgeCases }
      assertWithMessage("$edgeCase non ha un codice nella sezione «NAS nella Libreria»").that(covering).isNotEmpty()
    }
  }

  /**
   * Gli identificativi citati nei flussi della 4A (§2.1–§2.6) che NON producono un avviso, col motivo: regole di calcolo,
   * comportamenti della Libreria o del viewer, numeri gia' dentro una voce di stato, prove sul campo.
   */
  // Coperti gia' dai codici della Fase 2 e quindi non qui: E-N9 (NAS_PERMISSION_DENIED), E-R9 (NAS pieno), E-R15 (NET_NAS_TCP),
  // E-P4 ed E-S14.
  private val notADiagnosticWarning = mapOf(
    // Indice condiviso.
    "E-I2" to "prenotazione scaduta entro l'ora: regola di LeasePolicy, nessuno stato da mostrare",
    "E-I4" to "classi riservate (vettori): il segmento si rifiuta e si conta in INDEX_REJECTED per motivo",
    "E-I7" to "replica ricostruita identica: proprieta' provata nei test della replica",
    "E-I9" to "primo allineamento: si vede nel piede della Libreria (Aligning, byte e tempo)",
    "E-I11" to "anteprime di analisi lasciate sul NAS: regola (Fase 6)",
    "E-I13" to "indice invisibile a DLNA, Plex, Twonky, Foto di Windows: prova sul campo F14",
    "E-I15" to "dati di revocati e persi ignorati: le loro teste si contano in INDEX_REJECTED",
    "E-I16" to "prenotazione oltre il tetto o nel futuro = risorsa libera: regola di LeasePolicy",
    "E-I17" to "segmento elencato ma assente: i dati gia' presenti restano e i media tornano da preparare (stato della replica)",
    // Scansione.
    "E-N1" to "spostato dal PC: riconosciuto senza leggere, contato in NAS_SCAN_STATE (moved)",
    "E-N2" to "modificato dal PC: versione nuova, contata in NAS_SCAN_STATE (changed)",
    "E-N3" to "cancellato dal PC: esce dalla Libreria, contato in NAS_SCAN_STATE (vanished)",
    "E-N7" to "nomi diversi solo per maiuscole: un posto solo, regola della chiave",
    "E-N8" to "cartelle di sistema escluse sempre: contate in NAS_SCAN_STATE (skippedSystem)",
    // E-N11 (due familiari sullo stesso file) lo copre dalla 4B NAS_OPS_FAILED ("spostato da un altro dispositivo").
    "E-N12" to "indice vecchio rispetto al file: timbro diverso, si rileva di nuovo (regola D6)",
    "E-N15" to "cartelle escluse all'onboarding: contate in NAS_SCAN_STATE (excludedFolders)",
    "E-N16" to "cartelle col punto e cestini del NAS: mai scansionati, contati in NAS_SCAN_STATE (skippedSystem)",
    // Preparazione.
    "E-A9" to "un fotogramma ogni 30 s, da 1 a 8: regola della preparazione dei video",
    "E-M1" to "foto enormi: decodifica campionata con il tetto di pixel (PREP_TOO_LARGE oltre il tetto)",
    "E-M5" to "motion photo: foto ferma nelle anteprime",
    "E-M6" to "raffiche e RAW+JPEG: gruppi del catalogo come nella Fase 3",
    "E-M7" to "video da piu' GB: solo indice e fotogrammi, mai scaricati (regola)",
    "E-M8" to "date dal nome o dal file: la data e' marcata «stimata» nelle informazioni",
    "E-M9" to "date assurde: si passa alla fonte successiva, in silenzio",
    "E-M11" to "GIF e WebP animati: primo fotogramma nelle anteprime, animazione nel viewer",
    "E-M14" to "fuso dal luogo: regola di calcolo",
    "E-M15" to "orientamento: le anteprime sono sempre dritte (provato nei test della preparazione)",
    // Libreria e viewer.
    "E-L1" to "un media su telefono e NAS: una cella con badge «entrambi»",
    "E-L2" to "scaricato dal NAS: due posizioni, nessun doppione (collegamento prima della pubblicazione)",
    "E-L3" to "identici in piu' cartelle: uno (hash completo); i simili in Doppioni (Fase 7)",
    "E-L5" to "anteprima assente: segnaposto sfocato nella cella",
    "E-L7" to "100 mila elementi: guardia del benchmark (P10)",
    "E-L8" to "correzioni di data o luogo: Fase 5",
    "E-L9" to "video da verificare: una cella sola con «da verificare»",
    "E-V2" to "dati mobili: il viewer chiede conferma con la dimensione",
    "E-V3" to "orientamenti: dritti nel viewer",
    "E-V6" to "media sparito mentre lo si guarda: chiusura gentile del viewer",
    "E-V7" to "condividere un media solo-NAS: Fase 5",
    // Regole del formato.
    "M3" to "regola di pubblicazione del formato (solo dati della stessa share, una testa ogni 5 minuti)",
  )

  @Test
  fun leEccezioniDellaFase4AHannoUnMotivoENonSonoGiaCoperte() {
    notADiagnosticWarning.forEach { (id, reason) ->
      assertWithMessage(id).that(reason).isNotEmpty()
      assertWithMessage("$id e' coperto da un codice: toglilo dall'elenco delle eccezioni").that(id in DiagCatalog.coveredEdgeCases).isFalse()
    }
  }

  @Test
  fun ogniIdentificativoCitatoNeiFlussiDellaFase4AEClassificato() {
    val cited = citedIdentifiers()
    // Una guardia contro una lettura vuota o sbagliata del documento.
    assertThat(cited).containsAtLeast("E-I1", "E-N4", "E-M12", "E-L4")
    val unclassified = cited.filterNot { it in DiagCatalog.coveredEdgeCases || it in notADiagnosticWarning }
    assertWithMessage(
      "Identificativi citati nei §2.1–§2.6 di fase4-progetto.md senza un DiagCode e non classificati come «non e' un avviso»: $unclassified",
    ).that(unclassified).isEmpty()
    val stale = notADiagnosticWarning.keys.filterNot { it in cited }
    assertWithMessage("Eccezioni che il documento non cita piu': $stale").that(stale).isEmpty()
  }

  /** Gli identificativi citati fra «### 2.1 Indice condiviso» e «### 2.7 Operazioni sui file» (la 4A). */
  private fun citedIdentifiers(): Set<String> {
    val doc = File("../docs/fase4-progetto.md")
    assertWithMessage("docs/fase4-progetto.md non trovato dalla cartella ${File(".").absolutePath}").that(doc.exists()).isTrue()
    val text = doc.readText(Charsets.UTF_8)
    val from = text.indexOf("### 2.1 Indice condiviso")
    val to = text.indexOf("### 2.7 Operazioni sui file")
    assertWithMessage("§2.1–§2.6 non trovati nel progetto della Fase 4").that(from >= 0 && to > from).isTrue()
    return Regex("""\b(E-[A-Z]{1,2}[0-9]{1,3}|[BCLM][0-9]{1,3}|T-[0-9]{1,3})\b""")
      .findAll(text.substring(from, to))
      .map { it.value }
      .toSortedSet()
  }
}
