// Copyright 2023 ETH Zurich
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
import java.util.Arrays;
import org.scion.jpan.internal.util.ByteUtil;

public class ExtensionHeader {
  // 8 bit
  private int nextHdr;
  // 8 bit
  private int extLen;
  private int extLenBytes;
  // the options after the first two bytes: type, data length, data
  private byte[] options;

  /**
   * Read the extension header and consume the buffer.
   *
   * @param data incoming packet
   * @return the ExtensionHeader
   */
  public static ExtensionHeader consume(ByteBuffer data) {
    ExtensionHeader eh = new ExtensionHeader();
    eh.nextHdr = ByteUtil.toUnsigned(data.get());
    eh.extLen = ByteUtil.toUnsigned(data.get());
    eh.extLenBytes = (eh.extLen + 1) * 4;
    eh.options = new byte[eh.extLenBytes - 2];
    data.get(eh.options);
    // TODO validate checksum
    return eh;
  }

  /** The length of an extension header with these options, padded to a multiple of 4 bytes. */
  public static int length(byte[] options) {
    return (2 + options.length + 3) / 4 * 4;
  }

  /**
   * Writes an extension header: the next header, its length in 4-byte lines minus one, the options
   * and the padding to a multiple of 4 bytes.
   */
  public static void write(ByteBuffer data, int nextHdr, byte[] options) {
    int len = length(options);
    data.put((byte) nextHdr);
    data.put((byte) (len / 4 - 1));
    data.put(options);
    int pad = len - 2 - options.length;
    // As the Go prototype pads (serializeTLVOptionPadding): one byte is a Pad1, more are one PadN
    if (pad == 1) {
      data.put((byte) 0);
    } else if (pad > 1) {
      data.put((byte) 1).put((byte) (pad - 2)).put(new byte[pad - 2]);
    }
  }

  @Override
  public String toString() {
    return "ExtensionHeader{"
        + "nextHdr="
        + nextHdr
        + ", extLen="
        + extLen
        + ", options="
        + Arrays.toString(options)
        + '}';
  }

  /**
   * The data of the first option of the given type, or null if there is none. Each option is its
   * type, the length of its data and the data, except Pad1 (type 0), which is a single byte. An
   * option that reaches beyond the end of the header counts as missing.
   */
  public byte[] getOption(int type) {
    int i = 0;
    while (i < options.length) {
      int optType = ByteUtil.toUnsigned(options[i]);
      if (optType == 0) {
        i++; // Pad1
        continue;
      }
      if (i + 1 >= options.length || i + 2 + ByteUtil.toUnsigned(options[i + 1]) > options.length) {
        return null; // truncated
      }
      int optLen = ByteUtil.toUnsigned(options[i + 1]);
      if (optType == type) {
        return Arrays.copyOfRange(options, i + 2, i + 2 + optLen);
      }
      i += 2 + optLen;
    }
    return null;
  }

  public HeaderConstants.HdrTypes nextHdr() {
    return HeaderConstants.HdrTypes.parse(nextHdr);
  }

  public int getExtLenBytes() {
    return extLenBytes;
  }
}
