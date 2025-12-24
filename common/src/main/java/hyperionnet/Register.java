package hyperionnet;

import com.google.flatbuffers.BaseVector;
import com.google.flatbuffers.Constants;
import com.google.flatbuffers.FlatBufferBuilder;
import com.google.flatbuffers.Table;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@SuppressWarnings("unused")
public final class Register extends Table {
  public static void ValidateVersion() { Constants.FLATBUFFERS_23_5_26(); }
  public static Register getRootAsRegister(ByteBuffer _bb) { return getRootAsRegister(_bb, new Register()); }
  public static Register getRootAsRegister(ByteBuffer _bb, Register obj) { _bb.order(ByteOrder.LITTLE_ENDIAN); return (obj.__assign(_bb.getInt(_bb.position()) + _bb.position(), _bb)); }
  public void __init(int _i, ByteBuffer _bb) { __reset(_i, _bb); }
  public Register __assign(int _i, ByteBuffer _bb) { __init(_i, _bb); return this; }

  public String origin() { int o = __offset(4); return o != 0 ? __string(o + bb_pos) : null; }
  public ByteBuffer originAsByteBuffer() { return __vector_as_bytebuffer(4, 1); }
  public ByteBuffer originInByteBuffer(ByteBuffer _bb) { return __vector_in_bytebuffer(_bb, 4, 1); }
  public int priority() { int o = __offset(6); return o != 0 ? bb.getInt(o + bb_pos) : 0; }

  public static int createRegister(FlatBufferBuilder builder, int originOffset, int priority) {
    builder.startTable(2);
    Register.addPriority(builder, priority);
    Register.addOrigin(builder, originOffset);
    return Register.endRegister(builder);
  }

  public static void startRegister(FlatBufferBuilder builder) { builder.startTable(2); }
  public static void addOrigin(FlatBufferBuilder builder, int originOffset) { builder.addOffset(0, originOffset, 0); }
  public static void addPriority(FlatBufferBuilder builder, int priority) { builder.addInt(1, priority, 0); }
  public static int endRegister(FlatBufferBuilder builder) {
    int o = builder.endTable();
    builder.required(o, 4);  // origin
    return o;
  }
}
