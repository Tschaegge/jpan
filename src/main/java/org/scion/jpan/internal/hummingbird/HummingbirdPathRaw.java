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

package org.scion.jpan.internal.hummingbird;

import static org.scion.jpan.internal.util.ByteUtil.readInt;
import static org.scion.jpan.internal.util.ByteUtil.writeInt;

import java.nio.ByteBuffer;

public class HummingbirdPathRaw {

  private int currINF; //  2 bits
  private int currHF; //  8 bits — counts
  private int reserved; //  1 bit
  private final int[] segLen = new int[3]; //  7 bits each
  private int baseTsRaw; // 32 bits unsigned
  private int highResTsRaw; // 32 bits unsigned

  public void read(ByteBuffer data) {
    int i0 = data.getInt();
    currINF = readInt(i0, 0, 2);
    currHF = readInt(i0, 2, 8);
    reserved = readInt(i0, 10, 1);
    for (int i = 0; i < segLen.length; i++) {
      segLen[i] = readInt(i0, 11 + 7 * i, 7);
    }
    baseTsRaw = data.getInt();
    highResTsRaw = data.getInt();
  }

  public void write(ByteBuffer data) {
    int i0 = 0;
    i0 = writeInt(i0, 0, 2, currINF);
    i0 = writeInt(i0, 2, 8, currHF);
    i0 = writeInt(i0, 10, 1, reserved);
    for (int i = 0; i < segLen.length; i++) {
      i0 = writeInt(i0, 11 + 7 * i, 7, segLen[i]);
    }

    data.putInt(i0);
    data.putInt(baseTsRaw);
    data.putInt(highResTsRaw);
  }
}
