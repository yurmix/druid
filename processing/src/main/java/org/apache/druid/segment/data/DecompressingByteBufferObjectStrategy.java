/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.segment.data;

import org.apache.druid.collections.ResourceHolder;
import org.apache.druid.java.util.common.Pair;
import org.apache.druid.segment.CompressedPools;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ConcurrentHashMap;

public class DecompressingByteBufferObjectStrategy implements ObjectStrategy<ResourceHolder<ByteBuffer>>
{
  /**
   * Cache strategies in a static, because there are not very many distinct ones -- there are only so many combinations
   * of byte order and decompressor that we can possibly have -- and we need one of these per GenericIndexed, which
   * is a class that we tend to have tons of in heap.
   */
  private static final ConcurrentHashMap<Pair<ByteOrder, CompressionStrategy>, DecompressingByteBufferObjectStrategy> STRATEGIES =
      new ConcurrentHashMap<>();

  private final ByteOrder order;
  private final CompressionStrategy.Decompressor decompressor;

  private DecompressingByteBufferObjectStrategy(ByteOrder order, CompressionStrategy compression)
  {
    this.order = order;
    this.decompressor = compression.getDecompressor();
  }

  public static DecompressingByteBufferObjectStrategy of(
      final ByteOrder order,
      final CompressionStrategy compression
  )
  {
    return STRATEGIES.computeIfAbsent(
        Pair.of(order, compression),
        pair -> new DecompressingByteBufferObjectStrategy(pair.lhs, pair.rhs)
    );
  }

  @Override
  @SuppressWarnings("unchecked")
  public Class<ResourceHolder<ByteBuffer>> getClazz()
  {
    return (Class) ResourceHolder.class;
  }

  @Override
  public ResourceHolder<ByteBuffer> fromByteBuffer(ByteBuffer buffer, int numBytes)
  {
    final ResourceHolder<ByteBuffer> bufHolder = CompressedPools.getByteBuf(order);
    final ByteBuffer buf = bufHolder.get();
    buf.clear();

    decompressor.decompress(buffer, numBytes, buf);
    // Needed, because if e. g. if this compressed buffer contains 3-byte integers, it should be possible to getInt()
    // from the buffer, including padding. See CompressedVSizeColumnarIntsSupplier.bufferPadding().
    buf.limit(buf.capacity());
    return new ResourceHolder<>()
    {
      @Override
      public ByteBuffer get()
      {
        return buf;
      }

      @Override
      public void close()
      {
        bufHolder.close();
      }
    };
  }

  @Override
  public boolean canCompare()
  {
    return false;
  }

  @Override
  public int compare(ResourceHolder<ByteBuffer> o1, ResourceHolder<ByteBuffer> o2)
  {
    throw new UnsupportedOperationException();
  }

  @Override
  public byte[] toBytes(ResourceHolder<ByteBuffer> holder)
  {
    throw new UnsupportedOperationException();
  }
}
