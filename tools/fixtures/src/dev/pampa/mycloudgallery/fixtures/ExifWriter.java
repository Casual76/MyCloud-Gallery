package dev.pampa.mycloudgallery.fixtures;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Scrive un blocco TIFF/EXIF (IFD0, IFD Exif, IFD GPS, IFD1 con la miniatura) da una descrizione {@link Spec}. È l'unico
 * scrittore di EXIF dei file di prova: lo usano i test di core-metadata e il generatore del set per il telefono.
 *
 * <p>Indipendente dal lettore di produzione (che è in Kotlin): i test lo confrontano anche con ExifInterface di
 * Android, così un errore uguale da una parte e dall'altra non passa inosservato.
 */
public final class ExifWriter {

  private ExifWriter() {}

  /** Cio' che si vuole scrivere. I campi nulli non si scrivono. */
  public static final class Spec {
    public boolean bigEndian = false;
    public String make;
    public String model;
    public String lens;
    public String software;
    public Integer orientation;
    /** IFD0 {@code DateTime} (0x0132): la data di ultima modifica, che l'app non usa. */
    public String dateTime;
    public String dateOriginal;
    public String offsetOriginal;
    public String subSecOriginal;
    public String dateDigitized;
    public String offsetDigitized;
    public String subSecDigitized;
    public Double latitude;
    public Double longitude;
    public Double altitude;
    /** {@code GPSDateStamp}, per esempio "2024:03:12". */
    public String gpsDate;
    /** {@code GPSTimeStamp}: ore, minuti, secondi. */
    public int[] gpsTime;
    public Double fNumber;
    /** Il tempo di posa come numeratore e denominatore ({1, 250} = 1/250 s). */
    public int[] exposure;
    public Integer iso;
    public Double focalMm;
    public Integer flash;
    public Integer pixelX;
    public Integer pixelY;
    /** Un JPEG da mettere nell'IFD1 come miniatura. */
    public byte[] thumbnailJpeg;
  }

  private static final int ASCII = 2;
  private static final int SHORT = 3;
  private static final int LONG = 4;
  private static final int RATIONAL = 5;
  private static final int BYTE = 1;
  private static final int UNDEFINED = 7;

  private static final class Entry {
    final int tag;
    final int type;
    final int count;
    final byte[] raw;
    final long[] nums;

    Entry(int tag, int type, int count, byte[] raw, long[] nums) {
      this.tag = tag;
      this.type = type;
      this.count = count;
      this.raw = raw;
      this.nums = nums;
    }

    static Entry ascii(int tag, String s) {
      byte[] b = new Bytes().ascii(s).u8(0).toBytes();
      return new Entry(tag, ASCII, b.length, b, null);
    }

    static Entry bytes(int tag, byte[] b) {
      return new Entry(tag, BYTE, b.length, b, null);
    }

    static Entry undefined(int tag, byte[] b) {
      return new Entry(tag, UNDEFINED, b.length, b, null);
    }

    static Entry shortValue(int tag, int v) {
      return new Entry(tag, SHORT, 1, null, new long[] {v});
    }

    static Entry longValue(int tag, long v) {
      return new Entry(tag, LONG, 1, null, new long[] {v});
    }

    static Entry rational(int tag, long num, long den) {
      return new Entry(tag, RATIONAL, 1, null, new long[] {num, den});
    }

    static Entry rationals(int tag, long... pairs) {
      return new Entry(tag, RATIONAL, pairs.length / 2, null, pairs);
    }

    byte[] value(boolean be) {
      Bytes b = new Bytes();
      switch (type) {
        case ASCII:
        case BYTE:
        case UNDEFINED:
          return raw;
        case SHORT:
          for (long n : nums) b.u16((int) n, be);
          return b.toBytes();
        case LONG:
        case RATIONAL:
          for (long n : nums) b.u32(n, be);
          return b.toBytes();
        default:
          throw new IllegalStateException("tipo " + type);
      }
    }

    int valueSize() {
      return value(false).length;
    }
  }

  private static int ifdSize(List<Entry> entries) {
    int size = 2 + 12 * entries.size() + 4;
    for (Entry e : entries) {
      int len = e.valueSize();
      if (len > 4) size += (len + 1) & ~1;
    }
    return size;
  }

  private static void writeIfd(Bytes out, List<Entry> entries, int start, int nextIfd, boolean be) {
    entries.sort(Comparator.comparingInt(e -> e.tag));
    out.u16(entries.size(), be);
    int dataOffset = start + 2 + 12 * entries.size() + 4;
    Bytes data = new Bytes();
    for (Entry e : entries) {
      byte[] v = e.value(be);
      out.u16(e.tag, be).u16(e.type, be).u32(e.count, be);
      if (v.length <= 4) {
        out.raw(v).zeros(4 - v.length);
      } else {
        out.u32(dataOffset + data.size(), be);
        data.raw(v);
        if ((v.length & 1) == 1) data.u8(0);
      }
    }
    out.u32(nextIfd, be);
    out.raw(data.toBytes());
  }

  /** Il blocco TIFF (comincia con "II*" o "MM*"), senza l'intestazione "Exif\0\0" dei JPEG. */
  public static byte[] tiff(Spec s) {
    boolean be = s.bigEndian;
    List<Entry> ifd0 = new ArrayList<>();
    List<Entry> exif = new ArrayList<>();
    List<Entry> gps = new ArrayList<>();
    List<Entry> ifd1 = new ArrayList<>();

    if (s.make != null) ifd0.add(Entry.ascii(0x010F, s.make));
    if (s.model != null) ifd0.add(Entry.ascii(0x0110, s.model));
    if (s.orientation != null) ifd0.add(Entry.shortValue(0x0112, s.orientation));
    if (s.software != null) ifd0.add(Entry.ascii(0x0131, s.software));
    if (s.dateTime != null) ifd0.add(Entry.ascii(0x0132, s.dateTime));

    if (s.exposure != null) exif.add(Entry.rational(0x829A, s.exposure[0], s.exposure[1]));
    if (s.fNumber != null) exif.add(Entry.rational(0x829D, Math.round(s.fNumber * 10), 10));
    if (s.iso != null) exif.add(Entry.shortValue(0x8827, s.iso));
    if (s.dateOriginal != null) exif.add(Entry.ascii(0x9003, s.dateOriginal));
    if (s.dateDigitized != null) exif.add(Entry.ascii(0x9004, s.dateDigitized));
    if (s.offsetOriginal != null) exif.add(Entry.ascii(0x9011, s.offsetOriginal));
    if (s.offsetDigitized != null) exif.add(Entry.ascii(0x9012, s.offsetDigitized));
    if (s.flash != null) exif.add(Entry.shortValue(0x9209, s.flash));
    if (s.focalMm != null) exif.add(Entry.rational(0x920A, Math.round(s.focalMm * 10), 10));
    if (s.subSecOriginal != null) exif.add(Entry.ascii(0x9291, s.subSecOriginal));
    if (s.subSecDigitized != null) exif.add(Entry.ascii(0x9292, s.subSecDigitized));
    if (s.pixelX != null) exif.add(Entry.longValue(0xA002, s.pixelX));
    if (s.pixelY != null) exif.add(Entry.longValue(0xA003, s.pixelY));
    if (s.lens != null) exif.add(Entry.ascii(0xA434, s.lens));

    if (s.latitude != null && s.longitude != null) {
      gps.add(Entry.bytes(0x0000, new byte[] {2, 3, 0, 0}));
      gps.add(Entry.ascii(0x0001, s.latitude < 0 ? "S" : "N"));
      gps.add(Entry.rationals(0x0002, dms(Math.abs(s.latitude))));
      gps.add(Entry.ascii(0x0003, s.longitude < 0 ? "W" : "E"));
      gps.add(Entry.rationals(0x0004, dms(Math.abs(s.longitude))));
      if (s.altitude != null) {
        gps.add(Entry.bytes(0x0005, new byte[] {(byte) (s.altitude < 0 ? 1 : 0)}));
        gps.add(Entry.rational(0x0006, Math.round(Math.abs(s.altitude) * 100), 100));
      }
    }
    if (s.gpsTime != null) {
      gps.add(Entry.rationals(0x0007, s.gpsTime[0], 1, s.gpsTime[1], 1, s.gpsTime[2], 1));
    }
    if (s.gpsDate != null) gps.add(Entry.ascii(0x001D, s.gpsDate));

    boolean hasExif = !exif.isEmpty();
    boolean hasGps = !gps.isEmpty();
    boolean hasIfd1 = s.thumbnailJpeg != null;

    // Le dimensioni non dipendono dai valori dei puntatori (LONG in linea): si calcolano gli offset prima di scriverli.
    if (hasExif) ifd0.add(Entry.longValue(0x8769, 0));
    if (hasGps) ifd0.add(Entry.longValue(0x8825, 0));
    int offset = 8;
    int offIfd0 = offset;
    offset += ifdSize(ifd0);
    int offExif = offset;
    if (hasExif) offset += ifdSize(exif);
    int offGps = offset;
    if (hasGps) offset += ifdSize(gps);
    int offIfd1 = offset;
    if (hasIfd1) {
      ifd1.add(Entry.shortValue(0x0103, 6));
      ifd1.add(Entry.longValue(0x0201, 0));
      ifd1.add(Entry.longValue(0x0202, s.thumbnailJpeg.length));
      offset += ifdSize(ifd1);
    }
    int offThumb = offset;
    if (hasExif) replace(ifd0, Entry.longValue(0x8769, offExif));
    if (hasGps) replace(ifd0, Entry.longValue(0x8825, offGps));
    if (hasIfd1) replace(ifd1, Entry.longValue(0x0201, offThumb));

    Bytes out = new Bytes();
    out.ascii(be ? "MM" : "II").u16(42, be).u32(offIfd0, be);
    writeIfd(out, ifd0, offIfd0, hasIfd1 ? offIfd1 : 0, be);
    if (hasExif) writeIfd(out, exif, offExif, 0, be);
    if (hasGps) writeIfd(out, gps, offGps, 0, be);
    if (hasIfd1) {
      writeIfd(out, ifd1, offIfd1, 0, be);
      out.raw(s.thumbnailJpeg);
    }
    return out.toBytes();
  }

  private static void replace(List<Entry> list, Entry replacement) {
    for (int i = 0; i < list.size(); i++) {
      if (list.get(i).tag == replacement.tag) {
        list.set(i, replacement);
        return;
      }
    }
  }

  private static long[] dms(double degrees) {
    long deg = (long) degrees;
    double rest = (degrees - deg) * 60.0;
    long min = (long) rest;
    double sec = (rest - min) * 60.0;
    return new long[] {deg, 1, min, 1, Math.round(sec * 10000), 10000};
  }

  /** Il blocco di un segmento APP1 del JPEG: "Exif\0\0" e il TIFF. */
  public static byte[] jpegApp1Payload(Spec s) {
    return new Bytes().ascii("Exif").u8(0).u8(0).raw(tiff(s)).toBytes();
  }

  /** Per la voce {@code Exif} di un HEIC: scarto (6) + "Exif\0\0" + TIFF. */
  public static byte[] heifItemPayload(Spec s) {
    return new Bytes().u32be(6).ascii("Exif").u8(0).u8(0).raw(tiff(s)).toBytes();
  }
}
