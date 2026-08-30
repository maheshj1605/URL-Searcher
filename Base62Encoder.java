package com.mahii.urlshortener.util;

import org.springframework.stereotype.Component;

/**
 * Base62 encoder/decoder for converting between long IDs and short alphanumeric codes.
 * 
 * Uses characters: 0-9, a-z, A-Z (62 characters total)
 * Example: 1234567890 -> "21LCkS"
 */
@Component
public class Base62Encoder {
    
    private static final String BASE62_ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int BASE = 62;
    
    /**
     * Encode a long ID to a Base62 string.
     * @param num Long ID to encode
     * @return Base62 encoded string
     */
    public String encode(long num) {
        if (num == 0) {
            return "0";
        }
        
        StringBuilder sb = new StringBuilder();
        long n = num;
        
        while (n > 0) {
            sb.append(BASE62_ALPHABET.charAt((int) (n % BASE)));
            n /= BASE;
        }
        
        return sb.reverse().toString();
    }
    
    /**
     * Decode a Base62 string to a long ID.
     * @param encoded Base62 encoded string
     * @return Decoded long ID
     */
    public long decode(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            throw new IllegalArgumentException("Cannot decode empty or null string");
        }
        
        long result = 0;
        
        for (char c : encoded.toCharArray()) {
            int digit = BASE62_ALPHABET.indexOf(c);
            if (digit == -1) {
                throw new IllegalArgumentException("Invalid Base62 character: " + c);
            }
            result = result * BASE + digit;
        }
        
        return result;
    }
    
    /**
     * Check if a string is a valid Base62 code.
     */
    public boolean isValid(String code) {
        if (code == null || code.isEmpty()) {
            return false;
        }
        
        for (char c : code.toCharArray()) {
            if (BASE62_ALPHABET.indexOf(c) == -1) {
                return false;
            }
        }
        
        return true;
    }
}
