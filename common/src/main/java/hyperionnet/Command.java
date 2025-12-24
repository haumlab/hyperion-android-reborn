package hyperionnet;

@SuppressWarnings("unused")
public final class Command {
  private Command() { }
  public static final byte NONE = 0;
  public static final byte Color = 1;
  public static final byte Image = 2;
  public static final byte Clear = 3;
  public static final byte Register = 4;

  public static final String[] names = { "NONE", "Color", "Image", "Clear", "Register", };
  public static String name(int e) { return names[e]; }
}
