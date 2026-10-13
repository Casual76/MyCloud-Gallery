package dev.pampa.mycloudgallery.fixtures;

/** Pacchetti XMP di prova: motion photo (Google v1, in due scritture, e MicroVideo), panorama, sfera e Ultra HDR. */
public final class XmpPackets {

  private XmpPackets() {}

  private static final String NS_CAMERA = "http://ns.google.com/photos/1.0/camera/";
  private static final String NS_CONTAINER = "http://ns.google.com/photos/1.0/container/";
  private static final String NS_ITEM = "http://ns.google.com/photos/1.0/container/item/";
  private static final String NS_GPANO = "http://ns.google.com/photos/1.0/panorama/";
  private static final String NS_HDRGM = "http://ns.adobe.com/hdr-gain-map/1.0/";

  public static String wrap(String namespaces, String attributes, String inner) {
    return "<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n"
        + "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"MCG fixtures\">\n"
        + " <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n"
        + "  <rdf:Description rdf:about=\"\" " + namespaces + " " + attributes + ">\n"
        + inner
        + "  </rdf:Description>\n"
        + " </rdf:RDF>\n"
        + "</x:xmpmeta>\n"
        + "<?xpacket end=\"w\"?>";
  }

  /** Google v1 come lo scrive un Pixel: voci della directory con gli attributi, prefisso {@code GCamera:}. */
  public static String googleMotionAttributes(long videoLength, long presentationUs) {
    String ns = "xmlns:GCamera=\"" + NS_CAMERA + "\" xmlns:Container=\"" + NS_CONTAINER + "\" xmlns:Item=\"" + NS_ITEM + "\"";
    String attrs = "GCamera:MotionPhoto=\"1\" GCamera:MotionPhotoVersion=\"1\" GCamera:MotionPhotoPresentationTimestampUs=\"" + presentationUs + "\"";
    String inner = "   <Container:Directory><rdf:Seq>\n"
        + "    <rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Mime=\"image/jpeg\" Item:Semantic=\"Primary\" Item:Length=\"0\" Item:Padding=\"0\"/></rdf:li>\n"
        + "    <rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Mime=\"video/mp4\" Item:Semantic=\"MotionPhoto\" Item:Length=\"" + videoLength + "\" Item:Padding=\"0\"/></rdf:li>\n"
        + "   </rdf:Seq></Container:Directory>\n";
    return wrap(ns, attrs, inner);
  }

  /** Google v1 come lo descrive la specifica: prefisso {@code Camera:} e voci a elementi. */
  public static String googleMotionElements(long videoLength) {
    String ns = "xmlns:Camera=\"" + NS_CAMERA + "\" xmlns:Container=\"" + NS_CONTAINER + "\" xmlns:Item=\"" + NS_ITEM + "\"";
    String attrs = "Camera:MotionPhoto=\"1\" Camera:MotionPhotoVersion=\"1\"";
    String inner = "   <Container:Directory><rdf:Seq>\n"
        + "    <rdf:li rdf:parseType=\"Resource\"><Item:Mime>image/jpeg</Item:Mime><Item:Semantic>Primary</Item:Semantic><Item:Padding>0</Item:Padding></rdf:li>\n"
        + "    <rdf:li rdf:parseType=\"Resource\"><Item:Mime>video/mp4</Item:Mime><Item:Semantic>MotionPhoto</Item:Semantic><Item:Length>" + videoLength + "</Item:Length></rdf:li>\n"
        + "   </rdf:Seq></Container:Directory>\n";
    return wrap(ns, attrs, inner);
  }

  /** Il formato precedente di Google: {@code MicroVideoOffset} = byte dalla fine del file all'inizio del video. */
  public static String microVideo(long offsetFromEnd, long presentationUs) {
    String ns = "xmlns:GCamera=\"" + NS_CAMERA + "\"";
    String attrs = "GCamera:MicroVideo=\"1\" GCamera:MicroVideoVersion=\"1\" GCamera:MicroVideoOffset=\"" + offsetFromEnd
        + "\" GCamera:MicroVideoPresentationTimestampUs=\"" + presentationUs + "\"";
    return wrap(ns, attrs, "");
  }

  /** Dichiara una motion photo ma non dice dove sta il video (o il video non c'è più). */
  public static String motionFlagOnly() {
    return wrap("xmlns:GCamera=\"" + NS_CAMERA + "\"", "GCamera:MotionPhoto=\"1\" GCamera:MotionPhotoVersion=\"1\"", "");
  }

  public static String panorama() {
    return wrap("xmlns:GPano=\"" + NS_GPANO + "\"", "GPano:UsePanoramaViewer=\"True\" GPano:ProjectionType=\"cylindrical\"", "");
  }

  /** Una sfera completa: equirettangolare, ritaglio uguale al totale. */
  public static String sphere(int fullWidth, int fullHeight) {
    return wrap(
        "xmlns:GPano=\"" + NS_GPANO + "\"",
        "GPano:UsePanoramaViewer=\"True\" GPano:ProjectionType=\"equirectangular\" GPano:FullPanoWidthPixels=\"" + fullWidth
            + "\" GPano:FullPanoHeightPixels=\"" + fullHeight + "\" GPano:CroppedAreaImageWidthPixels=\"" + fullWidth
            + "\" GPano:CroppedAreaImageHeightPixels=\"" + fullHeight + "\"",
        "");
  }

  /** Un panorama equirettangolare parziale (360 gradi in orizzontale, mezza altezza in verticale): non è una sfera. */
  public static String partialEquirectangular(int fullWidth, int fullHeight, int croppedHeight) {
    return wrap(
        "xmlns:GPano=\"" + NS_GPANO + "\"",
        "GPano:ProjectionType=\"equirectangular\" GPano:FullPanoWidthPixels=\"" + fullWidth + "\" GPano:FullPanoHeightPixels=\""
            + fullHeight + "\" GPano:CroppedAreaImageWidthPixels=\"" + fullWidth + "\" GPano:CroppedAreaImageHeightPixels=\"" + croppedHeight + "\"",
        "");
  }

  /** Ultra HDR: {@code hdrgm:Version} e la voce {@code GainMap} nella directory. */
  public static String ultraHdr() {
    String ns = "xmlns:hdrgm=\"" + NS_HDRGM + "\" xmlns:Container=\"" + NS_CONTAINER + "\" xmlns:Item=\"" + NS_ITEM + "\"";
    String inner = "   <Container:Directory><rdf:Seq>\n"
        + "    <rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Semantic=\"Primary\" Item:Mime=\"image/jpeg\"/></rdf:li>\n"
        + "    <rdf:li rdf:parseType=\"Resource\"><Container:Item Item:Semantic=\"GainMap\" Item:Mime=\"image/jpeg\" Item:Length=\"2048\"/></rdf:li>\n"
        + "   </rdf:Seq></Container:Directory>\n";
    return wrap(ns, "hdrgm:Version=\"1.0\"", inner);
  }
}
