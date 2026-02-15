package com.hyperion.grabber.common.util;

import org.junit.Test;
import java.nio.ByteBuffer;
import static org.junit.Assert.*;

public class BorderProcessorTest {

    @Test
    public void testParseBorderCorrectness() {
        int width = 100;
        int height = 100;
        int rowStride = width * 4; // 4 bytes per pixel (RGBA)
        int pixelStride = 4;

        ByteBuffer buffer = ByteBuffer.allocateDirect(height * rowStride);

        // Fill with black
        for (int i = 0; i < buffer.capacity(); i++) {
            buffer.put(i, (byte) 0);
        }

        // Add non-black pixels to simulate content
        int x = 10;
        int y = 33; // height33percent
        int offset = (y * rowStride) + (x * pixelStride);
        buffer.put(offset, (byte) 255);     // R
        buffer.put(offset + 1, (byte) 255); // G
        buffer.put(offset + 2, (byte) 255); // B

        x = 33;
        y = 10;
        offset = (y * rowStride) + (x * pixelStride);
        buffer.put(offset, (byte) 255);
        buffer.put(offset + 1, (byte) 255);
        buffer.put(offset + 2, (byte) 255);

        BorderProcessor processor = new BorderProcessor(10); // Threshold 10

        // Need to call multiple times to satisfy consistency check
        // Logic requires > MAX_INCONSISTENT_FRAME_COUNT (10) frames to update mPreviousBorder from null
        for (int i = 0; i < 15; i++) {
            buffer.rewind(); // Important! parseBorder rewinds but let's be safe if we were reusing
            // Actually parseBorder uses absolute get or mark/reset.
            // But let's verify parseBorder implementation.
            // It does buffer.position(0).mark() and buffer.reset().
            // So position is preserved.
            processor.parseBorder(buffer, width, height, rowStride, pixelStride);
        }

        BorderProcessor.BorderObject border = processor.getCurrentBorder();

        assertNotNull("Border should not be null after 15 consistent frames", border);
        assertEquals("Horizontal border index mismatch", 10, border.getHorizontalBorderIndex());
        assertEquals("Vertical border index mismatch", 10, border.getVerticalBorderIndex());
    }
}
