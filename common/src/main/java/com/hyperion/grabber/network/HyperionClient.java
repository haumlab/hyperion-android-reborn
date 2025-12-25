package com.hyperion.grabber.common.network;

import java.io.IOException;

public interface HyperionClient {
    boolean isConnected();
    void disconnect() throws IOException;
    void clear(int priority) throws IOException;
    void clearAll() throws IOException;
    void setColor(int color, int priority) throws IOException;
    void setColor(int color, int priority, int duration_ms) throws IOException;
    void setImage(byte[] data, int width, int height, int priority) throws IOException;
    void setImage(byte[] data, int width, int height, int priority, int duration_ms) throws IOException;
}
