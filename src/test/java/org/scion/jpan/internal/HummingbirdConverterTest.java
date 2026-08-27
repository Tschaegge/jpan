package org.scion.jpan.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.scion.jpan.internal.header.HummingbirdPathConverter;
import org.scion.jpan.internal.header.HummingbirdPathRaw;
import org.scion.jpan.testutil.ExamplePacket;

public class HummingbirdConverterTest {

  private static final byte[] scionPathUCD = ExamplePacket.PATH_RAW_UP_CORE_DOWN;
  private static final byte[] scionPathtiny = ExamplePacket.PATH_RAW_TINY_110_112;

  @Test
  void testConvertUpCoreDown() {
    byte[] out = HummingbirdPathConverter.convertFromScion(scionPathUCD, 1786710247L, 72, 4);
    assertEquals(120, out.length);
  }

  void testConvertTiny() {
    byte[] out = HummingbirdPathConverter.convertFromScion(scionPathtiny, 1786710247L, 72, 4);
    HummingbirdPathRaw h = HummingbirdPathRaw.create(out);
    assertEquals(44, h.length());
    assertEquals(6, h.getSegLen(0));
    assertEquals(0, h.getSegLen(1));
    assertEquals(0, h.getSegLen(2));
    assertEquals(1, h.getSegmentCount());
    assertEquals(2, h.getHopFieldCount());
    assertEquals(1786710247L, h.getBaseTimestamp());
    assertEquals(72, h.getMillis());
    assertEquals(4, h.getCounter());
  }
}
