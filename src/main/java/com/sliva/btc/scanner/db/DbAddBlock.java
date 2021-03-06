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

import com.sliva.btc.scanner.db.model.BtcBlock;
import com.sliva.btc.scanner.util.Utils;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 *
 * @author Sliva Co
 */
@Slf4j
public class DbAddBlock extends DbUpdate {

    private static int MIN_BATCH_SIZE = 1;
    public static int MAX_BATCH_SIZE = 10000;
    private static int MAX_INSERT_QUEUE_LENGTH = 30000;
    private static final String TABLE_NAME = "block";
    private static final String SQL_ADD = "INSERT INTO block(height,hash,txn_count)VALUES(?,?,?)";
    private final ThreadLocal<PreparedStatement> psAdd;
    private final CacheData cacheData;

    public DbAddBlock(DBConnection conn) throws SQLException {
        this(conn, new CacheData());
    }

    public DbAddBlock(DBConnection conn, CacheData cacheData) throws SQLException {
        super(conn);
        this.psAdd = conn.prepareStatement(SQL_ADD);
        this.cacheData = cacheData;
    }

    public CacheData getCacheData() {
        return cacheData;
    }

    @Override
    public String getTableName() {
        return TABLE_NAME;
    }

    @Override
    public int getCacheFillPercent() {
        return cacheData == null ? 0 : cacheData.addQueue.size() * 100 / MAX_INSERT_QUEUE_LENGTH;
    }

    @Override
    public boolean needExecuteInserts() {
        return cacheData == null ? false : cacheData.addQueue.size() >= MIN_BATCH_SIZE;
    }

    public void add(BtcBlock btcBlock) throws SQLException {
        log.trace("add(btcBlock:{})", btcBlock);
        waitFullQueue(cacheData.addQueue, MAX_INSERT_QUEUE_LENGTH);
        synchronized (cacheData) {
            cacheData.addQueue.add(btcBlock);
        }
    }

    @Override
    public int executeInserts() {
        Collection<BtcBlock> temp = null;
        synchronized (cacheData) {
            if (!cacheData.addQueue.isEmpty()) {
                temp = new ArrayList<>();
                Iterator<BtcBlock> it = cacheData.addQueue.iterator();
                for (int i = 0; i < MAX_BATCH_SIZE && it.hasNext(); i++) {
                    temp.add(it.next());
                    it.remove();
                }
            }
        }
        if (temp != null) {
            synchronized (execSync) {
                BatchExecutor.executeBatch(temp, psAdd.get(), (BtcBlock t, PreparedStatement ps) -> {
                    ps.setInt(1, t.getHeight());
                    ps.setBytes(2, Utils.id2bin(t.getHash()));
                    ps.setInt(3, t.getTxnCount());
                });
            }
        }
        return temp == null ? 0 : temp.size();
    }

    @Getter
    public static class CacheData {

        private final Collection<BtcBlock> addQueue = new LinkedHashSet<>();
    }
}
