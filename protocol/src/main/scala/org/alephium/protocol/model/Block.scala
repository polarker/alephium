package org.alephium.protocol.model

import java.math.BigInteger

import org.alephium.crypto.{Keccak256, WithKeccak256}
import org.alephium.serde.Serde

case class Block(blockHeader: BlockHeader, transactions: Seq[Transaction])
    extends WithKeccak256[Block] {
  def prevBlockHash: Keccak256 = blockHeader.blockDeps.last
}

object Block {
  implicit val serde: Serde[Block] = Serde.forProduct2(apply, b => (b.blockHeader, b.transactions))

  def from(blockDeps: Seq[Keccak256], transactions: Seq[Transaction], nonce: BigInteger): Block = {
    // TODO: validate all the block dependencies; the first block dep should be previous block in the same chain
    val txsHash     = Keccak256.hash(transactions)
    val timestamp   = System.currentTimeMillis()
    val blockHeader = BlockHeader(blockDeps, txsHash, timestamp, nonce)
    Block(blockHeader, transactions)
  }
}
