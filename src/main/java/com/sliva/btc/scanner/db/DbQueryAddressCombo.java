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

import com.sliva.btc.scanner.db.model.BtcAddress;
import com.sliva.btc.scanner.src.SrcAddressType;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 *
 * @author whost
 */
public class DbQueryAddressCombo extends DbQueryAddress {

    private final Map<SrcAddressType, DbQueryAddress> queryAddressMap = new HashMap<>();

    public DbQueryAddressCombo(DBConnection conn) {
        super(null, null);
        BtcAddress.getRealTypes().forEach((t) -> queryAddressMap.put(t, new DbQueryAddress(conn, t)));
    }

    @Override
    public BtcAddress findByAddress(byte[] address) throws SQLException {
        for (SrcAddressType type : BtcAddress.getRealTypes()) {
            if (address.length == 20 ^ type == SrcAddressType.P2WSH) {
                BtcAddress a = queryAddressMap.get(type).findByAddress(address);
                if (a != null) {
                    return a;
                }
            }
        }
        return null;
    }

    @Override
    public BtcAddress findByAddressId(int addressId) throws SQLException {
        return queryAddressMap.get(BtcAddress.getTypeFromId(addressId)).findByAddressId(addressId);
    }

    @Override
    public int getLastAddressId() throws SQLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getWalletId(int addressId) throws SQLException {
        BtcAddress a = findByAddressId(addressId);
        return a != null ? a.getWalletId() : 0;
    }

    @Override
    public String getTableName() {
        return "";
    }

}
