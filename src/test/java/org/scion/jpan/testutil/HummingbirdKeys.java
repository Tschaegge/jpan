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

package org.scion.jpan.testutil;

import static org.scion.jpan.internal.util.ByteUtil.checkWidth;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.scion.jpan.internal.header.HummingbirdMac;
import org.scion.jpan.internal.util.ByteUtil;

/**
 * The AS side of the Hummingbird key chain: master secret to secret value (PBKDF2), secret value to
 * the authentication key Ak of one flyover (one AES block).
 *
 * <p>An endhost never computes these; it receives Ak with its reservation. They are needed only to
 * play every AS of a local topology, so they live in the test scope. Mirrors {@code
 * pkg/slayers/path/hummingbird/mac.go} of the reference implementation.
 */
public final class HummingbirdKeys {

  /** Same constants as in the reference implementation. */
  public static final String SECRET_VALUE_SALT = "Derive hbird sv";

  private static final int PBKDF2_ITERATIONS = 1000;

  private HummingbirdKeys() {}

  /**
   * Derives the Hummingbird secret value of an AS from its forwarding master secret:
   * PBKDF2-HMAC-SHA256 with {@link #SECRET_VALUE_SALT}, 1000 iterations, 16 bytes.
   */
  public static byte[] deriveSecretValue(byte[] masterSecret) {
    if (masterSecret == null || masterSecret.length == 0) {
      throw new IllegalArgumentException("Master secret must not be empty");
    }
    // The JDK's PBKDF2WithHmacSHA256 takes a char[] password and re-encodes it as UTF-8, which
    // turns every raw key byte >= 0x80 into two bytes. A 16 byte key needs exactly one derivation
    // block, so compute it directly (RFC 8018, 5.2).
    try {
      Mac hmac = Mac.getInstance("HmacSHA256");
      hmac.init(new SecretKeySpec(masterSecret, "HmacSHA256"));
      hmac.update(SECRET_VALUE_SALT.getBytes(StandardCharsets.UTF_8));
      byte[] u = hmac.doFinal(new byte[] {0, 0, 0, 1}); // U_1 = HMAC(P, salt || blockIndex)
      byte[] t = u.clone(); // running XOR, starts as a copy of U_1
      for (int i = 1; i < PBKDF2_ITERATIONS; i++) {
        u = hmac.doFinal(u); // U_i = HMAC(P, U_{i-1}), doFinal resets the Mac for reuse
        for (int j = 0; j < t.length; j++) {
          t[j] ^= u[j];
        }
      }
      return Arrays.copyOf(t, HummingbirdMac.KEY_LEN);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e); // HmacSHA256 is in every JDK
    }
  }

  /**
   * Derives the authentication key Ak of one flyover from the AS secret value.
   *
   * @param svCipher a cipher over the AS secret value, from {@link HummingbirdMac#createCipher}
   * @param startTime absolute reservation start, unix seconds
   */
  public static byte[] deriveAuthKey(
      Cipher svCipher, int resId, int bw, int ingress, int egress, long startTime, int duration) {
    checkWidth("resID", resId, 22);
    checkWidth("bw", bw, 10);
    checkWidth("ingress", ingress, 16);
    checkWidth("egress", egress, 16);
    checkWidth("duration", duration, 16);

    byte[] block = new byte[HummingbirdMac.KEY_LEN];
    ByteBuffer bb = ByteBuffer.wrap(block);
    bb.putShort((short) ingress);
    bb.putShort((short) egress);
    bb.putInt((resId << 10) | bw);
    bb.putInt(ByteUtil.toInt(startTime));
    bb.putShort((short) duration);
    // The last two bytes stay zero (padding).
    try {
      return svCipher.doFinal(block);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e); // one full block, no padding: cannot happen
    }
  }
}
