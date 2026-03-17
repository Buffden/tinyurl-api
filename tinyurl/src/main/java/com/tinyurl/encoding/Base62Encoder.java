package com.tinyurl.encoding;

public interface Base62Encoder {
    String encode(long id);
    long decode(String code);
}
