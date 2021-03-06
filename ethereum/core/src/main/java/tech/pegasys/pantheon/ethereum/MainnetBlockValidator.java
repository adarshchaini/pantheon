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
package tech.pegasys.pantheon.ethereum;

import static org.apache.logging.log4j.LogManager.getLogger;

import tech.pegasys.pantheon.ethereum.chain.MutableBlockchain;
import tech.pegasys.pantheon.ethereum.core.Block;
import tech.pegasys.pantheon.ethereum.core.BlockHeader;
import tech.pegasys.pantheon.ethereum.core.MutableWorldState;
import tech.pegasys.pantheon.ethereum.core.TransactionReceipt;
import tech.pegasys.pantheon.ethereum.mainnet.BlockBodyValidator;
import tech.pegasys.pantheon.ethereum.mainnet.BlockHeaderValidator;
import tech.pegasys.pantheon.ethereum.mainnet.BlockProcessor;
import tech.pegasys.pantheon.ethereum.mainnet.HeaderValidationMode;

import java.util.List;
import java.util.Optional;

import org.apache.logging.log4j.Logger;

public class MainnetBlockValidator<C> implements BlockValidator<C> {

  private static final Logger LOG = getLogger();

  private final BlockHeaderValidator<C> blockHeaderValidator;

  private final BlockBodyValidator<C> blockBodyValidator;

  private final BlockProcessor blockProcessor;

  public MainnetBlockValidator(
      final BlockHeaderValidator<C> blockHeaderValidator,
      final BlockBodyValidator<C> blockBodyValidator,
      final BlockProcessor blockProcessor) {
    this.blockHeaderValidator = blockHeaderValidator;
    this.blockBodyValidator = blockBodyValidator;
    this.blockProcessor = blockProcessor;
  }

  @Override
  public Optional<BlockProcessingOutputs> validateAndProcessBlock(
      final ProtocolContext<C> context,
      final Block block,
      final HeaderValidationMode headerValidationMode,
      final HeaderValidationMode ommerValidationMode) {
    final BlockHeader header = block.getHeader();

    final Optional<BlockHeader> maybeParentHeader =
        context.getBlockchain().getBlockHeader(header.getParentHash());
    if (!maybeParentHeader.isPresent()) {
      LOG.error(
          "Attempted to import block {} with hash {} but parent block {} was not present",
          header.getNumber(),
          header.getHash(),
          header.getParentHash());
      return Optional.empty();
    }
    final BlockHeader parentHeader = maybeParentHeader.get();

    if (!blockHeaderValidator.validateHeader(header, parentHeader, context, headerValidationMode)) {
      return Optional.empty();
    }

    final MutableBlockchain blockchain = context.getBlockchain();
    final MutableWorldState worldState =
        context.getWorldStateArchive().getMutable(parentHeader.getStateRoot());
    final BlockProcessor.Result result = blockProcessor.processBlock(blockchain, worldState, block);
    if (!result.isSuccessful()) {
      return Optional.empty();
    }

    final List<TransactionReceipt> receipts = result.getReceipts();
    if (!blockBodyValidator.validateBody(
        context, block, receipts, worldState.rootHash(), ommerValidationMode)) {
      return Optional.empty();
    }

    return Optional.of(new BlockProcessingOutputs(worldState, receipts));
  }

  @Override
  public boolean fastBlockValidation(
      final ProtocolContext<C> context,
      final Block block,
      final List<TransactionReceipt> receipts,
      final HeaderValidationMode headerValidationMode) {
    final BlockHeader header = block.getHeader();
    if (!blockHeaderValidator.validateHeader(header, context, headerValidationMode)) {
      return false;
    }

    if (!blockBodyValidator.validateBodyLight(
        context, block, receipts, HeaderValidationMode.FULL)) {
      return false;
    }
    return true;
  }
}
