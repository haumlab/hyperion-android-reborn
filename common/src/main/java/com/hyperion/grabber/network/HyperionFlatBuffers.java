package com.hyperion.grabber.common.network;

import com.google.flatbuffers.FlatBufferBuilder;
import hyperionnet.Clear;
import hyperionnet.Color;
import hyperionnet.Command;
import hyperionnet.Image;
import hyperionnet.ImageType;
import hyperionnet.RawImage;
import hyperionnet.Register;
import hyperionnet.Reply;
import hyperionnet.Request;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;

public class HyperionFlatBuffers implements HyperionClient {
    private final int TIMEOUT = 1000;
    private final Socket mSocket;
    private final int mPriority;
    private final FlatBufferBuilder mBuilder;

    public HyperionFlatBuffers(String address, int port, int priority) throws IOException {
        mSocket = new Socket();
        mSocket.connect(new InetSocketAddress(address, port), TIMEOUT);
        mPriority = priority;
        mBuilder = new FlatBufferBuilder(1024);
        register();
    }

    private void register() throws IOException {
        mBuilder.clear();
        int originOffset = mBuilder.createString("HyperionAndroidGrabber");
        int registerOffset = Register.createRegister(mBuilder, originOffset, mPriority);
        int requestOffset = Request.createRequest(mBuilder, Command.Register, registerOffset);
        Request.finishRequestBuffer(mBuilder, requestOffset);
        sendRequest(mBuilder.dataBuffer());
    }

    @Override
    public boolean isConnected() {
        return mSocket != null && mSocket.isConnected();
    }

    @Override
    public void disconnect() throws IOException {
        if (isConnected()) {
            mSocket.close();
        }
    }

    @Override
    public void clear(int priority) throws IOException {
        mBuilder.clear();
        int clearOffset = Clear.createClear(mBuilder, priority);
        int requestOffset = Request.createRequest(mBuilder, Command.Clear, clearOffset);
        Request.finishRequestBuffer(mBuilder, requestOffset);
        sendRequest(mBuilder.dataBuffer());
    }

    @Override
    public void clearAll() throws IOException {
        clear(-1);
    }

    @Override
    public void setColor(int color, int priority) throws IOException {
        setColor(color, priority, -1);
    }

    @Override
    public void setColor(int color, int priority, int duration_ms) throws IOException {
        mBuilder.clear();
        int colorOffset = Color.createColor(mBuilder, color, duration_ms);
        int requestOffset = Request.createRequest(mBuilder, Command.Color, colorOffset);
        Request.finishRequestBuffer(mBuilder, requestOffset);
        sendRequest(mBuilder.dataBuffer());
    }

    @Override
    public void setImage(byte[] data, int width, int height, int priority) throws IOException {
        setImage(data, width, height, priority, -1);
    }

    @Override
    public void setImage(byte[] data, int width, int height, int priority, int duration_ms) throws IOException {
        mBuilder.clear();
        int dataOffset = RawImage.createDataVector(mBuilder, data);
        int rawImageOffset = RawImage.createRawImage(mBuilder, dataOffset, width, height);
        int imageOffset = Image.createImage(mBuilder, ImageType.RawImage, rawImageOffset, duration_ms);
        int requestOffset = Request.createRequest(mBuilder, Command.Image, imageOffset);
        Request.finishRequestBuffer(mBuilder, requestOffset);
        sendRequest(mBuilder.dataBuffer());
    }

    private void sendRequest(ByteBuffer bb) throws IOException {
        if (isConnected()) {
            int size = bb.remaining();
            byte[] header = new byte[4];
            header[0] = (byte)((size >> 24) & 0xFF);
            header[1] = (byte)((size >> 16) & 0xFF);
            header[2] = (byte)((size >>  8) & 0xFF);
            header[3] = (byte)((size  ) & 0xFF);

            OutputStream output = mSocket.getOutputStream();
            output.write(header);
            
            byte[] data = new byte[bb.remaining()];
            bb.get(data);
            output.write(data);
            output.flush();

            receiveReply();
        }
    }

    private void receiveReply() throws IOException {
        // We don't really need to parse the reply for now, but we should consume it
        // to keep the socket clean.
        if (mSocket.getInputStream().available() >= 4) {
            byte[] header = new byte[4];
            int read = mSocket.getInputStream().read(header, 0, 4);
            if (read == 4) {
                int size = ((header[0] & 0xFF) << 24) | ((header[1] & 0xFF) << 16) | ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
                if (size > 0) {
                    byte[] data = new byte[size];
                    mSocket.getInputStream().read(data, 0, size);
                    // Reply reply = Reply.getRootAsReply(ByteBuffer.wrap(data));
                }
            }
        }
    }
}
