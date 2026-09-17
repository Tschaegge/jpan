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

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * The per-packet cryptography of a Hummingbird endhost: the flyover MAC Vk (one AES block keyed
 * with the reservation's Ak) and Vk[0:6] XOR SCION MAC as the aggregated MAC carried by a flyover
 * hop field.
 *
 * <p>Mirrors {@code pkg/slayers/path/hummingbird/mac.go} of the reference implementation. Ak
 * arrives with the reservation; deriving it (and the AS secret value it comes from) is the AS's
 * business and lives in the test scope, see {@code testutil.HummingbirdKeys}.
 */
public final class HummingbirdMac {

  /** Same constants as in the reference implementation. */
  public static final int KEY_LEN = 16;

  public static final int MAC_LEN = 6;

  private HummingbirdMac() {}

  /**
   * An encrypting AES cipher over a 16 byte key, the counterpart of Go's {@code cipher.Block}.
   * Reusable across calls: keep one per secret value or per Ak instead of creating one per packet.
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
      throw new IllegalStateException(e); // AES/ECB/NoPadding is in every JDK
    }
  }

  /**
   * Computes the flyover MAC Vk for one packet.
   *
   * @param akCipher a cipher over the flyover's Ak, from {@link #createCipher}
   * @param resStartTime seconds between the packet's base timestamp and the reservation start, the
   *     value carried in the hop field
   * @param highResTs the packet's high resolution timestamp, millis shifted 22 bits left, ORed with
   *     the duplicate detection counter
   */
  public static byte[] flyoverMac(
      Cipher akCipher, long dstIsdAs, int pktLen, int resStartTime, int highResTs) {
    checkWidth("pktLen", pktLen, 16);
    checkWidth("resStartTime", resStartTime, 16);

    byte[] block = new byte[KEY_LEN];
    ByteBuffer bb = ByteBuffer.wrap(block);
    bb.putLong(dstIsdAs);
    bb.putShort((short) pktLen);
    bb.putShort((short) resStartTime);
    bb.putInt(highResTs);
    return encryptBlock(akCipher, block);
  }

  /**
   * XORs the first six bytes of the flyover MAC into the SCION MAC: the aggregated MAC a flyover
   * hop field carries. XOR is an involution, so aggregating the same flyover MAC again restores the
   * SCION MAC.
   */
  public static byte[] aggregateMac(byte[] scionMac, byte[] flyoverMac) {
    byte[] out = new byte[MAC_LEN];
    for (int i = 0; i < MAC_LEN; i++) {
      out[i] = (byte) (scionMac[i] ^ flyoverMac[i]);
    }
    return out;
  }

  /** Encrypts the block in place and returns it. */
  private static byte[] encryptBlock(Cipher cipher, byte[] block) {
    try {
      cipher.doFinal(block, 0, KEY_LEN, block, 0);
      return block;
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e); // one full block, no padding: cannot happen
    }
  }

  /** A value that does not fit its field would leak into the neighbouring field. Check first. */
  private static void checkWidth(String name, int value, int bits) {
    if (value < 0 || value >= (1 << bits)) {
      throw new IllegalArgumentException(
          name + " must fit " + bits + " bits (0.." + ((1 << bits) - 1) + "), got " + value);
    }
  }
}
