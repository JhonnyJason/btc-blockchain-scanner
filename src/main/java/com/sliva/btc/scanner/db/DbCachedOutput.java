/* 
 * Copyright 2018 Sliva Co.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.sliva.btc.scanner.db;

import com.sliva.btc.scanner.db.model.InOutKey;
import com.sliva.btc.scanner.db.model.TxOutput;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 *
 * @author Sliva Co
 */
@Slf4j
public class DbCachedOutput implements AutoCloseable {

    private static final int MAX_CACHE_SIZE = 50000;
    private final DbUpdateOutput updateOutput;
    private final DbQueryOutput queryOutput;
    private final CacheData cacheData;

    public DbCachedOutput(DBConnection conn) throws SQLException {
        this(conn, new CacheData());
    }

    public DbCachedOutput(DBConnection conn, CacheData cacheData) throws SQLException {
        updateOutput = new DbUpdateOutput(conn, cacheData.updateCachedData);
        queryOutput = new DbQueryOutput(conn);
        this.cacheData = cacheData;
    }

    public CacheData getCacheData() {
        return cacheData;
    }

    public void add(TxOutput txOutput) {
        synchronized (cacheData) {
            updateOutput.add(txOutput);
            updateCache(txOutput);
        }
    }

    public void delete(TxOutput txOutput) throws SQLException {
        synchronized (cacheData) {
            updateOutput.delete(txOutput);
            OutputsList ol = cacheData.cacheMap.get(txOutput.getTransactionId());
            if (ol != null) {
                ol.list.remove(txOutput);
                if (ol.list.isEmpty()) {
                    cacheData.cacheMap.remove(txOutput.getTransactionId());
                }
            }
        }
    }

    public void updateStatus(int transactionId, short pos, byte status) throws SQLException {
        synchronized (cacheData) {
            updateOutput.updateSpent(transactionId, pos, status);
            OutputsList ol = cacheData.cacheMap.get(transactionId);
            if (ol != null) {
                TxOutput to = ol.find(pos);
                if (to != null && to.getStatus() != status) {
                    ol.merge(to.toBuilder().status(status).build());
                }
            }
        }
    }

    public void updateAddress(int transactionId, short pos, int addressId) throws SQLException {
        synchronized (cacheData) {
            updateOutput.updateAddress(transactionId, pos, addressId);
            OutputsList ol = cacheData.cacheMap.get(transactionId);
            if (ol != null) {
                TxOutput to = ol.find(pos);
                if (to != null && to.getAddressId() != addressId) {
                    ol.merge(to.toBuilder().addressId(addressId).build());
                }
            }
        }
    }

    public void updateAmount(int transactionId, short pos, long amount) throws SQLException {
        synchronized (cacheData) {
            updateOutput.updateAmount(transactionId, pos, amount);
            OutputsList ol = cacheData.cacheMap.get(transactionId);
            if (ol != null) {
                TxOutput to = ol.find(pos);
                if (to != null && to.getAmount() != amount) {
                    ol.merge(to.toBuilder().amount(amount).build());
                }
            }
        }
    }

    public List<TxOutput> getOutputs(int transactionId) throws SQLException {
        OutputsList ol = cacheData.cacheMap.get(transactionId);
        if (ol != null && ol.isComplete()) {
            updateCache(transactionId, ol);
            return ol.getList();
        }
        List<TxOutput> lt = updateOutput.getCacheData().getQueueMapTx().get(transactionId);
        if (lt != null) {
            updateCache(lt);
            ol = cacheData.cacheMap.get(transactionId);
        }
        lt = queryOutput.getOutputs(transactionId);
        if (lt != null) {
            if (ol != null) {
                ol.merge(lt, true);
                updateCache(transactionId, ol);
                return ol.getList();
            } else {
                updateCache(lt);
            }
        }
        return lt;
    }

    public TxOutput getOutput(int transactionId, short pos) throws SQLException {
        OutputsList ol = cacheData.cacheMap.get(transactionId);
        TxOutput result = ol == null ? null : ol.find(pos);
        if (result != null) {
            updateCache(transactionId);
            return result;
        }
        result = updateOutput.getCacheData().getQueueMap().get(new InOutKey(transactionId, pos));
        if (result != null) {
            updateCache(transactionId);
            return result;
        }
        TxOutput to = queryOutput.getOutput(transactionId, pos);
        if (to != null) {
            updateCache(to);
        }
        return to;
    }
//
//    private static TxOutput findOutput(int transactionId, int pos, Map<Integer, List<TxOutput>> map) {
//        List<TxOutput> list = map.get(transactionId);
//        return list == null ? null : list.stream().filter((t) -> t.getPos() == pos).findAny().orElse(null);
//    }

    private void updateCache(int transactionId) throws SQLException {
        synchronized (cacheData) {
            OutputsList ol = cacheData.cacheMap.remove(transactionId);
            if (ol != null) {
                cacheData.cacheMap.put(transactionId, ol);
            }
        }
    }

    private void updateCache(int transactionId, OutputsList ol) throws SQLException {
        synchronized (cacheData) {
            cacheData.cacheMap.remove(transactionId);
            cacheData.cacheMap.put(transactionId, ol);
        }
    }

    private void updateCache(TxOutput txOutput) {
        synchronized (cacheData) {
            int transactionId = txOutput.getTransactionId();
            OutputsList outList = cacheData.cacheMap.get(transactionId);
            if (outList == null) {
                outList = new OutputsList();
                cacheData.cacheMap.put(transactionId, outList);
            }
            outList.merge(txOutput);
            cacheData.cacheMap.remove(transactionId);
            cacheData.cacheMap.put(transactionId, outList);
            if (cacheData.cacheMap.size() >= MAX_CACHE_SIZE) {
                cacheData.cacheMap.remove(cacheData.cacheMap.entrySet().iterator().next().getKey());
            }
        }
    }

    private void updateCache(List<TxOutput> lt) throws SQLException {
        synchronized (cacheData) {
            if (!lt.isEmpty()) {
                int transactionId = lt.get(0).getTransactionId();
                OutputsList ol = new OutputsList();
                ol.merge(lt, true);
                cacheData.cacheMap.remove(transactionId);
                cacheData.cacheMap.put(transactionId, ol);
                if (cacheData.cacheMap.size() >= MAX_CACHE_SIZE) {
                    cacheData.cacheMap.remove(cacheData.cacheMap.entrySet().iterator().next().getKey());
                }
            }
        }
    }

    @Override
    public void close() throws SQLException {
        log.debug("DbCachedOutput.close()");
        synchronized (cacheData) {
            updateOutput.close();
        }
    }

    @Getter
    public static class CacheData {

        private final Map<Integer, OutputsList> cacheMap = new LinkedHashMap<>();
        private final DbUpdateOutput.CacheData updateCachedData = new DbUpdateOutput.CacheData();
    }

    @Getter
    @NoArgsConstructor
    static class OutputsList {

        private final List<TxOutput> list = new ArrayList<>();
        private boolean complete;

        public OutputsList(Collection<TxOutput> list, boolean complete) {
            this.list.addAll(list);
            this.complete = complete;
        }

        TxOutput find(short pos) {
            synchronized (list) {
                return list.stream().filter((t) -> t.getPos() == pos).findAny().orElse(null);
            }
        }

        void merge(Collection<TxOutput> c, boolean complete) {
            synchronized (list) {
                c.forEach((t) -> merge(t));
                this.complete |= complete;
            }
        }

        void merge(TxOutput c) {
            synchronized (list) {
                boolean added = false;
                for (int n = 0; n < list.size(); n++) {
                    if (c.getPos() == list.get(n).getPos()) {
                        list.set(n, c);
                        added = true;
                        break;
                    } else if (c.getPos() < list.get(n).getPos()) {
                        list.add(n, c);
                        added = true;
                        break;
                    }
                }
                if (!added) {
                    list.add(c);
                }
            }
        }
    }
}
