package com.hyperion.grabber.common.network;

import java.util.Objects;

public class ColorRgb {
    public int red;
    public int green;
    public int blue;
    
    public ColorRgb(int r, int g, int b) {
        this.red = r & 0xFF;
        this.green = g & 0xFF;
        this.blue = b & 0xFF;
    }

    public void set(int r, int g, int b) {
        this.red = r & 0xFF;
        this.green = g & 0xFF;
        this.blue = b & 0xFF;
    }
    
    public void set(ColorRgb other) {
        if (other != null) {
            this.red = other.red;
            this.green = other.green;
            this.blue = other.blue;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ColorRgb colorRgb = (ColorRgb) o;
        return red == colorRgb.red &&
                green == colorRgb.green &&
                blue == colorRgb.blue;
    }

    @Override
    public int hashCode() {
        return Objects.hash(red, green, blue);
    }
    
    public ColorRgb clone() {
        return new ColorRgb(red, green, blue);
    }
}
