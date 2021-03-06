/*
 * Copyright 2019 ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package tech.pegasys.pantheon.ethereum.eth.sync.tasks;

import static java.util.Collections.emptyMap;
import static java.util.stream.Collectors.toList;
import static tech.pegasys.pantheon.ethereum.mainnet.BodyValidation.receiptsRoot;

import tech.pegasys.pantheon.ethereum.core.BlockHeader;
import tech.pegasys.pantheon.ethereum.core.Hash;
import tech.pegasys.pantheon.ethereum.core.TransactionReceipt;
import tech.pegasys.pantheon.ethereum.eth.manager.AbstractPeerRequestTask;
import tech.pegasys.pantheon.ethereum.eth.manager.EthContext;
import tech.pegasys.pantheon.ethereum.eth.manager.EthPeer;
import tech.pegasys.pantheon.ethereum.eth.manager.RequestManager.ResponseStream;
import tech.pegasys.pantheon.ethereum.eth.messages.EthPV63;
import tech.pegasys.pantheon.ethereum.eth.messages.ReceiptsMessage;
import tech.pegasys.pantheon.ethereum.p2p.api.MessageData;
import tech.pegasys.pantheon.ethereum.p2p.api.PeerConnection.PeerNotConnected;
import tech.pegasys.pantheon.metrics.LabelledMetric;
import tech.pegasys.pantheon.metrics.OperationTimer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class GetReceiptsFromPeerTask
    extends AbstractPeerRequestTask<Map<BlockHeader, List<TransactionReceipt>>> {
  private static final Logger LOG = LogManager.getLogger();

  private final Collection<BlockHeader> blockHeaders;
  private final Map<Hash, List<BlockHeader>> headersByReceiptsRoot = new HashMap<>();

  private GetReceiptsFromPeerTask(
      final EthContext ethContext,
      final Collection<BlockHeader> blockHeaders,
      final LabelledMetric<OperationTimer> ethTasksTimer) {
    super(ethContext, EthPV63.GET_RECEIPTS, ethTasksTimer);
    this.blockHeaders = blockHeaders;
    blockHeaders.forEach(
        header ->
            headersByReceiptsRoot
                .computeIfAbsent(header.getReceiptsRoot(), key -> new ArrayList<>())
                .add(header));
  }

  public static GetReceiptsFromPeerTask forHeaders(
      final EthContext ethContext,
      final Collection<BlockHeader> blockHeaders,
      final LabelledMetric<OperationTimer> ethTasksTimer) {

    return new GetReceiptsFromPeerTask(ethContext, blockHeaders, ethTasksTimer);
  }

  @Override
  protected ResponseStream sendRequest(final EthPeer peer) throws PeerNotConnected {
    LOG.debug("Requesting {} receipts from peer {}.", blockHeaders.size(), peer);
    // Since we have to match up the data by receipt root, we only need to request receipts
    // for one of the headers with each unique receipt root.
    final List<Hash> blockHashes =
        headersByReceiptsRoot
            .values()
            .stream()
            .map(headers -> headers.get(0).getHash())
            .collect(toList());
    return peer.getReceipts(blockHashes);
  }

  @Override
  protected Optional<Map<BlockHeader, List<TransactionReceipt>>> processResponse(
      final boolean streamClosed, final MessageData message, final EthPeer peer) {
    if (streamClosed) {
      // All outstanding requests have been responded to and we still haven't found the response
      // we wanted. It must have been empty or contain data that didn't match.
      peer.recordUselessResponse();
      return Optional.of(emptyMap());
    }

    final ReceiptsMessage receiptsMessage = ReceiptsMessage.readFrom(message);
    final List<List<TransactionReceipt>> receiptsByBlock = receiptsMessage.receipts();
    if (receiptsByBlock.isEmpty()) {
      return Optional.empty();
    } else if (receiptsByBlock.size() > blockHeaders.size()) {
      return Optional.empty();
    }

    final Map<BlockHeader, List<TransactionReceipt>> receiptsByHeader = new HashMap<>();
    for (final List<TransactionReceipt> receiptsInBlock : receiptsByBlock) {
      final List<BlockHeader> blockHeaders =
          headersByReceiptsRoot.get(receiptsRoot(receiptsInBlock));
      if (blockHeaders == null) {
        // Contains receipts that we didn't request, so mustn't be the response we're looking for.
        return Optional.empty();
      }
      blockHeaders.forEach(header -> receiptsByHeader.put(header, receiptsInBlock));
    }
    return Optional.of(receiptsByHeader);
  }

  @Override
  protected Optional<EthPeer> findSuitablePeer() {
    final long maximumRequiredBlockNumber =
        blockHeaders
            .stream()
            .mapToLong(BlockHeader::getNumber)
            .max()
            .orElse(BlockHeader.GENESIS_BLOCK_NUMBER);
    return this.ethContext.getEthPeers().idlePeer(maximumRequiredBlockNumber);
  }
}
