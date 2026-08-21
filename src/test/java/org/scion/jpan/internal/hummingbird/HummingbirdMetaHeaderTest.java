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

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;

import org.junit.jupiter.api.Test;
import org.scion.jpan.testutil.HummingbirdExamplePacket;

class HummingbirdMetaHeaderTest {

    private static final int META_LEN = 12;

    @Test
    void testRoundTrip(){
        byte[] fixture = HummingbirdExamplePacket.PATH_RAW_HBIRD_112_111;

        HummingbirdPathRaw meta = new HummingbirdPathRaw();
        meta.read(ByteBuffer.wrap(fixture));

        System.out.println(fixture);
        System.out.println(ByteBuffer.wrap(fixture));

    }
}
