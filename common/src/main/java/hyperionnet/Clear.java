package hyperionnet;

import com.google.flatbuffers.Constants;
import com.google.flatbuffers.FlatBufferBuilder;
import com.google.flatbuffers.Table;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@SuppressWarnings("unused")
public final class Clear extends Table {
  public static void ValidateVersion() { Constants.FLATBUFFERS_23_5_26(); }
  public static Clear getRootAsClear(ByteBuffer _bb) { return getRootAsClear(_bb, new Clear()); }
  public static Clear getRootAsClear(ByteBuffer _bb, Clear obj) { _bb.order(ByteOrder.LITTLE_ENDIAN); return (obj.__assign(_bb.getInt(_bb.position()) + _bb.position(), _bb)); }
  public void __init(int _i, ByteBuffer _bb) { __reset(_i, _bb); }
  public Clear __assign(int _i, ByteBuffer _bb) { __init(_i, _bb); return this; }

  public int priority() { int o = __offset(4); return o != 0 ? bb.getInt(o + bb_pos) : 0; }

  public static int createClear(FlatBufferBuilder builder, int priority) {
    builder.startTable(1);
    Clear.addPriority(builder, priority);
    return Clear.endClear(builder);
  }

  public static void startClear(FlatBufferBuilder builder) { builder.startTable(1); }
  public static void addPriority(FlatBufferBuilder builder, int priority) { builder.addInt(0, priority, 0); }
  public static int endClear(FlatBufferBuilder builder) { return builder.endTable(); }
}
