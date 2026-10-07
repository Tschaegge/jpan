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

package org.scion.jpan.internal.header;

import static org.scion.jpan.internal.util.ByteUtil.checkWidth;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * The MACs a Hummingbird endhost computes for every packet: the flyover MAC Vk, one AES block keyed
 * with the reservation's Ak, and the aggregated MAC that goes into the hop field, the first 6 bytes
 * of Vk XOR the SCION MAC.
 *
 * <p>Specified in Appendix A.4, Eq. (6) and (7), of "Hummingbird: Fast, Flexible, and Fair
 * Inter-Domain Bandwidth Reservations" (SIGCOMM 2025, https://doi.org/10.1145/3718958.3750495). The
 * paper calls the function PRF. Its section 7.1 names it AES-128.
 */
public final class HummingbirdMac {

  public static final int KEY_LEN = 16;
  public static final int BLOCK_LEN = 16;
  public static final int MAC_LEN = 6;

  private HummingbirdMac() {}

  /**
   * An encrypting AES-128 cipher over a 16 byte key. Reusable across calls: keep one per secret
   * value or per Ak instead of creating one per packet.
   */
  public static Cipher createCipher(byte[] key) {
    if (key.length != KEY_LEN) {
      throw new IllegalArgumentException("Key must be " + KEY_LEN + " bytes, got " + key.length);
    }
    try {
      Cipher cipher =
          Cipher.getInstance("AES/ECB/NoPadding"); // ECB isn't a problem as we have only one block
      cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
      return cipher;
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e); // Java SE requires AES/ECB/NoPadding with 128-bit keys
    }
  }

  /**
   * Computes the flyover MAC for one packet, Eq. (7a) without the truncation to 6 bytes, which
   * happens in {@link #aggregateMac}.
   *
   * @param akCipher a cipher over the flyover's Ak, from {@link #createCipher}
   * @param dstIsdAs the packet's destination ISD and AS, DstAddr in Eq. (7c)
   * @param pktLen the length of the whole packet in bytes, PayloadLen + 4 * HdrLen (Eq. 7d)
   * @param resStartTime seconds between the packet's base timestamp and the reservation start, the
   *     value carried in the hop field
   * @param highResTs the packet's high resolution timestamp, millis shifted 22 bits left, ORed with
   *     the duplicate detection counter
   * @return the full 16 byte AES block
   */
  public static byte[] flyoverMac(
      Cipher akCipher, long dstIsdAs, int pktLen, int resStartTime, int highResTs) {
    checkWidth("pktLen", pktLen, 16);
    checkWidth("resStartTime", resStartTime, 16);

    byte[] block = new byte[BLOCK_LEN];
    ByteBuffer bb = ByteBuffer.wrap(block);
    bb.putLong(dstIsdAs);
    bb.putShort((short) pktLen);
    bb.putShort((short) resStartTime);
    bb.putInt(highResTs);
    try {
      akCipher.doFinal(block, 0, BLOCK_LEN, block, 0); // encrypts in place
      return block;
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e); // one full block, no padding: cannot happen
    }
  }

  /** XORs the first six bytes of the flyover MAC into the SCION MAC. */
  public static byte[] aggregateMac(byte[] scionMac, byte[] flyoverMac) {
    byte[] out = new byte[MAC_LEN];
    for (int i = 0; i < MAC_LEN; i++) {
      out[i] = (byte) (scionMac[i] ^ flyoverMac[i]);
    }
    return out;
  }
}
