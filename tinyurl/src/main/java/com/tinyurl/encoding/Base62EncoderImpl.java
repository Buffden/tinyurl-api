package com.tinyurl.encoding;

import org.springframework.stereotype.Component;

@Component
public class Base62EncoderImpl implements Base62Encoder {

    private static final String CHARSET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int BASE = CHARSET.length();
    private static final int MIN_LENGTH = 6;

    @Override
    public String encode(long id) {
        if (id < 0) {
            throw new IllegalArgumentException("id must be non-negative");
        }

        if (id == 0) {
            return "0".repeat(MIN_LENGTH);
        }

        StringBuilder encoded = new StringBuilder();
        long value = id;

        while (value > 0) {
            int index = (int) (value % BASE);
            encoded.append(CHARSET.charAt(index));
            value /= BASE;
        }

        while (encoded.length() < MIN_LENGTH) {
            encoded.append('0');
        }

        return encoded.reverse().toString();
    }

    @Override
    public long decode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("code must not be blank");
        }

        long result = 0;
        for (int i = 0; i < code.length(); i++) {
            int charIndex = CHARSET.indexOf(code.charAt(i));
            if (charIndex < 0) {
                throw new IllegalArgumentException("invalid base62 code");
            }
            result = (result * BASE) + charIndex;
        }
        return result;
    }
}
