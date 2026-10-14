package dev.pampa.mycloudgallery.core.nasmedia.testing

import dev.pampa.mycloudgallery.core.nas.contract.NasAttributes
import dev.pampa.mycloudgallery.core.nas.testing.InMemoryNasMedia
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import kotlin.random.Random

/**
 * Il NAS **sintetico** (docs/fase4-progetto.md §5.3): file virtuali (dimensione + seme, contenuto generato a richiesta per
 * le letture a intervalli) con le proporzioni dell'inventario (`docs/inventario-nas.md`), sopra il NAS finto
 * [InMemoryNasMedia]. Deterministico per [seed].
 *
 * **Scheletro di P0**: genera l'albero e i casi difficili per nome, dimensione, data e attributi, con un'intestazione
 * plausibile davanti al corpo virtuale (JPEG `FF D8 FF E1`, MP4 `ftyp`, PNG, HEIC). I file **veri** piccoli (JPEG con EXIF
 * e miniatura, HEIC, WebP animati, MP4 con indice in testa e in coda) li aggiunge P5 con [realContent], dalle fixture di
 * `tools/fixtures` (P6); gli scenari del §5.3 sono qui sotto, da completare con le misure.
 *
 * @property realContent se restituisce dei byte per un percorso, il file usa quelli invece del contenuto virtuale.
 */
class SyntheticNas(
  val nas: InMemoryNasMedia,
  val seed: Long = 1,
  private val realContent: (path: String) -> ByteArray? = { null },
) {

  /** Le proporzioni da generare. */
  enum class Profile(
    val privatePhotos: Int,
    val privateVideos: Int,
    /** Una cartella della share privata con questi file (8.910 in `alessio`, E-N5). */
    val bigFolder: Int,
    val musicFolders: Int,
    val commonPhotos: Int,
    val commonVideos: Int,
  ) {
    /** Le proporzioni dell'inventario, ridotte a ~20 mila file (M1 sull'emulatore, test di P5). */
    INVENTARIO(privatePhotos = 10_650, privateVideos = 1_595, bigFolder = 8_910, musicFolders = 2_300, commonPhotos = 500, commonVideos = 380),

    /** Per le prestazioni della scansione e della Libreria (P5, P10). */
    CENTOMILA(privatePhotos = 80_000, privateVideos = 6_000, bigFolder = 8_910, musicFolders = 2_300, commonPhotos = 8_000, commonVideos = 1_000),

    /** Piccolo, per i test veloci. */
    PICCOLO(privatePhotos = 300, privateVideos = 20, bigFolder = 120, musicFolders = 10, commonPhotos = 40, commonVideos = 5),
  }

  private val random = Random(seed)
  private var nextSeed = seed * 1_000_003

  /** I percorsi generati per categoria (per gli scenari e le asserzioni dei test). */
  val generated: MutableMap<String, MutableList<String>> = LinkedHashMap()

  /**
   * Riempie [privateShare] (piatta: 6 cartelle, una molto grande) e [commonShare] (molte cartelle musicali con copertine
   * da ~40 KB, foto e video fino a 9,4 GB virtuali), con date dal 1979, nomi WhatsApp/Screenshot/Pixel, emoji, accenti,
   * percorsi lunghi, nomi uguali a meno delle maiuscole, file a zero byte, corrotti, temporanei in arrivo, cartelle di
   * sistema e nascoste (E-N16).
   */
  fun populate(profile: Profile, privateShare: String = "test", commonShare: String = "test-comune") {
    nas.addShare(privateShare)
    nas.addShare(commonShare)
    val now = nas.nasNow()
    val folders = listOf("Camera", "WhatsApp Images", "Screenshots", "Download", "Pixel", "Vecchie 1979-2005")
    var photos = 0
    // La cartella enorme (E-N5).
    repeat(profile.bigFolder) { i ->
      put(privateShare, "Camera/IMG_${20240101 + i % 365}_${100000 + i}.jpg", jpeg(1_800_000), now - i * 3_600_000L, "foto")
      photos++
    }
    while (photos < profile.privatePhotos) {
      val folder = folders[1 + photos % (folders.size - 1)]
      val name = when (folder) {
        "WhatsApp Images" -> "IMG-2023${(photos % 12 + 1).toString().padStart(2, '0')}15-WA${photos.toString().padStart(4, '0')}.jpg"
        "Screenshots" -> "Screenshot_2024-05-0${photos % 9 + 1}-10-00-${photos % 60}.png"
        "Pixel" -> "PXL_20240601_${photos}.jpg"
        "Vecchie 1979-2005" -> "scansione ${photos}.jpg"
        else -> "foto_${photos}.heic"
      }
      val date = if (folder == "Vecchie 1979-2005") OLD_DATE + photos * 86_400_000L else now - photos * 600_000L
      put(privateShare, "$folder/$name", if (name.endsWith(".png")) png(400_000) else jpeg(1_800_000), date, "foto")
      photos++
    }
    repeat(profile.privateVideos) { i ->
      val size = if (i % 200 == 0) 9_400_000_000L else 120_000_000L
      put(privateShare, "Camera/VID_${i}.mp4", mp4(size), now - i * 86_400_000L, "video")
    }
    // Casi difficili (E-N6, E-N7, E-M12, E-N4, E-N16).
    put(privateShare, "Download/Città 🌅 al tramonto.jpg", jpeg(900_000), now - 10_000_000, "nomi")
    put(privateShare, "Download/" + "nome molto lungo ".repeat(12).trim() + ".jpg", jpeg(500_000), now - 9_000_000, "nomi")
    put(privateShare, "Download/Doppio.JPG", jpeg(700_000), now - 8_000_000, "maiuscole")
    put(privateShare, "Download/vuoto.jpg", NasContent.of(ByteArray(0)), now - 7_000_000, "zero")
    put(privateShare, "Download/finto.jpg", NasContent.virtual(50_000, nextSeed++), now - 6_000_000, "corrotti")
    put(privateShare, "Download/in arrivo.mp4.part", mp4(80_000_000), now - 10_000, "arrivo")
    nas.putDir(privateShare, ".recycle", attributes = NasAttributes.HIDDEN)
    put(privateShare, ".recycle/vecchia.jpg", jpeg(800_000), now - 5_000_000, "sistema")
    nas.putDir(privateShare, "@eaDir")
    put(privateShare, "@eaDir/SYNOFILE_THUMB_M.jpg", jpeg(20_000), now - 5_000_000, "sistema")

    // Area comune: musica con copertine (E-N14), poche foto, video grandi.
    repeat(profile.musicFolders) { i ->
      val dir = "Musica/Artista ${i / 10}/Album $i"
      put(commonShare, "$dir/01 Traccia.mp3", NasContent.virtual(8_000_000, nextSeed++), now - i * 100_000L, "musica")
      put(commonShare, "$dir/${COVER_NAMES[i % COVER_NAMES.size]}", jpeg(40_000), now - i * 100_000L, "copertine")
    }
    repeat(profile.commonPhotos) { i -> put(commonShare, "Famiglia/Foto ${i / 50}/IMG_$i.jpg", jpeg(2_500_000), now - i * 900_000L, "foto") }
    repeat(profile.commonVideos) { i ->
      put(commonShare, "Film/Film $i.mkv", NasContent.virtual(if (i % 10 == 0) 9_400_000_000L else 1_500_000_000L, nextSeed++, MKV), now - i * 86_400_000L, "video")
    }
  }

  // --- Scenari del §5.3 --------------------------------------------------------------------------------------------------

  /** Rinomina dal PC di una cartella intera (E-N1, F4): `fileId` conservati, nessun byte cambiato. */
  fun renameFolder(share: String, from: String, to: String) = nas.rename(share, from, to)

  /** Modifica sul posto dal PC (E-N2): stesso `fileId`, byte e data nuovi. */
  fun modifyInPlace(share: String, path: String) = nas.modify(share, path, jpeg(1_900_000))

  /** Cancellazione dal PC (E-N3). */
  fun deleteFromPc(share: String, path: String) = nas.delete(share, path)

  /**
   * "Ransomware" (D10, F9): [count] file della share rinominati con l'estensione `.locked` e byte nuovi. Restituisce i
   * percorsi nuovi.
   */
  fun ransomware(share: String, paths: List<String>, count: Int = 500): List<String> =
    paths.take(count).map { path ->
      val locked = "$path.locked"
      nas.delete(share, path)
      nas.putFile(share, locked, NasContent.virtual(1_800_000, nextSeed++))
      locked
    }

  /** La share sparisce (E-R10, E-I8). */
  fun removeShare(share: String) = nas.removeShare(share)

  // --- Contenuti ---------------------------------------------------------------------------------------------------------

  private fun put(share: String, path: String, content: NasContent, modifiedAt: Long, category: String) {
    val real = realContent(path)
    nas.putFile(share, path, real?.let { NasContent.of(it) } ?: content, modifiedAt)
    generated.getOrPut(category) { mutableListOf() } += path
  }

  private fun jpeg(size: Long): NasContent = NasContent.virtual(size + random.nextLong(0, 4096), nextSeed++, JPEG)

  private fun png(size: Long): NasContent = NasContent.virtual(size, nextSeed++, PNG)

  private fun mp4(size: Long): NasContent = NasContent.virtual(size, nextSeed++, MP4)

  private companion object {
    /** 1979-06-01: le scansioni di vecchie foto (E-M9). */
    const val OLD_DATE: Long = 296_870_400_000L

    val COVER_NAMES = listOf("cover.jpg", "folder.jpg", "front.jpg", "AlbumArtSmall.jpg", "AlbumArt_{ABC}_Large.jpg")

    val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE1.toByte())
    val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    val MP4 = byteArrayOf(0, 0, 0, 0x18, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6F, 0x6D)
    val MKV = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())
  }
}
