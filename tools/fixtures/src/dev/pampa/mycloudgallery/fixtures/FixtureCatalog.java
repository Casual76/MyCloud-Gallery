package dev.pampa.mycloudgallery.fixtures;

import dev.pampa.mycloudgallery.fixtures.Fixture.Expect;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Il catalogo dei file di prova di core-metadata (docs/fase3-progetto.md §5.3): lo usano i test e il generatore del
 * set per il telefono ({@code Pictures/MCG-prova/}). Le condizioni di riferimento sono quelle di docs/fase3-campo.md: il telefono
 * è in fuso {@code Europe/Rome}, i luoghi vengono da un dataset che contiene Firenze, Oslo, Tokyo e New York, e "adesso"
 * è il 13/10/2026 (solo per i casi che ripiegano sull'orologio, che sono marcati "solo test").
 */
public final class FixtureCatalog {

  private FixtureCatalog() {}

  public static final String DATA = "Date dall'EXIF (E-M8, E-M9, E-M14, E-P5)";
  public static final String ASSURDE = "Date assurde: si passa alla fonte successiva (E-M9)";
  public static final String NOMI = "Date e sorgenti dai nomi dei file";
  public static final String FORMATI = "Formati: HEIC, DNG, PNG, WebP, GIF, BMP, TIFF";
  public static final String ORIENTAMENTI = "Otto orientamenti (E-V3, E-M15)";
  public static final String TRATTI = "Tratti: panorama, sfera, Ultra HDR";
  public static final String MOTION = "Motion photo (E-M5, E-M16)";
  public static final String ROVINATI = "File rovinati, troncati, falsi (E-M10, E-M12)";

  /** "Adesso" nei test: il 13/10/2026 alle 12:00 UTC. */
  public static final long NOW_MS = ms("2026-10-13T12:00:00Z");

  public static final String CAMERA = "DCIM/Camera/";

  public static long ms(String isoInstant) {
    return Instant.parse(isoInstant).toEpochMilli();
  }

  // ---- scorciatoie ---------------------------------------------------------------------------------------------

  private static ExifWriter.Spec exif(String dateOriginal, String offset) {
    ExifWriter.Spec s = new ExifWriter.Spec();
    s.make = "samsung";
    s.model = "SM-S931B";
    s.dateOriginal = dateOriginal;
    s.offsetOriginal = offset;
    return s;
  }

  private static ExifWriter.Spec withGps(ExifWriter.Spec s, double lat, double lon) {
    s.latitude = lat;
    s.longitude = lon;
    return s;
  }

  private static Fixture jpeg(String section, String name, String folder, String description, ExifWriter.Spec exif, Expect expect) {
    return new Fixture(section, name, folder, "image/jpeg", description, () -> ImageFixtures.jpeg(64, 48, 0x4080C0, exif, null), expect);
  }

  private static Fixture jpegNoExif(String section, String name, String folder, String description, Expect expect) {
    return new Fixture(section, name, folder, "image/jpeg", description, () -> ImageFixtures.jpeg(64, 48, 0x4080C0), expect);
  }

  private static Fixture png(String section, String name, String folder, String description, Expect expect) {
    return new Fixture(section, name, folder, "image/png", description, () -> ImageFixtures.pngBytes(ImageFixtures.solid(64, 48, 0x40C080)), expect);
  }

  private static Expect camera(String local, int offset, String confidence, String source) {
    return Expect.read().date(local, offset, confidence, source).source("CAMERA");
  }

  // ---- il catalogo ---------------------------------------------------------------------------------------------

  public static List<Fixture> all() {
    List<Fixture> f = new ArrayList<>();
    dates(f);
    absurdDates(f);
    names(f);
    formats(f);
    orientations(f);
    traits(f);
    motion(f);
    broken(f);
    return f;
  }

  private static void dates(List<Fixture> f) {
    f.add(jpeg(DATA, "exif-01-con-fuso.jpg", CAMERA, "DateTimeOriginal e OffsetTimeOriginal +01:00",
        exif("2024:03:12 10:15:30", "+01:00"),
        camera("2024-03-12T10:15:30", 60, "EXACT", "EXIF_WITH_OFFSET")));
    f.add(jpeg(DATA, "exif-02-fuso-negativo.jpg", CAMERA, "Fuso -05:00: l'ora che si vede è quella di New York",
        exif("2024:07:04 22:30:00", "-05:00"),
        camera("2024-07-04T22:30:00", -300, "EXACT", "EXIF_WITH_OFFSET")));
    f.add(jpeg(DATA, "exif-03-fuso-mezz-ora.jpg", CAMERA, "Fuso +05:30 (India)",
        exif("2024:01:15 08:00:00", "+05:30"),
        camera("2024-01-15T08:00:00", 330, "EXACT", "EXIF_WITH_OFFSET")));
    ExifWriter.Spec sub = exif("2024:03:12 10:15:30", "+01:00");
    sub.subSecOriginal = "123";
    f.add(jpeg(DATA, "exif-04-sottosecondi.jpg", CAMERA, "SubSecTimeOriginal 123: i millisecondi ordinano le raffiche", sub,
        camera("2024-03-12T10:15:30.123", 60, "EXACT", "EXIF_WITH_OFFSET")));
    ExifWriter.Spec gpsTime = withGps(exif("2024:03:12 10:15:30", null), 59.9127, 10.7461);
    gpsTime.gpsDate = "2024:03:12";
    gpsTime.gpsTime = new int[] {9, 15, 28};
    f.add(jpeg(DATA, "exif-05-gps-ora-utc.jpg", CAMERA, "Senza fuso ma con data e ora GPS: fuso = ora locale - UTC, a 15 minuti (+01:00)", gpsTime,
        camera("2024-03-12T10:15:30", 60, "EXACT", "EXIF_WITH_OFFSET").place("Oslo")));
    ExifWriter.Spec india = withGps(exif("2024:03:12 15:45:00", null), 28.6139, 77.2090);
    india.gpsDate = "2024:03:12";
    india.gpsTime = new int[] {10, 15, 2};
    f.add(jpeg(DATA, "exif-06-gps-ora-utc-india.jpg", CAMERA, "Ora GPS 10:15:02 contro 15:45:00 locali: +05:30 arrotondato a 15 minuti (GPS a Nuova Delhi: il luogo non si verifica, il dataset di prova non ha l'India)", india,
        camera("2024-03-12T15:45:00", 330, "EXACT", "EXIF_WITH_OFFSET").place(null)));
    f.add(jpeg(DATA, "exif-07-gps-fuso-del-luogo.jpg", CAMERA, "Senza fuso, con GPS a Tokyo e senza ora GPS: il fuso è quello del luogo (+09:00)",
        withGps(exif("2024:07:04 18:00:00", null), 35.6895, 139.6917),
        camera("2024-07-04T18:00:00", 540, "OFFSET_ASSUMED", "EXIF_LOCAL").place("Tokyo")));
    f.add(jpeg(DATA, "exif-08-new-york-sera.jpg", CAMERA, "23:30 a New York (-04:00): per il giorno vale il 4 luglio, non il 5 di Roma (E-P5)",
        withGps(exif("2024:07:04 23:30:00", null), 40.7143, -74.0060),
        camera("2024-07-04T23:30:00", -240, "OFFSET_ASSUMED", "EXIF_LOCAL").place("New York")));
    f.add(jpeg(DATA, "exif-09-nessun-fuso-inverno.jpg", CAMERA, "Né fuso né GPS, in inverno: il fuso del telefono alla lettura (+01:00)",
        exif("2024:01:20 09:00:00", null),
        camera("2024-01-20T09:00:00", 60, "OFFSET_ASSUMED", "EXIF_LOCAL")));
    f.add(jpeg(DATA, "exif-10-nessun-fuso-estate.jpg", CAMERA, "Né fuso né GPS, in estate: ora legale (+02:00)",
        exif("2024:07:20 09:00:00", null),
        camera("2024-07-20T09:00:00", 120, "OFFSET_ASSUMED", "EXIF_LOCAL")));
    f.add(jpeg(DATA, "exif-11-salto-ora-legale.jpg", CAMERA, "02:30 del 31/3/2024 non esiste a Roma (salto dell'ora legale): si usa il fuso dopo il salto (+02:00)",
        exif("2024:03:31 02:30:00", null),
        camera("2024-03-31T02:30:00", 120, "OFFSET_ASSUMED", "EXIF_LOCAL")));
    ExifWriter.Spec digitized = new ExifWriter.Spec();
    digitized.model = "SM-S931B";
    digitized.dateDigitized = "2024:03:12 10:15:30";
    digitized.offsetDigitized = "+01:00";
    f.add(jpeg(DATA, "exif-12-solo-digitized.jpg", CAMERA, "Manca DateTimeOriginal: vale DateTimeDigitized con il suo OffsetTimeDigitized", digitized,
        camera("2024-03-12T10:15:30", 60, "EXACT", "EXIF_WITH_OFFSET")));
    ExifWriter.Spec nullIsland = exif("2024:03:12 10:15:30", "+01:00");
    nullIsland.latitude = 0.0;
    nullIsland.longitude = 0.0;
    f.add(jpeg(DATA, "exif-13-gps-a-zero.jpg", CAMERA, "GPS a (0, 0): molte fotocamere lo scrivono senza un fix, non è un luogo", nullIsland,
        camera("2024-03-12T10:15:30", 60, "EXACT", "EXIF_WITH_OFFSET")));
    ExifWriter.Spec big = exif("2024:03:12 10:15:30", "+01:00");
    big.bigEndian = true;
    f.add(jpeg(DATA, "exif-14-big-endian.jpg", CAMERA, "EXIF in big-endian (Motorola): stessi valori di exif-01", big,
        camera("2024-03-12T10:15:30", 60, "EXACT", "EXIF_WITH_OFFSET")));
    ExifWriter.Spec thumb = exif("2024:03:12 10:15:30", "+01:00");
    thumb.thumbnailJpeg = ImageFixtures.exifThumbnail(0xC08040);
    thumb.fNumber = 1.8;
    thumb.exposure = new int[] {1, 250};
    thumb.iso = 100;
    thumb.focalMm = 6.3;
    thumb.flash = 0;
    thumb.lens = "Samsung Galaxy S25 Wide";
    f.add(jpeg(DATA, "exif-15-con-miniatura.jpg", CAMERA, "EXIF completo con miniatura nell'IFD1: marca, modello, obiettivo, apertura, posa, ISO, focale", thumb,
        camera("2024-03-12T10:15:30", 60, "EXACT", "EXIF_WITH_OFFSET")));
    ExifWriter.Spec ifd0Only = new ExifWriter.Spec();
    ifd0Only.model = "SM-S931B";
    ifd0Only.dateTime = "2024:03:12 10:15:30";
    f.add(jpeg(DATA, "exif-16-solo-datetime-ifd0.jpg", CAMERA, "Solo il DateTime dell'IFD0 (ultima modifica): non è la data dello scatto e non si usa", ifd0Only,
        camera("2024-03-12T10:00:00", 60, "ESTIMATED", "FILE_MODIFIED"))
        .modifiedAt(ms("2024-03-12T09:00:00Z")));
  }

  private static void absurdDates(List<Fixture> f) {
    String[][] cases = {
        {"IMG_20240312_101530.jpg", "0000:00:00 00:00:00", "una data a zero (0000:00:00)"},
        {"IMG_20240312_101531.jpg", "    :  :     :  :  ", "una data vuota (solo spazi)"},
        {"IMG_20240312_101532.jpg", "1970:01:01 00:00:00", "la data dell'epoca (1970)"},
        {"IMG_20240312_101533.jpg", "1899:12:31 23:59:59", "una data prima del 1900"},
        {"IMG_20240312_101534.jpg", "2099:01:01 12:00:00", "una data nel futuro (oltre ora + 48 h)"},
        {"IMG_20240312_101535.jpg", "2024:13:40 10:00:00", "un mese e un giorno impossibili"},
    };
    for (String[] c : cases) {
      String second = c[0].substring(c[0].indexOf('_', 4) + 5, c[0].indexOf('.'));
      f.add(jpeg(ASSURDE, c[0], CAMERA, "EXIF con " + c[2] + ": si scarta e vale il nome del file (AOSP)", exif(c[1], null),
          camera("2024-03-12T10:15:" + second, 60, "OFFSET_ASSUMED", "FILE_NAME")));
    }
  }

  private static void names(List<Fixture> f) {
    String waImages = "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/";
    f.add(jpegNoExif(NOMI, "IMG-20240312-WA0001.jpg", waImages, "WhatsApp: il nome porta il giorno della ricezione, non l'ora",
        Expect.read().date("2024-03-12T12:00:00", 60, "ESTIMATED", "FILE_NAME").source("WHATSAPP")));
    f.add(jpegNoExif(NOMI, "IMG-20240312-WA0002.jpg", waImages + "Sent/", "WhatsApp, cartella Sent",
        Expect.read().date("2024-03-12T12:00:00", 60, "ESTIMATED", "FILE_NAME").source("WHATSAPP")));
    f.add(new Fixture(NOMI, "VID-20240312-WA0003.mp4", "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video/", "video/mp4",
        "Video WhatsApp (MP4 sintetico senza flussi: il sistema non lo apre, valgono nome e MediaStore)",
        () -> IsoFixtures.mp4(new IsoFixtures.Mp4Spec()),
        Expect.read().kind("VIDEO").size(null, null).place("-").date("2024-03-12T12:00:00", 60, "ESTIMATED", "FILE_NAME").source("WHATSAPP")));
    f.add(png(NOMI, "Screenshot_20240312_101530_Chrome.png", "Pictures/Screenshots/", "Screenshot Samsung: aaaammgg_hhmmss_App",
        Expect.read().date("2024-03-12T10:15:30", 60, "OFFSET_ASSUMED", "FILE_NAME").source("SCREENSHOT")));
    f.add(png(NOMI, "Screenshot_20240312-101531.png", "DCIM/Screenshots/", "Screenshot AOSP: aaaammgg-hhmmss",
        Expect.read().date("2024-03-12T10:15:31", 60, "OFFSET_ASSUMED", "FILE_NAME").source("SCREENSHOT")));
    f.add(new Fixture(NOMI, "Screen_Recording_20240312-101532_Chrome.mp4", "DCIM/Screen recordings/", "video/mp4",
        "Registrazione dello schermo: tratto SCREEN_RECORDING, sorgente Screenshot",
        () -> IsoFixtures.mp4(new IsoFixtures.Mp4Spec()),
        Expect.read().kind("VIDEO").size(null, null).date("2024-03-12T10:15:32", 60, "OFFSET_ASSUMED", "FILE_NAME")
            .source("SCREENSHOT").traits("SCREEN_RECORDING")));
    f.add(jpegNoExif(NOMI, "20240312_101533.jpg", CAMERA, "Fotocamera Samsung: aaaammgg_hhmmss",
        Expect.read().date("2024-03-12T10:15:33", 60, "OFFSET_ASSUMED", "FILE_NAME").source("CAMERA")));
    f.add(new Fixture(NOMI, "VID_20240312_101534.mp4", CAMERA, "video/mp4", "Video AOSP: VID_aaaammgg_hhmmss",
        () -> IsoFixtures.mp4(new IsoFixtures.Mp4Spec()),
        Expect.read().kind("VIDEO").size(null, null).date("2024-03-12T10:15:34", 60, "OFFSET_ASSUMED", "FILE_NAME").source("CAMERA")));
    f.add(jpegNoExif(NOMI, "PXL_20240312_101535123.jpg", CAMERA, "Pixel: il nome è in UTC (10:15:35.123Z = 11:15:35.123 a Roma)",
        Expect.read().date("2024-03-12T11:15:35.123", 60, "OFFSET_ASSUMED", "FILE_NAME").source("CAMERA")));
    f.add(jpegNoExif(NOMI, "signal-2024-03-12-101536.jpg", "Pictures/Signal/", "Signal: signal-aaaa-mm-gg-hhmmss",
        Expect.read().date("2024-03-12T10:15:36", 60, "OFFSET_ASSUMED", "FILE_NAME").source("OTHER")));
    f.add(jpegNoExif(NOMI, "photo_2024-03-12_10-15-37.jpg", "Pictures/Telegram/", "Telegram: photo_aaaa-mm-gg_hh-mm-ss",
        Expect.read().date("2024-03-12T10:15:37", 60, "OFFSET_ASSUMED", "FILE_NAME").source("OTHER")));
    f.add(jpegNoExif(NOMI, "foto-condivisa.jpg", "Download/Quick Share/", "Quick Share, senza data nel nome: solo la data del file (stimata)",
        Expect.read().date("2024-03-12T10:15:30", 60, "ESTIMATED", "FILE_MODIFIED").source("QUICK_SHARE"))
        .modifiedAt(ms("2024-03-12T09:15:30Z")));
    f.add(jpegNoExif(NOMI, "IMG_20241313_101530.jpg", CAMERA, "Nome con una data impossibile: si passa al DATE_TAKEN del MediaStore",
        Expect.read().date("2024-03-12T10:15:30", 60, "OFFSET_ASSUMED", "MEDIASTORE").source("CAMERA"))
        .takenAt(ms("2024-03-12T09:15:30Z")));
    f.add(jpegNoExif(NOMI, "IMG_19700101_000000.jpg", CAMERA, "Nome all'epoca (1970) e nessun DATE_TAKEN: vale la data del file (stimata)",
        Expect.read().date("2024-02-02T11:00:00", 60, "ESTIMATED", "FILE_MODIFIED").source("CAMERA"))
        .modifiedAt(ms("2024-02-02T10:00:00Z")));
    f.add(jpegNoExif(NOMI, "senza-data.jpg", "Pictures/", "Nessuna data, e la data del file è del 1979 (assurda): ripiego finale 'adesso', stimata",
        Expect.read().date("2026-10-13T14:00:00", 120, "ESTIMATED", "FILE_MODIFIED").source("OTHER"))
        .modifiedAt(ms("1979-06-01T00:00:00Z")));
  }

  private static void formats(List<Fixture> f) {
    ExifWriter.Spec base = exif("2024:03:12 10:15:30", "+01:00");
    f.add(new Fixture(FORMATI, "formato-png-con-exif.png", "Pictures/", "image/png", "PNG con chunk eXIf",
        () -> ImageFixtures.png(ImageFixtures.solid(64, 48, 0x40C080), base, null),
        Expect.read().date("2024-03-12T10:15:30", 60, "EXACT", "EXIF_WITH_OFFSET").source("OTHER")));
    f.add(new Fixture(FORMATI, "formato-webp-con-exif.webp", "Pictures/", "image/webp", "WebP lossless 64x48 con chunk EXIF",
        () -> ImageFixtures.webp(64, 48, 0xFF40C080, base, null),
        Expect.read().date("2024-03-12T10:15:30", 60, "EXACT", "EXIF_WITH_OFFSET").source("OTHER")));
    f.add(new Fixture(FORMATI, "IMG_20240312_101540.webp", CAMERA, "image/webp", "WebP semplice senza metadati: vale il nome",
        () -> ImageFixtures.webp(64, 48, 0xFF40C080, null, null),
        Expect.read().date("2024-03-12T10:15:40", 60, "OFFSET_ASSUMED", "FILE_NAME").source("CAMERA")));
    f.add(new Fixture(FORMATI, "IMG_20240312_101541_animato.webp", CAMERA, "image/webp", "WebP animato (VP8X + ANIM + 2 fotogrammi)",
        () -> ImageFixtures.webpAnimated(64, 48, new int[] {0xFFE04040, 0xFF4040E0}, 200),
        Expect.read().kind("ANIMATED_IMAGE").date("2024-03-12T10:15:41", 60, "OFFSET_ASSUMED", "FILE_NAME").source("CAMERA")));
    f.add(new Fixture(FORMATI, "IMG_20240312_101542.gif", "Pictures/", "image/gif", "GIF: sempre immagine animata",
        () -> ImageFixtures.gif(64, 48, 0xC04080),
        Expect.read().kind("ANIMATED_IMAGE").date("2024-03-12T10:15:42", 60, "OFFSET_ASSUMED", "FILE_NAME").source("OTHER")));
    f.add(new Fixture(FORMATI, "IMG_20240312_101543.bmp", "Pictures/", "image/bmp", "BMP",
        () -> ImageFixtures.bmp(64, 48, 0x8040C0),
        Expect.read().date("2024-03-12T10:15:43", 60, "OFFSET_ASSUMED", "FILE_NAME").source("OTHER")));

    IsoFixtures.HeicSpec heic = new IsoFixtures.HeicSpec();
    heic.exif = base;
    f.add(new Fixture(FORMATI, "formato-heic.heic", CAMERA, "image/heic", "HEIC con voce Exif (struttura vera, immagine finta)",
        () -> IsoFixtures.heic(heic),
        camera("2024-03-12T10:15:30", 60, "EXACT", "EXIF_WITH_OFFSET")));
    IsoFixtures.HeicSpec heicRotated = new IsoFixtures.HeicSpec();
    heicRotated.exif = base;
    heicRotated.irot = 1;
    heicRotated.width = 64;
    heicRotated.height = 48;
    f.add(new Fixture(FORMATI, "formato-heic-ruotato.heic", CAMERA, "image/heic", "HEIC con irot = 1 (90 gradi antiorari): si vede ruotato di 270 gradi in senso orario",
        () -> IsoFixtures.heic(heicRotated),
        camera("2024-03-12T10:15:30", 60, "EXACT", "EXIF_WITH_OFFSET").orientation(8).aspect(750)));
    IsoFixtures.HeicSpec heicXmp = new IsoFixtures.HeicSpec();
    heicXmp.exif = base;
    heicXmp.xmp = XmpPackets.ultraHdr();
    f.add(new Fixture(FORMATI, "formato-heic-xmp.heic", CAMERA, "image/heic", "HEIC con voce XMP (mime application/rdf+xml) che dichiara il gain map",
        () -> IsoFixtures.heic(heicXmp),
        camera("2024-03-12T10:15:30", 60, "EXACT", "EXIF_WITH_OFFSET").traits("ULTRA_HDR")));

    TiffFixtures.DngSpec dng = new TiffFixtures.DngSpec();
    f.add(new Fixture(FORMATI, "formato-dng.dng", CAMERA, "image/x-adobe-dng", "DNG: anteprima 256x192 nell'IFD0, grezzo 4000x3000 nel SubIFD, ritaglio 3984x2988",
        () -> TiffFixtures.dng(dng),
        camera("2024-03-12T10:15:30", 60, "EXACT", "EXIF_WITH_OFFSET").kind("RAW_PHOTO").size(3984, 2988)));
    TiffFixtures.DngSpec tiff = new TiffFixtures.DngSpec();
    tiff.isDng = false;
    tiff.cropSize = null;
    f.add(new Fixture(FORMATI, "formato-tiff.tif", "Pictures/", "image/tiff", "TIFF semplice (non DNG): è una foto",
        () -> TiffFixtures.dng(tiff),
        Expect.read().date("2024-03-12T10:15:30", 60, "EXACT", "EXIF_WITH_OFFSET").size(4000, 3000).source("OTHER")));
  }

  private static void orientations(List<Fixture> f) {
    String[] words = {"dritta", "specchiata", "180", "specchiata verticale", "trasposta", "90 orario", "trasversa", "270 orario"};
    for (int o = 1; o <= 8; o++) {
      final int orientation = o;
      ExifWriter.Spec s = exif("2024:03:12 10:15:30", "+01:00");
      s.orientation = orientation;
      Expect e = camera("2024-03-12T10:15:30", 60, "EXACT", "EXIF_WITH_OFFSET").orientation(orientation).aspect(orientation >= 5 ? 750 : 1333);
      f.add(new Fixture(ORIENTAMENTI, "orientamento-" + o + ".jpg", CAMERA, "image/jpeg",
          "Orientamento EXIF " + o + " (" + words[o - 1] + "): i pixel sono salvati 64x48 e la foto si deve vedere sempre dritta (la F con l'asta a sinistra)",
          () -> ImageFixtures.jpeg(ImageFixtures.storedFor(ImageFixtures.uprightF(orientation >= 5 ? 48 : 64, orientation >= 5 ? 64 : 48), orientation), s, null),
          e));
    }
  }

  private static void traits(List<Fixture> f) {
    ExifWriter.Spec base = exif("2024:03:12 10:15:30", "+01:00");
    f.add(new Fixture(TRATTI, "tratto-panorama-rapporto.jpg", CAMERA, "image/jpeg", "300x100: rapporto 3:1, oltre 2,5:1",
        () -> ImageFixtures.jpeg(300, 100, 0x6090B0, base, null),
        camera("2024-03-12T10:15:30", 60, "EXACT", "EXIF_WITH_OFFSET").size(300, 100).traits("PANORAMA").aspect(3000)));
    f.add(new Fixture(TRATTI, "tratto-panorama-gpano.jpg", CAMERA, "image/jpeg", "64x48 ma con XMP GPano (proiezione cilindrica)",
        () -> ImageFixtures.jpeg(64, 48, 0x6090B0, base, XmpPackets.panorama()),
        camera("2024-03-12T10:15:30", 60, "EXACT", "EXIF_WITH_OFFSET").traits("PANORAMA")));
    f.add(new Fixture(TRATTI, "tratto-sfera.jpg", CAMERA, "image/jpeg", "128x64 equirettangolare a copertura completa: sfera, non panorama",
        () -> ImageFixtures.jpeg(128, 64, 0x6090B0, base, XmpPackets.sphere(128, 64)),
        camera("2024-03-12T10:15:30", 60, "EXACT", "EXIF_WITH_OFFSET").size(128, 64).traits("SPHERE")));
    f.add(new Fixture(TRATTI, "tratto-equirettangolare-parziale.jpg", CAMERA, "image/jpeg", "Equirettangolare ma con mezza altezza: panorama, non sfera",
        () -> ImageFixtures.jpeg(128, 32, 0x6090B0, base, XmpPackets.partialEquirectangular(128, 64, 32)),
        camera("2024-03-12T10:15:30", 60, "EXACT", "EXIF_WITH_OFFSET").size(128, 32).traits("PANORAMA")));
    f.add(new Fixture(TRATTI, "tratto-ultra-hdr.jpg", CAMERA, "image/jpeg", "XMP con hdrgm:Version e voce GainMap: Ultra HDR",
        () -> ImageFixtures.jpeg(64, 48, 0x6090B0, base, XmpPackets.ultraHdr()),
        camera("2024-03-12T10:15:30", 60, "EXACT", "EXIF_WITH_OFFSET").traits("ULTRA_HDR")));
    f.add(new Fixture(TRATTI, "tratto-screenshot-lungo.png", "Pictures/Screenshots/", "image/png", "100x400: molto alta non vuol dire panorama (solo i larghi)",
        () -> ImageFixtures.pngBytes(ImageFixtures.solid(100, 400, 0x90B060)),
        Expect.read().size(100, 400).source("SCREENSHOT").date("2024-03-12T10:15:38", 60, "ESTIMATED", "FILE_MODIFIED").aspect(250))
        .modifiedAt(ms("2024-03-12T09:15:38Z")));
    ExifWriter.Spec rotated = exif("2024:03:12 10:15:30", "+01:00");
    rotated.orientation = 6;
    f.add(new Fixture(TRATTI, "tratto-panorama-ruotato.jpg", CAMERA, "image/jpeg", "100x300 salvato in piedi con orientamento 6: si vede 300x100, quindi panorama",
        () -> ImageFixtures.jpeg(100, 300, 0x6090B0, rotated, null),
        camera("2024-03-12T10:15:30", 60, "EXACT", "EXIF_WITH_OFFSET").size(100, 300).orientation(6).traits("PANORAMA").aspect(3000)));
  }

  /** La parte video usata dai file della sezione: il suo peso è quello che il test controlla "dalla fine". */
  public static final int MOTION_VIDEO_BYTES = 3000;

  private static void motion(List<Fixture> f) {
    ExifWriter.Spec base = exif("2024:03:12 10:15:30", "+01:00");
    Img still = ImageFixtures.solid(64, 48, 0xB07040);
    long videoLength = MotionFixtures.video(MOTION_VIDEO_BYTES).length;

    f.add(new Fixture(MOTION, "motion-google-v1.jpg", CAMERA, "image/jpeg", "Google v1 (XMP Container:Directory con Item:Length), come un Pixel",
        () -> MotionFixtures.googleV1(still, base, MotionFixtures.video(MOTION_VIDEO_BYTES)),
        camera("2024-03-12T10:15:30", 60, "EXACT", "EXIF_WITH_OFFSET").kind("MOTION_PHOTO").motion("GOOGLE_V1", videoLength)));
    f.add(new Fixture(MOTION, "motion-google-v1-specifica.jpg", CAMERA, "image/jpeg", "Google v1 scritto come nella specifica (prefisso Camera:, voci a elementi)",
        () -> MotionFixtures.googleV1Elements(still, base, MotionFixtures.video(MOTION_VIDEO_BYTES)),
        camera("2024-03-12T10:15:30", 60, "EXACT", "EXIF_WITH_OFFSET").kind("MOTION_PHOTO").motion("GOOGLE_V1", videoLength)));
    f.add(new Fixture(MOTION, "motion-microvideo.jpg", CAMERA, "image/jpeg", "MicroVideo (MicroVideoOffset dalla fine del file)",
        () -> MotionFixtures.microVideo(still, base, MotionFixtures.video(MOTION_VIDEO_BYTES)),
        camera("2024-03-12T10:15:30", 60, "EXACT", "EXIF_WITH_OFFSET").kind("MOTION_PHOTO").motion("MICRO_VIDEO", videoLength)));
    f.add(new Fixture(MOTION, "motion-samsung-sef.jpg", CAMERA, "image/jpeg", "Trailer SEF di Samsung: blocco 0x0A30 'MotionPhoto_Data' con il video",
        () -> MotionFixtures.samsungSef(still, base, MotionFixtures.video(MOTION_VIDEO_BYTES)),
        camera("2024-03-12T10:15:30", 60, "EXACT", "EXIF_WITH_OFFSET").kind("MOTION_PHOTO").motion("SAMSUNG_SEF", videoLength)));
    f.add(new Fixture(MOTION, "motion-samsung-sef-puntatore.jpg", CAMERA, "image/jpeg", "Trailer SEF con il blocco da 12 byte 'mpv2' (offset e dimensione assoluti)",
        () -> MotionFixtures.samsungSefPointer(still, base, MotionFixtures.video(MOTION_VIDEO_BYTES)),
        camera("2024-03-12T10:15:30", 60, "EXACT", "EXIF_WITH_OFFSET").kind("MOTION_PHOTO").motion("SAMSUNG_SEF", videoLength)));
    f.add(new Fixture(MOTION, "motion-dichiarata-senza-video.jpg", CAMERA, "image/jpeg", "XMP dichiara MotionPhoto=1 ma non dice dove sta il video (E-M16)",
        () -> ImageFixtures.jpeg(still, base, XmpPackets.motionFlagOnly()),
        camera("2024-03-12T10:15:30", 60, "EXACT", "EXIF_WITH_OFFSET").kind("MOTION_PHOTO").motion("declared", null)));
    f.add(new Fixture(MOTION, "motion-video-tolto.jpg", CAMERA, "image/jpeg", "La directory dice di un video da 90 KB ma il file è solo la foto: video tolto (E-M16)",
        () -> ImageFixtures.jpeg(still, base, XmpPackets.googleMotionAttributes(90_000, 1_500_000)),
        camera("2024-03-12T10:15:30", 60, "EXACT", "EXIF_WITH_OFFSET").kind("MOTION_PHOTO").motion("declared", null)));
    IsoFixtures.HeicSpec heic = new IsoFixtures.HeicSpec();
    heic.exif = base;
    byte[] mp4 = MotionFixtures.video(MOTION_VIDEO_BYTES);
    heic.xmp = XmpPackets.googleMotionElements(mp4.length);
    heic.trailing = IsoFixtures.box("mpvd", mp4);
    f.add(new Fixture(MOTION, "motion-heic-google.heic", CAMERA, "image/heic", "HEIC con XMP Google v1 e riquadro mpvd in fondo",
        () -> IsoFixtures.heic(heic),
        camera("2024-03-12T10:15:30", 60, "EXACT", "EXIF_WITH_OFFSET").kind("MOTION_PHOTO").motion("GOOGLE_V1", videoLength)));
  }

  private static void broken(List<Fixture> f) {
    ExifWriter.Spec base = exif("2024:03:12 10:15:30", "+01:00");
    f.add(new Fixture(ROVINATI, "vuoto.jpg", CAMERA, "image/jpeg", "Zero byte: ignorato e contato, mai in Libreria", () -> new byte[0],
        Expect.result("notmedia:ZERO_BYTES")));
    f.add(new Fixture(ROVINATI, "testo.jpg", CAMERA, "image/jpeg", "Un .jpg che dentro è una pagina di errore HTML",
        () -> "<html><body>404 - non trovato</body></html>".getBytes(StandardCharsets.UTF_8), Expect.result("notmedia:NOT_A_MEDIA_FILE")));
    f.add(new Fixture(ROVINATI, "casuali.png", "Pictures/", "image/png", "Duemila byte casuali con estensione .png", () -> {
      byte[] b = new byte[2000];
      new Random(7).nextBytes(b);
      b[0] = 0x12; // mai una firma valida per caso
      return b;
    }, Expect.result("notmedia:NOT_A_MEDIA_FILE")));
    f.add(new Fixture(ROVINATI, "video-falso.mp4", CAMERA, "video/mp4", "Testo con estensione .mp4",
        () -> "non sono un video".getBytes(StandardCharsets.UTF_8), Expect.result("notmedia:NOT_A_MEDIA_FILE")));
    f.add(new Fixture(ROVINATI, "formato-sconosciuto.jxl", "Pictures/", "image/jxl", "JPEG XL: un'immagine in un formato che non sappiamo leggere",
        () -> new Bytes().u8(0xFF).u8(0x0A).zeros(60).toBytes(), Expect.result("unsupported:UNSUPPORTED_FORMAT")));
    f.add(new Fixture(ROVINATI, "troncato.jpg", CAMERA, "image/jpeg", "JPEG tagliato a metà dei dati dell'immagine",
        () -> {
          byte[] whole = ImageFixtures.jpeg(ImageFixtures.noise(160, 120, 1), base, null);
          return Bytes.prefix(whole, whole.length * 3 / 4);
        }, Expect.result("corrupt:TRUNCATED")));
    f.add(new Fixture(ROVINATI, "troncato-nell-exif.jpg", CAMERA, "image/jpeg", "JPEG tagliato dentro il segmento EXIF",
        () -> Bytes.prefix(ImageFixtures.jpeg(ImageFixtures.noise(160, 120, 1), base, null), 60), Expect.result("corrupt:TRUNCATED")));
    f.add(new Fixture(ROVINATI, "solo-intestazione.jpg", CAMERA, "image/jpeg", "Solo SOI e JFIF: il file finisce subito",
        () -> Bytes.prefix(ImageFixtures.jpeg(64, 48, 0x4080C0), 20), Expect.result("corrupt:TRUNCATED")));
    f.add(new Fixture(ROVINATI, "senza-sof.jpg", CAMERA, "image/jpeg", "Segmenti ben formati ma nessun SOF: non c'è un'immagine da decodificare",
        () -> new Bytes().u8(0xFF).u8(0xD8).u8(0xFF).u8(0xE0).u16be(16).ascii("JFIF").u8(0).u8(1).u8(1).u8(0).u16be(1).u16be(1).u8(0).u8(0)
            .u8(0xFF).u8(0xDA).u16be(8).zeros(6).zeros(20).u8(0xFF).u8(0xD9).toBytes(),
        Expect.result("corrupt:DECODE_FAILED")));
    f.add(new Fixture(ROVINATI, "troncato.png", "Pictures/", "image/png", "PNG tagliato a metà",
        () -> {
          byte[] whole = ImageFixtures.pngBytes(ImageFixtures.noise(60, 60, 2));
          return Bytes.prefix(whole, whole.length / 2);
        }, Expect.result("corrupt:TRUNCATED")));
    f.add(new Fixture(ROVINATI, "png-intestazione-rotta.png", "Pictures/", "image/png", "Firma PNG seguita da un primo chunk che non è IHDR",
        () -> new Bytes().raw(new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A}).u32be(13).ascii("JUNK").zeros(40).toBytes(),
        Expect.result("corrupt:DECODE_FAILED")));
    f.add(new Fixture(ROVINATI, "troncato.webp", "Pictures/", "image/webp", "WebP con EXIF tagliato: la dimensione RIFF supera il file",
        () -> Bytes.prefix(ImageFixtures.webp(64, 48, 0xFF40C080, base, null), 70), Expect.result("corrupt:TRUNCATED")));
    IsoFixtures.HeicSpec heic = new IsoFixtures.HeicSpec();
    heic.exif = base;
    f.add(new Fixture(ROVINATI, "troncato.heic", CAMERA, "image/heic", "HEIC tagliato dentro il riquadro mdat",
        () -> {
          byte[] whole = IsoFixtures.heic(heic);
          return Bytes.prefix(whole, whole.length - 20);
        }, Expect.result("corrupt:TRUNCATED")));
    f.add(new Fixture(ROVINATI, "troncato.dng", CAMERA, "image/x-adobe-dng", "DNG tagliato nei dati grezzi",
        () -> {
          byte[] whole = TiffFixtures.dng(new TiffFixtures.DngSpec());
          return Bytes.prefix(whole, whole.length - 1000);
        }, Expect.result("corrupt:TRUNCATED")));
    f.add(new Fixture(ROVINATI, "registrazione-interrotta.mp4", CAMERA, "video/mp4", "Video senza il riquadro moov (batteria finita durante la ripresa)",
        () -> {
          IsoFixtures.Mp4Spec s = new IsoFixtures.Mp4Spec();
          s.includeMoov = false;
          return IsoFixtures.mp4(s);
        }, Expect.result("corrupt:TRUNCATED")));
    f.add(new Fixture(ROVINATI, "troncato.mp4", CAMERA, "video/mp4", "Video con il moov tagliato a metà",
        () -> {
          byte[] whole = IsoFixtures.mp4(new IsoFixtures.Mp4Spec());
          return Bytes.prefix(whole, whole.length - 40);
        }, Expect.result("corrupt:TRUNCATED")));
  }

  // ---- gruppi --------------------------------------------------------------------------------------------------

  /** Un insieme di file che la logica dei gruppi deve (o non deve) riunire. */
  public static final class GroupSet {
    public final String title;
    public final List<Fixture> members;
    /** I gruppi attesi: ogni stringa è "BURST" o "RAW_JPEG_PAIR", ":", gli indici dei membri e, dopo "@", la copertina. */
    public final List<String> expectedGroups;

    GroupSet(String title, List<Fixture> members, List<String> expectedGroups) {
      this.title = title;
      this.members = members;
      this.expectedGroups = expectedGroups;
    }
  }

  /** "2024:03:12 11:00:00" e sottosecondi "100" -> "2024-03-12T11:00:00.1" (come stampa ISO_LOCAL_DATE_TIME). */
  private static String isoLocal(String exifTime, String subSec) {
    String iso = exifTime.replace(':', '-').substring(0, 10) + "T" + exifTime.substring(11);
    int ms = (int) Math.round(Double.parseDouble("0." + subSec) * 1000);
    if (ms == 0) return iso;
    String fraction = String.format("%03d", ms).replaceAll("0+$", "");
    return iso + "." + fraction;
  }

  private static Fixture burstShot(String name, String subSec, String model, String time) {
    ExifWriter.Spec s = exif(time, "+01:00");
    s.model = model;
    s.subSecOriginal = subSec;
    return new Fixture("Gruppi", name, CAMERA, "image/jpeg", "Scatto di una raffica", () -> ImageFixtures.jpeg(64, 48, 0x70A0D0, s, null),
        camera(isoLocal(time, subSec), 60, "EXACT", "EXIF_WITH_OFFSET"));
  }

  private static Fixture rawShot(String name, String time, boolean raw) {
    ExifWriter.Spec s = exif(time, "+01:00");
    if (raw) {
      TiffFixtures.DngSpec spec = new TiffFixtures.DngSpec();
      spec.dateOriginal = time;
      return new Fixture("Gruppi", name, CAMERA, "image/x-adobe-dng", "RAW di una coppia RAW+JPEG", () -> TiffFixtures.dng(spec),
          camera(time.replace(':', '-').substring(0, 10) + "T" + time.substring(11), 60, "EXACT", "EXIF_WITH_OFFSET").kind("RAW_PHOTO").size(3984, 2988));
    }
    return new Fixture("Gruppi", name, CAMERA, "image/jpeg", "JPEG di una coppia RAW+JPEG", () -> ImageFixtures.jpeg(64, 48, 0x70A0D0, s, null),
        camera(time.replace(':', '-').substring(0, 10) + "T" + time.substring(11), 60, "EXACT", "EXIF_WITH_OFFSET"));
  }

  public static List<GroupSet> groupSets() {
    List<GroupSet> sets = new ArrayList<>();
    sets.add(new GroupSet("Raffica di tre scatti Samsung (_001, _002, _003) a meno di 1 s, stesso modello",
        List.of(burstShot("20240312_110000_001.jpg", "100", "SM-S931B", "2024:03:12 11:00:00"),
            burstShot("20240312_110000_002.jpg", "400", "SM-S931B", "2024:03:12 11:00:00"),
            burstShot("20240312_110000_003.jpg", "700", "SM-S931B", "2024:03:12 11:00:00")),
        List.of("BURST:0,1,2@0")));
    sets.add(new GroupSet("Raffica con copertina _COVER (stile Android)",
        List.of(burstShot("IMG_20240312_111000_BURST001.jpg", "100", "SM-S931B", "2024:03:12 11:10:00"),
            burstShot("IMG_20240312_111000_BURST002_COVER.jpg", "300", "SM-S931B", "2024:03:12 11:10:00"),
            burstShot("IMG_20240312_111000_BURST003.jpg", "600", "SM-S931B", "2024:03:12 11:10:00")),
        List.of("BURST:0,1,2@1")));
    sets.add(new GroupSet("Stessa raffica ma un modello diverso: nel dubbio non si raggruppa",
        List.of(burstShot("20240312_112000_001.jpg", "100", "SM-S931B", "2024:03:12 11:20:00"),
            burstShot("20240312_112000_002.jpg", "400", "Pixel 9", "2024:03:12 11:20:00")),
        List.of()));
    sets.add(new GroupSet("Scatti con numeri di raffica ma a più di 1 s l'uno dall'altro: nessun gruppo",
        List.of(burstShot("20240312_113000_001.jpg", "0", "SM-S931B", "2024:03:12 11:30:00"),
            burstShot("20240312_113000_002.jpg", "0", "SM-S931B", "2024:03:12 11:30:05")),
        List.of()));
    sets.add(new GroupSet("Una raffica spezzata da un buco di tempo: i due scatti vicini formano un gruppo, l'isolato resta solo",
        List.of(burstShot("20240312_114000_001.jpg", "100", "SM-S931B", "2024:03:12 11:40:00"),
            burstShot("20240312_114000_002.jpg", "600", "SM-S931B", "2024:03:12 11:40:00"),
            burstShot("20240312_114000_003.jpg", "100", "SM-S931B", "2024:03:12 11:40:05")),
        List.of("BURST:0,1@0")));
    sets.add(new GroupSet("Un solo scatto con suffisso: non è una raffica",
        List.of(burstShot("20240312_115000_001.jpg", "100", "SM-S931B", "2024:03:12 11:50:00")),
        List.of()));
    sets.add(new GroupSet("Coppia RAW+JPEG (stessa cartella, stesso nome base, 0 s): copertina il JPEG",
        List.of(rawShot("20240312_120000.dng", "2024:03:12 12:00:00", true), rawShot("20240312_120000.jpg", "2024:03:12 12:00:00", false)),
        List.of("RAW_JPEG_PAIR:1,0@1")));
    sets.add(new GroupSet("RAW e JPEG con lo stesso nome ma 5 s di distanza: nel dubbio non si raggruppa",
        List.of(rawShot("20240312_121000.dng", "2024:03:12 12:10:00", true), rawShot("20240312_121000.jpg", "2024:03:12 12:10:05", false)),
        List.of()));
    sets.add(new GroupSet("Un DNG e due immagini con lo stesso nome base: ambiguo, nessuna coppia",
        List.of(rawShot("20240312_122000.dng", "2024:03:12 12:20:00", true), rawShot("20240312_122000.jpg", "2024:03:12 12:20:00", false),
            rawShot("20240312_122000.heic", "2024:03:12 12:20:00", false)),
        List.of()));
    return sets;
  }
}
