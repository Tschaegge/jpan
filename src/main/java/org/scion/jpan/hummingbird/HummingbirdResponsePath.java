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

package org.scion.jpan.hummingbird;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import org.scion.jpan.Path;
import org.scion.jpan.ResponsePath;
import org.scion.jpan.internal.header.HeaderConstants;

public class HummingbirdResponsePath extends ResponsePath {

  public HummingbirdResponsePath(
      byte[] rawPath,
      long srcIsdAs,
      InetAddress srcIP,
      int srcPort,
      long dstIsdAs,
      InetAddress dstIP,
      int dstPort,
      InetSocketAddress firstHop) {
    super(rawPath, srcIsdAs, srcIP, srcPort, dstIsdAs, dstIP, dstPort, firstHop);
  }

  @Override
  public int getPathType() {
    return HeaderConstants.PathTypes.HUMMINGBIRD.code();
  }

  @Override
  public Path copy(InetAddress dstIP, int dstPort) {
    return new HummingbirdResponsePath(
        getRawPath(),
        getLocalIsdAs(),
        getLocalAddress(),
        getLocalPort(),
        getRemoteIsdAs(),
        dstIP,
        dstPort,
        getFirstHopAddress());
  }
}
