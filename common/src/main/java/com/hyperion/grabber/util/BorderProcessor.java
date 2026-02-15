package com.hyperion.grabber.common.util;

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
        checkNewBorder( findBorder(buffer, width, height, rowStride, pixelStride) );
    }

    private BorderObject findBorder(ByteBuffer buffer, int width, int height, int rowStride,
                                    int pixelStride) {

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

        // iterate through the X axis until we either hit 33% of the image width or a non-black pixel
        for (int xOffset = 0; xOffset < width33PercentPixelStride; xOffset += pixelStride) {

            // RGB values at 33% height - to left of image
            pos33percent = height33PercentRowStride + xOffset;
            p1R = buffer.get(pos33percent) & 0xff;
            p1G = buffer.get(pos33percent + 1) & 0xff;
            p1B = buffer.get(pos33percent + 2) & 0xff;

            // RGB values at 66% height - to left of image
            pos66percent = height66PercentRowStride + xOffset;
            p2R = buffer.get(pos66percent) & 0xff;
            p2G = buffer.get(pos66percent + 1) & 0xff;
            p2B = buffer.get(pos66percent + 2) & 0xff;

            // RGB values at center Y - to right of image
            posCentered = centerBaseX - xOffset;
            p3R = buffer.get(posCentered) & 0xff;
            p3G = buffer.get(posCentered + 1) & 0xff;
            p3B = buffer.get(posCentered + 2) & 0xff;

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
            p1R = buffer.get(pos33percent) & 0xff;
            p1G = buffer.get(pos33percent + 1) & 0xff;
            p1B = buffer.get(pos33percent + 2) & 0xff;

            // RGB values at 66% width - top of image
            pos66percent = width66PercentPixelStride + yOffset;
            p2R = buffer.get(pos66percent) & 0xff;
            p2G = buffer.get(pos66percent + 1) & 0xff;
            p2B = buffer.get(pos66percent + 2) & 0xff;

            // RGB values at center X - bottom of image
            posCentered = centerBaseY - yOffset;
            p3R = buffer.get(posCentered) & 0xff;
            p3G = buffer.get(posCentered + 1) & 0xff;
            p3B = buffer.get(posCentered + 2) & 0xff;

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
