package dev.pampa.mycloudgallery.core.nasmedia

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.LocationState
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.nas.contract.NasAttributes
import dev.pampa.mycloudgallery.core.nas.contract.NasEntry
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasMediaReader
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasFault
import dev.pampa.mycloudgallery.core.nas.testing.FakeNasOp
import dev.pampa.mycloudgallery.core.nasmedia.contract.NasScanStatus
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanMode
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanSettings
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanStop
import dev.pampa.mycloudgallery.core.nasmedia.testing.SyntheticNas
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Gli edge case della scansione (docs/fase4-progetto.md §2.3): E-N1–E-N16, E-M12, E-S11, E-R10. Ogni test costruisce il suo
 * NAS (con ora **+58 s** rispetto al telefono), fa una prima scansione, cambia il NAS "dal PC" e ne fa un'altra.
 */
@RunWith(AndroidJUnit4::class)
class ScanEdgeCasesTest {

  private lateinit var h: ScanHarness

  @After
  fun close() {
    if (::h.isInitialized) h.close()
  }

  private fun idAt(path: String): MediaId = checkNotNull(h.store.mediaIdAt(h.keyOf(path))) { "nessun media in $path" }

  // --- E-N1: spostato o rinominato dal PC ----------------------------------------------------------------------------------

  @Test
  fun e_n1_unaCartellaDa1000FileRinominataDalPcNonCostaUnRilevamento() = runTest {
    h = ScanHarness()
    repeat(1_000) { i -> h.photo("Vacanze/Spagna/IMG_$i.jpg", seed = i.toLong()) }
    h.photo("Altro/x.jpg")
    val first = h.scan(ScanMode.Full)
    assertThat(first.detected).isEqualTo(1_001)
    val ids = (0 until 1_000).associateWith { idAt("Vacanze/Spagna/IMG_$it.jpg") }
    val keysBefore = (0 until 1_000).associateWith { ContentKey.of(h.catalog.media(ids.getValue(it))!!.currentVersion.fingerprint) }
    val opens = h.nas.opens.get()
    val bytes = h.nas.bytesRead.get()

    h.synthetic.renameFolder("test", "Vacanze/Spagna", "Vacanze/Spagna 2024")
    val report = h.scan(ScanMode.Verify)

    // Zero rilevamenti: nemmeno un file aperto, nemmeno un byte letto.
    assertThat(report.detected).isEqualTo(0)
    assertThat(report.moved).isEqualTo(1_000)
    assertThat(report.adopted).isEqualTo(0)
    assertThat(report.bytesRead).isEqualTo(0)
    assertThat(h.nas.opens.get()).isEqualTo(opens)
    assertThat(h.nas.bytesRead.get()).isEqualTo(bytes)
    assertThat(report.vanished).isEqualTo(0)
    assertThat(report.freeze).isNull()
    assertThat(report.complete).isTrue()
    // Identita' conservata e posti nuovi: nessun media nuovo, nessuna assenza.
    assertThat(h.store.knownCount(h.me, "test")).isEqualTo(1_001)
    for (i in 0 until 1_000) {
      assertThat(h.store.mediaIdAt(h.keyOf("Vacanze/Spagna 2024/IMG_$i.jpg"))).isEqualTo(ids.getValue(i))
      assertThat(h.store.stateOf(h.keyOf("Vacanze/Spagna/IMG_$i.jpg"))).isNull()
    }
    // "Zero preparazioni": la preparazione e' per chiave di contenuto, e la chiave non e' cambiata.
    for (i in listOf(0, 1, 499, 999)) {
      val media = h.catalog.media(ids.getValue(i))!!
      assertThat(ContentKey.of(media.currentVersion.fingerprint)).isEqualTo(keysBefore.getValue(i))
      assertThat(media.locations.map { it.location.key }).containsExactly(h.keyOf("Vacanze/Spagna 2024/IMG_$i.jpg"))
      assertThat(media.versions).hasSize(1)
    }
  }

  @Test
  fun e_n1_ancheUnSingoloFileRinominatoNellaStessaCartella() = runTest {
    h = ScanHarness()
    h.photo("Foto/a.jpg")
    h.photo("Foto/b.jpg")
    h.scan()
    val id = idAt("Foto/a.jpg")
    val opens = h.nas.opens.get()
    h.nas.rename("test", "Foto/a.jpg", "Foto/a rinominata.jpg")
    val report = h.scan()
    assertThat(report.moved).isEqualTo(1)
    assertThat(report.detected).isEqualTo(0)
    assertThat(h.nas.opens.get()).isEqualTo(opens)
    assertThat(h.store.mediaIdAt(h.keyOf("Foto/a rinominata.jpg"))).isEqualTo(id)
  }

  @Test
  fun e_n1_unaCopiaNonEUnoSpostamento() = runTest {
    h = ScanHarness()
    h.photo("Foto/a.jpg", seed = 5)
    h.scan()
    // Un file nuovo con lo stesso contenuto apparente ma un altro fileId (copia): va letto, e il vecchio resta.
    h.photo("Foto/copia di a.jpg", seed = 5)
    val report = h.scan()
    assertThat(report.moved).isEqualTo(0)
    assertThat(report.detected).isEqualTo(1)
    assertThat(h.store.knownCount(h.me, "test")).isEqualTo(2)
    assertThat(report.vanished).isEqualTo(0)
  }

  // --- E-N2, E-N3, E-N11: modificato e cancellato dal PC ---------------------------------------------------------------------

  @Test
  fun e_n2_modificatoSulPostoDiventaUnaVersioneNuova() = runTest {
    h = ScanHarness()
    h.photo("Foto/a.jpg", seed = 1)
    h.photo("Foto/b.jpg", seed = 2)
    h.scan()
    val id = idAt("Foto/a.jpg")
    h.nas.modify("test", "Foto/a.jpg", SyntheticNas.content(1_900_000, 77, SyntheticNas.JPEG_HEAD), modifiedAt = h.nasNow - 10 * 60_000)
    val report = h.scan()
    assertThat(report.changed).isEqualTo(1)
    assertThat(report.detected).isEqualTo(1)
    assertThat(report.unchanged).isEqualTo(1)
    assertThat(h.store.mediaIdAt(h.keyOf("Foto/a.jpg"))).isEqualTo(id)
    val media = h.catalog.media(id)!!
    assertThat(media.versions).hasSize(2)
    assertThat(media.currentVersion.fingerprint.size).isEqualTo(1_900_000 + 0)
  }

  @Test
  fun e_n11_dueFamiliariSulloStessoFileLaScansioneVedeLUltimoStato() = runTest {
    h = ScanHarness()
    h.photo("Comune/a.jpg", seed = 1)
    h.scan()
    val id = idAt("Comune/a.jpg")
    h.nas.modify("test", "Comune/a.jpg", SyntheticNas.content(1_700_000, 10, SyntheticNas.JPEG_HEAD), modifiedAt = h.nasNow - 600_000)
    h.nas.modify("test", "Comune/a.jpg", SyntheticNas.content(1_800_000, 11, SyntheticNas.JPEG_HEAD), modifiedAt = h.nasNow - 300_000)
    h.scan()
    val media = h.catalog.media(id)!!
    assertThat(media.versions).hasSize(2)
    assertThat(media.currentVersion.fingerprint.size).isEqualTo(1_800_000)
  }

  @Test
  fun e_n3_cancellatoDalPcEsceDallaLibreriaDopoIlGiroCompleto() = runTest {
    h = ScanHarness()
    h.photo("Foto/a.jpg", seed = 1)
    h.photo("Foto/b.jpg", seed = 2)
    h.scan()
    val id = idAt("Foto/a.jpg")
    h.synthetic.deleteFromPc("test", "Foto/a.jpg")
    val report = h.scan()
    assertThat(report.vanished).isEqualTo(1)
    assertThat(h.store.stateOf(h.keyOf("Foto/a.jpg"))).isEqualTo(LocationState.ABSENT)
    assertThat(h.store.stateOf(h.keyOf("Foto/b.jpg"))).isEqualTo(LocationState.PRESENT)
    // Il media c'e' ancora nel catalogo (30 giorni di tolleranza), ma senza posizioni presenti.
    assertThat(h.catalog.media(id)!!.locations.none { it.isPresent }).isTrue()
  }

  // --- E-N4: in arrivo ---------------------------------------------------------------------------------------------------------

  @Test
  fun e_n4_unFileNuovoDelPcEInArrivoFinoAQuandoENonStabile() = runTest {
    h = ScanHarness()
    h.photo("Foto/a.jpg")
    h.scan()
    // Il PC copia un file adesso (ora del NAS) e uno col nome temporaneo del download.
    h.synthetic.arrive("test", "Foto/nuova.jpg", modifiedAt = h.nasNow)
    h.nas.putFile("test", "Foto/video.mp4.part", SyntheticNas.content(5_000_000, 3, SyntheticNas.MP4_HEAD), h.nasNow - 3_600_000)
    val r1 = h.scan()
    assertThat(r1.detected).isEqualTo(0)
    assertThat(r1.arriving).isEqualTo(2)
    assertThat(h.store.stateOf(h.keyOf("Foto/nuova.jpg"))).isNull()

    // 30 s dopo: la data di modifica e' vecchia di 30 s, ancora in arrivo.
    h.advance(30_000)
    assertThat(h.scan().detected).isEqualTo(0)
    // Due minuti e mezzo dopo la prima occhiata: visto uguale a due minuti di distanza: stabile.
    h.advance(120_000)
    val r3 = h.scan()
    assertThat(r3.detected).isEqualTo(1)
    assertThat(r3.arriving).isEqualTo(1) // resta il `.part`
    assertThat(h.store.stateOf(h.keyOf("Foto/nuova.jpg"))).isEqualTo(LocationState.PRESENT)

    // Finito il download: il file prende il nome vero, con la data del file originale.
    h.nas.rename("test", "Foto/video.mp4.part", "Foto/video.mp4")
    val r4 = h.scan()
    assertThat(r4.detected).isEqualTo(1)
    assertThat(r4.arriving).isEqualTo(0)
  }

  @Test
  fun e_n4_unFileCheCresceNonSiRilevaFinoAQuandoSiFerma() = runTest {
    h = ScanHarness()
    h.photo("Foto/a.jpg")
    h.scan()
    h.nas.putFile("test", "Foto/film.mp4", SyntheticNas.content(10_000_000, 3, SyntheticNas.MP4_HEAD), h.nasNow - 5_000)
    assertThat(h.scan().detected).isEqualTo(0)
    // Cresce: dimensione e data nuove a ogni elenco.
    repeat(3) { n ->
      h.advance(100_000)
      h.nas.putFile("test", "Foto/film.mp4", SyntheticNas.content(10_000_000L + (n + 1) * 5_000_000L, 3, SyntheticNas.MP4_HEAD), h.nasNow - 1_000)
      assertThat(h.scan().detected).isEqualTo(0)
    }
    // Finisce di scriversi: ancora recente, poi visto uguale due volte a due minuti di distanza.
    h.advance(60_000)
    assertThat(h.scan().detected).isEqualTo(0)
    h.advance(70_000)
    assertThat(h.scan().detected).isEqualTo(1)
  }

  // --- E-N5, E-N6, E-N7: elenchi grandi, nomi, maiuscole ------------------------------------------------------------------------

  @Test
  fun e_n6_nomiNonGestibiliSiContanoEGliAltriEntrano() = runTest {
    h = ScanHarness()
    h.photo("Strani/ok.jpg")
    h.photo("Strani/Città 🌅 àèìòù.jpg")
    h.photo("Strani/" + "x".repeat(250) + ".jpg") // 254 byte: ammesso
    listOf("CON.jpg", "NUL.png", "bad|name.jpg", "a<b>.jpg", "y".repeat(300) + ".jpg").forEach { h.photo("Strani/$it") }
    val report = h.scan()
    assertThat(report.skippedNames).isEqualTo(5)
    assertThat(h.store.knownCount(h.me, "test")).isEqualTo(3)
    assertThat(report.complete).isTrue()
  }

  @Test
  fun e_n7_unaRinominaSoloDiMaiuscoleNonEUnFileNuovo() = runTest {
    h = ScanHarness()
    h.photo("Foto/Doppio.JPG")
    h.scan()
    val id = idAt("Foto/Doppio.JPG")
    val opens = h.nas.opens.get()
    h.nas.rename("test", "Foto/Doppio.JPG", "Foto/doppio.jpg")
    val report = h.scan()
    assertThat(report.detected).isEqualTo(0)
    assertThat(report.moved).isEqualTo(0)
    assertThat(report.unchanged).isEqualTo(1)
    assertThat(h.nas.opens.get()).isEqualTo(opens)
    assertThat(h.store.knownCount(h.me, "test")).isEqualTo(1)
    assertThat(h.store.mediaIdAt(h.keyOf("FOTO/DOPPIO.JPG"))).isEqualTo(id)
  }

  @Test
  fun e_n7_dueNomiUgualiAMenoDelleMaiuscoleInUnElencoSiTieneIlPrimo() = runTest {
    h = ScanHarness()
    h.photo("Foto/a.jpg")
    val reader = TamperingReader(h.nas.reader, { dir, entries ->
      if (dir == "Foto") entries + entries.single().copy(name = "A.JPG", fileId = 999_999) else entries
    })
    val tampered = ScanHarness(readerWrap = { reader }, sharedNas = h.nas, clockSource = h)
    val report = tampered.scan()
    tampered.close()
    assertThat(report.skippedNames).isEqualTo(1)
    assertThat(report.detected).isEqualTo(1)
    assertThat(tampered.store.knownCount(tampered.me, "test")).isEqualTo(1)
  }

  // --- E-N8, E-N16: cartelle di sistema e nascoste ------------------------------------------------------------------------------

  @Test
  fun e_n8_e_n16_lePerCartelleDiSistemaENascosteNonSiElencanoMai() = runTest {
    h = ScanHarness()
    h.photo("Foto/a.jpg")
    val hiddenFolders = listOf(".recycle", "\$RECYCLE.BIN", ".AppleDouble", ".wdmc", "@eaDir", ".mycloudgallery", ".Trash-1000")
    hiddenFolders.forEach { h.photo("$it/x.jpg") }
    h.nas.putDir("test", "Nascosta", attributes = NasAttributes.HIDDEN)
    h.photo("Nascosta/y.jpg")
    h.photo("Foto/._a.jpg")
    h.nas.putFile("test", "Foto/Thumbs.db", SyntheticNas.content(1_000, 1), h.nasNow - 3_600_000)
    val report = h.scan()
    assertThat(report.skippedSystem).isEqualTo(hiddenFolders.size + 1 + 2)
    assertThat(h.store.knownCount(h.me, "test")).isEqualTo(1)
    assertThat(h.reader.listed).containsExactly("", "Foto")
  }

  // --- E-N9: sola lettura ------------------------------------------------------------------------------------------------------------

  @Test
  fun e_n9_loScannerVedeIlNasSoloDalContrattoDiLetturaEUnaShareInSolaLetturaSiScansionaPerIntero() = runTest {
    // Il contratto da cui la scansione legge non ha nessuna operazione che scrive, rinomina o cancella (E-S11).
    val verbs = NasMediaReader::class.java.methods.map { it.name.lowercase() }
    assertThat(verbs).containsAtLeast("list", "stat", "open", "readall")
    val destructive = listOf("write", "delete", "remove", "rename", "move", "mkdir", "create", "put", "truncate", "copy", "trash", "publish", "set")
    assertThat(verbs.filter { name -> destructive.any { name.startsWith(it) } }).isEmpty()

    // Una cartella leggibile ma non scrivibile, e un file che l'account non puo' aprire: si legge tutto il resto.
    h = ScanHarness()
    h.photo("Sola lettura/a.jpg")
    h.photo("Sola lettura/b.jpg")
    h.photo("Sola lettura/c.jpg")
    h.nas.fault = FakeNasFault { op, _, path -> if (op == FakeNasOp.OPEN && path?.name == "b.jpg") NasError.PermissionDenied else null }
    val report = h.scan()
    assertThat(report.busy).isEqualTo(1)
    assertThat(report.detected).isEqualTo(2)
    assertThat(report.complete).isTrue()
    assertThat(h.store.knownCount(h.me, "test")).isEqualTo(2)
  }

  // --- E-N10: collegamenti e cicli ------------------------------------------------------------------------------------------------

  @Test
  fun e_n10_iPuntiDiRianalisiNonSiSeguonoMai() = runTest {
    h = ScanHarness()
    h.photo("Foto/a.jpg")
    h.nas.putDir("test", "Collegamento", attributes = NasAttributes.REPARSE_POINT)
    h.photo("Collegamento/b.jpg")
    val report = h.scan()
    assertThat(report.skippedSystem).isEqualTo(1)
    assertThat(h.reader.listed).doesNotContain("Collegamento")
    assertThat(h.store.knownCount(h.me, "test")).isEqualTo(1)
  }

  @Test
  fun e_n10_unaCartellaRaggiungibilePerDueStradeSiVisitaUnaVolta() = runTest {
    h = ScanHarness()
    h.photo("Camera/a.jpg")
    // `Copia` e' un collegamento simbolico a `Camera`: Samba lo mostra come una cartella normale con lo stesso `fileId`.
    val cameraId = h.nas.reader.let { reader ->
      var id: Long? = null
      reader.list("test", null, dev.pampa.mycloudgallery.core.nas.contract.Purpose.BACKGROUND) { page -> id = page.single { it.name == "Camera" }.fileId }
      id
    }
    val tampered = ScanHarness(
      sharedNas = h.nas,
      clockSource = h,
      readerWrap = { inner ->
        TamperingReader(
          inner,
          tamper = { dir, entries ->
            if (dir == "") entries + NasEntry("Copia", true, 0, 0, null, cameraId, NasAttributes.DIRECTORY) else entries
          },
          aliases = mapOf("Copia" to "Camera"),
        )
      },
    )
    val report = tampered.scan()
    tampered.close()
    // Una delle due strade si e' visitata, l'altra no (quale, dipende dall'ordine delle cartelle).
    assertThat(tampered.reader.listed.count { it == "Camera" || it == "Copia" }).isEqualTo(1)
    assertThat(report.skippedSystem).isEqualTo(1)
    assertThat(tampered.store.knownCount(tampered.me, "test")).isEqualTo(1)
  }

  @Test
  fun e_n10_unaCatenaSenzaFineSiFermaA64Livelli() = runTest {
    h = ScanHarness()
    h.photo("Foto/a.jpg")
    h.nas.putDir("test", "Vuota")
    // Ogni cartella contiene una cartella `giu` con un fileId sempre nuovo, che porta a una cartella vuota: senza fine.
    var counter = 5_000L
    val chain = TamperingReader(
      h.nas.reader,
      tamper = { _, entries -> entries + NasEntry("giu", true, 0, 0, null, counter++, NasAttributes.DIRECTORY) },
      aliases = (1..90).associate { n -> List(n) { "giu" }.joinToString("/") to "Vuota" },
    )
    val deep = ScanHarness(sharedNas = h.nas, clockSource = h, readerWrap = { chain })
    val report = deep.scan()
    deep.close()
    assertThat(report.folders).isAtMost(70)
    assertThat(report.skippedSystem).isAtLeast(1)
    assertThat(report.stoppedBy).isNull()
    assertThat(report.complete).isTrue()
  }

  // --- E-N13: file occupato dal PC --------------------------------------------------------------------------------------------------

  @Test
  fun e_n13_unFileBloccatoDalPcSiRiprovaAlGiroDopoEMaiCorrotto() = runTest {
    h = ScanHarness()
    h.photo("Foto/a.jpg")
    h.photo("Foto/b.jpg")
    h.nas.lock("test", "Foto/b.jpg")
    val first = h.scan()
    assertThat(first.busy).isEqualTo(1)
    assertThat(first.detected).isEqualTo(1)
    assertThat(h.store.stateOf(h.keyOf("Foto/b.jpg"))).isNull()
    assertThat(first.complete).isTrue()
    h.nas.unlock("test", "Foto/b.jpg")
    val second = h.scan()
    assertThat(second.busy).isEqualTo(0)
    assertThat(second.detected).isEqualTo(1)
    assertThat(h.store.stateOf(h.keyOf("Foto/b.jpg"))).isEqualTo(LocationState.PRESENT)
  }

  @Test
  fun e_n13_unFileNotoModificatoEBloccatoNonDiventaAssente() = runTest {
    h = ScanHarness()
    h.photo("Foto/a.jpg")
    h.scan()
    h.nas.modify("test", "Foto/a.jpg", SyntheticNas.content(1_900_000, 55, SyntheticNas.JPEG_HEAD), modifiedAt = h.nasNow - 600_000)
    h.nas.lock("test", "Foto/a.jpg")
    val report = h.scan()
    assertThat(report.busy).isEqualTo(1)
    assertThat(report.vanished).isEqualTo(0)
    assertThat(h.store.stateOf(h.keyOf("Foto/a.jpg"))).isEqualTo(LocationState.PRESENT)
    h.nas.unlock("test", "Foto/a.jpg")
    assertThat(h.scan().detected).isEqualTo(1)
    assertThat(h.catalog.media(idAt("Foto/a.jpg"))!!.versions).hasSize(2)
  }

  // --- E-N14, E-N15: copertine e cartelle escluse --------------------------------------------------------------------------------------

  private fun albums(count: Int, covers: Int = 3) {
    repeat(count) { a ->
      h.nas.putFile("test", "Musica/Artista/Album $a/01 Traccia.mp3", SyntheticNas.content(8_000_000, a.toLong()), h.nasNow - 3 * DAY)
      repeat(covers) { c -> h.photo("Musica/Artista/Album $a/${listOf("cover.jpg", "folder.jpg", "AlbumArtSmall.jpg")[c]}", size = 40_000, seed = a * 10L + c) }
    }
  }

  @Test
  fun e_n14_leCopertineDegliAlbumSonoEscluseDiDefaultEContate() = runTest {
    h = ScanHarness()
    albums(10)
    h.photo("Foto/a.jpg")
    val report = h.scan()
    assertThat(report.albumCovers).isEqualTo(30)
    assertThat(h.store.knownCount(h.me, "test")).isEqualTo(1)
    // Con l'opzione "Mostra le copertine degli album" entrano.
    h.settings = ScanSettings(showAlbumCovers = true)
    val shown = h.scan()
    assertThat(shown.albumCovers).isEqualTo(0)
    assertThat(h.store.knownCount(h.me, "test")).isEqualTo(31)
  }

  @Test
  fun e_n14_spegnereLeCopertineLeToglieSenzaScambiarleConUnaCancellazioneDiMassa() = runTest {
    h = ScanHarness(settings = ScanSettings(showAlbumCovers = true))
    albums(40) // 120 copertine: la soglia e' max(50, 5% di 120) = 50, e quelle uscite sono molte di piu'
    assertThat(h.scan().detected).isEqualTo(120)
    h.settings = ScanSettings(showAlbumCovers = false)
    val report = h.scan()
    assertThat(report.freeze).isNull()
    assertThat(report.complete).isTrue()
    assertThat(report.albumCovers).isEqualTo(120)
    assertThat(h.store.stateOf(h.keyOf("Musica/Artista/Album 0/cover.jpg"))).isEqualTo(LocationState.ABSENT)
    assertThat(h.store.countPresent(h.me, "test")).isEqualTo(0)
  }

  @Test
  fun e_n14_unaFotoGrandeInUnaCartellaDiMusicaNonEUnaCopertina() = runTest {
    h = ScanHarness()
    albums(1, covers = 1)
    h.photo("Musica/Artista/Album 0/foto della band.jpg", size = 3_000_000)
    val report = h.scan()
    assertThat(report.albumCovers).isEqualTo(1)
    assertThat(h.store.stateOf(h.keyOf("Musica/Artista/Album 0/foto della band.jpg"))).isEqualTo(LocationState.PRESENT)
  }

  @Test
  fun e_n15_leCartelleEscluseNonSiElencanoOltreLaRadiceDellEsclusione() = runTest {
    h = ScanHarness(settings = ScanSettings(excluded = listOf(SharePath.of("Musica"), SharePath.of("Film", "Serie TV"))))
    albums(5)
    h.photo("Foto/a.jpg")
    h.photo("Film/Serie TV/S01/ep1.jpg")
    h.photo("Film/Altro/b.jpg")
    val report = h.scan()
    assertThat(report.excludedFolders).isEqualTo(2)
    assertThat(h.reader.listed).containsNoneOf("Musica", "Musica/Artista", "Film/Serie TV", "Film/Serie TV/S01")
    assertThat(h.store.knownCount(h.me, "test")).isEqualTo(2)
  }

  @Test
  fun e_n15_escludereUnaCartellaGiaScansionataLaToglieDalLaLibreriaSenzaCongelare() = runTest {
    h = ScanHarness()
    repeat(80) { i -> h.photo("Vecchi/IMG_$i.jpg", seed = i.toLong()) }
    h.photo("Foto/a.jpg")
    h.scan()
    assertThat(h.store.countPresent(h.me, "test")).isEqualTo(81)
    h.settings = ScanSettings(excluded = listOf(SharePath.of("Vecchi")))
    val report = h.scan()
    assertThat(report.freeze).isNull()
    assertThat(report.excludedFolders).isEqualTo(1)
    assertThat(h.store.countPresent(h.me, "test")).isEqualTo(1)
  }

  // --- E-M12, E-S11, E-R9, E-R10 --------------------------------------------------------------------------------------------------------

  @Test
  fun e_m12_zeroByteEFalsiMediaSiContanoENonEntranoMai() = runTest {
    h = ScanHarness()
    h.photo("Foto/a.jpg")
    h.nas.putFile("test", "Foto/vuoto.jpg", dev.pampa.mycloudgallery.core.nas.testing.NasContent.of(ByteArray(0)), h.nasNow - DAY)
    h.nas.putFile("test", "Foto/testo.jpg", dev.pampa.mycloudgallery.core.nas.testing.NasContent.of("non sono una foto".repeat(100).toByteArray()), h.nasNow - DAY)
    h.nas.putFile("test", "Foto/falso.mp4", SyntheticNas.content(500_000, 4), h.nasNow - DAY)
    val first = h.scan()
    assertThat(first.notMedia).isEqualTo(3)
    assertThat(h.store.knownCount(h.me, "test")).isEqualTo(1)
    // Non si rileggono a ogni giro finche' non cambiano.
    val second = h.scan()
    assertThat(second.notMedia).isEqualTo(3)
    assertThat(second.detected).isEqualTo(0)
    assertThat(second.bytesRead).isEqualTo(0)
  }

  @Test
  fun e_s11_laScansioneNonScriveNeCancellaNulla() = runTest {
    h = ScanHarness()
    repeat(30) { h.photo("Foto/f$it.jpg", seed = it.toLong()) }
    val before = h.nas.bytes("test", "Foto/f3.jpg")!!.contentHashCode()
    val count = h.nas.fileCount("test")
    h.scan()
    h.synthetic.deleteFromPc("test", "Foto/f7.jpg")
    h.scan()
    assertThat(h.nas.fileCount("test")).isEqualTo(count - 1) // solo la cancellazione del "PC"
    assertThat(h.nas.bytes("test", "Foto/f3.jpg")!!.contentHashCode()).isEqualTo(before)
    assertThat(h.nas.exists("test", ".mycloudgallery")).isFalse()
  }

  @Test
  fun e_r10_laShareSparitaRendeIFileNonRaggiungibiliNonAssenti() = runTest {
    h = ScanHarness()
    h.photo("Foto/a.jpg")
    h.photo("Foto/b.jpg")
    h.scan()
    h.synthetic.removeShare("test")
    val report = h.scan()
    assertThat(report.stoppedBy).isEqualTo(ScanStop.UNAVAILABLE)
    assertThat(report.complete).isFalse()
    assertThat(h.scanner.status.value).isEqualTo(NasScanStatus.Unavailable("SHARE_MISSING"))
    assertThat(h.store.stateOf(h.keyOf("Foto/a.jpg"))).isEqualTo(LocationState.UNAVAILABLE)
    assertThat(report.vanished).isEqualTo(0)
    // Torna la share con gli stessi file: tornano presenti, senza rilevare niente.
    h.nas.addShare("test")
    h.photo("Foto/a.jpg")
    h.photo("Foto/b.jpg")
    val back = h.scan()
    assertThat(back.complete).isTrue()
    assertThat(h.store.stateOf(h.keyOf("Foto/a.jpg"))).isEqualTo(LocationState.PRESENT)
  }

  @Test
  fun unNasCheNonRispondeFermaIlGiroEIlGiroDopoRiprendeSenzaPerdereNulla() = runTest {
    h = ScanHarness()
    repeat(20) { h.photo("Foto/f$it.jpg", seed = it.toLong()) }
    h.nas.failWith = NasError.Timeout
    val down = h.scan()
    assertThat(down.stoppedBy).isEqualTo(ScanStop.UNAVAILABLE)
    assertThat(down.complete).isFalse()
    assertThat(h.scanner.status.value).isEqualTo(NasScanStatus.Unavailable("NAS_UNREACHABLE"))
    assertThat(h.store.knownCount(h.me, "test")).isEqualTo(0)
    h.nas.failWith = null
    val up = h.scan()
    assertThat(up.resumed).isTrue()
    assertThat(up.complete).isTrue()
    assertThat(h.store.knownCount(h.me, "test")).isEqualTo(20)
  }

  @Test
  fun unaCartellaIllegibileImpedisceDiSegnareAssenze() = runTest {
    h = ScanHarness()
    h.photo("Foto/a.jpg")
    h.photo("Foto/b.jpg")
    h.photo("Altro/c.jpg")
    h.scan()
    h.synthetic.deleteFromPc("test", "Foto/a.jpg")
    h.nas.fault = FakeNasFault { op, _, path ->
      if (op == FakeNasOp.LIST && path?.value == "Altro") NasError.PermissionDenied else null
    }
    val report = h.scan()
    assertThat(report.blockedFolders).isEqualTo(1)
    assertThat(report.vanished).isEqualTo(0)
    assertThat(report.complete).isFalse()
    assertThat(h.store.stateOf(h.keyOf("Foto/a.jpg"))).isEqualTo(LocationState.PRESENT)
    // Il permesso torna: il giro dopo e' completo e segna la cancellazione vera.
    h.nas.fault = null
    val next = h.scan()
    assertThat(next.complete).isTrue()
    assertThat(next.vanished).isEqualTo(1)
    assertThat(h.store.stateOf(h.keyOf("Foto/a.jpg"))).isEqualTo(LocationState.ABSENT)
  }

  @Test
  fun scansionePerCartellaNonSegnaAssenzeENonToccaIlGiroCompleto() = runTest {
    h = ScanHarness()
    h.photo("Foto/a.jpg")
    h.photo("Altro/b.jpg")
    h.scan()
    h.synthetic.deleteFromPc("test", "Altro/b.jpg")
    h.photo("Foto/nuova.jpg")
    val report = h.scan(ScanMode.Folder(SharePath.of("Foto")))
    assertThat(report.complete).isTrue()
    assertThat(report.detected).isEqualTo(1)
    assertThat(report.vanished).isEqualTo(0)
    assertThat(h.store.stateOf(h.keyOf("Altro/b.jpg"))).isEqualTo(LocationState.PRESENT)
    // Lo scopo e' quello di un utente che aspetta.
    assertThat(h.reader.purposes).contains(dev.pampa.mycloudgallery.core.nas.contract.Purpose.INTERACTIVE)
  }
}
