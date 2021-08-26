/*
 * Copyright ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package org.hyperledger.besu.ethereum.eth.transactions.sorter;

import static java.util.Comparator.comparing;
import static java.util.stream.Collectors.toUnmodifiableList;
import static org.hyperledger.besu.ethereum.eth.transactions.sorter.AbstractPendingTransactionsSorter.TransactionAddedStatus.ADDED;
import static org.hyperledger.besu.ethereum.eth.transactions.sorter.AbstractPendingTransactionsSorter.TransactionAddedStatus.ALREADY_KNOWN;

import org.hyperledger.besu.ethereum.core.Block;
import org.hyperledger.besu.ethereum.core.BlockHeader;
import org.hyperledger.besu.ethereum.core.Transaction;
import org.hyperledger.besu.plugin.services.MetricsSystem;
import org.hyperledger.besu.util.number.Percentage;

import java.time.Clock;
import java.util.Comparator;
import java.util.Iterator;
import java.util.NavigableSet;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Holds the current set of pending transactions with the ability to iterate them based on priority
 * for mining or look-up by hash.
 *
 * <p>This class is safe for use across multiple threads.
 */
public class BaseFeePendingTransactionsSorter extends AbstractPendingTransactionsSorter {

  private static final Logger LOG = LogManager.getLogger();

  private Optional<Long> baseFee;

  public BaseFeePendingTransactionsSorter(
      final int maxTransactionRetentionHours,
      final int maxPendingTransactions,
      final int maxPooledTransactionHashes,
      final Clock clock,
      final MetricsSystem metricsSystem,
      final Supplier<BlockHeader> chainHeadHeaderSupplier,
      final Percentage priceBump) {
    super(
        maxTransactionRetentionHours,
        maxPendingTransactions,
        maxPooledTransactionHashes,
        clock,
        metricsSystem,
        chainHeadHeaderSupplier,
        priceBump);
    this.baseFee = chainHeadHeaderSupplier.get().getBaseFee();
  }

  /**
   * See this post for an explainer about these data structures:
   * https://hackmd.io/@adietrichs/1559-transaction-sorting
   */
  private final NavigableSet<TransactionInfo> prioritizedTransactionsStaticRange =
      new TreeSet<>(
          comparing(TransactionInfo::isReceivedFromLocalSource)
              .thenComparing(
                  transactionInfo ->
                      transactionInfo
                          .getTransaction()
                          .getMaxPriorityFeePerGas()
                          // safe to .get() here because only 1559 txs can be in the static range
                          .get()
                          .getValue()
                          .longValue())
              .thenComparing(this::distanceFromNextNonce)
              .thenComparing(TransactionInfo::getSequence)
              .reversed());

  private final NavigableSet<TransactionInfo> prioritizedTransactionsDynamicRange =
      new TreeSet<>(
          comparing(TransactionInfo::isReceivedFromLocalSource)
              .thenComparing(
                  transactionInfo ->
                      transactionInfo
                          .getTransaction()
                          .getMaxFeePerGas()
                          .map(maxFeePerGas -> maxFeePerGas.getValue().longValue())
                          .orElse(transactionInfo.getGasPrice().toLong()))
              .thenComparing(this::distanceFromNextNonce)
              .thenComparing(TransactionInfo::getSequence)
              .reversed());

  @Override
  public void manageBlockAdded(final Block block) {
    block.getHeader().getBaseFee().ifPresent(this::updateBaseFee);
  }

  @Override
  protected void doRemoveTransaction(final Transaction transaction, final boolean addedToBlock) {
    synchronized (lock) {
      final TransactionInfo removedTransactionInfo =
          pendingTransactions.remove(transaction.getHash());
      if (removedTransactionInfo != null) {
        if (!prioritizedTransactionsDynamicRange.remove(removedTransactionInfo))
          prioritizedTransactionsStaticRange.remove(removedTransactionInfo);
        removeTransactionTrackedBySenderAndNonce(transaction);
        incrementTransactionRemovedCounter(
            removedTransactionInfo.isReceivedFromLocalSource(), addedToBlock);
      }
    }
  }

  @Override
  protected Iterator<TransactionInfo> prioritizedTransactions() {
    return new Iterator<>() {
      final Iterator<TransactionInfo> staticRangeIterable =
          prioritizedTransactionsStaticRange.iterator();
      final Iterator<TransactionInfo> dynamicRangeIterable =
          prioritizedTransactionsDynamicRange.iterator();

      Optional<TransactionInfo> currentStaticRangeTransaction =
          getNextOptional(staticRangeIterable);
      Optional<TransactionInfo> currentDynamicRangeTransaction =
          getNextOptional(dynamicRangeIterable);

      @Override
      public boolean hasNext() {
        return currentStaticRangeTransaction.isPresent()
            || currentDynamicRangeTransaction.isPresent();
      }

      @Override
      public TransactionInfo next() {
        if (currentStaticRangeTransaction.isEmpty() && currentDynamicRangeTransaction.isEmpty()) {
          throw new NoSuchElementException("Tried to iterate past end of iterator.");
        } else if (currentStaticRangeTransaction.isEmpty()) {
          // only dynamic range txs left
          final TransactionInfo best = currentDynamicRangeTransaction.get();
          currentDynamicRangeTransaction = getNextOptional(dynamicRangeIterable);
          return best;
        } else if (currentDynamicRangeTransaction.isEmpty()) {
          // only static range txs left
          final TransactionInfo best = currentStaticRangeTransaction.get();
          currentStaticRangeTransaction = getNextOptional(staticRangeIterable);
          return best;
        } else {
          // there are both static and dynamic txs remaining so we need to compare them by their
          // effective priority fees
          final long dynamicRangeEffectivePriorityFee =
              currentDynamicRangeTransaction
                  .get()
                  .getTransaction()
                  .getEffectivePriorityFeePerGas(baseFee);
          final long staticRangeEffectivePriorityFee =
              currentStaticRangeTransaction
                  .get()
                  .getTransaction()
                  .getEffectivePriorityFeePerGas(baseFee);
          final TransactionInfo best;
          if (dynamicRangeEffectivePriorityFee > staticRangeEffectivePriorityFee) {
            best = currentDynamicRangeTransaction.get();
            currentDynamicRangeTransaction = getNextOptional(dynamicRangeIterable);
          } else {
            best = currentStaticRangeTransaction.get();
            currentStaticRangeTransaction = getNextOptional(staticRangeIterable);
          }
          return best;
        }
      }

      private Optional<TransactionInfo> getNextOptional(
          final Iterator<TransactionInfo> transactionInfoIterator) {
        return transactionInfoIterator.hasNext()
            ? Optional.of(transactionInfoIterator.next())
            : Optional.empty();
      }
    };
  }

  @Override
  protected TransactionAddedStatus addTransaction(final TransactionInfo transactionInfo) {
    Optional<Transaction> droppedTransaction = Optional.empty();
    final Transaction transaction = transactionInfo.getTransaction();
    synchronized (lock) {
      if (pendingTransactions.containsKey(transactionInfo.getHash())) {
        return ALREADY_KNOWN;
      }

      final TransactionAddedStatus transactionAddedStatus =
          addTransactionForSenderAndNonce(transactionInfo);
      if (!transactionAddedStatus.equals(ADDED)) {
        return transactionAddedStatus;
      }
      // check if it's in static or dynamic range
      if (isInStaticRange(transaction, baseFee)) {
        prioritizedTransactionsStaticRange.add(transactionInfo);
      } else {
        prioritizedTransactionsDynamicRange.add(transactionInfo);
      }
      LOG.trace("Adding {} to pending transactions", transactionInfo);
      pendingTransactions.put(transactionInfo.getHash(), transactionInfo);
      tryEvictTransactionHash(transactionInfo.getHash());

      if (pendingTransactions.size() > maxPendingTransactions) {
        final Stream.Builder<TransactionInfo> removalCandidates = Stream.builder();
        if (!prioritizedTransactionsDynamicRange.isEmpty())
          removalCandidates.add(prioritizedTransactionsDynamicRange.last());
        if (!prioritizedTransactionsStaticRange.isEmpty())
          removalCandidates.add(prioritizedTransactionsStaticRange.last());
        final TransactionInfo toRemove =
            removalCandidates
                .build()
                .min(
                    Comparator.comparing(
                        txInfo -> txInfo.getTransaction().getEffectivePriorityFeePerGas(baseFee)))
                // safe because we just added a tx to the pool so we're guaranteed to have one
                .get();
        doRemoveTransaction(toRemove.getTransaction(), false);
        LOG.trace("Evicted {} due to transaction pool size", toRemove);
        droppedTransaction = Optional.of(toRemove.getTransaction());
      }
    }
    notifyTransactionAdded(transaction);
    droppedTransaction.ifPresent(this::notifyTransactionDropped);
    return ADDED;
  }

  private boolean isInStaticRange(final Transaction transaction, final Optional<Long> baseFee) {
    return transaction
        .getMaxPriorityFeePerGas()
        .map(
            maxPriorityFeePerGas ->
                transaction.getEffectivePriorityFeePerGas(baseFee)
                    >= maxPriorityFeePerGas.getValue().longValue())
        .orElse(
            // non-eip-1559 txs can't be in static range
            false);
  }

  public void updateBaseFee(final Long newBaseFee) {
    LOG.trace("Updating base fee from {} to {}", this.baseFee, newBaseFee);
    if (this.baseFee.orElse(0L).equals(newBaseFee)) {
      return;
    }
    synchronized (lock) {
      final boolean baseFeeIncreased = newBaseFee > this.baseFee.orElse(0L);
      this.baseFee = Optional.of(newBaseFee);
      if (baseFeeIncreased) {
        // base fee increases can only cause transactions to go from static to dynamic range
        prioritizedTransactionsStaticRange.stream()
            .filter(
                // these are the transactions whose effective priority fee have now dropped
                // below their max priority fee
                transactionInfo1 -> !isInStaticRange(transactionInfo1.getTransaction(), baseFee))
            .collect(toUnmodifiableList())
            .forEach(
                transactionInfo -> {
                  LOG.trace("Moving {} from static to dynamic gas fee paradigm", transactionInfo);
                  prioritizedTransactionsStaticRange.remove(transactionInfo);
                  prioritizedTransactionsDynamicRange.add(transactionInfo);
                });
      } else {
        // base fee decreases can only cause transactions to go from dynamic to static range
        prioritizedTransactionsDynamicRange.stream()
            .filter(
                // these are the transactions whose effective priority fee are now above their
                // max priority fee
                transactionInfo1 -> isInStaticRange(transactionInfo1.getTransaction(), baseFee))
            .collect(toUnmodifiableList())
            .forEach(
                transactionInfo -> {
                  LOG.trace("Moving {} from dynamic to static gas fee paradigm", transactionInfo);
                  prioritizedTransactionsDynamicRange.remove(transactionInfo);
                  prioritizedTransactionsStaticRange.add(transactionInfo);
                });
      }
    }
  }
}
