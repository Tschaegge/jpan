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

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;
import org.scion.jpan.ScionUtil;
import org.scion.jpan.internal.header.HummingbirdMac;

/**
 * Tests the flyover MAC Vk, the aggregated MAC that goes into the hop field, and that inputs which
 * do not fit their field are rejected.
 */
class HummingbirdMacTest {

  // Any 16-byte key would do; this one is the Ak of the reference's TestDeriveAuthKey.
  private static final byte[] AK = fromHex("25e6b97596070393ef5473671bf63a9a");

  // Computed with OpenSSL from the inputs of testFlyoverMac:
  //   echo -n "0001ff0000000110 03e8 0102 7d000007" | xxd -r -p |
  //     openssl enc -aes-128-ecb -K 25e6b97596070393ef5473671bf63a9a -nopad | xxd -p
  private static final byte[] EXPECTED_VK = fromHex("59b4bad6993ab19700c508e931e8f005");

  @Test
  void testFlyoverMac() {
    long dstIsdAs = ScionUtil.parseIA("1-ff00:0:110"); // 0001ff0000000110
    int pktLen = 1000; // 03e8
    int resStartTime = 0x0102; // 0102
    int highResTs = (500 << 22) | 7; // 7d000007: 500 ms, counter 7

    HummingbirdMac mac = new HummingbirdMac(AK);
    byte[] vk = mac.flyoverMac(dstIsdAs, pktLen, resStartTime, highResTs);
    assertArrayEquals(EXPECTED_VK, vk);

    // One instance per flyover is reused for every packet, so a second call must give the same Vk.
    vk = mac.flyoverMac(dstIsdAs, pktLen, resStartTime, highResTs);
    assertArrayEquals(EXPECTED_VK, vk);
  }

  /** The aggregated MAC is the SCION MAC XOR the first 6 bytes of Vk, written in place. */
  @Test
  void testAggregateMac() {
    // The SCION MAC (any 6 bytes) sits at position 2; the bytes around it must not change.
    ByteBuffer packet = ByteBuffer.wrap(fromHex("aaaa" + "c47cf0e2eb51" + "bbbb"));
    HummingbirdMac.aggregateMac(packet, 2, EXPECTED_VK);
    assertArrayEquals(fromHex("aaaa" + "9dc84a34726b" + "bbbb"), packet.array()); // c4^59, ...
    assertEquals(0, packet.position()); // writes at fixed positions, the position does not move

    // The router XORs Vk in once more to get the SCION MAC back.
    HummingbirdMac.aggregateMac(packet, 2, EXPECTED_VK);
    assertArrayEquals(fromHex("aaaa" + "c47cf0e2eb51" + "bbbb"), packet.array());
  }

  @Test
  void testInputsMustFit() {
    HummingbirdMac mac = new HummingbirdMac(AK);
    assertThrows(
        IllegalArgumentException.class, () -> mac.flyoverMac(0, 1 << 16, 0, 0)); // pktLen 16 bits
    assertThrows(
        IllegalArgumentException.class, () -> mac.flyoverMac(0, 0, -1, 0)); // resStartTime >= 0

    // The key must have exactly 16 bytes.
    assertThrows(IllegalArgumentException.class, () -> new HummingbirdMac(new byte[15]));
    assertThrows(IllegalArgumentException.class, () -> new HummingbirdMac(new byte[32]));
  }

  private static byte[] fromHex(String hex) {
    byte[] out = new byte[hex.length() / 2];
    for (int i = 0; i < out.length; i++) {
      out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
    }
    return out;
  }
}
