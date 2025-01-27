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

package org.alephium.flow.handler

import akka.actor.ActorSystem
import akka.testkit.{EventFilter, TestActorRef, TestProbe}
import akka.util.Timeout
import org.scalacheck.Gen

import org.alephium.flow.{AlephiumFlowActorSpec, FlowFixture}
import org.alephium.flow.core.BlockFlowState
import org.alephium.flow.core.BlockFlowState.MemPooled
import org.alephium.flow.model.PersistedTxId
import org.alephium.flow.network.{InterCliqueManager, IntraCliqueManager}
import org.alephium.flow.network.broker.BrokerHandler
import org.alephium.flow.validation.NonExistInput
import org.alephium.protocol.ALPH
import org.alephium.protocol.model._
import org.alephium.serde.serialize
import org.alephium.util._

class TxHandlerSpec extends AlephiumFlowActorSpec {

  it should "add intra-clique transactions to mempool" in new Fixture {
    brokerConfig.brokerNum is 3
    brokerConfig.brokerId is 0
    override lazy val chainIndex = ChainIndex.unsafe(1, 0)
    val tx = (new FlowFixture {
      override val configValues = Map(("alephium.broker.broker-id", 1))
      val block                 = transfer(blockFlow, chainIndex)
      val tx                    = block.nonCoinbase.head
    }).tx

    setSynced()

    txHandler ! addTx(tx, isIntraCliqueSyncing = true)
    val mempool = blockFlow.getMemPool(GroupIndex.unsafe(0))
    eventually {
      mempool.contains(tx.id) is true
      brokerConfig.cliqueChainIndexes.foreach { index =>
        val n = mempool.flow.takeSourceNodes(index.flattenIndex, Int.MaxValue, identity).length
        if (index != chainIndex) {
          n is 0
        } else {
          n is 1
        }
      }
    }
  }

  it should "broadcast valid transactions for single-broker clique" in new Fixture {
    override val configValues = Map(
      ("alephium.mempool.batch-broadcast-txs-frequency", "500 ms"),
      ("alephium.broker.groups", 4),
      ("alephium.broker.broker-num", 1),
      ("alephium.broker.broker-id", 0)
    )

    setSynced()

    val txs = prepareRandomSequentialTxs(groupConfig.groups)
    txs.length is 4
    txHandler.underlyingActor.txsBuffer.isEmpty is true

    val txs0 = txs.take(2)
    checkInterCliqueBroadcast(txs0)
    intraCliqueProbe.expectNoMessage()

    val txs1 = txs.drop(2)
    checkInterCliqueBroadcast(txs1)
    intraCliqueProbe.expectNoMessage()

    // won't broadcast when there are no txs in buffer
    interCliqueProbe.expectNoMessage()
  }

  it should "broadcast valid transactions for multi-broker clique" in new Fixture {
    override lazy val chainIndex = ChainIndex.unsafe(0, 1)

    setSynced()

    val block = transfer(blockFlow, chainIndex)
    val txs   = block.nonCoinbase
    checkInterCliqueBroadcast(txs)
    val broadcastMsg = AVector(chainIndex -> txs.map(_.toTemplate))
    intraCliqueProbe.expectMsg(IntraCliqueManager.BroadCastTx(broadcastMsg))
  }

  it should "not broadcast invalid tx" in new Fixture {
    setSynced()
    val tx = transactionGen(chainIndexGen = Gen.const(chainIndex)).sample.get
    txHandler ! addTx(tx)
    expectMsg(
      TxHandler.AddFailed(
        tx.id,
        s"Failed in validating tx ${tx.id.toHexString} due to ${NonExistInput}: ${hex(tx)}"
      )
    )
    interCliqueProbe.expectNoMessage()
  }

  it should "load persisted pending txs only once when node synced" in new FlowFixture {
    implicit lazy val system = createSystem(Some(AlephiumActorSpec.infoConfig))
    val txHandler = TestActorRef[TxHandler](
      TxHandler.props(blockFlow, storages.pendingTxStorage)
    )

    EventFilter.info(start = "Start to load", occurrences = 0).intercept {
      txHandler ! InterCliqueManager.SyncedResult(false)
    }

    EventFilter.info(start = "Start to load", occurrences = 1).intercept {
      txHandler ! InterCliqueManager.SyncedResult(true)
    }

    EventFilter.info(start = "Start to load", occurrences = 0).intercept {
      txHandler ! InterCliqueManager.SyncedResult(true)
      txHandler ! InterCliqueManager.SyncedResult(true)
    }
  }

  it should "load all of the pending txs once the node is synced" in new Fixture {
    override val configValues = Map(("alephium.broker.broker-num", 1))

    val txs     = prepareRandomSequentialTxs(4)
    val startTs = TimeStamp.now()
    txs.foreachWithIndex { case (tx, index) =>
      storages.pendingTxStorage.put(
        PersistedTxId(startTs.plusSecondsUnsafe(index.toLong), tx.id),
        tx.toTemplate
      ) isE ()
    }
    blockFlow.getGrandPool().mempools.foreach(_.size is 0)

    setSynced()
    eventually {
      blockFlow.getGrandPool().getOutTxsWithTimestamp().map(_._2.id).sorted is
        txs.map(_.id).sorted
    }
  }

  it should "persist all of the pending txs once the handler is stopped" in new Fixture {
    implicit lazy val system  = createSystem(Some(AlephiumActorSpec.infoConfig))
    override val configValues = Map(("alephium.broker.broker-num", 1))

    val txs = prepareRandomSequentialTxs(4)
    txs.foreach(tx => blockFlow.getGrandPool().add(tx.chainIndex, tx.toTemplate, TimeStamp.now()))
    blockFlow.getGrandPool().getOutTxsWithTimestamp().map(_._2.id).sorted is
      txs.map(_.id).sorted
    checkPersistedTxs(AVector.empty)

    system.stop(txHandler)
    eventually(checkPersistedTxs(txs))
  }

  it should "fail in case of duplicate txs" in new Fixture {
    override val configValues = Map(("alephium.mempool.batch-broadcast-txs-frequency", "200 ms"))

    val tx = transferTxs(blockFlow, chainIndex, ALPH.alph(1), 1, None, true, None).head

    setSynced()
    txHandler ! addTx(tx)
    expectMsg(TxHandler.AddSucceeded(tx.id))
    interCliqueProbe.expectMsg(
      InterCliqueManager.BroadCastTx(AVector((chainIndex, AVector(tx.id))))
    )

    EventFilter.warning(pattern = ".*already existed.*").intercept {
      txHandler ! addTx(tx)
      expectMsg(
        TxHandler.AddFailed(tx.id, s"tx ${tx.id.toHexString} is already included")
      )
      interCliqueProbe.expectNoMessage()
    }
  }

  it should "fail in double-spending" in new Fixture {
    override val configValues = Map(("alephium.mempool.batch-broadcast-txs-frequency", "200 ms"))

    val tx0 = transferTxs(blockFlow, chainIndex, ALPH.alph(1), 1, None, true, None).head
    val tx1 = transferTxs(blockFlow, chainIndex, ALPH.alph(2), 1, None, true, None).head

    setSynced()
    txHandler ! addTx(tx0)
    expectMsg(TxHandler.AddSucceeded(tx0.id))
    interCliqueProbe.expectMsg(
      InterCliqueManager.BroadCastTx(AVector((chainIndex, AVector(tx0.id))))
    )

    EventFilter.warning(pattern = ".*double spending.*").intercept {
      txHandler ! addTx(tx1)
      expectMsg(
        TxHandler
          .AddFailed(tx1.id, s"tx ${tx1.id.shortHex} is double spending: ${hex(tx1)}")
      )
      interCliqueProbe.expectNoMessage()
    }
  }

  it should "download txs" in new Fixture {
    override val configValues = Map(
      ("alephium.mempool.batch-broadcast-txs-frequency", "200 ms"),
      ("alephium.broker.groups", 4),
      ("alephium.broker.broker-num", 1),
      ("alephium.broker.broker-id", 0)
    )

    def sendAnnouncement(
        chainIndex: ChainIndex,
        txId: TransactionId,
        haveAnnouncement: Boolean
    ): (TestProbe, AVector[(ChainIndex, AVector[TransactionId])]) = {
      val brokerHandler = TestProbe()
      val announcement  = TxHandler.Announcement(ActorRefT(brokerHandler.ref), chainIndex, txId)
      brokerHandler.send(txHandler, TxHandler.TxAnnouncements(AVector((chainIndex, AVector(txId)))))
      eventually {
        txHandler.underlyingActor.fetching.states.contains(txId) is true
        txHandler.underlyingActor.announcements.contains(announcement) is haveAnnouncement
      }
      brokerHandler -> AVector(chainIndex -> AVector(txId))
    }

    val chain01     = ChainIndex.unsafe(0, 1)
    val txHash1     = TransactionId.generate
    val maxCapacity = (brokerConfig.groupNumPerBroker * brokerConfig.groups * 10) * 32

    setSynced()
    txHandler.underlyingActor.txBufferMaxCapacity is maxCapacity
    txHandler.underlyingActor.announcements.isEmpty is true

    (0 until TxHandler.MaxDownloadTimes)
      .map(_ => sendAnnouncement(chain01, txHash1, true))
      .foreach { case (brokerHandler, hashes) =>
        brokerHandler.expectMsg(BrokerHandler.DownloadTxs(hashes))
      }
    eventually(txHandler.underlyingActor.announcements.isEmpty is true)

    val (brokerHandler, _) = sendAnnouncement(chain01, txHash1, false)
    brokerHandler.expectNoMessage()
    txHandler.underlyingActor.announcements.isEmpty is true

    val chain02 = ChainIndex.unsafe(0, 2)
    val chain03 = ChainIndex.unsafe(0, 3)
    val tx2     = transactionGen(chainIndexGen = Gen.const(chain02)).sample.get.toTemplate
    val txHash3 = TransactionId.generate
    val txHash4 = TransactionId.generate
    val mempool = blockFlow.getMemPool(chain02)
    mempool.contains(tx2.id) is false
    blockFlow.getGrandPool().add(chain02, tx2, TimeStamp.now())
    mempool.contains(tx2.id) is true

    txHandler ! TxHandler.TxAnnouncements(
      AVector(
        (chain01, AVector(txHash1, txHash3)),
        (chain02, AVector(tx2.id))
      )
    )
    txHandler ! TxHandler.TxAnnouncements(
      AVector(
        (chain03, AVector(txHash4))
      )
    )
    expectMsg(
      BrokerHandler.DownloadTxs(
        AVector(
          (chain01, AVector(txHash3)),
          (chain03, AVector(txHash4))
        )
      )
    )
    eventually(txHandler.underlyingActor.announcements.isEmpty is true)
  }

  trait PeriodicTaskFixture extends FlowFixture {
    implicit lazy val system: ActorSystem = createSystem(Some(AlephiumActorSpec.debugConfig))

    def test(message: String) = {
      EventFilter.debug(message, occurrences = 5).intercept {
        val txHandler = system.actorOf(
          TxHandler.props(blockFlow, storages.pendingTxStorage)
        )
        txHandler ! InterCliqueManager.SyncedResult(true)
      }
    }
  }

  it should "broadcast txs regularly" in new PeriodicTaskFixture {
    override val configValues = Map(("alephium.mempool.batch-broadcast-txs-frequency", "300 ms"))

    test("Start to broadcast txs")
  }

  it should "download txs regularly" in new PeriodicTaskFixture {
    override val configValues = Map(("alephium.mempool.batch-download-txs-frequency", "300 ms"))

    test("Start to download txs")
  }

  it should "clean mempools regularly" in new PeriodicTaskFixture {
    override val configValues = Map(("alephium.mempool.clean-mempool-frequency", "300 ms"))

    test("Start to clean mempools")
  }

  it should "reject tx with low gas price" in new Fixture {
    override val configValues = Map(("alephium.broker.broker-num", 1))

    val tx            = transactionGen().sample.get
    val lowGasPriceTx = tx.copy(unsigned = tx.unsigned.copy(gasPrice = minimalGasPrice))

    txHandler ! addTx(lowGasPriceTx)
    expectMsg(
      TxHandler.AddFailed(lowGasPriceTx.id, s"tx has lower gas price than ${defaultGasPrice}")
    )
  }

  it should "check gas price" in new Fixture {
    val tx            = transactionGen().sample.get.toTemplate
    val lowGasPriceTx = tx.copy(unsigned = tx.unsigned.copy(gasPrice = minimalGasPrice))
    TxHandler.checkHighGasPrice(tx) is true
    TxHandler.checkHighGasPrice(lowGasPriceTx) is false
    TxHandler.checkHighGasPrice(
      ALPH.LaunchTimestamp.plusUnsafe(Duration.ofDaysUnsafe(366 + 365 / 2)),
      lowGasPriceTx
    ) is true
  }

  it should "mine new block if auto-mine is enabled" in new Fixture {
    override val configValues = Map(("alephium.mempool.auto-mine-for-dev", true))
    config.mempool.autoMineForDev is true

    val block = transfer(blockFlow, chainIndex)
    val tx    = block.transactions.head
    txHandler ! addTx(tx)
    expectMsg(TxHandler.AddSucceeded(tx.id))
    eventually(blockFlow.getMemPool(chainIndex).size is 0)

    val status = blockFlow.getTransactionStatus(tx.id, chainIndex).rightValue.get
    status is a[BlockFlowState.Confirmed]
    val confirmed = status.asInstanceOf[BlockFlowState.Confirmed]
    confirmed.chainConfirmations is 1
    confirmed.fromGroupConfirmations is 1
    confirmed.toGroupConfirmations is 1
    val blockHash = confirmed.index.hash
    blockFlow.getBestDeps(chainIndex.from).deps.contains(blockHash) is true
  }

  it should "auto mine new blocks if auto-mine is enabled" in new Fixture {
    override val configValues = Map(("alephium.mempool.auto-mine-for-dev", true))
    config.mempool.autoMineForDev is true
    val old = blockFlow.getBlockChain(chainIndex).maxHeight.rightValue
    TxHandler.forceMineForDev(blockFlow, chainIndex, Env.Prod, _ => ()) is Right(())
    (old + 1) is blockFlow.getBlockChain(chainIndex).maxHeight.rightValue
    txHandler ! TxHandler.MineOneBlock(chainIndex)
    eventually(
      (old + 2) is blockFlow.getBlockChain(chainIndex).maxHeight.rightValue
    )
  }

  it should "auto mine new blocks if env is not PROD" in new Fixture {
    override val configValues = Map(("alephium.mempool.auto-mine-for-dev", false))
    config.mempool.autoMineForDev is false
    TxHandler.forceMineForDev(blockFlow, chainIndex, Env.Test, _ => ()) isE ()
    TxHandler.forceMineForDev(blockFlow, chainIndex, Env.Prod, _ => ()).isLeft is true
  }

  it should "check force mine block for dev if auto-mine is disabled" in new Fixture {
    override val configValues = Map(("alephium.mempool.auto-mine-for-dev", false))
    config.mempool.autoMineForDev is false
    val old = blockFlow.getBlockChain(chainIndex).maxHeight.rightValue
    TxHandler.forceMineForDev(blockFlow, chainIndex, Env.Prod, _ => ()) is Left(
      "CPU mining for dev is not enabled, please turn it on in config:\n alephium.mempool.auto-mine-for-dev = true"
    )
    old is blockFlow.getBlockChain(chainIndex).maxHeight.rightValue
    txHandler ! TxHandler.MineOneBlock(chainIndex)
    eventually(
      old is blockFlow.getBlockChain(chainIndex).maxHeight.rightValue
    )
  }

  it should "mine new block for inter-group chain if auto-mine is enabled" in new Fixture {
    override val configValues =
      Map(("alephium.broker.broker-num", 1), ("alephium.mempool.auto-mine-for-dev", true))
    config.mempool.autoMineForDev is true

    val index            = ChainIndex.unsafe(0, 1)
    val (privKey0, _, _) = genesisKeys(0)
    val (_, pubKey1, _)  = genesisKeys(1)
    val genesisAddress0  = getGenesisLockupScript(index.from)
    val genesisAddress1  = getGenesisLockupScript(index.to)
    val balance0         = blockFlow.getBalance(genesisAddress0, Int.MaxValue).rightValue._1
    val balance1         = blockFlow.getBalance(genesisAddress1, Int.MaxValue).rightValue._1

    val block = transfer(blockFlow, privKey0, pubKey1, ALPH.oneAlph)
    val tx    = block.transactions.head
    txHandler ! addTx(tx)
    expectMsg(TxHandler.AddSucceeded(tx.id))
    eventually(blockFlow.getMemPool(index).size is 0)

    val status = blockFlow.getTransactionStatus(tx.id, index).rightValue.get
    status is a[BlockFlowState.Confirmed]
    val confirmed = status.asInstanceOf[BlockFlowState.Confirmed]
    confirmed.chainConfirmations is 1
    confirmed.fromGroupConfirmations is 1
    confirmed.toGroupConfirmations is 0
    val blockHash = confirmed.index.hash
    blockFlow.getBestDeps(index.from).deps.contains(blockHash) is true

    val balance01 = blockFlow.getBalance(genesisAddress0, Int.MaxValue).rightValue._1
    val balance11 = blockFlow.getBalance(genesisAddress1, Int.MaxValue).rightValue._1
    (balance01 < balance0.subUnsafe(ALPH.oneAlph)) is true // due to gas fee
    balance11 is balance1.addUnsafe(ALPH.oneAlph)

    val block0 = transfer(blockFlow, ChainIndex.unsafe(0, 0))
    val block1 = transfer(blockFlow, ChainIndex.unsafe(1, 1))
    addAndCheck(blockFlow, block0)
    addAndCheck(blockFlow, block1)
    val balance02 = blockFlow.getBalance(genesisAddress0, Int.MaxValue).rightValue._1
    val balance12 = blockFlow.getBalance(genesisAddress1, Int.MaxValue).rightValue._1
    balance02 is balance01.subUnsafe(ALPH.oneAlph)
    balance12 is balance11.subUnsafe(ALPH.oneAlph)
  }

  trait Fixture extends FlowFixture with TxGenerators {
    implicit val timeout: Timeout = Timeout(Duration.ofSecondsUnsafe(2).asScala)

    // use lazy here because we want to override config values
    lazy val chainIndex = ChainIndex.unsafe(0, 0)
    lazy val txHandler =
      newTestActorRef[TxHandler](
        TxHandler.props(blockFlow, storages.pendingTxStorage)
      )

    def addTx(tx: Transaction, isIntraCliqueSyncing: Boolean = false) =
      TxHandler.AddToMemPool(AVector(tx.toTemplate), isIntraCliqueSyncing)
    def hex(tx: Transaction) = Hex.toHexString(serialize(tx.toTemplate))
    def setSynced() = {
      txHandler ! InterCliqueManager.SyncedResult(true)
      eventually {
        val result = txHandler
          .ask(InterCliqueManager.IsSynced)
          .mapTo[InterCliqueManager.SyncedResult]
          .futureValue
        result.isSynced is true
      }
    }

    lazy val interCliqueProbe = TestProbe()
    system.eventStream.subscribe(interCliqueProbe.ref, classOf[InterCliqueManager.BroadCastTx])
    lazy val intraCliqueProbe = TestProbe()
    system.eventStream.subscribe(intraCliqueProbe.ref, classOf[IntraCliqueManager.BroadCastTx])

    def checkInterCliqueBroadcast(txs: AVector[Transaction]) = {
      txs.foreach(txHandler ! addTx(_))
      txs.foreach(tx => expectMsg(TxHandler.AddSucceeded(tx.id)))
      interCliqueProbe.expectMsgPF() { case InterCliqueManager.BroadCastTx(indexedHashes) =>
        val hashes = indexedHashes.flatMap(_._2)
        hashes.length is txs.length
        hashes.contains(txs.head.id) is true
        hashes.contains(txs.last.id) is true
      }
      // use eventually here to avoid test failure on windows
      eventually(txHandler.underlyingActor.txsBuffer.isEmpty is true)
      txs.foreach { tx =>
        txHandler.underlyingActor.blockFlow.getTransactionStatus(tx.id, tx.chainIndex) isE
          Option(MemPooled)
      }
    }

    def checkPersistedTxs(txs: AVector[Transaction]) = {
      var buffer = AVector.empty[TransactionId]
      storages.pendingTxStorage.iterate { case (persistedId, _) =>
        buffer = buffer :+ persistedId.txId
      }
      buffer.sorted is txs.map(_.id).sorted
    }
  }
}
