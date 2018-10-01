/*
 * Copyright (c) 2008-2018, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.internal.eviction;

import com.hazelcast.core.IBiFunction;
import com.hazelcast.internal.nearcache.impl.invalidation.InvalidationQueue;
import com.hazelcast.spi.NodeEngine;
import com.hazelcast.spi.Operation;
import com.hazelcast.spi.OperationService;
import com.hazelcast.util.CollectionUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Queue;

/**
 * Helper class to create and send backup expiration operations.
 *
 * @param <RS> type of record store
 */
public final class ToBackupSender<RS> {

    private static final int MAX_EXPIRED_KEY_COUNT_IN_BATCH = 100;

    private final String serviceName;
    private final OperationService operationService;
    private final IBiFunction<Integer, Integer, Boolean> backupOpFilter;
    private final IBiFunction<RS, Collection<ExpiredKey>, Operation> backupOpSupplier;

    private ToBackupSender(String serviceName,
                           IBiFunction<RS, Collection<ExpiredKey>, Operation> backupOpSupplier,
                           IBiFunction<Integer, Integer, Boolean> backupOpFilter,
                           NodeEngine nodeEngine) {
        this.serviceName = serviceName;
        this.backupOpFilter = backupOpFilter;
        this.backupOpSupplier = backupOpSupplier;
        this.operationService = nodeEngine.getOperationService();
    }

    static <S> ToBackupSender<S> newToBackupSender(String serviceName,
                                                   IBiFunction<S, Collection<ExpiredKey>, Operation> operationSupplier,
                                                   IBiFunction<Integer, Integer, Boolean> backupOpFilter,
                                                   NodeEngine nodeEngine) {
        return new ToBackupSender<S>(serviceName, operationSupplier, backupOpFilter, nodeEngine);
    }

    private static Collection<ExpiredKey> tryTakeExpiredKeys(InvalidationQueue<ExpiredKey> invalidationQueue,
                                                             boolean checkIfReachedBatch) {
        int size = invalidationQueue.size();
        if (size == 0 || checkIfReachedBatch && size < MAX_EXPIRED_KEY_COUNT_IN_BATCH) {
            return null;
        }

        if (!invalidationQueue.tryAcquire()) {
            return null;
        }

        Collection<ExpiredKey> expiredKeys;
        try {
            expiredKeys = pollExpiredKeys(invalidationQueue);
        } finally {
            invalidationQueue.release();
        }

        return expiredKeys;
    }

    private static Collection<ExpiredKey> pollExpiredKeys(Queue<ExpiredKey> expiredKeys) {
        Collection<ExpiredKey> polledKeys = new ArrayList<ExpiredKey>(expiredKeys.size());

        do {
            ExpiredKey expiredKey = expiredKeys.poll();
            if (expiredKey == null) {
                break;
            }
            polledKeys.add(expiredKey);
        } while (true);

        return polledKeys;
    }

    public void trySendExpiryOp(RS recordStore, InvalidationQueue invalidationQueue,
                                int backupReplicaCount, int partitionId, boolean checkIfReachedBatch) {
        Collection<ExpiredKey> expiredKeys = tryTakeExpiredKeys(invalidationQueue, checkIfReachedBatch);
        if (CollectionUtil.isEmpty(expiredKeys)) {
            return;
        }
        // send expired keys to all backups
        invokeBackupExpiryOperation(expiredKeys, backupReplicaCount, partitionId, recordStore);
    }

    private void invokeBackupExpiryOperation(Collection<ExpiredKey> expiredKeys, int backupReplicaCount,
                                             int partitionId, RS recordStore) {
        for (int replicaIndex = 1; replicaIndex < backupReplicaCount + 1; replicaIndex++) {
            if (backupOpFilter.apply(partitionId, replicaIndex)) {
                Operation operation = backupOpSupplier.apply(recordStore, expiredKeys);
                operationService.createInvocationBuilder(serviceName, operation, partitionId)
                        .setReplicaIndex(replicaIndex).invoke();
            }
        }
    }
}
