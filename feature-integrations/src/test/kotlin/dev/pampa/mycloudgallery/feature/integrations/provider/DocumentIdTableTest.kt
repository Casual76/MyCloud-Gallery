package dev.pampa.mycloudgallery.feature.integrations.provider

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.integrations.contract.DocumentId
import dev.pampa.mycloudgallery.feature.integrations.contract.DocumentIds
import java.util.Random
import org.junit.Test

/**
 * Fase 5B (P10, D23, C17; E-X1): gli id opachi dei documenti. Token casuali, mai derivati da un media; validi per un tempo che scorre con
 * l'uso e poi scadono; revocabili; con una capienza. JVM puro: l'orologio e' un contatore.
 */
class DocumentIdTableTest {

  private class Clock(var now: Long = 0) : DocumentClock {
    override fun elapsedMs(): Long = now
  }

  private fun table(
    clock: Clock = Clock(),
    browseTtl: Long = 1_000,
    deliveredTtl: Long = 10_000,
    browseCapacity: Int = 5,
    deliveredCapacity: Int = 3,
  ) = DocumentIdTable(clock, Random(42), browseTtl, deliveredTtl, browseCapacity, deliveredCapacity)

  private fun media() = DocumentTarget.Media(MediaId.random())

  @Test
  fun gliIdSonoOpachiAUnaForma() {
    val table = table(browseCapacity = 500)
    val ids = (1..200).map { table.idFor(media()) }
    for (id in ids) {
      assertThat(DocumentIds.isOpaque(id.value)).isTrue()
      assertThat(id.value).hasLength(24)
      assertThat(id.value).doesNotContain(".")
      assertThat(id.value).doesNotContain("/")
    }
    assertThat(ids.map { it.value }.toSet()).hasSize(200)
  }

  @Test
  fun nonContengonoNeDerivanoDaUnMediaId() {
    val table = table()
    val id = MediaId.random()
    val token = table.idFor(DocumentTarget.Media(id)).value
    // Nessuna parte dell'identita' del media (o del suo UUID senza trattini) compare nel token.
    assertThat(token).doesNotContain(id.value)
    for (chunk in id.value.split('-')) if (chunk.length >= 6) assertThat(token.lowercase()).doesNotContain(chunk)
    // E non e' una funzione del media: un'altra tabella (altro seme) emette un altro token per lo stesso media.
    val other = DocumentIdTable(Clock(), Random(7)).idFor(DocumentTarget.Media(id)).value
    assertThat(other).isNotEqualTo(token)
  }

  @Test
  fun lIdDiUnMediaEStabileFincheVale() {
    val clock = Clock()
    val table = table(clock)
    val target = media()
    val first = table.idFor(target)
    clock.now = 500
    assertThat(table.idFor(target)).isEqualTo(first)
    assertThat(table.size).isEqualTo(1)
  }

  @Test
  fun treIdSonoFissiENonScadono() {
    val clock = Clock()
    val table = table(clock)
    val root = table.idFor(DocumentTarget.Root)
    val recent = table.idFor(DocumentTarget.Recent)
    val folders = table.idFor(DocumentTarget.NasFolders)
    assertThat(setOf(root, recent, folders)).hasSize(3)
    clock.now = 10_000_000
    // Non scadono: nemmeno dopo ore (sono stabili, IN-2).
    assertThat(table.idFor(DocumentTarget.Root)).isEqualTo(root)
    assertThat((table.resolve(recent) as DocumentResolution.Found).target).isEqualTo(DocumentTarget.Recent)
    assertThat(table.size).isEqualTo(0)
  }

  private fun keyFile() = java.io.File.createTempFile("doc-id", ".key").also { it.delete(); it.deleteOnExit() }

  /**
   * IN-2 della verifica funzionale 5B: gli id della radice e delle cartelle erano token casuali nuovi a ogni processo e DocumentsUI li tiene in cache,
   * quindi dopo un riavvio "Allega file" di Gmail non trovava piu' niente. Adesso sono derivati con una chiave locale su file: lo stesso processo
   * nuovo, con lo stesso file, ridA gli stessi id.
   */
  @Test
  fun in2_radiceERecentiECartelleSonoUgualiDopoUnRiavvioDelProcesso() {
    val file = keyFile()
    val first = DocumentIdTable(Clock(), Random(1), idKey = FileDocumentIdKey(file, Random(1)))
    val folder = DocumentTarget.Folder(NasFolderKey("alessio\u001FVacanze/Mare"))
    val ids = listOf(DocumentTarget.Root, DocumentTarget.Recent, DocumentTarget.NasFolders, folder).map { first.idFor(it, label = "Mare") }

    // Il processo nuovo: tabella nuova (vuota), altro generatore casuale, stesso file della chiave.
    val second = DocumentIdTable(Clock(), Random(999), idKey = FileDocumentIdKey(file, Random(999)))
    val again = listOf(DocumentTarget.Root, DocumentTarget.Recent, DocumentTarget.NasFolders, folder).map { second.idFor(it, label = "Mare") }

    assertThat(again).isEqualTo(ids)
    assertThat(ids.toSet()).hasSize(4)
    // Una chiave diversa (un altro telefono, o dopo l'azzeramento) da' id diversi: gli id non dipendono da niente che si indovini.
    val other = DocumentIdTable(Clock(), Random(5), idKey = FileDocumentIdKey(keyFile(), Random(5)))
    assertThat(other.idFor(DocumentTarget.Root)).isNotEqualTo(ids[0])
    assertThat(other.idFor(folder)).isNotEqualTo(ids[3])
  }

  @Test
  fun in2_gliIdStabiliSonoOpachiNonDicononiLaCartellaENonSiIndovinano() {
    val table = DocumentIdTable(Clock(), Random(1), idKey = FileDocumentIdKey(keyFile(), Random(1)))
    val key = NasFolderKey("alessio\u001FFoto/Vacanze 2025/Mare")
    val id = table.idFor(DocumentTarget.Folder(key), label = "Mare")
    assertThat(DocumentIds.isOpaque(id.value)).isTrue()
    assertThat(id.value).hasLength(24)
    for (part in listOf("alessio", "Foto", "Vacanze", "2025", "Mare")) assertThat(id.value).doesNotContain(part)
    assertThat(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(key.value.toByteArray())).isNotEqualTo(id.value)
    // Lo stesso percorso da' sempre lo stesso id; un percorso diverso (anche di una lettera) un altro.
    assertThat(table.idFor(DocumentTarget.Folder(key))).isEqualTo(id)
    assertThat(table.idFor(DocumentTarget.Folder(NasFolderKey("alessio\u001FFoto/Vacanze 2025/Mar")))).isNotEqualTo(id)
  }

  @Test
  fun in2_unaCartellaDopoIlRiavvioSiRisolveSoloQuandoLaLibreriaLaRicorda() {
    val file = keyFile()
    val key = NasFolderKey("alessio\u001FVacanze")
    val id = DocumentIdTable(Clock(), Random(1), idKey = FileDocumentIdKey(file, Random(1))).idFor(DocumentTarget.Folder(key), label = "Vacanze")

    val restarted = DocumentIdTable(Clock(), Random(2), idKey = FileDocumentIdKey(file, Random(2)))
    // La tabella riparte vuota: l'id non si risolve da solo (nessun percorso e' ricavabile da lui)...
    assertThat(restarted.resolve(id)).isEqualTo(DocumentResolution.Unknown)
    // ...ma la radice e le cartelle virtuali si': sono stabili e si derivano.
    assertThat((restarted.resolve(restarted.idFor(DocumentTarget.Recent)) as DocumentResolution.Found).target).isEqualTo(DocumentTarget.Recent)
    // Chi ha la Libreria ricorda la cartella; un nome o un id inventato non entra.
    restarted.rememberFolder(id, key, "Vacanze")
    val found = restarted.resolve(id) as DocumentResolution.Found
    assertThat(found.target).isEqualTo(DocumentTarget.Folder(key))
    assertThat(found.label).isEqualTo("Vacanze")
    restarted.rememberFolder(DocumentId("aaaaaaaaaaaaaaaaaaaaaaaa"), key, "Falsa")
    assertThat(restarted.resolve(DocumentId("aaaaaaaaaaaaaaaaaaaaaaaa"))).isEqualTo(DocumentResolution.Unknown)
  }

  @Test
  fun in2_lAzzeramentoFaRuotareLaChiaveEGliIdVecchiNonValgonoPiu() {
    val file = keyFile()
    val table = DocumentIdTable(Clock(), Random(1), idKey = FileDocumentIdKey(file, Random(1)))
    val key = NasFolderKey("alessio\u001FVacanze")
    val root = table.idFor(DocumentTarget.Root)
    val folder = table.idFor(DocumentTarget.Folder(key), label = "Vacanze")
    val media = table.idFor(media(), delivered = true)

    table.revokeAll()

    assertThat(table.resolve(root)).isEqualTo(DocumentResolution.Unknown)
    assertThat(table.resolve(folder)).isEqualTo(DocumentResolution.Unknown)
    assertThat(table.resolve(media)).isEqualTo(DocumentResolution.Unknown)
    assertThat(table.idFor(DocumentTarget.Root)).isNotEqualTo(root)
    assertThat(table.idFor(DocumentTarget.Folder(key))).isNotEqualTo(folder)
    // La chiave nuova e' quella sul file: un processo nuovo vede gli id di adesso, non quelli di prima.
    val restarted = DocumentIdTable(Clock(), Random(3), idKey = FileDocumentIdKey(file, Random(3)))
    assertThat(restarted.idFor(DocumentTarget.Root)).isEqualTo(table.idFor(DocumentTarget.Root))
  }

  @Test
  fun in2_unaChiaveRovinataOChePiovePerTerraNonRompeNiente() {
    // File di una lunghezza sbagliata: se ne fa una nuova. Cartella impossibile da scrivere: la chiave vale per il processo.
    val bad = keyFile().also { it.writeBytes(ByteArray(7)) }
    val key = FileDocumentIdKey(bad, Random(1))
    assertThat(key.bytes()).hasLength(32)
    assertThat(bad.length()).isEqualTo(32L)
    val blocker = java.io.File.createTempFile("blocca", ".tmp").also { it.deleteOnExit() }
    val impossible = FileDocumentIdKey(java.io.File(blocker, "sotto/doc-id.key"), Random(2))
    val first = impossible.bytes()
    assertThat(first).hasLength(32)
    assertThat(impossible.bytes()).isEqualTo(first) // stessa nel processo
  }

  @Test
  fun unIdSconosciutoEUnIdScadutoSonoDistinti() {
    val clock = Clock()
    val table = table(clock)
    assertThat(table.resolve(DocumentId("aaaaaaaaaaaaaaaaaaaaaaaa"))).isEqualTo(DocumentResolution.Unknown)
    val id = table.idFor(media())
    assertThat(table.resolve(id)).isInstanceOf(DocumentResolution.Found::class.java)
    clock.now = 1_000 // E-X1: scaduto.
    assertThat(table.resolve(id)).isEqualTo(DocumentResolution.Expired)
    // Poi non c'e' piu': sconosciuto, e non torna a valere.
    assertThat(table.resolve(id)).isEqualTo(DocumentResolution.Unknown)
  }

  @Test
  fun laScadenzaScorreConLUso() {
    val clock = Clock()
    val table = table(clock)
    val id = table.idFor(media())
    repeat(5) {
      clock.now += 900
      assertThat(table.resolve(id)).isInstanceOf(DocumentResolution.Found::class.java)
    }
    clock.now += 1_000
    assertThat(table.resolve(id)).isEqualTo(DocumentResolution.Expired)
  }

  @Test
  fun unIdConsegnatoVialePiuALungoEResistePerPrimo() {
    val clock = Clock()
    val table = table(clock)
    val target = media()
    val browse = table.idFor(target)
    val delivered = table.idFor(target, delivered = true)
    // Lo stesso token passa fra i consegnati: chi aveva l'id dello sfoglio lo ritrova valido.
    assertThat(delivered).isEqualTo(browse)
    assertThat(table.isDelivered(delivered)).isTrue()
    clock.now = 5_000 // oltre il tempo dello sfoglio, dentro quello dei consegnati
    assertThat((table.resolve(delivered) as DocumentResolution.Found).delivered).isTrue()
    clock.now += 20_000
    assertThat(table.resolve(delivered)).isEqualTo(DocumentResolution.Expired)
    assertThat(table.isDelivered(delivered)).isFalse()
  }

  @Test
  fun losfoglioNonScalzaIConsegnatiEViceversa() {
    val table = table(browseCapacity = 3, deliveredCapacity = 2)
    val delivered = (1..2).map { table.idFor(media(), delivered = true) }
    repeat(50) { table.idFor(media()) }
    for (id in delivered) assertThat(table.isDelivered(id)).isTrue()
    assertThat(table.size).isEqualTo(3 + 2)
  }

  @Test
  fun oltreLaCapienzaEscePrimoIlMenoUsato() {
    val table = table(browseCapacity = 3)
    val targets = (1..3).map { media() }
    val ids = targets.map { table.idFor(it) }
    table.resolve(ids[0]) // l'uso riporta il primo in coda: il meno usato e' il secondo
    table.idFor(media())
    assertThat(table.resolve(ids[1])).isEqualTo(DocumentResolution.Unknown)
    assertThat(table.resolve(ids[0])).isInstanceOf(DocumentResolution.Found::class.java)
    assertThat(table.resolve(ids[2])).isInstanceOf(DocumentResolution.Found::class.java)
  }

  @Test
  fun unaRevocaDiUnIdNonToccaGliStabili() {
    val table = table()
    val key = NasFolderKey("alessio\u001FVacanze")
    val folder = table.idFor(DocumentTarget.Folder(key), label = "Vacanze")
    table.revoke(listOf(folder, table.idFor(DocumentTarget.Root)))
    assertThat(table.resolve(folder)).isInstanceOf(DocumentResolution.Found::class.java)
  }

  @Test
  fun unaRevocaEDefinitiva() {
    val table = table()
    val target = media()
    val id = table.idFor(target, delivered = true)
    table.revoke(listOf(id))
    assertThat(table.resolve(id)).isEqualTo(DocumentResolution.Unknown)
    assertThat(table.isDelivered(id)).isFalse()
    // Lo stesso media riceve un id nuovo: il vecchio non torna a valere.
    assertThat(table.idFor(target)).isNotEqualTo(id)
    table.revoke(listOf(DocumentId("zzzzzzzzzzzzzzzzzzzzzzzz"))) // un id mai emesso non fa danni
  }

  @Test
  fun leEtichetteDelleCartelleNonEntranoNellId() {
    val table = table()
    val id = table.idFor(DocumentTarget.Folder(NasFolderKey("tmp/privato/alessio")), label = "Vacanze 2025")
    assertThat(id.value).doesNotContain("Vacanze")
    assertThat(id.value).doesNotContain("alessio")
    val found = table.resolve(id) as DocumentResolution.Found
    assertThat(found.label).isEqualTo("Vacanze 2025")
  }

  @Test
  fun nessunaStringaDellUtenteNelToString() {
    val target = DocumentTarget.Media(MediaId("11111111-2222-3333-4444-555555555555"))
    assertThat(target.toString()).doesNotContain("1111")
    assertThat(DocumentTarget.Folder(NasFolderKey("percorso/segreto")).toString()).doesNotContain("segreto")
  }
}
