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

import static org.scion.jpan.internal.util.ByteUtil.readInt;
import static org.scion.jpan.internal.util.ByteUtil.writeInt;

import java.nio.ByteBuffer;
import org.scion.jpan.internal.util.ByteUtil;

public class HummingbirdPathConverter {

  public static byte[] convertFromScion(byte[] scionPath, long baseTs, int millis, int counter) {

    int i0 = ByteBuffer.wrap(scionPath).getInt();

    int currINF = readInt(i0, 0, 2);
    int currHF = readInt(i0, 2, 6);
    int[] segLen = new int[3];
    segLen[0] = readInt(i0, 14, 6);
    segLen[1] = readInt(i0, 20, 6);
    segLen[2] = readInt(i0, 26, 6);

    int i1 = 0;
    i1 = writeInt(i1, 0, 2, currINF);
    i1 = writeInt(i1, 2, 8, currHF * HummingbirdPathRaw.HOP_LINES);
    i1 = writeInt(i1, 11, 7, segLen[0] * HummingbirdPathRaw.HOP_LINES);
    i1 = writeInt(i1, 18, 7, segLen[1] * HummingbirdPathRaw.HOP_LINES);
    i1 = writeInt(i1, 25, 7, segLen[2] * HummingbirdPathRaw.HOP_LINES);

    byte[] out = new byte[scionPath.length + 8]; // adjust for increased meta header length
    ByteBuffer bb = ByteBuffer.wrap(out);
    bb.putInt(i1);
    bb.putInt(ByteUtil.toInt(baseTs));
    bb.putInt((millis << 22) | counter);
    System.arraycopy(scionPath, 4, out, 12, scionPath.length - 4);
    return out;
  }

  /*
  This method assures that on segment boundaries only one segment is assigned as flyover field. See A.5
  */
  public static int[] flyoverEligibleHopFields(HummingbirdPathRaw path) {
    int segments = path.getSegmentCount();
    if (segments == 0) {
      return new int[0];
    }
    int[] eligible = new int[path.getHopFieldCount()];
    int n = 0;
    for (int seg = 0; seg < segments; seg++) {
      int first = path.getFirstHopOfSegment(seg);
      int hops = path.getSegmentHopCount(seg);
    }

    return null;
  }
}
