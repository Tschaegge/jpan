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

import javax.crypto.Cipher;
import org.junit.jupiter.api.Test;
import org.scion.jpan.ScionUtil;
import org.scion.jpan.internal.header.HummingbirdMac;

/**
 * The expected values are pinned vectors computed with implementations independent of both JPAN
 * and the Go reference: OpenSSL for the AES blocks, Python hashlib for PBKDF2.
 *
 * <pre>
 *   Ak: echo -n 00020005000102030003000102030000 | xxd -r -p | \
 *       openssl enc -aes-128-ecb -K 00010203040506070001020304050607 -nopad | xxd -p
 *   Vk: echo -n 0001ff000000011003e801027d000007 | xxd -r -p | \
 *       openssl enc -aes-128-ecb -K &lt;Ak&gt; -nopad | xxd -p
 *   sv: hashlib.pbkdf2_hmac('sha256', bytes(range(16)), b'Derive hbird sv', 1000, 16)
 * </pre>
 *
 * <p>The Ak inputs are the ones of {@code TestDeriveAuthKey} in the reference implementation
 * ({@code pkg/slayers/path/hummingbird/mac_test.go}).
 */
class HummingbirdMacTest {

  /** Secret value used by the reference implementation's TestDeriveAuthKey. */
  private static final byte[] SV = fromHex("00010203040506070001020304050607");

  private static final int RES_ID = 0x40;
  private static final int BW = 0x0203;
  private static final int INGRESS = 2;
  private static final int EGRESS = 5;
  private static final long START_TIME = 0x00030001L;
  private static final int DURATION = 0x0203;

  private static final byte[] EXPECTED_AK = fromHex("25e6b97596070393ef5473671bf63a9a");
  private static final byte[] EXPECTED_VK = fromHex("59b4bad6993ab19700c508e931e8f005");
  private static final byte[] EXPECTED_SV = fromHex("4a2bd9c1b3113c296bcee23c3d620f9c");

  @Test
  void testDeriveSecretValue() {
    byte[] masterSecret = new byte[16];
    for (int i = 0; i < masterSecret.length; i++) {
      masterSecret[i] = (byte) i;
    }
    assertArrayEquals(EXPECTED_SV, HummingbirdMac.deriveSecretValue(masterSecret));
  }

  @Test
  void testDeriveSecretValueEmpty() {
    assertThrows(IllegalArgumentException.class, () -> HummingbirdMac.deriveSecretValue(null));
    assertThrows(
        IllegalArgumentException.class, () -> HummingbirdMac.deriveSecretValue(new byte[0]));
  }

  @Test
  void testDeriveAuthKey() {
    Cipher svCipher = HummingbirdMac.createCipher(SV);
    byte[] ak =
        HummingbirdMac.deriveAuthKey(svCipher, RES_ID, BW, INGRESS, EGRESS, START_TIME, DURATION);
    assertArrayEquals(EXPECTED_AK, ak);
    // Repeat with the same cipher: must yield the same result (the cipher holds no state).
    ak = HummingbirdMac.deriveAuthKey(svCipher, RES_ID, BW, INGRESS, EGRESS, START_TIME, DURATION);
    assertArrayEquals(EXPECTED_AK, ak);
  }

  @Test
  void testFlyoverMac() {
    long dstIsdAs = ScionUtil.parseIA("1-ff00:0:110");
    assertEquals(0x0001ff0000000110L, dstIsdAs); // the value baked into the OpenSSL vector
    int pktLen = 1000; // 0x03e8
    int resStartTime = 0x0102;
    int highResTs = (500 << 22) | 7; // 0x7d000007: 500 millis, counter 7

    Cipher akCipher = HummingbirdMac.createCipher(EXPECTED_AK);
    byte[] vk = HummingbirdMac.flyoverMac(akCipher, dstIsdAs, pktLen, resStartTime, highResTs);
    assertArrayEquals(EXPECTED_VK, vk);
    // Repeat with the same cipher: must yield the same result.
    vk = HummingbirdMac.flyoverMac(akCipher, dstIsdAs, pktLen, resStartTime, highResTs);
    assertArrayEquals(EXPECTED_VK, vk);
  }

  @Test
  void testAggregateMac() {
    byte[] scionMac = fromHex("c47cf0e2eb51");
    byte[] aggregated = HummingbirdMac.aggregateMac(scionMac, EXPECTED_VK);
    for (int i = 0; i < HummingbirdMac.MAC_LEN; i++) {
      assertEquals((byte) (scionMac[i] ^ EXPECTED_VK[i]), aggregated[i]);
    }
    // XOR is an involution: aggregating again restores the SCION MAC.
    assertArrayEquals(scionMac, HummingbirdMac.aggregateMac(aggregated, EXPECTED_VK));
  }

  @Test
  void testFieldWidths() {
    Cipher svCipher = HummingbirdMac.createCipher(SV);
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HummingbirdMac.deriveAuthKey(
                svCipher, 1 << 22, BW, INGRESS, EGRESS, START_TIME, DURATION));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            HummingbirdMac.deriveAuthKey(
                svCipher, RES_ID, 1 << 10, INGRESS, EGRESS, START_TIME, DURATION));
    assertThrows(
        IllegalArgumentException.class,
        () -> HummingbirdMac.flyoverMac(svCipher, 0, 1 << 16, 0, 0));
    assertThrows(
        IllegalArgumentException.class, () -> HummingbirdMac.flyoverMac(svCipher, 0, 0, -1, 0));
    assertThrows(IllegalArgumentException.class, () -> HummingbirdMac.createCipher(new byte[15]));
  }

  private static byte[] fromHex(String hex) {
    byte[] out = new byte[hex.length() / 2];
    for (int i = 0; i < out.length; i++) {
      out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
    }
    return out;
  }
}
