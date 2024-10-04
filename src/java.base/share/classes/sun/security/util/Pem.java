/*
 * Copyright (c) 2015, 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package sun.security.util;

import sun.security.x509.AlgorithmId;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.AccessController;
import java.security.NoSuchAlgorithmException;
import java.security.PrivilegedAction;
import java.security.Security;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A utility class for PEM format encoding.
 */
public class Pem {
    private static final char WS = 0x20;  // Whitespace

    // Default algorithm from jdk.epkcs8.defaultAlgorithm in java.security
    public static final String DEFAULT_ALGO;

    // Pattern matching for EKPI operations
    private static final Pattern pbePattern;

    // Lazy initialized PBES2 OID value
    private static ObjectIdentifier PBES2OID;

    static {
        @SuppressWarnings("removal")
        String d = AccessController.doPrivileged(
            (PrivilegedAction<String>) () ->
                Security.getProperty("jdk.epkcs8.defaultAlgorithm"));
        DEFAULT_ALGO = d;
        pbePattern = Pattern.compile("^PBEWith.*And.*");
    }

    /**
     * Decodes a PEM-encoded block.
     *
     * @param input the input string, according to RFC 1421, can only contain
     *              characters in the base-64 alphabet and whitespaces.
     * @return the decoded bytes
     */
    public static byte[] decode(String input) {
        byte[] src = input.replaceAll("\\s+", "").
            getBytes(StandardCharsets.ISO_8859_1);
            return Base64.getDecoder().decode(src);
    }

    /**
     * Return the OID for a given PBE algorithm.  PBES1 has an OID for each
     * algorithm, while PBES2 has one OID for everything that complies with
     * the formatting.  Therefore, if the algorithm is not PBES1, it will
     * return PBES2.  Cipher will determine if this is a valid PBE algorithm.
     * PBES2 specifies AES as the cipher algorithm, but any block cipher could
     * be supported.
     */
    public static ObjectIdentifier getPBEID(String algorithm) {

        // Verify pattern matches PBE Standard Name spec
        if (!pbePattern.matcher(algorithm).matches()) {
            throw new IllegalArgumentException("Invalid algorithm format.");
        }

        // Return the PBES1 OID if it matches
        try {
            return AlgorithmId.get(algorithm).getOID();
        } catch (NoSuchAlgorithmException e) {
            // fall-through
        }

        // Lazy initialize
        if (PBES2OID == null) {
            try {
                // Set to the hardcoded OID in KnownOID.java
                PBES2OID = AlgorithmId.get("PBES2").getOID();
            } catch (NoSuchAlgorithmException e) {
                // Should never fail.
                throw new IllegalArgumentException(e);
            }
        }
        return PBES2OID;
    }

    /**
     * Read the PEM text and return it in it's three components:  header,
     * base64, and footer.
     *
     * The method will leave the stream after reading the end of line of the
     * footer or end of file
     * @param is The pem data
     * @param shortHeader if true, the hyphen length is 4 because the first
     *                    hyphen is assumed to have been read.  This is needed
     *                    for the CertificateFactory X509 implementation.
     * @return A new Pem object containing the three components
     * @throws IOException on read errors
     */
    public static PEMRecord readPEM(InputStream is, boolean shortHeader)
        throws IOException {
        Objects.requireNonNull(is);

        int hyphen = (shortHeader ? 1 : 0);
        int eol = 0;

        // Find starting hyphens
        do {
            switch (is.read()) {
                case '-' -> hyphen++;
                case -1 -> {
                    return null;
                }
                default -> hyphen = 0;
            }
        } while (hyphen != 5);

        StringBuilder sb = new StringBuilder(64);
        sb.append("-----");
        hyphen = 0;
        int c;

        // Get header definition until first hyphen
        do {
            switch (c = is.read()) {
                case '-' -> hyphen++;
                case -1 -> throw new IllegalArgumentException(
                    "Input ended prematurely");
                case '\n', '\r' -> throw new IllegalArgumentException(
                    "Incomplete header");
                default -> sb.append((char) c);
            }
        } while (hyphen == 0);

        // Verify header ending with 5 hyphens.
        do {
            switch (is.read()) {
                case '-' -> hyphen++;
                default ->
                    throw new IllegalArgumentException("Incomplete header");
            }
        } while (hyphen < 5);

        sb.append("-----");
        String header = sb.toString();
        if (header.length() < 16 || !header.startsWith("-----BEGIN ") ||
            !header.endsWith("-----")) {
            throw new IllegalArgumentException("Illegal header: " + header);
        }

        hyphen = 0;
        sb = new StringBuilder(1024);

        // Determine the line break using the char after the last hyphen
        switch (c = is.read()) {
            case WS -> {} // skip whitespace
            case '\r' -> {
                c = is.read();
                if (c == '\n') {
                    eol = '\n';
                } else {
                    eol = '\r';
                    sb.append((char) c);
                }
            }
            case '\n' -> eol = '\n';
            default ->
                throw new IllegalArgumentException("No EOL character found");
        }

        // Read data until we find the first footer hyphen.
        do {
            switch (c = is.read()) {
                case -1 ->
                    throw new IllegalArgumentException("Incomplete header");
                case '-' -> hyphen++;
                case WS, '\t', '\n', '\r' -> {} // skip whitespace, tab, etc
                default -> sb.append((char) c);
            }
        } while (hyphen == 0);

        String data = sb.toString();

        // Verify footer starts with 5 hyphens.
        do {
            switch (is.read()) {
                case '-' -> hyphen++;
                case -1 -> throw new IllegalArgumentException(
                    "Input ended prematurely");
                default -> throw new IllegalArgumentException(
                    "Incomplete footer");
            }
        } while (hyphen < 5);

        hyphen = 0;
        sb = new StringBuilder(64);
        sb.append("-----");

        // Look for Complete header by looking for the end of the hyphens
        do {
            switch (c = is.read()) {
                case '-' -> hyphen++;
                case -1 -> throw new IllegalArgumentException(
                    "Input ended prematurely");
                default -> sb.append((char) c);
            }
        } while (hyphen == 0);

        // Verify ending with 5 hyphens.
        do {
            switch (is.read()) {
                case '-' -> hyphen++;
                case -1 -> throw new IllegalArgumentException(
                    "Input ended prematurely");
                default -> throw new IllegalArgumentException(
                    "Incomplete footer");
            }
        } while (hyphen < 5);

        while ((c = is.read()) != eol && c != -1 && c != '\r' && c != WS) {
            throw new IllegalArgumentException("Invalid PEM format:  " +
                "No EOL char found in footer:  0x" +
                HexFormat.of().toHexDigits((byte) c));
        }

        sb.append("-----");
        String footer = sb.toString();
        if (footer.length() < 14 || !footer.startsWith("-----END ") ||
            !footer.endsWith("-----")) {
            throw new IOException("Illegal footer: " + footer);
        }

        // Verify the object type in the header and the footer are the same.
        String headerType = header.substring(11, header.length() - 5);
        String footerType = footer.substring(9, footer.length() - 5);
        if (!headerType.equals(footerType)) {
            throw new IOException("Header and footer do not match: " +
                headerType + " " + footerType);
        }

        return new PEMRecord(header, data);
    }

    public static PEMRecord readPEM(InputStream is) throws IOException {
        return readPEM(is, false);
    }

}
