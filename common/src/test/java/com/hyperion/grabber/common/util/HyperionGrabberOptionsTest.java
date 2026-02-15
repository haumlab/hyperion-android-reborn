package com.hyperion.grabber.common.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class HyperionGrabberOptionsTest {

    @Test
    public void findDivisor_returnsCorrectDivisor_whenScreenIsLargeAndRequirementsSmall() {
        // Requirements: 10x10 LEDs = 100 pixels. Minimum packet size = 300 bytes.
        // Screen: 100x100 pixels.
        // Divisors of 100: 1, 2, 4, 5, 10, 20, 25, 50, 100.
        // We want largest divisor i such that (100/i)*(100/i)*3 >= 300.
        // i=10: (10)*(10)*3 = 300 >= 300. YES.
        // i=20: (5)*(5)*3 = 75 < 300. NO.
        // So expected divisor is 10.

        HyperionGrabberOptions options = new HyperionGrabberOptions(10, 10, 60, false, 10);
        int divisor = options.findDivisor(100, 100);
        assertEquals(10, divisor);
    }

    @Test
    public void findDivisor_returnsOne_whenScreenIsSmallAndRequirementsLarge() {
        // Requirements: 100x100 LEDs = 10000 pixels. Minimum packet size = 30000 bytes.
        // Screen: 50x50 pixels.
        // Divisors of 50: 1, 2, 5, 10, 25, 50.
        // even with divisor 1: (50/1)*(50/1)*3 = 2500*3 = 7500 bytes.
        // 7500 < 30000.
        // No divisor satisfies the condition, so findDivisor returns 1 (no downscaling).

        HyperionGrabberOptions options = new HyperionGrabberOptions(100, 100, 60, false, 10);
        int divisor = options.findDivisor(50, 50);
        assertEquals(1, divisor);
    }

    @Test
    public void findDivisor_returnsLargestPossibleDivisor() {
        // Requirements: 10x10 LEDs = 100 pixels. Minimum packet size = 300 bytes.
        // Screen: 200x200 pixels.
        // Divisors of 200: 1, 2, 4, 5, 8, 10, 20, 25, 40, 50, 100, 200.
        // We want largest divisor i such that (200/i)*(200/i)*3 >= 300.
        // i=20: (10)*(10)*3 = 300 >= 300. YES.
        // i=25: (8)*(8)*3 = 192 < 300. NO.
        // Expected divisor is 20.

        HyperionGrabberOptions options = new HyperionGrabberOptions(10, 10, 60, false, 10);
        int divisor = options.findDivisor(200, 200);
        assertEquals(20, divisor);
    }

    @Test
    public void findDivisor_handlesNonSquareScreens() {
        // Requirements: 10x10 LEDs = 100 pixels. Minimum packet size = 300 bytes.
        // Screen: 100x200 pixels.
        // Common divisors: 1, 2, 4, 5, 10, 20, 25, 50, 100.
        // We want largest divisor i such that (100/i)*(200/i)*3 >= 300.
        // i=10: (10)*(20)*3 = 600 >= 300. YES.
        // i=20: (5)*(10)*3 = 150 < 300. NO.
        // Expected divisor is 10.

        HyperionGrabberOptions options = new HyperionGrabberOptions(10, 10, 60, false, 10);
        int divisor = options.findDivisor(100, 200);
        assertEquals(10, divisor);
    }

    @Test
    public void findDivisor_handlesPrimeDimensions() {
        // Requirements: 2x2 LEDs = 4 pixels. Min packet size = 12 bytes.
        // Screen: 17x19 pixels.
        // Common divisors: 1.
        // i=1: (17)*(19)*3 = 969 >= 12. YES.
        // Expected divisor is 1.

        HyperionGrabberOptions options = new HyperionGrabberOptions(2, 2, 60, false, 10);
        int divisor = options.findDivisor(17, 19);
        assertEquals(1, divisor);
    }

    @Test
    public void findDivisor_handlesExactMatch() {
        // Requirements: 10x10 LEDs. Min packet size = 300.
        // Screen: 30x30.
        // Divisors of 30: 1, 2, 3, 5, 6, 10, 15, 30.
        // i=3: (10)*(10)*3 = 300 >= 300. YES.
        // i=5: (6)*(6)*3 = 108 < 300. NO.
        // Expected divisor is 3.

        HyperionGrabberOptions options = new HyperionGrabberOptions(10, 10, 60, false, 10);
        int divisor = options.findDivisor(30, 30);
        assertEquals(3, divisor);
    }
}
