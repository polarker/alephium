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

package org.alephium.flow.core

import java.math.BigInteger

import org.alephium.flow.Utils
import org.alephium.flow.setting.ConsensusSetting
import org.alephium.io.{IOResult, IOUtils}
import org.alephium.protocol.ALPH
import org.alephium.protocol.config.{BrokerConfig, NetworkConfig}
import org.alephium.protocol.model._
import org.alephium.util.{AVector, Cache, Duration, Math, TimeStamp}

trait FlowDifficultyAdjustment {
  implicit def brokerConfig: BrokerConfig
  implicit def consensusConfig: ConsensusSetting
  implicit def networkConfig: NetworkConfig

  def genesisHashes: AVector[AVector[BlockHash]]

  def getBlockHeaderUnsafe(hash: BlockHash): BlockHeader
  def getHeightUnsafe(hash: BlockHash): Int
  def getHeaderChain(hash: BlockHash): BlockHeaderChain
  def getHashChain(hash: BlockHash): BlockHashChain

  def getNextHashTarget(
      chainIndex: ChainIndex,
      deps: BlockDeps,
      nextTimeStamp: TimeStamp
  ): IOResult[Target] = {
    if (networkConfig.getHardFork(nextTimeStamp).isLemanEnabled()) {
      getNextHashTargetLeman(chainIndex, deps, nextTimeStamp)
    } else {
      getNextHashTargetGenesis(chainIndex, deps, nextTimeStamp)
    }
  }

  def getNextHashTargetGenesis(
      chainIndex: ChainIndex,
      deps: BlockDeps,
      nextTimeStamp: TimeStamp
  ): IOResult[Target] = {
    for {
      newTarget <- {
        val tip = deps.uncleHash(chainIndex.to)
        getHeaderChain(tip).getNextHashTargetRaw(tip, nextTimeStamp)
      }
      depTargets <- deps.deps.mapE(hash => getHeaderChain(hash).getTarget(hash))
    } yield {
      val weightedTarget = Target.average(newTarget, depTargets)
      val maxTarget      = depTargets.fold(weightedTarget)(Math.max)
      Target.clipByTwoTimes(maxTarget, weightedTarget)
    }
  }

  def getNextHashTargetLeman(
      chainIndex: ChainIndex,
      deps: BlockDeps,
      nextTimeStamp: TimeStamp
  ): IOResult[Target] = IOUtils.tryExecute {
    val commonIntraGroupDeps             = calCommonIntraGroupDepsUnsafe(deps, chainIndex.from)
    val (diffSum, timeSpanSum, oldestTs) = getDiffAndTimeSpanUnsafe(commonIntraGroupDeps)
    val diffAverage                      = diffSum.divide(brokerConfig.chainNum)
    val timeSpanAverage                  = timeSpanSum.divUnsafe(brokerConfig.chainNum.toLong)

    val chainDep  = deps.getOutDep(chainIndex.to)
    val heightGap = calHeightDiffUnsafe(chainDep, oldestTs)
    val targetDiff = if (triggerDiffPenaltyLeman(nextTimeStamp)) {
      consensusConfig.penalizeDiffForHeightGapLeman(diffAverage, heightGap)
    } else {
      diffAverage
    }
    ChainDifficultyAdjustment.calNextHashTargetRaw(targetDiff.getTarget(), timeSpanAverage)
  }

  // scalastyle:off magic.number
  val testnetDiffPenaltyTriggerTs =
    TimeStamp.unsafe(1674039600000L) // Jan 18 2023 12:00:00 GMT+0100
  // scalastyle:on magic.number
  // As testnet is running with Leman hardfork already, we add another activation timestamp to trigger the penalty.
  @inline def triggerDiffPenaltyLeman(nextTimeStamp: TimeStamp): Boolean = {
    networkConfig.networkId != NetworkId.AlephiumTestNet ||
    nextTimeStamp > testnetDiffPenaltyTriggerTs
  }

  @SuppressWarnings(Array("org.wartremover.warts.Recursion"))
  final def calHeightDiffUnsafe(chainDep: BlockHash, oldTimeStamp: TimeStamp): Int = {
    val header = getBlockHeaderUnsafe(chainDep)
    if (header.timestamp <= oldTimeStamp) {
      0
    } else {
      calHeightDiffUnsafe(header.parentHash, oldTimeStamp) + 1
    }
  }

  @SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
  def calCommonIntraGroupDepsUnsafe(
      deps: BlockDeps,
      mainGroup: GroupIndex
  ): AVector[BlockHash] = {
    val headerOfIntraDeps =
      deps.unorderedIntraDeps(mainGroup).view.map(intraDep => getBlockHeaderUnsafe(intraDep))
    brokerConfig.cliqueGroupIndexes.map { groupIndex =>
      headerOfIntraDeps
        .map { header =>
          if (header.isGenesis) {
            genesisHashes(groupIndex.value)(groupIndex.value) -> ALPH.GenesisHeight
          } else {
            val intraDep = header.getIntraDep(groupIndex)
            val height   = getHeightUnsafe(intraDep)
            intraDep -> height
          }
        }
        .minBy(_._2)
        ._1
    }
  }

  def getOutTips(header: BlockHeader): AVector[BlockHash]

  private[core] val diffAndTimeSpanCache = Cache.fifoSafe[BlockHash, (Difficulty, Duration)](
    consensusConfig.blockCacheCapacityPerChain * brokerConfig.chainNum * 8
  )
  def getDiffAndTimeSpanUnsafe(hash: BlockHash): (Difficulty, Duration) = {
    diffAndTimeSpanCache.get(hash).getOrElse {
      if (hash == BlockHash.zero) {
        (
          consensusConfig.maxMiningTarget.getDifficulty(),
          consensusConfig.expectedWindowTimeSpan
        )
      } else {
        val diff   = getBlockHeaderUnsafe(hash).target.getDifficulty()
        val height = getHeightUnsafe(hash)
        if (ChainDifficultyAdjustment.enoughHeight(height)) {
          val (timestampLast, timestampNow) =
            Utils.unsafe(getHashChain(hash).calTimeSpan(hash, height))
          (diff, timestampNow.deltaUnsafe(timestampLast))
        } else {
          (diff, consensusConfig.expectedWindowTimeSpan)
        }
      }
    }
  }

  private[core] val diffAndTimeSpanForIntraDepCache =
    Cache.fifoSafe[BlockHash, (Difficulty, Duration, TimeStamp)](
      consensusConfig.blockCacheCapacityPerChain * brokerConfig.chainNum * 8
    )
  def getDiffAndTimeSpanForIntraDepUnsafe(
      intraDep: BlockHash
  ): (Difficulty, Duration, TimeStamp) = {
    diffAndTimeSpanForIntraDepCache.get(intraDep).getOrElse {
      if (intraDep == BlockHash.zero) {
        (
          consensusConfig.maxMiningTarget.getDifficulty().times(brokerConfig.groups),
          consensusConfig.expectedWindowTimeSpan.timesUnsafe(brokerConfig.groups.toLong),
          ALPH.GenesisTimestamp
        )
      } else {
        assume(ChainIndex.from(intraDep).isIntraGroup)

        var diffSum     = Difficulty.zero.value
        var timeSpanSum = Duration.zero
        val outDeps     = getOutTips(getBlockHeaderUnsafe(intraDep))
        outDeps.foreach { dep =>
          val (diff, timeSpan) = getDiffAndTimeSpanUnsafe(dep)
          diffSum = diffSum.add(diff.value)
          timeSpanSum = timeSpanSum + timeSpan
        }
        @SuppressWarnings(Array("org.wartremover.warts.IterableOps"))
        val oldestTs = outDeps.view.map(dep => getHashChain(dep).getTimestampUnsafe(dep)).min
        (Difficulty.unsafe(diffSum), timeSpanSum, oldestTs)
      }
    }
  }

  def cacheDiffAndTimeSpan(header: BlockHeader): Unit = {
    diffAndTimeSpanCache.put(header.hash, getDiffAndTimeSpanUnsafe(header.hash))
    if (header.chainIndex.isIntraGroup) {
      diffAndTimeSpanForIntraDepCache.put(
        header.hash,
        getDiffAndTimeSpanForIntraDepUnsafe(header.hash)
      )
    }
  }

  def getDiffAndTimeSpanUnsafe(
      intraGroupDeps: AVector[BlockHash]
  ): (Difficulty, Duration, TimeStamp) = {
    var diffSum     = BigInteger.valueOf(0)
    var timeSpanSum = Duration.zero
    var oldestTs    = TimeStamp.Max
    intraGroupDeps.foreach { intraDep =>
      val (diff, timeSpan, intraOldestTs) = getDiffAndTimeSpanForIntraDepUnsafe(intraDep)
      diffSum = diffSum.add(diff.value)
      timeSpanSum = timeSpanSum + timeSpan
      if (oldestTs > intraOldestTs) {
        oldestTs = intraOldestTs
      }
    }
    (Difficulty.unsafe(diffSum), timeSpanSum, oldestTs)
  }
}
