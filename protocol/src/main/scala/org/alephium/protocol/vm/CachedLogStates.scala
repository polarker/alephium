// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.protocol.vm

import scala.collection.mutable

import org.alephium.io._

final class CachedLogStates(
    val underlying: KeyValueStorage[LogStatesId, LogStates],
    val caches: mutable.Map[LogStatesId, Cache[LogStates]]
) extends CachedKV[LogStatesId, LogStates, Cache[LogStates]] {
  protected def getOptFromUnderlying(key: LogStatesId): IOResult[Option[LogStates]] = {
    underlying.getOpt(key).map { valueOpt =>
      valueOpt.foreach(value => caches.addOne(key -> Cached(value)))
      valueOpt
    }
  }

  def persist(): IOResult[KeyValueStorage[LogStatesId, LogStates]] = {
    underlying
      .putBatch { putAccumulate =>
        caches.foreach {
          case (_, Cached(_))         => Right(())
          case (key, Updated(value))  => putAccumulate(key, value)
          case (key, Inserted(value)) => putAccumulate(key, value)
          case (_, Removed())         => Right(()) // Should never be `Remove` in cache
        }
      }
      .map(_ => underlying)
  }

  def staging(): StagingLogStates = new StagingLogStates(this, mutable.Map.empty)
}

object CachedLogStates {
  def from(storage: KeyValueStorage[LogStatesId, LogStates]): CachedLogStates = {
    new CachedLogStates(storage, mutable.Map.empty)
  }
}