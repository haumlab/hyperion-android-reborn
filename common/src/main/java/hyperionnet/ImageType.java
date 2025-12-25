package hyperionnet;

@SuppressWarnings("unused")
public final class ImageType {
  private ImageType() { }
  public static final byte NONE = 0;
  public static final byte RawImage = 1;
  public static final byte NV12Image = 2;

  public static final String[] names = { "NONE", "RawImage", "NV12Image", };
  public static String name(int e) { return names[e]; }
}
