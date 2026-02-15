package com.hyperion.grabber.common.util;

import android.graphics.PixelFormat;
import java.nio.ByteBuffer;
import java.util.Objects;

public class BorderProcessor {

    private final int MAX_INCONSISTENT_FRAME_COUNT = 10;
    private final int MAX_UNKNOWN_FRAME_COUNT = 600;
    private final int BORDER_CHANGE_FRAME_COUNT = 50;

    private final int BLACK_THRESHOLD; // unit to detect how black a pixel is [0..255]
    private BorderObject mPreviousBorder;
    private BorderObject mCurrentBorder;
    private int mConsistentFrames = 0;
    private int mInconsistentFrames = 0;

    public BorderProcessor(int blackThreshold) {
        BLACK_THRESHOLD = blackThreshold;
    }

    public BorderObject getCurrentBorder() { return mCurrentBorder; }

    private void checkNewBorder(BorderObject newBorder) {
        if (mPreviousBorder != null && mPreviousBorder.equals(newBorder)) {
            ++mConsistentFrames;
            mInconsistentFrames = 0;
        } else {
            ++mInconsistentFrames;

            if (mInconsistentFrames <= MAX_INCONSISTENT_FRAME_COUNT) {
                return;
            }

            mPreviousBorder = newBorder;
            mConsistentFrames = 0;
        }

        if (mCurrentBorder != null && mCurrentBorder.equals(newBorder)) {
            mInconsistentFrames = 0;
            return;
        }

        if (!newBorder.isKnown()) {
            if (mConsistentFrames == MAX_UNKNOWN_FRAME_COUNT) {
                mCurrentBorder = newBorder;
            }
        } else {
            if (mCurrentBorder == null || !mCurrentBorder.isKnown() ||
                    mConsistentFrames == BORDER_CHANGE_FRAME_COUNT) {
                        mCurrentBorder = newBorder;
            }
        }
    }

    public void parseBorder(ByteBuffer buffer, int width, int height, int rowStride,
                            int pixelStride) {
        // Fallback to RGBA_8888 if not specified (legacy support if any)
        parseBorder(buffer, width, height, rowStride, pixelStride, PixelFormat.RGBA_8888);
    }

    public void parseBorder(ByteBuffer buffer, int width, int height, int rowStride,
                            int pixelStride, int pixelFormat) {
        checkNewBorder( findBorder(buffer, width, height, rowStride, pixelStride, pixelFormat) );
    }

    private BorderObject findBorder(ByteBuffer buffer, int width, int height, int rowStride,
                                    int pixelStride, int pixelFormat) {

        int width33percent = width / 3;
        int width66percent = width33percent * 2;
        int height33percent = height / 3;
        int height66percent = height33percent * 2;
        int yCenter = height / 2;
        int xCenter = width / 2;
        int firstNonBlackYPixelIndex = -1;
        int firstNonBlackXPixelIndex = -1;

        // placeholders for the RGB values of each of the 3 pixel positions we check
        int p1R, p1G, p1B, p2R, p2G, p2B, p3R, p3G, p3B;

        // positions in the byte array that represent 33%, 66%, and center.
        // used when parsing both the X and Y axis of the image
        int pos33percent, pos66percent, posCentered;

        buffer.position(0).mark();

        // Optimization: Pre-calculate invariant multiplications outside the loops
        int height33PercentRowStride = height33percent * rowStride;
        int height66PercentRowStride = height66percent * rowStride;
        int widthPixelStride = width * pixelStride;
        int yCenterRowStride = yCenter * rowStride;
        // centerBaseX = (yCenter * rowStride) + ((width - 1) * pixelStride)
        // posCentered = centerBaseX - (x * pixelStride)
        int centerBaseX = yCenterRowStride + widthPixelStride - pixelStride;

        int width33PercentPixelStride = width33percent * pixelStride;

        int[] rgb = new int[3]; // Reusable array for RGB values

        // iterate through the X axis until we either hit 33% of the image width or a non-black pixel
        for (int xOffset = 0; xOffset < width33PercentPixelStride; xOffset += pixelStride) {

            // RGB values at 33% height - to left of image
            pos33percent = height33PercentRowStride + xOffset;
            decodePixel(buffer, pos33percent, pixelFormat, rgb);
            p1R = rgb[0]; p1G = rgb[1]; p1B = rgb[2];

            // RGB values at 66% height - to left of image
            pos66percent = height66PercentRowStride + xOffset;
            decodePixel(buffer, pos66percent, pixelFormat, rgb);
            p2R = rgb[0]; p2G = rgb[1]; p2B = rgb[2];

            // RGB values at center Y - to right of image
            posCentered = centerBaseX - xOffset;
            decodePixel(buffer, posCentered, pixelFormat, rgb);
            p3R = rgb[0]; p3G = rgb[1]; p3B = rgb[2];

            // check if any of our RGB values DO NOT evaluate as black
            if (!isBlack(p1R,p1G,p1B) || !isBlack(p2R,p2G,p2B) || !isBlack(p3R,p3G,p3B)) {
                firstNonBlackXPixelIndex = xOffset / pixelStride;
                break;
            }
        }

        buffer.reset();

        // width33PercentPixelStride is already calculated above
        int width66PercentPixelStride = width66percent * pixelStride;
        int xCenterPixelStride = xCenter * pixelStride;
        int heightRowStride = height * rowStride;
        // centerBaseY = (xCenter * pixelStride) + ((height - 1) * rowStride)
        // posCentered = centerBaseY - (y * rowStride)
        int centerBaseY = xCenterPixelStride + heightRowStride - rowStride;

        int height33PercentRowStrideLoopLimit = height33percent * rowStride;

        // iterate through the Y axis until we either hit 33% of the image height or a non-black pixel
        for (int yOffset = 0; yOffset < height33PercentRowStrideLoopLimit; yOffset += rowStride) {

            // RGB values at 33% width - top of image
            pos33percent = width33PercentPixelStride + yOffset;
            decodePixel(buffer, pos33percent, pixelFormat, rgb);
            p1R = rgb[0]; p1G = rgb[1]; p1B = rgb[2];

            // RGB values at 66% width - top of image
            pos66percent = width66PercentPixelStride + yOffset;
            decodePixel(buffer, pos66percent, pixelFormat, rgb);
            p2R = rgb[0]; p2G = rgb[1]; p2B = rgb[2];

            // RGB values at center X - bottom of image
            posCentered = centerBaseY - yOffset;
            decodePixel(buffer, posCentered, pixelFormat, rgb);
            p3R = rgb[0]; p3G = rgb[1]; p3B = rgb[2];

            // check if any of our RGB values DO NOT evaluate as black
            if (!isBlack(p1R,p1G,p1B) || !isBlack(p2R,p2G,p2B) || !isBlack(p3R,p3G,p3B)) {
                firstNonBlackYPixelIndex = yOffset / rowStride;
                break;
            }
        }

        return new BorderObject(firstNonBlackXPixelIndex, firstNonBlackYPixelIndex);
    }

    private boolean isBlack(int red, int green, int blue) {
        return red < BLACK_THRESHOLD && green < BLACK_THRESHOLD && blue < BLACK_THRESHOLD;
    }

    /**
     * Decodes a pixel at the given buffer offset into RGB888 values.
     * Handles both RGB_565 and RGBA_8888 formats.
     * 
     * @param buffer The ByteBuffer containing pixel data
     * @param offset The byte offset of the pixel
     * @param pixelFormat The pixel format (PixelFormat.RGB_565 or PixelFormat.RGBA_8888)
     * @param outRgb Output array of length 3 to store R, G, B values (0-255)
     */
    private void decodePixel(ByteBuffer buffer, int offset, int pixelFormat, int[] outRgb) {
        if (pixelFormat == PixelFormat.RGB_565) {
            // Read 16-bit pixel (little-endian)
            int pixel = ((buffer.get(offset + 1) & 0xff) << 8) | (buffer.get(offset) & 0xff);
            // Extract and expand 5-bit red, 6-bit green, 5-bit blue to 8-bit
            int R = ((pixel >> 11) & 0x1F);
            outRgb[0] = (R << 3) | (R >> 2);
            int G = ((pixel >> 5) & 0x3F);
            outRgb[1] = (G << 2) | (G >> 4);
            int B = (pixel & 0x1F);
            outRgb[2] = (B << 3) | (B >> 2);
        } else {
            // RGBA_8888 or other formats - read RGB bytes directly
            outRgb[0] = buffer.get(offset) & 0xff;
            outRgb[1] = buffer.get(offset + 1) & 0xff;
            outRgb[2] = buffer.get(offset + 2) & 0xff;
        }
    }

    public static class BorderObject {
        private boolean isKnown;
        private int horizontalBorderIndex;
        private int verticalBorderIndex;

        BorderObject(int horizontalBorderIndex, int verticalBorderIndex) {
            this.horizontalBorderIndex = horizontalBorderIndex;
            this.verticalBorderIndex = verticalBorderIndex;
            this.isKnown = this.horizontalBorderIndex != -1 && this.verticalBorderIndex != -1;
        }

        public boolean isKnown() { return isKnown; }
        public int getHorizontalBorderIndex() { return horizontalBorderIndex; }
        public int getVerticalBorderIndex() { return verticalBorderIndex; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            BorderObject that = (BorderObject) o;
            return isKnown == that.isKnown &&
                    horizontalBorderIndex == that.horizontalBorderIndex &&
                    verticalBorderIndex == that.verticalBorderIndex;
        }

        @Override
        public int hashCode() {
            return Objects.hash(isKnown, horizontalBorderIndex, verticalBorderIndex);
        }

        @Override
        public String toString() {
            return "BorderObject{" +
                    "isKnown=" + isKnown +
                    ", horizontalBorderIndex=" + horizontalBorderIndex +
                    ", verticalBorderIndex=" + verticalBorderIndex +
                    '}';
        }
    }
}
