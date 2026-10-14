package dev.pampa.mycloudgallery.fixtures;

import java.util.ArrayList;
import java.util.List;

/**
 * I campioni della PREPARAZIONE (docs/fase4-progetto.md §2.4, P6): video con un indice vero e payload finto (anche
 * "virtuali": un video da 9 GB che non occupa memoria), un APV finto, un DNG piccolo e un HEIC rifatto da un flusso HEVC.
 * Solo JDK. Non fanno parte di {@link FixtureCatalog} (che e' il catalogo dei metadati di core-metadata): i test di
 * core-prepare e {@link PrepareFixtureGenerator} li usano direttamente.
 */
public final class PrepareFixtures {

  private PrepareFixtures() {}

  // ---- Video con indice vero ----------------------------------------------------------------------------------

  /** Cio' che un video di prova dichiara. Il payload dei fotogrammi e' finto: l'indice (stbl) invece e' quello giusto. */
  public static final class VideoSpec {
    /** Il marchio del sample entry: {@code avc1}, {@code hvc1}, {@code av01}, {@code vp09}, {@code apv1}... */
    public String sampleEntry = "avc1";
    /** Dimensioni memorizzate (prima della rotazione). */
    public int width = 1920;
    public int height = 1080;
    /** Gradi orari da applicare per mostrarlo dritto, nella matrice del {@code tkhd}: 0, 90, 180, 270. */
    public int rotation = 0;
    public int fps = 30;
    public long durationMs = 90_000;
    /** Un fotogramma chiave ogni tanti fotogrammi. */
    public int gopFrames = 60;
    public int keyframeBytes = 120_000;
    public int deltaFrameBytes = 6_000;
    /** L'indice ({@code moov}) dopo i dati ({@code mdat}), come le registrazioni di Android e ffmpeg senza faststart. */
    public boolean moovLast = true;
    /** Un flusso audio (senza campioni) prima del video: chi legge deve scegliere il flusso {@code vide}. */
    public boolean withAudio = true;
  }

  /** Un riquadro-sintetico: o byte veri, o {@code length} byte calcolati dalla posizione. */
  private static final class Segment {
    final long start;
    final long length;
    final byte[] data;

    Segment(long start, byte[] data) {
      this.start = start;
      this.length = data.length;
      this.data = data;
    }

    Segment(long start, long length) {
      this.start = start;
      this.length = length;
      this.data = null;
    }
  }

  /**
   * Un MP4 "virtuale": la struttura (ftyp, indice, intestazione del mdat) e' fatta di byte veri; il payload dei fotogrammi si
   * calcola alla lettura, quindi si puo' descrivere un video da 9 GB senza tenerlo da nessuna parte. {@link #toBytes()} lo
   * materializza (solo per quelli piccoli).
   */
  public static final class VirtualMp4 {
    private final List<Segment> segments = new ArrayList<>();
    private final long size;
    public final long moovOffset;
    public final long moovSize;
    private final long[] sampleOffsets;
    private final int[] sampleSizes;
    private final boolean[] sync;
    private final int fps;

    VirtualMp4(List<Segment> segments, long size, long moovOffset, long moovSize, long[] offsets, int[] sizes, boolean[] sync, int fps) {
      this.segments.addAll(segments);
      this.size = size;
      this.moovOffset = moovOffset;
      this.moovSize = moovSize;
      this.sampleOffsets = offsets;
      this.sampleSizes = sizes;
      this.sync = sync;
      this.fps = fps;
    }

    public long size() {
      return size;
    }

    public int sampleCount() {
      return sampleOffsets.length;
    }

    public long sampleOffset(int index) {
      return sampleOffsets[index];
    }

    public int sampleSize(int index) {
      return sampleSizes[index];
    }

    public boolean isSync(int index) {
      return sync[index];
    }

    public long sampleTimeMs(int index) {
      return index * 1000L / fps;
    }

    /** L'indice del fotogramma chiave piu' vicino a {@code timeMs} (a sinistra o a destra, come {@code OPTION_CLOSEST_SYNC}). */
    public int closestSync(long timeMs) {
      int best = -1;
      long bestDistance = Long.MAX_VALUE;
      for (int i = 0; i < sync.length; i++) {
        if (!sync[i]) continue;
        long distance = Math.abs(sampleTimeMs(i) - timeMs);
        if (distance < bestDistance) {
          best = i;
          bestDistance = distance;
        }
      }
      return best;
    }

    /** Fino a {@code length} byte da {@code position} (meno a fine file). */
    public byte[] read(long position, int length) {
      if (position < 0 || position >= size || length <= 0) return new byte[0];
      int n = (int) Math.min(length, size - position);
      byte[] out = new byte[n];
      for (Segment segment : segments) {
        long from = Math.max(position, segment.start);
        long to = Math.min(position + n, segment.start + segment.length);
        if (from >= to) continue;
        for (long p = from; p < to; p++) {
          out[(int) (p - position)] = segment.data != null ? segment.data[(int) (p - segment.start)] : payloadByte(p);
        }
      }
      return out;
    }

    public byte[] toBytes() {
      if (size > Integer.MAX_VALUE - 8) throw new IllegalStateException("troppo grande per materializzarlo");
      return read(0, (int) size);
    }

    /** Il payload finto: deterministico, per posizione. */
    static byte payloadByte(long position) {
      long h = position * 0x9E3779B97F4A7C15L;
      return (byte) (h >>> 56);
    }
  }

  /** Un MP4 con indice vero (stts, stss, stsc, stsz, stco o co64) e payload finto. */
  public static VirtualMp4 video(VideoSpec s) {
    int frames = (int) Math.max(1, s.durationMs * s.fps / 1000);
    int[] sizes = new int[frames];
    boolean[] sync = new boolean[frames];
    long mdatPayload = 0;
    for (int i = 0; i < frames; i++) {
      sync[i] = i % s.gopFrames == 0;
      sizes[i] = sync[i] ? s.keyframeBytes : s.deltaFrameBytes;
      mdatPayload += sizes[i];
    }
    byte[] ftyp = IsoFixtures.box("ftyp", Bytes.ascii4("isom"), new Bytes().u32be(0x200).toBytes(), Bytes.ascii4("isom"), Bytes.ascii4("iso2"),
        Bytes.ascii4("mp41"));
    boolean large = mdatPayload + 16 > 0xFFFFFFFFL;
    int mdatHeader = large ? 16 : 8;
    byte[] mdatHead = large
        ? new Bytes().u32be(1).ascii("mdat").u64be(mdatHeader + mdatPayload).toBytes()
        : new Bytes().u32be(mdatHeader + mdatPayload).ascii("mdat").toBytes();

    // Prima passata per misurare il moov (gli offset hanno sempre la stessa larghezza), poi quella vera.
    long[] offsets = new long[frames];
    byte[] moov = moov(s, frames, sizes, sync, offsets, large);
    long mdatStart = s.moovLast ? ftyp.length : ftyp.length + moov.length;
    long cursor = mdatStart + mdatHeader;
    for (int i = 0; i < frames; i++) {
      offsets[i] = cursor;
      cursor += sizes[i];
    }
    moov = moov(s, frames, sizes, sync, offsets, large);

    List<Segment> segments = new ArrayList<>();
    long position = 0;
    segments.add(new Segment(position, ftyp));
    position += ftyp.length;
    long moovOffset;
    if (!s.moovLast) {
      moovOffset = position;
      segments.add(new Segment(position, moov));
      position += moov.length;
    } else {
      moovOffset = -1;
    }
    segments.add(new Segment(position, mdatHead));
    position += mdatHead.length;
    segments.add(new Segment(position, mdatPayload));
    position += mdatPayload;
    if (s.moovLast) {
      moovOffset = position;
      segments.add(new Segment(position, moov));
      position += moov.length;
    }
    return new VirtualMp4(segments, position, moovOffset, moov.length, offsets, sizes, sync, s.fps);
  }

  // ---- moov ---------------------------------------------------------------------------------------------------

  private static byte[] u32(long v) {
    return new Bytes().u32be(v).toBytes();
  }

  private static byte[] u16(int v) {
    return new Bytes().u16be(v).toBytes();
  }

  private static byte[] moov(VideoSpec s, int frames, int[] sizes, boolean[] sync, long[] offsets, boolean large) {
    List<byte[]> parts = new ArrayList<>();
    // mvhd: scala dei tempi 1000, durata in ms, velocita' 1.0, volume 1.0, matrice identita'.
    Bytes mvhd = new Bytes().u32be(0).u32be(0).u32be(1000).u32be(s.durationMs).u32be(0x00010000L).u16be(0x0100).u16be(0).u32be(0).u32be(0);
    matrix(mvhd, 0);
    mvhd.zeros(24).u32be(3);
    parts.add(IsoFixtures.fullBox("mvhd", 0, 0, mvhd.toBytes()));
    if (s.withAudio) parts.add(audioTrak(s));
    parts.add(videoTrak(s, frames, sizes, sync, offsets, large));
    return IsoFixtures.box("moov", parts.toArray(new byte[0][]));
  }

  /** Matrice 3x3 del tkhd/mvhd: a, b, u, c, d, v, x, y, w; la rotazione in gradi orari. */
  private static void matrix(Bytes out, int rotation) {
    long one = 0x00010000L;
    long a;
    long b;
    long c;
    long d;
    switch (rotation) {
      case 90: a = 0; b = one; c = -one & 0xFFFFFFFFL; d = 0; break;
      case 180: a = -one & 0xFFFFFFFFL; b = 0; c = 0; d = -one & 0xFFFFFFFFL; break;
      case 270: a = 0; b = -one & 0xFFFFFFFFL; c = one; d = 0; break;
      default: a = one; b = 0; c = 0; d = one; break;
    }
    out.u32be(a).u32be(b).u32be(0).u32be(c).u32be(d).u32be(0).u32be(0).u32be(0).u32be(0x40000000L);
  }

  private static byte[] tkhd(int trackId, long durationMs, int rotation, int width, int height, int volume) {
    Bytes b = new Bytes().u32be(0).u32be(0).u32be(trackId).u32be(0).u32be(durationMs).zeros(8).u16be(0).u16be(0).u16be(volume).u16be(0);
    matrix(b, rotation);
    b.u32be((long) width << 16).u32be((long) height << 16);
    return IsoFixtures.fullBox("tkhd", 0, 7, b.toBytes());
  }

  private static byte[] mdhd(long timescale, long duration) {
    return IsoFixtures.fullBox("mdhd", 0, 0, new Bytes().u32be(0).u32be(0).u32be(timescale).u32be(duration).u16be(0x55C4).u16be(0).toBytes());
  }

  private static byte[] hdlr(String type, String name) {
    return IsoFixtures.fullBox("hdlr", 0, 0, new Bytes().u32be(0).ascii(type).zeros(12).ascii(name).u8(0).toBytes());
  }

  private static byte[] dinf() {
    return IsoFixtures.box("dinf", IsoFixtures.fullBox("dref", 0, 0, u32(1), IsoFixtures.fullBox("url ", 0, 1)));
  }

  private static byte[] audioTrak(VideoSpec s) {
    Bytes entry = new Bytes().zeros(6).u16be(1).zeros(8).u16be(2).u16be(16).u16be(0).u16be(0).u32be(48000L << 16);
    byte[] mp4a = IsoFixtures.box("mp4a", entry.toBytes());
    byte[] stbl = IsoFixtures.box("stbl",
        IsoFixtures.fullBox("stsd", 0, 0, u32(1), mp4a),
        IsoFixtures.fullBox("stts", 0, 0, u32(0)),
        IsoFixtures.fullBox("stsc", 0, 0, u32(0)),
        IsoFixtures.fullBox("stsz", 0, 0, u32(0), u32(0)),
        IsoFixtures.fullBox("stco", 0, 0, u32(0)));
    byte[] minf = IsoFixtures.box("minf", IsoFixtures.fullBox("smhd", 0, 0, u32(0)), dinf(), stbl);
    byte[] mdia = IsoFixtures.box("mdia", mdhd(48000, 48000L * s.durationMs / 1000), hdlr("soun", "SoundHandler"), minf);
    return IsoFixtures.box("trak", tkhd(1, s.durationMs, 0, 0, 0, 0x0100), mdia);
  }

  private static byte[] videoTrak(VideoSpec s, int frames, int[] sizes, boolean[] sync, long[] offsets, boolean large) {
    Bytes entry = new Bytes().zeros(6).u16be(1).zeros(16).u16be(s.width).u16be(s.height).u32be(0x00480000L).u32be(0x00480000L).u32be(0).u16be(1)
        .zeros(32).u16be(0x18).u16be(0xFFFF);
    byte[] config = codecConfig(s.sampleEntry);
    byte[] sampleEntry = IsoFixtures.box(s.sampleEntry, entry.toBytes(), config);

    long timescale = 1000L * s.fps;
    byte[] stts = IsoFixtures.fullBox("stts", 0, 0, u32(1), u32(frames), u32(1000));
    List<Long> syncNumbers = new ArrayList<>();
    for (int i = 0; i < frames; i++) if (sync[i]) syncNumbers.add(i + 1L);
    Bytes stss = new Bytes().u32be(syncNumbers.size());
    for (long n : syncNumbers) stss.u32be(n);
    // Un campione per riquadro (chunk): la tabella degli offset ha una voce per campione.
    byte[] stsc = IsoFixtures.fullBox("stsc", 0, 0, u32(1), u32(1), u32(1), u32(1));
    Bytes stsz = new Bytes().u32be(0).u32be(frames);
    for (int size : sizes) stsz.u32be(size);
    Bytes offsetsTable = new Bytes().u32be(frames);
    for (long offset : offsets) {
      if (large) offsetsTable.u64be(offset); else offsetsTable.u32be(offset);
    }
    byte[] stbl = IsoFixtures.box("stbl",
        IsoFixtures.fullBox("stsd", 0, 0, u32(1), sampleEntry),
        stts,
        IsoFixtures.fullBox("stss", 0, 0, stss.toBytes()),
        stsc,
        IsoFixtures.fullBox("stsz", 0, 0, stsz.toBytes()),
        IsoFixtures.fullBox(large ? "co64" : "stco", 0, 0, offsetsTable.toBytes()));
    byte[] minf = IsoFixtures.box("minf", IsoFixtures.fullBox("vmhd", 0, 1, new byte[8]), dinf(), stbl);
    byte[] mdia = IsoFixtures.box("mdia", mdhd(timescale, (long) frames * 1000), hdlr("vide", "VideoHandler"), minf);
    return IsoFixtures.box("trak", tkhd(2, s.durationMs, s.rotation, s.width, s.height, 0), mdia);
  }

  /** La configurazione del codec: abbastanza per sembrare quella vera, non per decodificare. */
  private static byte[] codecConfig(String sampleEntry) {
    switch (sampleEntry) {
      case "avc1":
      case "avc3":
        return IsoFixtures.box("avcC", new Bytes().u8(1).u8(66).u8(0).u8(30).u8(0xFF).u8(0xE1).u16be(4).u8(0x67).u8(0x42).u8(0).u8(0x1E)
            .u8(1).u16be(2).u8(0x68).u8(0xCE).toBytes());
      case "hvc1":
      case "hev1":
        return IsoFixtures.box("hvcC", new byte[23]);
      case "av01":
        return IsoFixtures.box("av1C", new byte[] {(byte) 0x81, 0, 0, 0});
      case "apv1":
        return IsoFixtures.box("apvC", new byte[8]);
      default:
        return new byte[0];
    }
  }

  // ---- DNG piccolo ---------------------------------------------------------------------------------------------

  private static final class Tag {
    final int tag;
    final int type;
    final int count;
    final byte[] value;

    Tag(int tag, int type, int count, byte[] value) {
      this.tag = tag;
      this.type = type;
      this.count = count;
      this.value = value;
    }
  }

  private static Tag shortTag(int tag, int... v) {
    Bytes b = new Bytes();
    for (int x : v) b.u16le(x);
    return new Tag(tag, 3, v.length, b.toBytes());
  }

  private static Tag longTag(int tag, long... v) {
    Bytes b = new Bytes();
    for (long x : v) b.u32le(x);
    return new Tag(tag, 4, v.length, b.toBytes());
  }

  private static Tag byteTag(int tag, int... v) {
    byte[] b = new byte[v.length];
    for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
    return new Tag(tag, 1, v.length, b);
  }

  private static Tag asciiTag(int tag, String s) {
    byte[] v = new Bytes().ascii(s).u8(0).toBytes();
    return new Tag(tag, 2, v.length, v);
  }

  private static Tag rationalTag(int tag, boolean signed, int... numDen) {
    Bytes b = new Bytes();
    for (int x : numDen) b.u32le(x & 0xFFFFFFFFL);
    return new Tag(tag, signed ? 10 : 5, numDen.length / 2, b.toBytes());
  }

  /**
   * Un DNG piccolo e decodificabile: mosaico Bayer RGGB a 16 bit, non compresso, in un solo IFD, {@code w x h} pixel, con
   * quattro quadranti (rosso, verde, blu, giallo) cosi' si vede come e' orientato. Il profilo colore e' sRGB (D65) e la
   * bilanciatura neutra: un decodificatore DNG lo sviluppa in colori riconoscibili.
   *
   * <p>Non e' un DNG di fotocamera: ha solo i tag che l'SDK DNG di Android richiede. Se un telefono lo rifiutasse, e' un
   * limite del campione da correggere qui (F6 di docs/fase4-progetto.md).
   *
   * @param orientation il valore EXIF {@code Orientation} (1..8) che il file dichiara: il decodificatore di sistema non lo applica.
   */
  public static byte[] smallDng(int w, int h, int orientation) {
    if (w % 2 != 0 || h % 2 != 0) throw new IllegalArgumentException("il mosaico RGGB vuole lati pari");
    Bytes raw = new Bytes();
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        boolean left = x < w / 2;
        boolean top = y < h / 2;
        // Colore lineare del quadrante, 0..1: rosso, verde, blu, giallo.
        double r = top ? (left ? 1.0 : 0.0) : (left ? 0.0 : 1.0);
        double g = top ? (left ? 0.0 : 1.0) : (left ? 0.0 : 1.0);
        double b = top ? 0.0 : (left ? 1.0 : 0.0);
        double v;
        boolean evenRow = (y & 1) == 0;
        boolean evenCol = (x & 1) == 0;
        if (evenRow && evenCol) v = r; // R
        else if (!evenRow && !evenCol) v = b; // B
        else v = g; // G
        raw.u16le((int) Math.round(0.02 * 65535 + 0.9 * 65535 * v));
      }
    }
    byte[] data = raw.toBytes();

    List<Tag> tags = new ArrayList<>();
    tags.add(longTag(0x00FE, 0));
    tags.add(longTag(0x0100, w));
    tags.add(longTag(0x0101, h));
    tags.add(shortTag(0x0102, 16));
    tags.add(shortTag(0x0103, 1));
    tags.add(shortTag(0x0106, 32803));
    tags.add(asciiTag(0x010F, "MCG"));
    tags.add(asciiTag(0x0110, "MCG Prova DNG"));
    tags.add(longTag(0x0111, 0)); // StripOffsets: si sistema dopo
    tags.add(shortTag(0x0112, orientation));
    tags.add(shortTag(0x0115, 1));
    tags.add(longTag(0x0116, h));
    tags.add(longTag(0x0117, data.length));
    tags.add(shortTag(0x011C, 1));
    tags.add(shortTag(0x828D, 2, 2));
    tags.add(byteTag(0x828E, 0, 1, 1, 2));
    tags.add(byteTag(0xC612, 1, 4, 0, 0));
    tags.add(byteTag(0xC613, 1, 1, 0, 0));
    tags.add(asciiTag(0xC614, "MCG Prova DNG"));
    tags.add(byteTag(0xC616, 0, 1, 2));
    tags.add(shortTag(0xC617, 1));
    tags.add(shortTag(0xC61A, 0));
    tags.add(longTag(0xC61D, 65535));
    // ColorMatrix1: da XYZ (D65) a RGB lineare sRGB.
    int[] cm = {32406, 10000, -15372, 10000, -4986, 10000, -9689, 10000, 18758, 10000, 415, 10000, 557, 10000, -2040, 10000, 10570, 10000};
    tags.add(rationalTag(0xC621, true, cm));
    tags.add(rationalTag(0xC628, false, 1, 1, 1, 1, 1, 1));
    tags.add(shortTag(0xC65A, 21));
    tags.sort((a, b) -> Integer.compare(a.tag, b.tag));

    int ifdSize = 2 + 12 * tags.size() + 4;
    int extra = 0;
    for (Tag t : tags) if (t.value.length > 4) extra += (t.value.length + 1) & ~1;
    int dataStart = 8 + ifdSize + extra;
    // StripOffsets
    for (int i = 0; i < tags.size(); i++) {
      if (tags.get(i).tag == 0x0111) tags.set(i, longTag(0x0111, dataStart));
    }

    Bytes out = new Bytes().ascii("II").u16le(42).u32le(8);
    out.u16le(tags.size());
    Bytes overflow = new Bytes();
    int overflowAt = 8 + ifdSize;
    for (Tag t : tags) {
      out.u16le(t.tag).u16le(t.type).u32le(t.count);
      if (t.value.length <= 4) {
        out.raw(t.value).zeros(4 - t.value.length);
      } else {
        out.u32le(overflowAt + overflow.size());
        overflow.raw(t.value);
        if ((t.value.length & 1) == 1) overflow.u8(0);
      }
    }
    out.u32le(0);
    out.raw(overflow.toBytes());
    out.raw(data);
    return out.toBytes();
  }

  // ---- HEIC da un flusso HEVC ----------------------------------------------------------------------------------

  /**
   * Un HEIC decodificabile ricavato da un MP4 con un solo fotogramma HEVC (per esempio quello di
   * {@code ffmpeg -i foto.png -frames:v 1 -c:v libx265 -tag:v hvc1 out.mp4}): prende da li' la configurazione {@code hvcC}, le
   * dimensioni e i byte del fotogramma e li ricompone in un contenitore HEIF.
   *
   * @param irot quarti di giro antiorari dichiarati nel contenitore ({@code irot}), o -1 per nessuno
   * @param exifOrientation l'orientamento EXIF (1..8) scritto in una voce Exif, o 0 per nessuna voce
   * @throws IllegalArgumentException se l'MP4 non ha la forma attesa
   */
  public static byte[] heicFromHevcMp4(byte[] mp4, int irot, int exifOrientation) {
    int hvc = indexOf(mp4, Bytes.ascii4("hvcC"), 0);
    if (hvc < 4) throw new IllegalArgumentException("nessun hvcC");
    long hvcSize = u32(mp4, hvc - 4);
    byte[] hvcPayload = java.util.Arrays.copyOfRange(mp4, hvc + 4, (int) (hvc - 4 + hvcSize));
    int hvc1 = indexOf(mp4, Bytes.ascii4("hvc1"), 0);
    if (hvc1 < 4) hvc1 = indexOf(mp4, Bytes.ascii4("hev1"), 0);
    if (hvc1 < 4) throw new IllegalArgumentException("nessun hvc1");
    int width = ((mp4[hvc1 + 4 + 24] & 0xFF) << 8) | (mp4[hvc1 + 4 + 25] & 0xFF);
    int height = ((mp4[hvc1 + 4 + 26] & 0xFF) << 8) | (mp4[hvc1 + 4 + 27] & 0xFF);
    int stsz = indexOf(mp4, Bytes.ascii4("stsz"), 0);
    int stco = indexOf(mp4, Bytes.ascii4("stco"), 0);
    if (stsz < 0 || stco < 0) throw new IllegalArgumentException("nessuna tabella dei campioni");
    long fixedSize = u32(mp4, stsz + 4 + 4);
    long sampleSize = fixedSize != 0 ? fixedSize : u32(mp4, stsz + 4 + 12);
    long sampleOffset = u32(mp4, stco + 4 + 8);
    byte[] sample = java.util.Arrays.copyOfRange(mp4, (int) sampleOffset, (int) (sampleOffset + sampleSize));

    IsoFixtures.HeicSpec spec = new IsoFixtures.HeicSpec();
    spec.width = width;
    spec.height = height;
    spec.hvcC = hvcPayload;
    spec.imageData = sample;
    if (irot >= 0) spec.irot = irot;
    if (exifOrientation > 0) {
      ExifWriter.Spec exif = new ExifWriter.Spec();
      exif.make = "MCG";
      exif.model = "MCG Prova HEIC";
      exif.dateOriginal = "2024:03:12 10:15:30";
      exif.offsetOriginal = "+01:00";
      exif.orientation = exifOrientation;
      spec.exif = exif;
    }
    return IsoFixtures.heic(spec);
  }

  private static long u32(byte[] b, int i) {
    return ((b[i] & 0xFFL) << 24) | ((b[i + 1] & 0xFFL) << 16) | ((b[i + 2] & 0xFFL) << 8) | (b[i + 3] & 0xFFL);
  }

  private static int indexOf(byte[] haystack, byte[] needle, int from) {
    outer:
    for (int i = from; i <= haystack.length - needle.length; i++) {
      for (int j = 0; j < needle.length; j++) if (haystack[i + j] != needle[j]) continue outer;
      return i;
    }
    return -1;
  }
}
