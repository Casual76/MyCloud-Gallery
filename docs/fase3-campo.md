# Fase 3 sul campo: il set di prova dei metadati (P3)

> Pacchetto **P3** (`core-metadata`, `docs/fase3-progetto.md` §2.2, §5.3 e §5.5). Questo documento è la fonte dei **valori attesi**
> per la prova **F2** ("Set `MCG-prova`, contro i valori di `docs/fase3-campo.md`"): per ogni file di prova dice che data, ora, fuso,
> "stimata", luogo, tipo e sorgente deve mostrare l'app.
>
> Le tabelle qui sotto sono **generate** dal catalogo di `tools/fixtures` e un test (`FieldDocConsistencyTest`) fallisce se il
> documento e il catalogo divergono. I valori attesi sono scritti a mano dalla specifica (non ricavati dal programma) e gli stessi
> file, passati dall'estrattore vero, li confermano nei test JVM (`FixtureCatalogTest`).

## 1. A cosa serve

| Livello | Cosa prova | Dove |
|---|---|---|
| Test JVM (senza dispositivo) | Il lettore dei metadati su file scritti dai test (stesso catalogo): date, nomi, sorgenti, tipi, tratti, gruppi, motion photo, file rovinati | `core-metadata/src/test` |
| Questo set sul telefono (F2) | Che il **MediaStore vero** e **Samsung One UI** non cambino i risultati: `DATE_TAKEN`, `RELATIVE_PATH`, accesso ai byte, fuso del telefono | `Pictures/MCG-prova/` |
| Campioni veri (§5.5 A.2) | Quello che un file sintetico non può dire: motion photo Samsung, HEIC e DNG veri, Ultra HDR, panorami, raffiche dell'S25 | scatti dell'utente |

## 2. Come si genera

```powershell
powershell -ExecutionPolicy Bypass -File tools\fixtures\generate.ps1            # tutto, compreso il JPEG da 200 MP
powershell -ExecutionPolicy Bypass -File tools\fixtures\generate.ps1 -SkipBig   # senza i circa 70 MB
powershell -ExecutionPolicy Bypass -File tools\fixtures\generate.ps1 -Out D:\prove
powershell -ExecutionPolicy Bypass -File tools\fixtures\generate.ps1 -Doc tabelle.md   # solo le tabelle di questo documento
```

- Servono solo il JDK 17 (`javac`, `java`) e PowerShell: nessuna libreria. I sorgenti stanno in `tools/fixtures/src` (Java); sono gli
  stessi che i test di `core-metadata` compilano (`compileFixtures` in `core-metadata/build.gradle`).
- L'uscita è `tools\fixtures\out\MCG-prova\` (circa 100 file e `MANIFEST.tsv` con i valori attesi). **Non si committa**: la regola
  `out/` del `.gitignore` la copre, e il file da 200 MP si genera su richiesta.
- La stessa cartella vale per tutto: i nomi sono unici.

## 3. Come si prova sul telefono (serve il consenso dell'utente)

La Fase 3 chiede il consenso a copiare le fixture in `Pictures/MCG-prova/` e solo lì (§5.5 A.3). Con il telefono collegato e il debug USB attivo:

```powershell
adb push tools\fixtures\out\MCG-prova /sdcard/Pictures/MCG-prova
adb shell content call --uri content://media/ --method scan_volume --arg external_primary   # se non basta, riavviare il telefono
# ... prova F2 ...
adb shell rm -r /sdcard/Pictures/MCG-prova               # a fine prove si cancella
```

L'app non scrive mai in quella cartella. Dopo la scansione (l'app la vede entro pochi secondi, T-13) ogni file compare nella Libreria,
ordinato per la data della colonna "Atteso"; il pannello Informazioni di ogni elemento mostra data, ora, fuso, "stimata" e luogo.

## 4. Condizioni di riferimento

- **Fuso del telefono: `Europe/Rome`** (ora solare +01:00 fino al 31/3/2024 e dal 27/10, legale +02:00 in mezzo; per le date del 2026
  vale quella del giorno). Se il telefono è altrove, le righe "ora certa, fuso supposto" cambiano il fuso mostrato e **non** l'ora
  scritta nel file (E-P5): è un buon modo per provare F9.
- **Luoghi:** nei test, un mini-dataset con Firenze, Fiesole, Milano, Sesto San Giovanni, Roma, Oslo, Londra, Monaco di Baviera,
  Lisbona, Parigi, Tokyo e New York. Sul telefono, il dataset vero di `core-places`: stessi nomi per questi luoghi. Il luogo vale solo
  entro 50 km (`CITY` e `NEAR`): oltre, la foto ha il GPS e nessun luogo.
- **"Adesso"** (per i soli casi marcati "solo test"): il 13/10/2026 alle 12:00 UTC. Una data oltre ora + 48 h è assurda.
- Il tipo MIME che l'app legge è quello dei **byte**, non dell'estensione: un `.jpg` che dentro è testo non è un media.

## 5. Come leggere le tabelle

- **Cartella simulata nei test.** Nei test la cartella di MediaStore (`RELATIVE_PATH`) è quella indicata e la sorgente (fotocamera,
  WhatsApp, screenshot, Quick Share) si decide da cartella e nome. **Sul telefono tutto sta in `Pictures/MCG-prova/`**, quindi la
  sorgente che dipende dalla sola cartella (Fotocamera, Quick Share) diventa "Altro", mentre quella che il nome dice da solo
  (`Screenshot_*`, `Screen_Recording_*`, `IMG-aaaammgg-WA nnnn`) resta valida. La colonna **"Atteso sul telefono"** riporta già questa
  differenza. La classificazione per cartella la provano i test JVM (`FileNameAndSourceTest`).
- **Ora certa, fuso supposto** = l'ora è quella scritta nel file, il fuso è dedotto (luogo, vicini, telefono). **Esatta** = ora e fuso
  sono scritti nel file (o ricavati dall'ora GPS). **Stimata** = la data stessa è approssimata (solo il giorno nel nome, o data del file).
- **Solo test**: il valore dipende da cosa scrive il MediaProvider in `DATE_TAKEN` (che sul telefono non si controlla) o da "adesso".
  Il file non viene generato per il telefono.
- I tre **MP4 sintetici** (`VID-…-WA0003`, `Screen_Recording_…`, `VID_…`) hanno la struttura giusta (`ftyp`, `moov`, `mdat`) ma nessun flusso:
  il sistema non li apre, quindi valgono nome e MediaStore; il MediaProvider potrebbe addirittura non indicizzarli (in quel caso la
  riga manca e non è un errore dell'app).
- I **HEIC** e il **DNG** hanno `meta`/`iloc`/`ipco` (o gli IFD) veri e i byte dell'immagine finti: la miniatura non si disegna, ma data,
  dimensioni, orientamento e tipo si leggono. I **WebP**, i **PNG**, i **JPEG**, il **GIF** e il **BMP** sono invece immagini vere.
- Una cella **"non leggibile"** (file troncato o rovinato) non blocca mai la Libreria (E-M10); un file ignorato (zero byte, falso media)
  non compare e viene contato in Diagnostica (E-M12).

## 6. Valori attesi, file per file

<!-- FIXTURES:BEGIN (generato da tools/fixtures: non modificare a mano) -->

### Date dall'EXIF (E-M8, E-M9, E-M14, E-P5)

| File | Cartella simulata nei test | Cosa è | Atteso sul telefono | Sul telefono |
|---|---|---|---|---|
| `exif-01-con-fuso.jpg` | `DCIM/Camera/` | DateTimeOriginal e OffsetTimeOriginal +01:00 | Foto, 64×48; 12/03/2024 10:15:30 (UTC+1), esatta; sorgente Altro | sì |
| `exif-02-fuso-negativo.jpg` | `DCIM/Camera/` | Fuso -05:00: l'ora che si vede è quella di New York | Foto, 64×48; 04/07/2024 22:30:00 (UTC-5), esatta; sorgente Altro | sì |
| `exif-03-fuso-mezz-ora.jpg` | `DCIM/Camera/` | Fuso +05:30 (India) | Foto, 64×48; 15/01/2024 08:00:00 (UTC+5:30), esatta; sorgente Altro | sì |
| `exif-04-sottosecondi.jpg` | `DCIM/Camera/` | SubSecTimeOriginal 123: i millisecondi ordinano le raffiche | Foto, 64×48; 12/03/2024 10:15:30.123 (UTC+1), esatta; sorgente Altro | sì |
| `exif-05-gps-ora-utc.jpg` | `DCIM/Camera/` | Senza fuso ma con data e ora GPS: fuso = ora locale - UTC, a 15 minuti (+01:00) | Foto, 64×48; 12/03/2024 10:15:30 (UTC+1), esatta; sorgente Altro; luogo Oslo | sì |
| `exif-06-gps-ora-utc-india.jpg` | `DCIM/Camera/` | Ora GPS 10:15:02 contro 15:45:00 locali: +05:30 arrotondato a 15 minuti (GPS a Nuova Delhi: il luogo non si verifica, il dataset di prova non ha l'India) | Foto, 64×48; 12/03/2024 15:45:00 (UTC+5:30), esatta; sorgente Altro | sì |
| `exif-07-gps-fuso-del-luogo.jpg` | `DCIM/Camera/` | Senza fuso, con GPS a Tokyo e senza ora GPS: il fuso è quello del luogo (+09:00) | Foto, 64×48; 04/07/2024 18:00:00 (UTC+9), ora certa, fuso supposto; sorgente Altro; luogo Tokyo | sì |
| `exif-08-new-york-sera.jpg` | `DCIM/Camera/` | 23:30 a New York (-04:00): per il giorno vale il 4 luglio, non il 5 di Roma (E-P5) | Foto, 64×48; 04/07/2024 23:30:00 (UTC-4), ora certa, fuso supposto; sorgente Altro; luogo New York | sì |
| `exif-09-nessun-fuso-inverno.jpg` | `DCIM/Camera/` | Né fuso né GPS, in inverno: il fuso del telefono alla lettura (+01:00) | Foto, 64×48; 20/01/2024 09:00:00 (UTC+1), ora certa, fuso supposto; sorgente Altro | sì |
| `exif-10-nessun-fuso-estate.jpg` | `DCIM/Camera/` | Né fuso né GPS, in estate: ora legale (+02:00) | Foto, 64×48; 20/07/2024 09:00:00 (UTC+2), ora certa, fuso supposto; sorgente Altro | sì |
| `exif-11-salto-ora-legale.jpg` | `DCIM/Camera/` | 02:30 del 31/3/2024 non esiste a Roma (salto dell'ora legale): si usa il fuso dopo il salto (+02:00) | Foto, 64×48; 31/03/2024 02:30:00 (UTC+2), ora certa, fuso supposto; sorgente Altro | sì |
| `exif-12-solo-digitized.jpg` | `DCIM/Camera/` | Manca DateTimeOriginal: vale DateTimeDigitized con il suo OffsetTimeDigitized | Foto, 64×48; 12/03/2024 10:15:30 (UTC+1), esatta; sorgente Altro | sì |
| `exif-13-gps-a-zero.jpg` | `DCIM/Camera/` | GPS a (0, 0): molte fotocamere lo scrivono senza un fix, non è un luogo | Foto, 64×48; 12/03/2024 10:15:30 (UTC+1), esatta; sorgente Altro | sì |
| `exif-14-big-endian.jpg` | `DCIM/Camera/` | EXIF in big-endian (Motorola): stessi valori di exif-01 | Foto, 64×48; 12/03/2024 10:15:30 (UTC+1), esatta; sorgente Altro | sì |
| `exif-15-con-miniatura.jpg` | `DCIM/Camera/` | EXIF completo con miniatura nell'IFD1: marca, modello, obiettivo, apertura, posa, ISO, focale | Foto, 64×48; 12/03/2024 10:15:30 (UTC+1), esatta; sorgente Altro | sì |
| `exif-16-solo-datetime-ifd0.jpg` | `DCIM/Camera/` | Solo il DateTime dell'IFD0 (ultima modifica): non è la data dello scatto e non si usa | Foto, 64×48; 12/03/2024 10:00:00 (UTC+1), stimata; sorgente Altro | solo test |

### Date assurde: si passa alla fonte successiva (E-M9)

| File | Cartella simulata nei test | Cosa è | Atteso sul telefono | Sul telefono |
|---|---|---|---|---|
| `IMG_20240312_101530.jpg` | `DCIM/Camera/` | EXIF con una data a zero (0000:00:00): si scarta e vale il nome del file (AOSP) | Foto, 64×48; 12/03/2024 10:15:30 (UTC+1), ora certa, fuso supposto; sorgente Altro | sì |
| `IMG_20240312_101531.jpg` | `DCIM/Camera/` | EXIF con una data vuota (solo spazi): si scarta e vale il nome del file (AOSP) | Foto, 64×48; 12/03/2024 10:15:31 (UTC+1), ora certa, fuso supposto; sorgente Altro | sì |
| `IMG_20240312_101532.jpg` | `DCIM/Camera/` | EXIF con la data dell'epoca (1970): si scarta e vale il nome del file (AOSP) | Foto, 64×48; 12/03/2024 10:15:32 (UTC+1), ora certa, fuso supposto; sorgente Altro | sì |
| `IMG_20240312_101533.jpg` | `DCIM/Camera/` | EXIF con una data prima del 1900: si scarta e vale il nome del file (AOSP) | Foto, 64×48; 12/03/2024 10:15:33 (UTC+1), ora certa, fuso supposto; sorgente Altro | sì |
| `IMG_20240312_101534.jpg` | `DCIM/Camera/` | EXIF con una data nel futuro (oltre ora + 48 h): si scarta e vale il nome del file (AOSP) | Foto, 64×48; 12/03/2024 10:15:34 (UTC+1), ora certa, fuso supposto; sorgente Altro | sì |
| `IMG_20240312_101535.jpg` | `DCIM/Camera/` | EXIF con un mese e un giorno impossibili: si scarta e vale il nome del file (AOSP) | Foto, 64×48; 12/03/2024 10:15:35 (UTC+1), ora certa, fuso supposto; sorgente Altro | sì |

### Date e sorgenti dai nomi dei file

| File | Cartella simulata nei test | Cosa è | Atteso sul telefono | Sul telefono |
|---|---|---|---|---|
| `IMG-20240312-WA0001.jpg` | `Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/` | WhatsApp: il nome porta il giorno della ricezione, non l'ora | Foto, 64×48; 12/03/2024 12:00:00 (UTC+1), stimata; sorgente WhatsApp | sì |
| `IMG-20240312-WA0002.jpg` | `Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/Sent/` | WhatsApp, cartella Sent | Foto, 64×48; 12/03/2024 12:00:00 (UTC+1), stimata; sorgente WhatsApp | sì |
| `VID-20240312-WA0003.mp4` | `Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video/` | Video WhatsApp (MP4 sintetico senza flussi: il sistema non lo apre, valgono nome e MediaStore) | Video; 12/03/2024 12:00:00 (UTC+1), stimata; sorgente WhatsApp | sì |
| `Screenshot_20240312_101530_Chrome.png` | `Pictures/Screenshots/` | Screenshot Samsung: aaaammgg_hhmmss_App | Foto, 64×48; 12/03/2024 10:15:30 (UTC+1), ora certa, fuso supposto; sorgente Screenshot | sì |
| `Screenshot_20240312-101531.png` | `DCIM/Screenshots/` | Screenshot AOSP: aaaammgg-hhmmss | Foto, 64×48; 12/03/2024 10:15:31 (UTC+1), ora certa, fuso supposto; sorgente Screenshot | sì |
| `Screen_Recording_20240312-101532_Chrome.mp4` | `DCIM/Screen recordings/` | Registrazione dello schermo: tratto SCREEN_RECORDING, sorgente Screenshot | Video; 12/03/2024 10:15:32 (UTC+1), ora certa, fuso supposto; sorgente Screenshot; tratti SCREEN_RECORDING | sì |
| `20240312_101533.jpg` | `DCIM/Camera/` | Fotocamera Samsung: aaaammgg_hhmmss | Foto, 64×48; 12/03/2024 10:15:33 (UTC+1), ora certa, fuso supposto; sorgente Altro | sì |
| `VID_20240312_101534.mp4` | `DCIM/Camera/` | Video AOSP: VID_aaaammgg_hhmmss | Video; 12/03/2024 10:15:34 (UTC+1), ora certa, fuso supposto; sorgente Altro | sì |
| `PXL_20240312_101535123.jpg` | `DCIM/Camera/` | Pixel: il nome è in UTC (10:15:35.123Z = 11:15:35.123 a Roma) | Foto, 64×48; 12/03/2024 11:15:35.123 (UTC+1), ora certa, fuso supposto; sorgente Altro | sì |
| `signal-2024-03-12-101536.jpg` | `Pictures/Signal/` | Signal: signal-aaaa-mm-gg-hhmmss | Foto, 64×48; 12/03/2024 10:15:36 (UTC+1), ora certa, fuso supposto; sorgente Altro | sì |
| `photo_2024-03-12_10-15-37.jpg` | `Pictures/Telegram/` | Telegram: photo_aaaa-mm-gg_hh-mm-ss | Foto, 64×48; 12/03/2024 10:15:37 (UTC+1), ora certa, fuso supposto; sorgente Altro | sì |
| `foto-condivisa.jpg` | `Download/Quick Share/` | Quick Share, senza data nel nome: solo la data del file (stimata) | Foto, 64×48; 12/03/2024 10:15:30 (UTC+1), stimata; sorgente Altro | solo test |
| `IMG_20241313_101530.jpg` | `DCIM/Camera/` | Nome con una data impossibile: si passa al DATE_TAKEN del MediaStore | Foto, 64×48; 12/03/2024 10:15:30 (UTC+1), ora certa, fuso supposto; sorgente Altro | solo test |
| `IMG_19700101_000000.jpg` | `DCIM/Camera/` | Nome all'epoca (1970) e nessun DATE_TAKEN: vale la data del file (stimata) | Foto, 64×48; 02/02/2024 11:00:00 (UTC+1), stimata; sorgente Altro | solo test |
| `senza-data.jpg` | `Pictures/` | Nessuna data, e la data del file è del 1979 (assurda): ripiego finale 'adesso', stimata | Foto, 64×48; 13/10/2026 14:00:00 (UTC+2), stimata; sorgente Altro | solo test |

### Formati: HEIC, DNG, PNG, WebP, GIF, BMP, TIFF

| File | Cartella simulata nei test | Cosa è | Atteso sul telefono | Sul telefono |
|---|---|---|---|---|
| `formato-png-con-exif.png` | `Pictures/` | PNG con chunk eXIf | Foto, 64×48; 12/03/2024 10:15:30 (UTC+1), esatta; sorgente Altro | sì |
| `formato-webp-con-exif.webp` | `Pictures/` | WebP lossless 64x48 con chunk EXIF | Foto, 64×48; 12/03/2024 10:15:30 (UTC+1), esatta; sorgente Altro | sì |
| `IMG_20240312_101540.webp` | `DCIM/Camera/` | WebP semplice senza metadati: vale il nome | Foto, 64×48; 12/03/2024 10:15:40 (UTC+1), ora certa, fuso supposto; sorgente Altro | sì |
| `IMG_20240312_101541_animato.webp` | `DCIM/Camera/` | WebP animato (VP8X + ANIM + 2 fotogrammi) | Animata, 64×48; 12/03/2024 10:15:41 (UTC+1), ora certa, fuso supposto; sorgente Altro | sì |
| `IMG_20240312_101542.gif` | `Pictures/` | GIF: sempre immagine animata | Animata, 64×48; 12/03/2024 10:15:42 (UTC+1), ora certa, fuso supposto; sorgente Altro | sì |
| `IMG_20240312_101543.bmp` | `Pictures/` | BMP | Foto, 64×48; 12/03/2024 10:15:43 (UTC+1), ora certa, fuso supposto; sorgente Altro | sì |
| `formato-heic.heic` | `DCIM/Camera/` | HEIC con voce Exif (struttura vera, immagine finta) | Foto, 64×48; 12/03/2024 10:15:30 (UTC+1), esatta; sorgente Altro | sì |
| `formato-heic-ruotato.heic` | `DCIM/Camera/` | HEIC con irot = 1 (90 gradi antiorari): si vede ruotato di 270 gradi in senso orario | Foto, 64×48, orientamento EXIF 8; 12/03/2024 10:15:30 (UTC+1), esatta; sorgente Altro | sì |
| `formato-heic-xmp.heic` | `DCIM/Camera/` | HEIC con voce XMP (mime application/rdf+xml) che dichiara il gain map | Foto, 64×48; 12/03/2024 10:15:30 (UTC+1), esatta; sorgente Altro; tratti ULTRA_HDR | sì |
| `formato-dng.dng` | `DCIM/Camera/` | DNG: anteprima 256x192 nell'IFD0, grezzo 4000x3000 nel SubIFD, ritaglio 3984x2988 | RAW, 3984×2988; 12/03/2024 10:15:30 (UTC+1), esatta; sorgente Altro | sì |
| `formato-tiff.tif` | `Pictures/` | TIFF semplice (non DNG): è una foto | Foto, 4000×3000; 12/03/2024 10:15:30 (UTC+1), esatta; sorgente Altro | sì |

### Otto orientamenti (E-V3, E-M15)

| File | Cartella simulata nei test | Cosa è | Atteso sul telefono | Sul telefono |
|---|---|---|---|---|
| `orientamento-1.jpg` | `DCIM/Camera/` | Orientamento EXIF 1 (dritta): i pixel sono salvati 64x48 e la foto si deve vedere sempre dritta (la F con l'asta a sinistra) | Foto, 64×48; 12/03/2024 10:15:30 (UTC+1), esatta; sorgente Altro | sì |
| `orientamento-2.jpg` | `DCIM/Camera/` | Orientamento EXIF 2 (specchiata): i pixel sono salvati 64x48 e la foto si deve vedere sempre dritta (la F con l'asta a sinistra) | Foto, 64×48, orientamento EXIF 2; 12/03/2024 10:15:30 (UTC+1), esatta; sorgente Altro | sì |
| `orientamento-3.jpg` | `DCIM/Camera/` | Orientamento EXIF 3 (180): i pixel sono salvati 64x48 e la foto si deve vedere sempre dritta (la F con l'asta a sinistra) | Foto, 64×48, orientamento EXIF 3; 12/03/2024 10:15:30 (UTC+1), esatta; sorgente Altro | sì |
| `orientamento-4.jpg` | `DCIM/Camera/` | Orientamento EXIF 4 (specchiata verticale): i pixel sono salvati 64x48 e la foto si deve vedere sempre dritta (la F con l'asta a sinistra) | Foto, 64×48, orientamento EXIF 4; 12/03/2024 10:15:30 (UTC+1), esatta; sorgente Altro | sì |
| `orientamento-5.jpg` | `DCIM/Camera/` | Orientamento EXIF 5 (trasposta): i pixel sono salvati 64x48 e la foto si deve vedere sempre dritta (la F con l'asta a sinistra) | Foto, 64×48, orientamento EXIF 5; 12/03/2024 10:15:30 (UTC+1), esatta; sorgente Altro | sì |
| `orientamento-6.jpg` | `DCIM/Camera/` | Orientamento EXIF 6 (90 orario): i pixel sono salvati 64x48 e la foto si deve vedere sempre dritta (la F con l'asta a sinistra) | Foto, 64×48, orientamento EXIF 6; 12/03/2024 10:15:30 (UTC+1), esatta; sorgente Altro | sì |
| `orientamento-7.jpg` | `DCIM/Camera/` | Orientamento EXIF 7 (trasversa): i pixel sono salvati 64x48 e la foto si deve vedere sempre dritta (la F con l'asta a sinistra) | Foto, 64×48, orientamento EXIF 7; 12/03/2024 10:15:30 (UTC+1), esatta; sorgente Altro | sì |
| `orientamento-8.jpg` | `DCIM/Camera/` | Orientamento EXIF 8 (270 orario): i pixel sono salvati 64x48 e la foto si deve vedere sempre dritta (la F con l'asta a sinistra) | Foto, 64×48, orientamento EXIF 8; 12/03/2024 10:15:30 (UTC+1), esatta; sorgente Altro | sì |

### Tratti: panorama, sfera, Ultra HDR

| File | Cartella simulata nei test | Cosa è | Atteso sul telefono | Sul telefono |
|---|---|---|---|---|
| `tratto-panorama-rapporto.jpg` | `DCIM/Camera/` | 300x100: rapporto 3:1, oltre 2,5:1 | Foto, 300×100; 12/03/2024 10:15:30 (UTC+1), esatta; sorgente Altro; tratti PANORAMA | sì |
| `tratto-panorama-gpano.jpg` | `DCIM/Camera/` | 64x48 ma con XMP GPano (proiezione cilindrica) | Foto, 64×48; 12/03/2024 10:15:30 (UTC+1), esatta; sorgente Altro; tratti PANORAMA | sì |
| `tratto-sfera.jpg` | `DCIM/Camera/` | 128x64 equirettangolare a copertura completa: sfera, non panorama | Foto, 128×64; 12/03/2024 10:15:30 (UTC+1), esatta; sorgente Altro; tratti SPHERE | sì |
| `tratto-equirettangolare-parziale.jpg` | `DCIM/Camera/` | Equirettangolare ma con mezza altezza: panorama, non sfera | Foto, 128×32; 12/03/2024 10:15:30 (UTC+1), esatta; sorgente Altro; tratti PANORAMA | sì |
| `tratto-ultra-hdr.jpg` | `DCIM/Camera/` | XMP con hdrgm:Version e voce GainMap: Ultra HDR | Foto, 64×48; 12/03/2024 10:15:30 (UTC+1), esatta; sorgente Altro; tratti ULTRA_HDR | sì |
| `tratto-screenshot-lungo.png` | `Pictures/Screenshots/` | 100x400: molto alta non vuol dire panorama (solo i larghi) | Foto, 100×400; 12/03/2024 10:15:38 (UTC+1), stimata; sorgente Altro | solo test |
| `tratto-panorama-ruotato.jpg` | `DCIM/Camera/` | 100x300 salvato in piedi con orientamento 6: si vede 300x100, quindi panorama | Foto, 100×300, orientamento EXIF 6; 12/03/2024 10:15:30 (UTC+1), esatta; sorgente Altro; tratti PANORAMA | sì |

### Motion photo (E-M5, E-M16)

| File | Cartella simulata nei test | Cosa è | Atteso sul telefono | Sul telefono |
|---|---|---|---|---|
| `motion-google-v1.jpg` | `DCIM/Camera/` | Google v1 (XMP Container:Directory con Item:Length), come un Pixel | Motion photo, 64×48; 12/03/2024 10:15:30 (UTC+1), esatta; sorgente Altro; parte video GOOGLE_V1 | sì |
| `motion-google-v1-specifica.jpg` | `DCIM/Camera/` | Google v1 scritto come nella specifica (prefisso Camera:, voci a elementi) | Motion photo, 64×48; 12/03/2024 10:15:30 (UTC+1), esatta; sorgente Altro; parte video GOOGLE_V1 | sì |
| `motion-microvideo.jpg` | `DCIM/Camera/` | MicroVideo (MicroVideoOffset dalla fine del file) | Motion photo, 64×48; 12/03/2024 10:15:30 (UTC+1), esatta; sorgente Altro; parte video MICRO_VIDEO | sì |
| `motion-samsung-sef.jpg` | `DCIM/Camera/` | Trailer SEF di Samsung: blocco 0x0A30 'MotionPhoto_Data' con il video | Motion photo, 64×48; 12/03/2024 10:15:30 (UTC+1), esatta; sorgente Altro; parte video SAMSUNG_SEF | sì |
| `motion-samsung-sef-puntatore.jpg` | `DCIM/Camera/` | Trailer SEF con il blocco da 12 byte 'mpv2' (offset e dimensione assoluti) | Motion photo, 64×48; 12/03/2024 10:15:30 (UTC+1), esatta; sorgente Altro; parte video SAMSUNG_SEF | sì |
| `motion-dichiarata-senza-video.jpg` | `DCIM/Camera/` | XMP dichiara MotionPhoto=1 ma non dice dove sta il video (E-M16) | Motion photo, 64×48; 12/03/2024 10:15:30 (UTC+1), esatta; sorgente Altro; motion photo dichiarata, parte video non riproducibile | sì |
| `motion-video-tolto.jpg` | `DCIM/Camera/` | La directory dice di un video da 90 KB ma il file è solo la foto: video tolto (E-M16) | Motion photo, 64×48; 12/03/2024 10:15:30 (UTC+1), esatta; sorgente Altro; motion photo dichiarata, parte video non riproducibile | sì |
| `motion-heic-google.heic` | `DCIM/Camera/` | HEIC con XMP Google v1 e riquadro mpvd in fondo | Motion photo, 64×48; 12/03/2024 10:15:30 (UTC+1), esatta; sorgente Altro; parte video GOOGLE_V1 | sì |

### File rovinati, troncati, falsi (E-M10, E-M12)

| File | Cartella simulata nei test | Cosa è | Atteso sul telefono | Sul telefono |
|---|---|---|---|---|
| `vuoto.jpg` | `DCIM/Camera/` | Zero byte: ignorato e contato, mai in Libreria | Ignorato e contato (zero byte); mai nella Libreria | sì |
| `testo.jpg` | `DCIM/Camera/` | Un .jpg che dentro è una pagina di errore HTML | Ignorato e contato (non è un media); mai nella Libreria | sì |
| `casuali.png` | `Pictures/` | Duemila byte casuali con estensione .png | Ignorato e contato (non è un media); mai nella Libreria | sì |
| `video-falso.mp4` | `DCIM/Camera/` | Testo con estensione .mp4 | Ignorato e contato (non è un media); mai nella Libreria | sì |
| `formato-sconosciuto.jxl` | `Pictures/` | JPEG XL: un'immagine in un formato che non sappiamo leggere | Formato non supportato: resta in Libreria senza metadati | sì |
| `troncato.jpg` | `DCIM/Camera/` | JPEG tagliato a metà dei dati dell'immagine | Non leggibile (file troncato); nessuna eccezione, la cella è "non leggibile" | sì |
| `troncato-nell-exif.jpg` | `DCIM/Camera/` | JPEG tagliato dentro il segmento EXIF | Non leggibile (file troncato); nessuna eccezione, la cella è "non leggibile" | sì |
| `solo-intestazione.jpg` | `DCIM/Camera/` | Solo SOI e JFIF: il file finisce subito | Non leggibile (file troncato); nessuna eccezione, la cella è "non leggibile" | sì |
| `senza-sof.jpg` | `DCIM/Camera/` | Segmenti ben formati ma nessun SOF: non c'è un'immagine da decodificare | Non leggibile (file rovinato); la cella è "non leggibile" | sì |
| `troncato.png` | `Pictures/` | PNG tagliato a metà | Non leggibile (file troncato); nessuna eccezione, la cella è "non leggibile" | sì |
| `png-intestazione-rotta.png` | `Pictures/` | Firma PNG seguita da un primo chunk che non è IHDR | Non leggibile (file rovinato); la cella è "non leggibile" | sì |
| `troncato.webp` | `Pictures/` | WebP con EXIF tagliato: la dimensione RIFF supera il file | Non leggibile (file troncato); nessuna eccezione, la cella è "non leggibile" | sì |
| `troncato.heic` | `DCIM/Camera/` | HEIC tagliato dentro il riquadro mdat | Non leggibile (file troncato); nessuna eccezione, la cella è "non leggibile" | sì |
| `troncato.dng` | `DCIM/Camera/` | DNG tagliato nei dati grezzi | Non leggibile (file troncato); nessuna eccezione, la cella è "non leggibile" | sì |
| `registrazione-interrotta.mp4` | `DCIM/Camera/` | Video senza il riquadro moov (batteria finita durante la ripresa) | Non leggibile (file troncato); nessuna eccezione, la cella è "non leggibile" | sì |
| `troncato.mp4` | `DCIM/Camera/` | Video con il moov tagliato a metà | Non leggibile (file troncato); nessuna eccezione, la cella è "non leggibile" | sì |

### Gruppi: raffiche e coppie RAW+JPEG (D20)

| Insieme | File | Gruppi attesi |
|---|---|---|
| Raffica di tre scatti Samsung (_001, _002, _003) a meno di 1 s, stesso modello | 0 `20240312_110000_001.jpg`, 1 `20240312_110000_002.jpg`, 2 `20240312_110000_003.jpg` | raffica dei file 0,1,2, copertina 0 |
| Raffica con copertina _COVER (stile Android) | 0 `IMG_20240312_111000_BURST001.jpg`, 1 `IMG_20240312_111000_BURST002_COVER.jpg`, 2 `IMG_20240312_111000_BURST003.jpg` | raffica dei file 0,1,2, copertina 1 |
| Stessa raffica ma un modello diverso: nel dubbio non si raggruppa | 0 `20240312_112000_001.jpg`, 1 `20240312_112000_002.jpg` | nessuno |
| Scatti con numeri di raffica ma a più di 1 s l'uno dall'altro: nessun gruppo | 0 `20240312_113000_001.jpg`, 1 `20240312_113000_002.jpg` | nessuno |
| Una raffica spezzata da un buco di tempo: i due scatti vicini formano un gruppo, l'isolato resta solo | 0 `20240312_114000_001.jpg`, 1 `20240312_114000_002.jpg`, 2 `20240312_114000_003.jpg` | raffica dei file 0,1, copertina 0 |
| Un solo scatto con suffisso: non è una raffica | 0 `20240312_115000_001.jpg` | nessuno |
| Coppia RAW+JPEG (stessa cartella, stesso nome base, 0 s): copertina il JPEG | 0 `20240312_120000.dng`, 1 `20240312_120000.jpg` | coppia RAW+JPEG dei file 1,0, copertina 1 |
| RAW e JPEG con lo stesso nome ma 5 s di distanza: nel dubbio non si raggruppa | 0 `20240312_121000.dng`, 1 `20240312_121000.jpg` | nessuno |
| Un DNG e due immagini con lo stesso nome base: ambiguo, nessuna coppia | 0 `20240312_122000.dng`, 1 `20240312_122000.jpg`, 2 `20240312_122000.heic` | nessuno |

<!-- FIXTURES:END -->

### Il JPEG da 200 MP (solo generatore)

| File | Atteso |
|---|---|
| `grande-200mp.jpg` | Foto, 16320×12240 (200 MP, circa 70 MB), 12/03/2024 10:15:30 (UTC+1), esatta, orientamento 1. La lettura non lo apre intero (nei test: meno di 300 KB letti su 62 MB); nel viewer si vede **solo a tasselli**, senza OOM (F12; PSS di picco da annotare) |

Lo scrive `generate.ps1` senza tenerlo in memoria (bastano 768 MB di heap) e impiega circa un quarto di minuto. Con `-SkipBig` non si genera.

## 7. La tabella delle date in breve (§2.2)

Si prende la prima fonte che regge; una data assurda si scarta e si passa alla successiva (E-M9).

| # | Fonte | Fuso | Esito |
|---|---|---|---|
| 1 | EXIF `DateTimeOriginal` + `OffsetTimeOriginal` (se manca la data: `DateTimeDigitized` + `OffsetTimeDigitized`) | scritto | esatta |
| 2 | EXIF senza fuso + `GPSDateStamp`/`GPSTimeStamp` | ora locale − UTC del GPS, a 15 minuti, entro ±14 h | esatta |
| 3 | EXIF senza fuso, con GPS | fuso del luogo | ora certa, fuso supposto |
| 4 | EXIF senza fuso e senza GPS | vicini (stessa cartella e modello, entro 6 h), poi telefono alla lettura | ora certa, fuso supposto |
| 5 | Video: data UTC del contenitore | GPS del video, poi vicini, poi telefono | ora certa, fuso supposto |
| 6 | Nome del file | come la 4 | stimata (solo il giorno, mezzogiorno) o ora certa |
| 7 | `DATE_TAKEN` di MediaStore | vicini, poi telefono | ora certa, fuso supposto |
| 8 | Data del file | telefono | stimata |
| 9 | Nient'altro | telefono | "adesso", stimata |

**Assurde:** vuote o a zero, prima del 1900, a meno di un giorno dall'epoca, oltre ora + 48 h; la data del file anche prima del 1995;
la data UTC di un video anche prima del 1970 (un contenitore a zero vale il 1904). Il `DateTime` dell'IFD0 non si usa mai (è l'ultima modifica).

## 8. Edge case e file che li provano

| ID | Dove si prova |
|---|---|
| E-M8 (date stimate) | `IMG-…-WA0001`, `foto-condivisa.jpg`, `senza-data.jpg`, `exif-16-solo-datetime-ifd0.jpg` |
| E-M9 (date assurde) | le sei `IMG_20240312_1015{30..35}.jpg`; `IMG_19700101_000000.jpg`; `senza-data.jpg` |
| E-M14 (video con fuso diverso) | test JVM (`CaptureTimeResolverTest`, `ExtractorBehaviorTest`): sul telefono serve un video vero con GPS |
| E-P5 (fuso del telefono) | `exif-09`/`exif-10` e, con il telefono su Tokyo, F9 |
| E-M10 (file rovinati) | tutta la sezione "File rovinati, troncati, falsi" |
| E-M12 (zero byte e falsi media) | `vuoto.jpg`, `testo.jpg`, `casuali.png`, `video-falso.mp4` |
| E-M15 e E-V3 (orientamenti) | `orientamento-1…8.jpg`, `formato-heic-ruotato.heic`, `tratto-panorama-ruotato.jpg` |
| E-M16 (motion photo non riconosciuta) | `motion-dichiarata-senza-video.jpg`, `motion-video-tolto.jpg`; un HEIC Samsung vero: T-27 |
| E-M5 (motion photo) | le sei `motion-*` |
| E-M6 e E-M13 (raffiche e RAW+JPEG) | la tabella dei gruppi (D20) |
| E-M11 (animati) | `IMG_20240312_101541_animato.webp`, `IMG_20240312_101542.gif` |
| E-P4 (nessuna lettura a file interi) | `grande-200mp.jpg` e `RobustnessTest` |

## 9. Cosa va provato con campioni veri (non con i sintetici)

Le fixture sono scritte da noi, con la nostra conoscenza dei formati: dove l'errore sarebbe **nostro e simmetrico** (lo scrittore e il
lettore sbagliano allo stesso modo) il confronto con ExifInterface nei test (`ExifInterfaceCrossCheckTest`) lo smaschera per JPEG, PNG
e WebP; per HEIC, DNG e trailer SEF non c'è un oracolo indipendente, quindi servono file veri.

| Prova | Perché |
|---|---|
| **T-27: motion photo Samsung** (3 scatti dell'S25) | Il trailer SEF è ricostruito dalla documentazione di ExifTool; la variante "mpv2" a puntatore e il caso HEIC (box `sefd`) non sono verificati. Se la parte video non si trova, la foto resta ferma con "parte video non riproducibile" (E-M16) |
| **HEIC veri** (S25 in HEIC, iPhone) | `irot`/`imir`, `iloc` con più estensioni, `infe` v3, Exif in `mdat` |
| **DNG di Expert RAW** | `SubIFD`, `DefaultCropSize`, orientamento; le sue dimensioni sviluppate |
| **Ultra HDR veri** | Il tratto `ULTRA_HDR` si legge dall'XMP (`hdrgm:Version` o voce `GainMap`) del JPEG primario |
| **Panorama e sfere** | `GPano` reale (proiezione e ritaglio) e la regola dei 2,5:1 |
| **Raffica dell'S25 (T-29)** | I suffissi dei nomi (`_001`, `_BURSTnnn`) e i sotto-secondi delle raffiche vere; **nel dubbio non si raggruppa** |
| **Video 4K HDR10+ e oltre 2 GB** | La sonda (`MediaMetadataRetriever`) e il tratto `HDR_VIDEO`; la struttura (`moov` in fondo, 64 bit) è provata a 20 GB finti |
| **Foto con fuso nell'EXIF (Android 12+)** | `OffsetTimeOriginal` reale dell'S25 |
| **Latenza di `loadThumbnail` e decodifica HEIC (T-26)** | Non è di questo pacchetto |

## 10. Scelte e limiti noti (da conoscere prima di fidarsi)

- **Luogo = `CITY` o `NEAR`** (entro 50 km dal luogo noto più vicino). Oltre, il GPS si salva e il luogo no.
- **GPS a (0, 0)** non è un luogo (molte fotocamere lo scrivono senza fix).
- **Troncamento:** un JPEG è "tagliato" se non finisce con l'EOI nella coda e la coda è ancora il flusso compresso dell'immagine (nessun `FF` seguito da altro che
  `00` o un RSTn, e non è un riempimento di zeri): un video aggiunto in fondo, un trailer SEF o un riempimento non lo rendono "tagliato". Un JPEG tagliato **con** una
  miniatura Exif nei primi 64 KiB può passare per intero (l'EOI della miniatura): la cella si disegna con quello che il decodificatore recupera. PNG, WebP, BMP,
  HEIC, DNG e MP4 hanno la lunghezza dentro la struttura e il taglio si riconosce sempre; il GIF no.
- **Orientamento di un HEIC/AVIF:** vale solo `irot`/`imir`; l'`Orientation` dell'EXIF si ignora (i decodificatori lo ignorano, e le fotocamere lo scrivono in più,
  coerente con `irot`: contarlo due volte ruoterebbe due volte). Senza `irot`/`imir` la foto è dritta.
- **Motion photo:** `MOTION_PHOTO` con la parte video nulla significa "dichiarata ma non trovata" (E-M16); la UI mostra la foto ferma.
- **Pannello e panorama:** solo i **larghi** (rapporto ≥ 2,5:1 dopo l'orientamento) sono panorami; uno screenshot lungo no. Una sfera completa
  ha `SPHERE` e non `PANORAMA`; un equirettangolare parziale è `PANORAMA`.
- **Raffiche:** servono almeno 2 foto con suffisso, stesso nome base, **stesso modello noto**, scatti consecutivi a meno di 1 s. `(n)` vale solo con un nome
  che ha data e ora e con ore diverse. Un media sta in un gruppo solo; la coppia RAW+JPEG vince sulla raffica. Gli indizi (`GroupHint`) arrivano dall'estrazione:
  senza (rielaborazione del solo catalogo) non si formano raffiche.
- **`ExtractHints.msDateModifiedMs`** (nuovo, opzionale): la data del file per l'ultimo ripiego. Chi costruisce gli indizi dalla riga del MediaStore
  (`MediaStoreRow.toExtractHints`, P2) deve passarlo; senza, la data senza altre fonti cade su "adesso" (stimata).
- **ThumbHash:** `decode(hash, n)` dà un quadrato `n × n` (come il segnaposto di P0, adatto alla cella quadrata); `decodeImage` dà la proporzione vera con le dimensioni.
  Il segnaposto si calcola da un'anteprima di al massimo 100 px, scelta nell'ordine del §11: **prima la miniatura incorporata** (se coincide per proporzione con
  l'immagine), poi la decodifica **in streaming** del file (solo fino a `maxFullDecodeBytes`, 16 MiB). I video non hanno segnaposto in Fase 3.

## 11. Costo della prima lettura: stima per 10 mila foto

Il segnaposto sfocato è la sola parte di META che può leggere un file intero, quindi è quella che decide il costo della prima lettura (F1: circa 6.700 media
sull'S25). L'ordine delle fonti (regola completa nel KDoc di `DefaultMetadataExtractor`):

1. **Miniatura incorporata**: la miniatura JPEG dell'IFD1 dell'EXIF (già in memoria con l'EXIF) o l'anteprima JPEG di un DNG. Si usa se la sua proporzione coincide con
   quella delle dimensioni vere (scarto massimo del 4%, o 1 pixel a 100 px): le miniature delle fotocamere sono spesso 4:3 anche per foto 16:9 o ritagliate, e
   in quel caso si passa al ripiego. Un HEIC non ha questa strada (`thmb` è un flusso HEVC grezzo), salvo una miniatura IFD1 nel suo EXIF.
2. **Ripiego**: decodifica completa **in streaming** (`decodeStream` con `inSampleSize` sulla `MediaDataSource`, mai un `ByteArray` del file) per i file fino a 16 MiB.
3. Oltre, o senza nessuna delle due, nessun segnaposto (la cella usa la miniatura di sistema).

| Scenario (10.000 foto da circa 5 MB) | Byte letti | Memoria transitoria per foto | Tempo stimato |
|---|---|---|---|
| **A. Tutte JPEG di fotocamera con miniatura valida** (il caso normale dell'S25) | **circa 1,4–1,8 GB** (misurati sui test: 135–182 KiB e 2 letture per foto, da 3 a 12 MB) | meno di 0,5 MB (pagine da 64 KiB, EXIF, miniatura 160×120) | 5–10 ms a foto: **circa 1–2 minuti** |
| **B. Mista**: 60% come la A; 40% senza miniatura valida (screenshot PNG, WhatsApp, HEIC, JPEG 16:9 con miniatura 4:3), in media 2 MB | circa 1 GB + 8 GB = **circa 9 GB** | decodificatore + bitmap campionata (circa 1 MB) | A come sopra; ripiego 50–100 ms a file: **circa 5–8 minuti** |
| **C. Caso peggiore**: nessuna miniatura valida, tutte da 5 MB | **circa 50 GB** (come leggere tutto) | circa 1 MB | 60–120 ms a foto: **circa 10–20 minuti** |
| Prima della regola (tutte lette per intero in un `ByteArray`) | circa 50 GB | **fino a 16 MB per file** | come la C più la copia |

I byte sono misurati sui file sintetici (`ExtractorBehaviorTest.unaFotoComuneCostaPocheCentinaiaDiKiBLettiEZeroDecodificheDelFile`); i **tempi sono stime** su latenze tipiche
di `ContentResolver` e `BitmapFactory`, da confermare con F1 e T-26. Lavorano prima le foto visibili (`MetaPriority`), quindi la Libreria si riempie di segnaposto
dall'alto mentre il resto arriva; la fase non tocca la rete. Se F1 mostrasse un costo eccessivo del ripiego, si abbassa `maxFullDecodeBytes` (con 0 il segnaposto viene solo
dalle miniature).

**Compromesso noto (E-M10):** con una miniatura valida l'immagine vera non si decodifica in META, quindi un JPEG con i dati compressi rovinati ma la struttura intera non
diventa `Corrupt(DECODE_FAILED)` qui (i tagli si riconoscono comunque dalla struttura); il difetto si vede quando la miniatura di sistema o il viewer lo decodificano.
