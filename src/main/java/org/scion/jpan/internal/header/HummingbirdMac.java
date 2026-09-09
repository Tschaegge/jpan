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
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.scion.jpan.internal.util.ByteUtil;

/**
 * The Hummingbird key and MAC chain: master secret to AS secret value (PBKDF2), secret value to
 * authentication key Ak (one AES block), Ak to per-packet flyover MAC Vk (one AES block), and
 * Vk[0:6] XOR SCION MAC as the aggregated MAC carried by a flyover hop field.
 *
 * <p>Mirrors {@code pkg/slayers/path/hummingbird/mac.go} of the reference implementation. An
 * endhost normally computes only {@link #flyoverMac} (per packet) and {@link #aggregateMac}; Ak
 * arrives with the reservation. Deriving Ak and the secret value happens on the AS side and in
 * tests.
 */
public final class HummingbirdMac {

  /** PBKDF2 salt for deriving the AS secret value, same constant as the reference. */
  public static final String SECRET_VALUE_SALT = "Derive hbird sv";

  /** The secret value, Ak and Vk are one AES-128 block each. */
  public static final int KEY_LEN = 16;

  /** Only the first six bytes of Vk take part in the aggregated hop field MAC. */
  public static final int MAC_LEN = 6;

  private static final int PBKDF2_ITERATIONS = 1000;

  private HummingbirdMac() {}

  /**
   * Derives the Hummingbird secret value of an AS from its forwarding master secret:
   * PBKDF2-HMAC-SHA256 with {@link #SECRET_VALUE_SALT}, 1000 iterations, 16 bytes.
   */
  public static byte[] deriveSecretValue(byte[] masterSecret) {
    if (masterSecret == null || masterSecret.length == 0) {
      throw new IllegalArgumentException("Master secret must not be empty");
    }
    // The JDK's own PBKDF2WithHmacSHA256 takes a char[] password and re-encodes it as UTF-8,
    // which mangles a master secret that is raw bytes. A 16 byte key needs exactly one
    // derivation block, so compute it directly (RFC 8018, 5.2).
    try {
      Mac hmac = Mac.getInstance("HmacSHA256");
      hmac.init(new SecretKeySpec(masterSecret, "HmacSHA256"));
      hmac.update(SECRET_VALUE_SALT.getBytes(StandardCharsets.UTF_8));
      byte[] u = hmac.doFinal(new byte[] {0, 0, 0, 1}); // U_1 = HMAC(P, salt || blockIndex)
      byte[] t = u.clone();
      for (int i = 1; i < PBKDF2_ITERATIONS; i++) {
        u = hmac.doFinal(u); // U_i = HMAC(P, U_{i-1}), doFinal resets the Mac for reuse
        for (int j = 0; j < t.length; j++) {
          t[j] ^= u[j];
        }
      }
      return Arrays.copyOf(t, KEY_LEN);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e); // HmacSHA256 is in every JDK
    }
  }

  /**
   * An encrypting AES cipher over a 16 byte key, the counterpart of Go's {@code cipher.Block}.
   * Reusable across calls: keep one per secret value or per Ak instead of creating one per packet.
   */
  public static Cipher createCipher(byte[] key) {
    if (key.length != KEY_LEN) {
      throw new IllegalArgumentException("Key must be " + KEY_LEN + " bytes, got " + key.length);
    }
    try {
      Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
      return cipher;
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e); // AES/ECB/NoPadding is in every JDK
    }
  }

  /**
   * Derives the authentication key Ak of one flyover from the AS secret value.
   *
   * @param svCipher a cipher over the AS secret value, from {@link #createCipher}
   * @param startTime absolute reservation start, unix seconds
   */
  public static byte[] deriveAuthKey(
      Cipher svCipher, int resId, int bw, int ingress, int egress, long startTime, int duration) {
    checkWidth("resID", resId, 22);
    checkWidth("bw", bw, 10);
    checkWidth("ingress", ingress, 16);
    checkWidth("egress", egress, 16);
    checkWidth("duration", duration, 16);

    byte[] block = new byte[KEY_LEN];
    ByteBuffer bb = ByteBuffer.wrap(block);
    bb.putShort((short) ingress);
    bb.putShort((short) egress);
    bb.putInt((resId << 10) | bw);
    bb.putInt(ByteUtil.toInt(startTime));
    bb.putShort((short) duration);
    // The last two bytes stay zero (padding).
    return encryptBlock(svCipher, block);
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
