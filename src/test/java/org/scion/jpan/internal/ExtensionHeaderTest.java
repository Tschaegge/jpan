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
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.scion.jpan.internal.header.ExtensionHeader;
import org.scion.jpan.internal.header.HeaderConstants;

class ExtensionHeaderTest {

  /**
   * The end-to-end extension a Go client puts in front of the UDP header to offer a reverse
   * reservation: option type 3 with 147 bytes of data, then one Pad1. Made by the Go prototype's
   * BuildReverseReservationExtn, from corpus/golden/reverse_reservation_extn.json in
   * hbird-conformance. Its Aks are synthetic.
   */
  private static final byte[] GO_EXTENSION =
      fromHex(
          "11250393040b0b0001ff00000001110000002900000464101112131415161718191a1b1c1d1e1f6a"
              + "7f08b8003c00000001ff00000001100001000200000864202122232425262728292a2b2c2d2e2f6a"
              + "7f08b8003c00000001ff00000001120001000000000c64303132333435363738393a3b3c3d3e3f6a"
              + "7f08b8003c0000a9be1d3a07e6c47cf0e2eb515d7b73e0d131e3fcf2dbcd9300");

  @Test
  void testGoReversePathOption() {
    ByteBuffer buffer = ByteBuffer.wrap(GO_EXTENSION);
    ExtensionHeader ext = ExtensionHeader.consume(buffer);
    assertEquals(HeaderConstants.HdrTypes.UDP, ext.nextHdr());
    assertEquals(152, buffer.position()); // the whole extension, the UDP header comes next
    // Its data starts after the 2 header bytes and the option's type and length.
    assertArrayEquals(Arrays.copyOfRange(GO_EXTENSION, 4, 4 + 147), ext.getOption(3));
    assertNull(ext.getOption(2));
  }

  /** Padding in front of the option: Pad1 (a single byte), then PadN (type 1) with no data. */
  @Test
  void testOptionAfterPadding() {
    byte[] bytes = {17, 1, 0, 1, 0, 3, 1, (byte) 0xab};
    ExtensionHeader ext = ExtensionHeader.consume(ByteBuffer.wrap(bytes));
    assertArrayEquals(new byte[] {(byte) 0xab}, ext.getOption(3));
  }

  @Test
  void testTruncatedOption() {
    // Option 3 claims 5 bytes of data, but the header ends after its length byte.
    byte[] bytes = {17, 0, 3, 5};
    ExtensionHeader ext = ExtensionHeader.consume(ByteBuffer.wrap(bytes));
    assertNull(ext.getOption(3)); // counts as missing
  }

  private static byte[] fromHex(String hex) {
    byte[] out = new byte[hex.length() / 2];
    for (int i = 0; i < out.length; i++) {
      out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
    }
    return out;
  }
}
