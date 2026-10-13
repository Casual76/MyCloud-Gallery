package dev.pampa.mycloudgallery.fixtures;

import java.util.ArrayList;
import java.util.List;

/**
 * File ISO BMFF di prova: HEIC (struttura vera, con {@code meta}, {@code iloc}, {@code ipco}, Exif e XMP; i byte
 * dell'immagine sono finti, quindi non si decodificano) e MP4 (riquadri {@code ftyp}, {@code moov} con {@code mvhd},
 * {@code mdat}; nessuna traccia, quindi non si riproducono).
 */
public final class IsoFixtures {

  private IsoFixtures() {}

  public static byte[] box(String type, byte[]... children) {
    Bytes payload = new Bytes();
    for (byte[] c : children) payload.raw(c);
    byte[] p = payload.toBytes();
    return new Bytes().u32be(8L + p.length).ascii(type).raw(p).toBytes();
  }

  public static byte[] fullBox(String type, int version, int flags, byte[]... children) {
    Bytes head = new Bytes().u8(version).u8(flags >> 16).u8(flags >> 8).u8(flags);
    byte[][] all = new byte[children.length + 1][];
    all[0] = head.toBytes();
    System.arraycopy(children, 0, all, 1, children.length);
    return box(type, all);
  }

  private static byte[] u16(int v) {
    return new Bytes().u16be(v).toBytes();
  }

  private static byte[] u32(long v) {
    return new Bytes().u32be(v).toBytes();
  }

  private static byte[] ascii(String s) {
    return Bytes.ascii4(s);
  }

  // ---- HEIC ----------------------------------------------------------------------------------------------------

  /** Cio' che un HEIC di prova deve dichiarare. */
  public static final class HeicSpec {
    public int width = 64;
    public int height = 48;
    /** {@code irot}: quarti di giro **antiorari** (0..3); null = nessuna proprieta'. */
    public Integer irot;
    /** {@code imir}: 0 = asse verticale (specchio sinistra-destra), 1 = asse orizzontale; null = nessuna proprieta'. */
    public Integer imirAxis;
    public ExifWriter.Spec exif;
    public String xmp;
    /** Riquadri aggiunti in fondo al file (per esempio un {@code mpvd} con il video di una motion photo). */
    public byte[] trailing;
    public String majorBrand = "heic";
  }

  public static byte[] heic(HeicSpec s) {
    byte[] ftyp = box("ftyp", ascii(s.majorBrand), u32(0), ascii("mif1"), ascii(s.majorBrand));
    byte[] image = new byte[32];
    for (int i = 0; i < image.length; i++) image[i] = (byte) (0x40 + i);
    byte[] exifItem = s.exif != null ? ExifWriter.heifItemPayload(s.exif) : null;
    byte[] xmpItem = s.xmp != null ? s.xmp.getBytes(java.nio.charset.StandardCharsets.UTF_8) : null;

    // Prima passata con gli offset a zero per misurare `meta`, poi quella vera.
    byte[] meta = meta(s, image.length, exifItem, xmpItem, 0);
    long mdatPayloadStart = ftyp.length + meta.length + 8L;
    meta = meta(s, image.length, exifItem, xmpItem, mdatPayloadStart);

    Bytes mdatPayload = new Bytes().raw(image);
    if (exifItem != null) mdatPayload.raw(exifItem);
    if (xmpItem != null) mdatPayload.raw(xmpItem);
    Bytes out = new Bytes().raw(ftyp).raw(meta).raw(box("mdat", mdatPayload.toBytes()));
    if (s.trailing != null) out.raw(s.trailing);
    return out.toBytes();
  }

  private static byte[] meta(HeicSpec s, int imageLength, byte[] exifItem, byte[] xmpItem, long mdatPayloadStart) {
    List<byte[]> infe = new ArrayList<>();
    infe.add(fullBox("infe", 2, 0, u16(1), u16(0), ascii("hvc1"), new byte[] {0}));
    if (exifItem != null) infe.add(fullBox("infe", 2, 0, u16(2), u16(0), ascii("Exif"), new byte[] {0}));
    if (xmpItem != null) {
      infe.add(fullBox("infe", 2, 0, u16(3), u16(0), ascii("mime"), new byte[] {0}, ascii("application/rdf+xml"), new byte[] {0}));
    }
    byte[][] iinfParts = new byte[infe.size() + 1][];
    iinfParts[0] = u16(infe.size());
    for (int i = 0; i < infe.size(); i++) iinfParts[i + 1] = infe.get(i);

    long offset = mdatPayloadStart;
    List<byte[]> locations = new ArrayList<>();
    locations.add(new Bytes().u16be(1).u16be(0).u16be(0).u16be(1).u32be(offset).u32be(imageLength).toBytes());
    offset += imageLength;
    if (exifItem != null) {
      locations.add(new Bytes().u16be(2).u16be(0).u16be(0).u16be(1).u32be(offset).u32be(exifItem.length).toBytes());
      offset += exifItem.length;
    }
    if (xmpItem != null) {
      locations.add(new Bytes().u16be(3).u16be(0).u16be(0).u16be(1).u32be(offset).u32be(xmpItem.length).toBytes());
    }
    Bytes ilocBody = new Bytes().u8(0x44).u8(0x00).u16be(locations.size());
    for (byte[] l : locations) ilocBody.raw(l);

    List<byte[]> props = new ArrayList<>();
    List<Integer> assoc = new ArrayList<>();
    props.add(fullBox("ispe", 0, 0, u32(s.width), u32(s.height)));
    assoc.add(props.size());
    if (s.irot != null) {
      props.add(box("irot", new byte[] {(byte) (s.irot & 3)}));
      assoc.add(0x80 | props.size());
    }
    if (s.imirAxis != null) {
      props.add(box("imir", new byte[] {(byte) (s.imirAxis & 1)}));
      assoc.add(0x80 | props.size());
    }
    Bytes ipmaBody = new Bytes().u32be(1).u16be(1).u8(assoc.size());
    for (int a : assoc) ipmaBody.u8(a);
    byte[] ipco = box("ipco", props.toArray(new byte[0][]));
    byte[] iprp = box("iprp", ipco, fullBox("ipma", 0, 0, ipmaBody.toBytes()));

    byte[] hdlr = fullBox("hdlr", 0, 0, u32(0), ascii("pict"), new byte[12], new byte[] {0});
    return fullBox("meta", 0, 0, hdlr, fullBox("pitm", 0, 0, u16(1)), fullBox("iinf", 0, 0, iinfParts), fullBox("iloc", 1, 0, ilocBody.toBytes()), iprp);
  }

  // ---- MP4 -----------------------------------------------------------------------------------------------------

  /** Secondi dal 1 gennaio 1904 (l'epoca di MP4 e QuickTime) a un istante UTC in millisecondi. */
  public static long mp4Seconds(long utcMillis) {
    return utcMillis / 1000 + 2_082_844_800L;
  }

  public static final class Mp4Spec {
    public String brand = "isom";
    /** Data di creazione nel `mvhd`, in secondi dal 1904 (0 = non dichiarata). */
    public long creationSeconds = 0;
    public long durationMs = 3000;
    public int mdatBytes = 2048;
    /** Il `moov` dopo il `mdat` (come le registrazioni di Android) invece che prima. */
    public boolean moovLast = true;
    public boolean includeMoov = true;
    /** Una posizione ISO 6709 ({@code +59.9127+010.7461/}) nel riquadro {@code udta/©xyz}. */
    public String iso6709;
  }

  public static byte[] mp4(Mp4Spec s) {
    byte[] ftyp = box("ftyp", ascii(s.brand), u32(0x200), ascii("isom"), ascii("mp42"));
    byte[] mdat = box("mdat", new byte[s.mdatBytes]);
    Bytes out = new Bytes().raw(ftyp);
    byte[] moov = s.includeMoov ? moov(s) : new byte[0];
    if (!s.moovLast) out.raw(moov);
    out.raw(mdat);
    if (s.moovLast) out.raw(moov);
    return out.toBytes();
  }

  private static byte[] moov(Mp4Spec s) {
    Bytes mvhd = new Bytes().u32be(s.creationSeconds).u32be(s.creationSeconds).u32be(1000).u32be(s.durationMs)
        .u32be(0x00010000L).u16be(0x0100).u16be(0).u32be(0).u32be(0)
        .u32be(0x00010000L).u32be(0).u32be(0).u32be(0).u32be(0x00010000L).u32be(0).u32be(0).u32be(0).u32be(0x40000000L)
        .zeros(24).u32be(2);
    byte[] mvhdBox = fullBox("mvhd", 0, 0, mvhd.toBytes());
    if (s.iso6709 == null) return box("moov", mvhdBox);
    byte[] text = s.iso6709.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
    byte[] xyz = new Bytes().u32be(8L + 4 + text.length).u8(0xA9).ascii("xyz").u16be(text.length).u16be(0x15C7).raw(text).toBytes();
    return box("moov", mvhdBox, box("udta", xyz));
  }
}
