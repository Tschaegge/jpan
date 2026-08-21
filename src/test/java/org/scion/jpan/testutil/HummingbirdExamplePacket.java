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

public class HummingbirdExamplePacket {

  // TODO provenance javadoc
  // This is just the path header

  public static final byte[] PATH_RAW_HBIRD_112_111 = {
    0, 2, -124, 0, 106, 127, 8, -25, 18, 0, 0, 4, 0, 0, -69, -2,
    106, 127, 8, -66, 1, 0, -24, 92, 106, 127, 8, -71, -128, 63, 0, 1,
    0, 0, -29, -4, -14, -37, -51, -109, 0, 0, 7, -1, 0, 2, 0, 9,
    -128, 63, 0, 0, 0, 2, 93, 123, 115, -32, -47, 49, 0, 0, 7, -1,
    0, 2, 0, 9, 0, 63, 0, 0, 0, 1, -60, 124, -16, -30, -21, 81,
    -128, 63, 0, 41, 0, 0, -87, -66, 29, 58, 7, -26, 0, 0, 7, -1,
    0, 2, 0, 9,
  };

  // TODO provenance javadoc
  //this is the whole packet

  public static final byte[] PACKET_BYTES_HBIRD_112_111 = {
    0, 0, 0, 1, 17, 34, 5, 8, 5, 0, 0, 0, 0, 1, -1, 0,
    0, 0, 1, 17, 0, 1, -1, 0, 0, 0, 1, 18, 127, 0, 0, 1,
    127, 0, 0, 1, 0, 2, -124, 0, 106, 127, 8, -25, 18, 0, 0, 4,
    0, 0, -69, -2, 106, 127, 8, -66, 1, 0, -24, 92, 106, 127, 8, -71,
    -128, 63, 0, 1, 0, 0, -29, -4, -14, -37, -51, -109, 0, 0, 7, -1,
    0, 2, 0, 9, -128, 63, 0, 0, 0, 2, 93, 123, 115, -32, -47, 49,
    0, 0, 7, -1, 0, 2, 0, 9, 0, 63, 0, 0, 0, 1, -60, 124,
    -16, -30, -21, 81, -128, 63, 0, 41, 0, 0, -87, -66, 29, 58, 7, -26,
    0, 0, 7, -1, 0, 2, 0, 9,
  };
}
