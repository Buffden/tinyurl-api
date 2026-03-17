package com.tinyurl.encoding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class Base62EncoderImplTest {

    private final Base62EncoderImpl encoder = new Base62EncoderImpl();

    @Test
    void encodeShouldPadToAtLeastSixCharacters() {
        assertEquals(6, encoder.encode(1).length());
        assertEquals("000000", encoder.encode(0));
    }

    @Test
    void decodeShouldReturnOriginalValue() {
        long value = 123456789L;
        assertEquals(value, encoder.decode(encoder.encode(value)));
    }

    @Test
    void decodeShouldRejectInvalidCode() {
        assertThrows(IllegalArgumentException.class, () -> encoder.decode("***"));
    }
}
