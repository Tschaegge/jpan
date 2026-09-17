// Copyright 2026 ETH Zurich
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.scion.jpan.internal;

import static org.junit.jupiter.api.Assertions.*;
import static org.scion.jpan.internal.HummingbirdMacTest.fromHex;

import javax.crypto.Cipher;
import org.junit.jupiter.api.Test;
import org.scion.jpan.internal.header.HummingbirdMac;
import org.scion.jpan.testutil.HummingbirdKeys;

/**
 * Two kinds of expected values.
 *
 * <p>Independent vectors, computed with tools that share no code with JPAN or the Go reference:
 *
 * <pre>
 *   sv: hashlib.pbkdf2_hmac('sha256', bytes(range(16)), b'Derive hbird sv', 1000, 16)
 *   Ak: echo -n 00020005000102030003000102030000 | xxd -r -p | \
 *       openssl enc -aes-128-ecb -K 00010203040506070001020304050607 -nopad | xxd -p
 * </pre>
 *
 * The Ak inputs are the ones of {@code TestDeriveAuthKey} in the reference implementation ({@code
 * pkg/slayers/path/hummingbird/mac_test.go}).
 *
 * <p>Golden vectors, recorded from the reference implementation at commit {@code 97bcc2b6a} by
 * hbird-conformance ({@code corpus/golden/derive_sv.json}, {@code derive_ak.json}). The keys are
 * synthetic. {@code high-bytes} and {@code mixed-bytes} are the vectors that the JDK's
 * PBKDF2WithHmacSHA256 gets wrong, because it re-encodes the key as UTF-8.
 */
class HummingbirdKeysTest {

  /** Secret value used by the reference implementation's TestDeriveAuthKey. */
  private static final byte[] SV = fromHex("00010203040506070001020304050607");

  private static final byte[] EXPECTED_SV = fromHex("4a2bd9c1b3113c296bcee23c3d620f9c");
  private static final byte[] EXPECTED_AK = fromHex("25e6b97596070393ef5473671bf63a9a");

  /** name, master key, secret value. */
  private static final String[][] GOLDEN_SV = {
    {"all-ones", "ffffffffffffffffffffffffffffffff", "6d00106179a8d4fc4e3073736ad1617d"},
    {"all-zero", "00000000000000000000000000000000", "e6b6c0cb20196acaec8a7a3f122d3fe9"},
    {"corpus-key", "68626972642d636f6e666f726d2d6b30", "3b069bf735d89db4d7d455c746fef9f3"},
    {"high-bytes", "8081c3a9fffe90a0deadbeefc0ffee99", "284fa3920932d835c337e632f0b63f47"},
    {"mixed-bytes", "007f8081ffc3a910deadbeef01020304", "6acc65afb014fabcb9ecf6f8d4a4671f"},
  };

  /** Secret value of the corpus key, used by every GOLDEN_AK vector. */
  private static final byte[] CORPUS_SV = fromHex("3b069bf735d89db4d7d455c746fef9f3");

  /** name, resID, bw, ingress, egress, startTime, duration, Ak. */
  private static final Object[][] GOLDEN_AK = {
    {"all-zero", 0, 0, 0, 0, 0L, 0, "e9ef856574e0d8d598f6d1f7817dea89"},
    {"braccept", 42, 129, 101, 102, 1786710245L, 301, "c894e12a74e712b23ca680ac48ae960c"},
    {"maxima", 4194303, 1023, 65535, 65535, 4294967295L, 65535, "f04d9f66e966cd6430c345e5b9193c46"},
    {"startTime-high-bit", 1, 1023, 1, 2, 2147483648L, 9, "1119d11f58cc52a787a0940b3d0d7561"},
    {"tiny4-hop0", 1, 1023, 1, 0, 1786710245L, 9, "d5ba4a5827bb8302b889e0f241671375"},
    {"tiny4-hop1", 1, 1023, 0, 2, 1786710245L, 9, "20d9a9f0a4d3b03315be9402f53e6839"},
  };

  @Test
  void testDeriveSecretValue() {
    byte[] masterSecret = new byte[16];
    for (int i = 0; i < masterSecret.length; i++) {
      masterSecret[i] = (byte) i;
    }
    assertArrayEquals(EXPECTED_SV, HummingbirdKeys.deriveSecretValue(masterSecret));
  }

  @Test
  void testDeriveSecretValueEmpty() {
    assertThrows(IllegalArgumentException.class, () -> HummingbirdKeys.deriveSecretValue(null));
    assertThrows(
        IllegalArgumentException.class, () -> HummingbirdKeys.deriveSecretValue(new byte[0]));
  }

  @Test
  void testDeriveAuthKey() {
    Cipher svCipher = HummingbirdMac.createCipher(SV);
    byte[] ak = HummingbirdKeys.deriveAuthKey(svCipher, 0x40, 0x0203, 2, 5, 0x00030001L, 0x0203);
    assertArrayEquals(EXPECTED_AK, ak);
    // Repeat with the same cipher: must yield the same result (the cipher holds no state).
    ak = HummingbirdKeys.deriveAuthKey(svCipher, 0x40, 0x0203, 2, 5, 0x00030001L, 0x0203);
    assertArrayEquals(EXPECTED_AK, ak);
  }

  @Test
  void testDeriveAuthKeyFieldWidths() {
    Cipher svCipher = HummingbirdMac.createCipher(SV);
    assertThrows(
        IllegalArgumentException.class,
        () -> HummingbirdKeys.deriveAuthKey(svCipher, 1 << 22, 0, 0, 0, 0L, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> HummingbirdKeys.deriveAuthKey(svCipher, 0, 1 << 10, 0, 0, 0L, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> HummingbirdKeys.deriveAuthKey(svCipher, 0, 0, 1 << 16, 0, 0L, 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> HummingbirdKeys.deriveAuthKey(svCipher, 0, 0, 0, 0, 0L, -1));
  }

  @Test
  void testGoldenSecretValues() {
    for (String[] v : GOLDEN_SV) {
      assertArrayEquals(fromHex(v[2]), HummingbirdKeys.deriveSecretValue(fromHex(v[1])), v[0]);
    }
  }

  @Test
  void testGoldenAuthKeys() {
    Cipher svCipher = HummingbirdMac.createCipher(CORPUS_SV);
    for (Object[] v : GOLDEN_AK) {
      byte[] ak =
          HummingbirdKeys.deriveAuthKey(
              svCipher, (int) v[1], (int) v[2], (int) v[3], (int) v[4], (long) v[5], (int) v[6]);
      assertArrayEquals(fromHex((String) v[7]), ak, (String) v[0]);
    }
  }
}
