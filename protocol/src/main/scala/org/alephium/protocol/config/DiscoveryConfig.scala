package org.alephium.protocol.config

import org.alephium.crypto.{ALFPrivateKey, ALFPublicKey}
import org.alephium.util.Duration

// TODO: refactor this as two configs
trait DiscoveryConfig {
  def discoveryPrivateKey: ALFPrivateKey

  def discoveryPublicKey: ALFPublicKey

  /* Maximum number of peers to track. */
  def peersPerGroup: Int

  /* Maximum number of peers used for probing during a scan. */
  def scanMaxPerGroup: Int

  /* Wait time between two scan. */
  def scanFrequency: Duration

  def scanFastFrequency: Duration

  /* Maximum number of peers returned from a query (`k` in original kademlia paper). */
  def neighborsPerGroup: Int

  /** Duration we wait before considering a peer dead. **/
  def peersTimeout: Duration = scanFrequency.timesUnsafe(3)

  def expireDuration: Duration = Duration.ofHoursUnsafe(1)
}
