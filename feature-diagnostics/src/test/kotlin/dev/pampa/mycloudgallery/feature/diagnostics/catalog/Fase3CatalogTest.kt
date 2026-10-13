package dev.pampa.mycloudgallery.feature.diagnostics.catalog

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagAction
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagCode
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSection
import dev.pampa.mycloudgallery.core.common.diagnostics.DiagSeverity
import dev.pampa.mycloudgallery.feature.diagnostics.actions.DiagnosticsActions
import java.io.File
import java.lang.reflect.Modifier
import org.junit.Test

/**
 * Il catalogo della Fase 3 (docs/fase3-progetto.md §1.3 e §2): **ogni edge case della Fase 3 che produce un avviso in
 * Diagnostica ha il suo codice** nella sezione «Libreria del telefono».
 *
 * Come in `DiagCatalogTest` per la Fase 2, gli identificativi citati nel documento (§2 «Flussi») vengono **letti dal
 * documento**: ciascuno deve essere coperto da un codice oppure stare nell'elenco di quelli che non sono un avviso,
 * col motivo. Un edge case nuovo nel documento fa fallire il test finche' non lo si classifica.
 */
class Fase3CatalogTest {

  // --- I codici della Fase 3 ---------------------------------------------------------------------------------

  /**
   * I codici di `PhoneLibraryDiagnostics` (core-mediastore), scritti qui a mano: questo modulo non li vede. Un
   * test di `:app` verifica che coincidano con `PhoneLibraryDiagCodes`; questo li ferma contro un refuso.
   */
  private val phoneLibraryContributorCodes: Map<String, Set<String>> = mapOf(
    "PHONE_ACCESS" to setOf("E-P1", "E-P15"),
    "PHONE_MEDIA_LOCATION" to setOf("E-P21"),
    "PHONE_SYNC" to setOf("E-P4", "E-P8", "E-P12", "E-P22"),
    "PHONE_DISK_FULL" to setOf("E-P3"),
    "PHONE_STORAGE_LOCKED" to setOf("E-P2", "E-P10", "E-P13"),
    "PHONE_VOLUMES" to setOf("E-P6"),
    "PHONE_IGNORED" to setOf("E-M12"),
    "PHONE_ABSENCES_BLOCKED" to setOf("E-P1", "E-P15"),
    "PHONE_OTHER_PROFILES" to setOf("E-P7", "E-P17"),
  )

  /**
   * I codici del contributore di `:app` (luoghi, indice, miniature). Nessun edge case per luoghi e indice: E-H1 (i
   * nascosti non entrano mai nell'indice) e' una regola di progetto provata altrove, non un avviso.
   */
  private val appContributorCodes: Map<String, Set<String>> = mapOf(
    "PHONE_PLACES" to emptySet(),
    "PHONE_INDEX" to emptySet(),
    "PHONE_THUMBNAILS" to setOf("E-V5"),
  )

  private fun declaredPhoneCodes(): List<DiagCode> = DiagCodes::class.java.declaredFields
    .filter { Modifier.isStatic(it.modifiers) && it.type == DiagCode::class.java }
    .map { it.apply { isAccessible = true }.get(null) as DiagCode }
    .filter { it.id.startsWith("PHONE_") }

  @Test
  fun iCodiciDellaFase3HannoEsattamenteGliIdEGliEdgeCaseAttesi() {
    val expected = phoneLibraryContributorCodes + appContributorCodes
    val declared = declaredPhoneCodes().filterNot { it == DiagCodes.PHONE_LIBRARY_STATE }

    assertThat(declared.map { it.id }).containsNoDuplicates()
    assertThat(declared.map { it.id }).containsExactlyElementsIn(expected.keys)
    declared.forEach { code ->
      assertWithMessage(code.id).that(code.edgeCases).containsExactlyElementsIn(expected.getValue(code.id))
    }
  }

  @Test
  fun ilNomeDelCampoDiOgniCodiceDellaFase3EUgualeAlSuoId() {
    // I campi `PHONE_*` si chiamano come l'id che portano: cosi' un refuso nell'uno o nell'altro salta all'occhio.
    val fields = DiagCodes::class.java.declaredFields
      .filter { Modifier.isStatic(it.modifiers) && it.type == DiagCode::class.java && it.name.startsWith("PHONE_") }
    assertThat(fields).isNotEmpty()
    fields.forEach { field ->
      val code = field.apply { isAccessible = true }.get(null) as DiagCode
      assertWithMessage(field.name).that(code.id).isEqualTo(field.name)
    }
  }

  @Test
  fun ogniCodiceDellaFase3StaNellaSezioneDellaLibreriaDelTelefono() {
    declaredPhoneCodes().forEach { code ->
      val entry = checkNotNull(DiagCatalog.entry(code)) { "${code.id} non e' nel catalogo" }
      assertWithMessage(code.id).that(entry.section).isEqualTo(DiagSection.PHONE_LIBRARY)
    }
    // E viceversa: nella sezione ci sono solo codici PHONE_*.
    DiagCatalog.entriesOf(DiagSection.PHONE_LIBRARY).forEach { entry ->
      assertWithMessage(entry.code.id).that(entry.code.id).startsWith("PHONE_")
    }
  }

  @Test
  fun ogniCodiceDellaFase3HaTitoloECosaFare() {
    (phoneLibraryContributorCodes + appContributorCodes).keys.forEach { id ->
      val entry = checkNotNull(DiagCatalog.entry(DiagCode(id))) { "$id non e' nel catalogo" }
      assertWithMessage(id).that(entry.titleRes).isNotEqualTo(0)
      // Anche le voci informative hanno la loro guida: in Diagnostica ogni cosa si spiega.
      assertWithMessage(id).that(entry.guideRes).isNotEqualTo(0)
    }
  }

  @Test
  fun leGravitaTipicheELeAzioniSonoQuelleDecise() {
    val severities = mapOf(
      DiagCodes.PHONE_ACCESS to DiagSeverity.WARNING,
      DiagCodes.PHONE_MEDIA_LOCATION to DiagSeverity.WARNING,
      DiagCodes.PHONE_SYNC to DiagSeverity.INFO,
      DiagCodes.PHONE_DISK_FULL to DiagSeverity.ERROR,
      DiagCodes.PHONE_STORAGE_LOCKED to DiagSeverity.INFO,
      DiagCodes.PHONE_VOLUMES to DiagSeverity.INFO,
      DiagCodes.PHONE_IGNORED to DiagSeverity.INFO,
      DiagCodes.PHONE_ABSENCES_BLOCKED to DiagSeverity.WARNING,
      DiagCodes.PHONE_OTHER_PROFILES to DiagSeverity.INFO,
      DiagCodes.PHONE_PLACES to DiagSeverity.INFO,
      DiagCodes.PHONE_INDEX to DiagSeverity.INFO,
      DiagCodes.PHONE_THUMBNAILS to DiagSeverity.INFO,
    )
    severities.forEach { (code, severity) ->
      assertWithMessage(code.id).that(checkNotNull(DiagCatalog.entry(code)).severity).isEqualTo(severity)
    }

    // Le azioni: solo "Apri le impostazioni dell'app", dove i permessi dell'utente cambiano le cose.
    val withAppDetails = setOf(DiagCodes.PHONE_ACCESS, DiagCodes.PHONE_MEDIA_LOCATION, DiagCodes.PHONE_ABSENCES_BLOCKED)
    severities.keys.forEach { code ->
      val action = checkNotNull(DiagCatalog.entry(code)).action
      if (code in withAppDetails) {
        assertWithMessage(code.id).that(action).isEqualTo(DiagAction.OPEN_APP_DETAILS)
        assertWithMessage(code.id).that(DiagnosticsActions.labelRes(DiagAction.OPEN_APP_DETAILS)).isNotEqualTo(0)
      } else {
        assertWithMessage(code.id).that(action).isNull()
      }
    }
  }

  // --- Gli edge case della Fase 3 -------------------------------------------------------------------------------

  /**
   * Gli edge case della Fase 3 (progetto §2.1 e §2.5) che **producono un avviso** in Diagnostica. Ognuno ha almeno un
   * codice della sezione «Libreria del telefono» che lo dichiara.
   *
   * Aggiungere o togliere un elemento richiede un motivo scritto qui: E-P5 (fuso della data) e E-P9 (orologio) non ci
   * sono perche' sono regole di calcolo, non stati (E-P9 ha comunque il suo codice di Fase 2, `NAS_CLOCK_SKEW`).
   */
  private val warningEdgeCases = setOf(
    "E-P1", "E-P2", "E-P3", "E-P4", "E-P6", "E-P7", "E-P8", "E-P10", "E-P12", "E-P13", "E-P15", "E-P17", "E-P21", "E-P22",
    "E-M12",
    "E-V5",
  )

  @Test
  fun ogniEdgeCaseDellaFase3CheProduceUnAvvisoHaIlSuoCodiceNellaLibreriaDelTelefono() {
    val phoneCodes = DiagCatalog.entriesOf(DiagSection.PHONE_LIBRARY).map { it.code }
    warningEdgeCases.forEach { edgeCase ->
      val covering = phoneCodes.filter { edgeCase in it.edgeCases }
      assertWithMessage("$edgeCase non ha un codice nella sezione «Libreria del telefono»").that(covering).isNotEmpty()
    }
    assertThat(warningEdgeCases.filterNot { it in DiagCatalog.coveredEdgeCases }).isEmpty()
  }

  /**
   * Gli identificativi citati nel §2 del progetto di Fase 3 che NON producono un avviso in Diagnostica, col motivo.
   * Sono comportamenti che si vedono nel visualizzatore o nelle celle, regole di calcolo, vincoli di progetto, prove
   * sul campo o cose che appartengono a un'altra fase.
   */
  private val notADiagnosticWarning = mapOf(
    // Formati e decodifica (E-M*): si vedono nel visualizzatore o nella cella, non in Diagnostica.
    "E-M1" to "grandi formati (200 MP): si zooma a tasselli nel visualizzatore, nessuno stato da segnalare",
    "E-M5" to "motion photo in formato sconosciuto: foto ferma e nota nelle informazioni del visualizzatore",
    "E-M7" to "codec video non supportato: messaggio nel visualizzatore",
    "E-M8" to "data del file come ultima fonte: la data e' marcata «stimata» nelle informazioni",
    "E-M9" to "date assurde: la fonte si scarta e si passa alla successiva, in silenzio",
    "E-M10" to "file corrotto: la cella e' «non leggibile», mai bloccante",
    "E-M11" to "GIF e WebP animati: li gestisce il visualizzatore; nella griglia restano fermi",
    "E-M13" to "video 8K o APV: stesso messaggio del codec non supportato, nel visualizzatore",
    "E-M14" to "fuso dei video: lo risolve il fuso del luogo (regola di calcolo)",
    "E-M15" to "orientamento EXIF: la decodifica raddrizza sempre (provato con screenshot)",
    "E-M16" to "motion photo di formato sconosciuto: foto ferma, come E-M5",
    // Visualizzatore (E-V*): comportamenti provati con test dedicati o sul campo.
    "E-V1" to "anteprima e poi immagine nitida senza salti: comportamento del visualizzatore",
    "E-V2" to "stato «dati mobili» con la dimensione: del visualizzatore, con sorgente finta (il NAS arriva in Fase 4)",
    "E-V3" to "orientamenti: otto fixture sempre dritte, provate con screenshot",
    "E-V4" to "Ultra HDR e video HDR: prova sul campo (F12)",
    "E-V6" to "elemento sparito dalla vista: il visualizzatore passa al vicino con un breve avviso",
    "E-V7" to "media solo sul NAS: Fase 5, fuori dalla Fase 3",
    "E-V8" to "ricreazione dell'activity e rotazione: id e zoom restaurati, provato con ActivityScenario",
    // Nascosti: regole di privacy provate nei test della Libreria.
    "E-H1" to "i nascosti non entrano mai nell'indice ne' nell'istantanea: regola provata nei test dell'indice, non uno stato",
    "E-H3" to "credenziale di sistema a ogni ingresso nei Nascosti, un minuto di tolleranza: comportamento della schermata",
    "E-S5" to "schermo sicuro nei Nascosti (screenshot bloccati): comportamento della schermata, non uno stato",
    // Telefono: regole di calcolo e cose senza uno stato da mostrare.
    "E-P5" to "regola della data: l'ora mostrata usa il fuso dello scatto; non e' uno stato",
    "E-P11" to "cestino di Samsung: la posizione diventa assente con tolleranza; il messaggio arriva con lo stato del backup (E-B4)",
    "E-P18" to "ridimensionamento e finestre: layout della Libreria ancorato, provato con test di layout",
    // Altre fasi e prove sul campo.
    "E-B4" to "messaggio «Recuperala dal cestino di Galleria» con lo stato del backup: Fase 5",
    "T-13" to "prova sul campo (trigger di contenuto di WorkManager)",
  )

  @Test
  fun leEccezioniDellaFase3HannoUnMotivoENonSonoGiaCoperte() {
    notADiagnosticWarning.forEach { (id, reason) ->
      assertWithMessage(id).that(reason).isNotEmpty()
      assertWithMessage("$id e' coperto da un codice: toglilo dall'elenco delle eccezioni")
        .that(id in DiagCatalog.coveredEdgeCases).isFalse()
    }
  }

  @Test
  fun leEccezioniDellaFase3SonoTutteCitateNelDocumento() {
    val cited = citedIdentifiers()
    val stale = notADiagnosticWarning.keys.filterNot { it in cited }

    assertWithMessage("Eccezioni che il documento non cita piu': $stale").that(stale).isEmpty()
  }

  @Test
  fun ogniIdentificativoCitatoNeiFlussiDellaFase3EClassificato() {
    val cited = citedIdentifiers()
    assertThat(cited).isNotEmpty()
    // Una guardia contro una lettura vuota o sbagliata del documento: gli edge case della 3.1 ci sono.
    assertThat(cited).containsAtLeast("E-P1", "E-P21", "E-M12", "E-V5")

    // M4 viene da «E-M1–M4» e coincide col M4 della Fase 2 (TRUST_OLD_RECOVERY_KIT): e' coperto lo stesso.
    val unclassified = cited.filterNot { it in DiagCatalog.coveredEdgeCases || it in notADiagnosticWarning }
    assertWithMessage(
      "Identificativi citati nel §2 di fase3-progetto.md senza un DiagCode e non classificati come «non e' un avviso»: $unclassified",
    ).that(unclassified).isEmpty()
  }

  /** Gli identificativi (`E-P1`, `T-13`...) citati fra «## 2. Flussi» e «## 3. Scelte tecniche». */
  private fun citedIdentifiers(): Set<String> {
    val doc = File("../docs/fase3-progetto.md")
    assertWithMessage("docs/fase3-progetto.md non trovato dalla cartella ${File(".").absolutePath}").that(doc.exists()).isTrue()
    val text = doc.readText(Charsets.UTF_8)
    val from = text.indexOf("## 2. Flussi")
    val to = text.indexOf("## 3. Scelte tecniche")
    assertWithMessage("§2 non trovato nel progetto di Fase 3").that(from >= 0 && to > from).isTrue()

    return Regex("""\b(E-[A-Z]{1,2}[0-9]{1,3}|[BCLM][0-9]{1,3}|T-[0-9]{1,3})\b""")
      .findAll(text.substring(from, to))
      .map { it.value }
      .toSortedSet()
  }
}
